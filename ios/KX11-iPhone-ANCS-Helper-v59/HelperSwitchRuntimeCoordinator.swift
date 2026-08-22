import Foundation
import Darwin

/// The only application-level owner of the role reducer and both route namespaces.
///
/// Every state-changing callback is serialized here. A reducer state is persisted before any
/// Core Bluetooth effect is dispatched, so a crash can only replay a drain, never start a second
/// route beside an unconfirmed restored owner.
public final class HelperSwitchRuntimeCoordinator {
    /// Route A is the shipped topology. Route B is retained only for controlled diagnostics and
    /// can be enabled with `-KX11ANCSHelper.experimentalRouteB YES` in a developer launch.
    public enum RuntimeMode: Equatable {
        case productionRouteA
        case diagnosticDualRoute
    }

    public enum Phase: Equatable {
        case active
        case switching
        case failed
    }

    public struct Snapshot: Equatable {
        public let desiredRole: BleRoleSwitchPolicy.Role
        public let activeRole: BleRoleSwitchPolicy.Role?
        public let phase: Phase
        public let detail: String
        public let routeBDiagnosticsEnabled: Bool
    }

    private enum Origin: String, Codable {
        case local
        case remote
        case localOnlyRestore
        case legacyMigration
    }

    private struct PersistedSnapshot: Codable, Equatable {
        static let schemaValue = "BRS2"

        let schema: String
        let reducerPhase: BleRoleSwitchPolicy.Phase
        let desiredRole: BleRoleSwitchPolicy.Role
        let activeRole: BleRoleSwitchPolicy.Role?
        let activeGeneration: BleRoleSwitchPolicy.Sequence?
        let sourceRole: BleRoleSwitchPolicy.Role?
        let sourceGeneration: BleRoleSwitchPolicy.Sequence?
        let targetRole: BleRoleSwitchPolicy.Role?
        let targetGeneration: BleRoleSwitchPolicy.Sequence?
        let epoch: BleRoleSwitchPolicy.Sequence
        let failure: BleRoleSwitchPolicy.Failure
        let origin: Origin?
        let switchToken: [UInt8]?
    }

    private struct PendingTransmit: Equatable {
        let frame: BleRoleSwitchPolicy.ControlFrame
        let epoch: BleRoleSwitchPolicy.Sequence
        let generation: BleRoleSwitchPolicy.Sequence
        let role: BleRoleSwitchPolicy.Role
        let attempt: BleRoleSwitchPolicy.Sequence
    }

    private struct ReleasedSource: Equatable {
        let epoch: BleRoleSwitchPolicy.Sequence
        let generation: BleRoleSwitchPolicy.Sequence
        let role: BleRoleSwitchPolicy.Role
    }

    private struct ClosedCleanup {
        let epoch: BleRoleSwitchPolicy.Sequence
        let routeAGeneration: BleRoleSwitchPolicy.Sequence
        let routeBGeneration: BleRoleSwitchPolicy.Sequence
        var routeATerminal = false
        var routeAOwnersZero = false
        var routeBTerminal = false
        var routeBOwnersZero = false
    }

    public var onSnapshot: ((Snapshot) -> Void)? {
        didSet { queue.async { [weak self] in self?.publishSnapshot() } }
    }

    public var onEnrollmentEvent: ((HelperPeripheralRoute.EnrollmentEvent) -> Void)?
    /// Privacy-safe stage messages for the user-visible ANCS connection journal.
    public var onDiagnosticEvent: ((String) -> Void)?

    /// Delivered off-main from the sole runtime queue. The app bridges this to the main-thread
    /// telemetry source. Multiple R frames are coalesced until one fresh sample returns.
    public var onTelemetryRefreshRequest: (() -> Void)?
    /// Valid C5 frame from the currently active authenticated route; delivered off-main.
    public var onCarRemoteFrame: ((Data) -> Void)?

    private static let snapshotKey = "KX11ANCSHelper.v2.roleSwitch.BRS2"
    private static let legacyRoleKey = "KX11ANCSHelper.bleRole.v12"
    private static let experimentalRouteBKey = "KX11ANCSHelper.experimentalRouteB"
    private static let stopTimeoutMs: UInt64 = 15_000
    private static let drainDurationMs: UInt64 = 750
    private static let targetStartTimeoutMs: UInt64 = 12_000
    private static let controlRetryDelayMs: UInt64 = 250
    private static let closedCleanupTimeoutMs: UInt64 = 5_000

    private let queue = DispatchQueue(label: "ru.natro.kx11ancshelper.v54.switch-owner")
    private let defaults: UserDefaults
    private let runtimeMode: RuntimeMode
    private let snapshotURL: URL
    private let routes: HelperBleRuntimeCoordinator
    private var policy: BleRoleSwitchPolicy
    private var origin: Origin?
    private var switchToken: [UInt8]?
    private var started = false
    /// Exact authenticated Android CONTROL owner. Local Core Bluetooth operational readiness is
    /// tracked by the reducer's ACTIVE phase and must never open telemetry by itself.
    private var peerReady = false
    private var bootDetail = "Инициализация единственного BLE-владельца"
    private var runtimeFailure: String?
    private var pendingStartupEffects: [BleRoleSwitchPolicy.Effect] = []
    private var deferredRestorationFreeze: BleRoleSwitchPolicy.Effect?
    private var releasedSource: ReleasedSource?
    private var pendingTransmit: PendingTransmit?
    private var stopTimerToken: UUID?
    private var drainTimerToken: UUID?
    private var targetStartTimerToken: UUID?
    private var retryTimerToken: UUID?
    private var closedCleanup: ClosedCleanup?
    private var closedCleanupTimerToken: UUID?
    private var telemetryGeneration: BleRoleSwitchPolicy.Sequence?
    private var telemetrySequence: UInt16 = 0
    private var telemetryRefreshPending = false

    public init(
        defaults: UserDefaults = .standard,
        runtimeMode: RuntimeMode? = nil
    ) throws {
        self.defaults = defaults
        self.runtimeMode = runtimeMode ?? (
            defaults.bool(forKey: Self.experimentalRouteBKey)
                ? .diagnosticDualRoute
                : .productionRouteA
        )
        self.snapshotURL = try Self.makeSnapshotURL()
        self.routes = try HelperBleRuntimeCoordinator(
            routeAConfiguration: .init(defaults: defaults),
            routeBConfiguration: .init(defaults: defaults)
        )

        let persisted: PersistedSnapshot?
        let quarantinedCorruption: Bool
        do {
            persisted = try Self.loadSnapshot(url: snapshotURL, defaults: defaults)
            quarantinedCorruption = false
        } catch PersistenceError.invalidPersistedSnapshot {
            persisted = nil
            quarantinedCorruption = true
        }

        if quarantinedCorruption {
            // Never guess an active role from a torn/unknown record. Replace it with a durable
            // CLOSED tombstone first; start() will reclaim both stable restoration namespaces.
            var quarantined = BleRoleSwitchPolicy(activeRole: .helperPeripheralAndroidCentral)
            let closed = quarantined.close()
            let token = Self.randomToken()
            self.policy = quarantined
            self.origin = .localOnlyRestore
            self.switchToken = token
            self.pendingStartupEffects = closed.effects
            self.bootDetail = "Повреждённый BRS2: durable CLOSED и drain обоих namespace"
            try Self.persist(
                policy: quarantined,
                origin: .localOnlyRestore,
                token: token,
                url: snapshotURL,
                defaults: defaults
            )
        } else if let persisted,
                  persisted.reducerPhase == .failed || persisted.reducerPhase == .closed {
            // Helper 56 could persist ingressFreezeFailed while migrating a legacy namespace
            // before Core Bluetooth had delivered its restoration callback. On the next launch
            // that terminal snapshot could never start a route, which produced the visible
            // "новый маршрут не запущен" first-launch failure. A new process owns no live local
            // manager; resume Route A and let its exact CONTROL proof decide whether a restored
            // remote owner is usable. No C/A frame is emitted from guessed state.
            self.policy = BleRoleSwitchPolicy(activeRole: .helperPeripheralAndroidCentral)
            self.origin = nil
            self.switchToken = nil
            self.pendingStartupEffects = []
            self.bootDetail = "Helper 59: терминальный снимок v56 восстановлен в основной Route A"
            defaults.removeObject(forKey: Self.legacyRoleKey)
            try Self.persist(
                policy: policy,
                origin: nil,
                token: nil,
                url: snapshotURL,
                defaults: defaults
            )
        } else if let persisted {
            let restored = try Self.restorePolicy(from: persisted)
            self.policy = restored.policy
            self.origin = restored.origin
            self.switchToken = restored.token
            self.pendingStartupEffects = restored.effects
            self.bootDetail = "Безопасное восстановление: сначала дочищается прежний маршрут"
            try Self.persist(
                policy: restored.policy,
                origin: restored.origin,
                token: restored.token,
                url: snapshotURL,
                defaults: defaults
            )
        } else if try Self.loadLegacyRole(defaults: defaults) != nil {
            // The old process is gone before this coordinator is constructed. Starting the
            // production namespace directly is both safer and more reliable than freezing a
            // manager that does not exist yet; Route A still requires the full installation-ID
            // and CONTROL proof before telemetry or commands become ready.
            self.policy = BleRoleSwitchPolicy(activeRole: .helperPeripheralAndroidCentral)
            self.origin = nil
            self.switchToken = nil
            self.pendingStartupEffects = []
            self.bootDetail = "Helper 59: legacy-настройка перенесена в основной Route A"
            defaults.removeObject(forKey: Self.legacyRoleKey)
            try Self.persist(
                policy: policy,
                origin: nil,
                token: nil,
                url: snapshotURL,
                defaults: defaults
            )
        } else {
            self.policy = BleRoleSwitchPolicy(activeRole: .helperPeripheralAndroidCentral)
            self.origin = nil
            self.switchToken = nil
            try Self.persist(
                policy: policy,
                origin: nil,
                token: nil,
                url: snapshotURL,
                defaults: defaults
            )
        }

        routes.onEvent = { [weak self] event in
            self?.queue.async { self?.handle(event) }
        }
        routes.onDiagnosticEvent = { [weak self] message in
            self?.queue.async { self?.onDiagnosticEvent?(message) }
        }
    }

    public func start() {
        queue.async { [weak self] in
            guard let self, !self.started else { return }
            self.started = true
            if self.policy.state.phase == .closed {
                for effect in self.pendingStartupEffects { self.dispatch(effect) }
                self.pendingStartupEffects = []
                self.bootDetail = "Durable CLOSED tombstone: ни один BLE-маршрут не запущен"
                self.publishSnapshot()
                return
            }
            if self.pendingStartupEffects.isEmpty {
                guard let role = self.policy.state.activeRole,
                      let generation = self.policy.state.activeGeneration else {
                    self.failRuntime("BRS2 active snapshot has no owner")
                    return
                }
                self.startRoute(role, generation: generation, restorationEpoch: nil)
            } else {
                guard let role = self.policy.state.sourceRole,
                      let generation = self.policy.state.sourceGeneration else {
                    self.failRuntime("BRS2 drain snapshot has no source owner")
                    return
                }
                let epoch = self.policy.state.epoch
                self.startRoute(role, generation: generation, restorationEpoch: epoch)
                let effects = self.pendingStartupEffects
                self.pendingStartupEffects = []
                for effect in effects {
                    if effect.type == .freezeSourceIngress {
                        if role == .helperCentralAndroidPeripheral {
                            // CBCentral restoration must first reclaim and authenticate the exact
                            // F202 owner; the freeze callback is deferred until Route B is ready.
                            self.deferredRestorationFreeze = effect
                        }
                        // Route A's drain-only restoration disposition performs the freeze itself.
                    } else {
                        self.dispatch(effect)
                    }
                }
            }
            self.publishSnapshot()
        }
    }

    public func select(_ role: BleRoleSwitchPolicy.Role) {
        queue.async { [weak self] in
            guard let self, self.started, self.runtimeFailure == nil,
                  self.policy.state.phase == .active else { return }
            guard self.runtimeMode == .diagnosticDualRoute ||
                    role == .helperPeripheralAndroidCentral else {
                self.bootDetail = "Route B доступен только в явном диагностическом запуске"
                self.publishSnapshot()
                return
            }
            guard role != self.policy.state.activeRole else { return }
            self.releasedSource = nil
            let token = Self.randomToken()
            self.reduce(nextOrigin: .local, nextToken: token) { policy in
                policy.requestSwitch(
                    to: role,
                    nowMs: Self.nowMs(),
                    stopTimeoutMs: Self.stopTimeoutMs,
                    drainDurationMs: Self.drainDurationMs
                )
            }
        }
    }

    public func publishTelemetry(_ sample: HelperTelemetrySample) {
        queue.async { [weak self] in
            guard let self, self.runtimeFailure == nil, self.peerReady,
                  self.policy.state.phase == .active,
                  let role = self.policy.state.activeRole,
                  let generation = self.policy.state.activeGeneration else { return }
            self.telemetryRefreshPending = false
            if self.telemetryGeneration != generation {
                self.telemetryGeneration = generation
                self.telemetrySequence = 0
            }
            let sequence = self.telemetrySequence
            self.telemetrySequence &+= 1
            let telemetry = IphoneBleWireProtocolV2.Telemetry(
                batteryPercent: sample.batteryPercent,
                externalPower: sample.externalPower,
                chargeState: sample.chargeState,
                network: sample.network,
                locked: sample.locked,
                sequence: sequence
            )
            if role == .helperPeripheralAndroidCentral {
                self.routes.publishRouteATelemetry(telemetry, generation: generation)
            } else {
                self.routes.publishRouteBTelemetry(telemetry, generation: generation)
            }
        }
    }

    public func sendCarRemoteFrame(_ frame: Data) {
        queue.async { [weak self] in
            guard let self, frame.count == CarRemoteProtocolV1.frameBytes,
                  CarRemoteProtocolV1.decode(frame) != nil,
                  self.runtimeFailure == nil, self.peerReady,
                  self.policy.state.phase == .active,
                  let role = self.policy.state.activeRole,
                  let generation = self.policy.state.activeGeneration else { return }
            if role == .helperPeripheralAndroidCentral {
                self.routes.sendRouteACarRemoteFrame(frame, generation: generation)
            } else {
                self.routes.sendRouteBCarRemoteFrame(frame, generation: generation)
            }
        }
    }

    public func beginEnrollment() {
        queue.async { [weak self] in
            guard let self, self.started, self.runtimeFailure == nil,
                  self.policy.state.phase == .active,
                  self.policy.state.activeRole == .helperPeripheralAndroidCentral,
                  let generation = self.policy.state.activeGeneration else {
                self?.onEnrollmentEvent?(.failed(
                    reason: "Привязка доступна только на активном основном Route A"
                ))
                return
            }
            self.routes.beginRouteAEnrollment(generation: generation) { [weak self] event in
                self?.queue.async { self?.onEnrollmentEvent?(event) }
            }
        }
    }

    public func confirmEnrollmentSAS() {
        queue.async { [weak self] in
            guard let self,
                  self.policy.state.phase == .active,
                  self.policy.state.activeRole == .helperPeripheralAndroidCentral,
                  let generation = self.policy.state.activeGeneration else { return }
            self.routes.confirmRouteAEnrollmentSAS(generation: generation)
        }
    }

    public func cancelEnrollment() {
        queue.async { [weak self] in
            guard let self,
                  self.policy.state.activeRole == .helperPeripheralAndroidCentral,
                  let generation = self.policy.state.activeGeneration else { return }
            self.routes.cancelRouteAEnrollment(generation: generation)
        }
    }

    public func enrollmentBindingState(
        _ completion: @escaping (HelperPeripheralRoute.EnrollmentBindingState) -> Void
    ) {
        routes.routeAEnrollmentBindingState(completion)
    }

    public func resetEnrollmentBinding(
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        queue.async { [weak self] in
            guard let self,
                  self.policy.state.phase == .active,
                  self.policy.state.activeRole == .helperPeripheralAndroidCentral,
                  let generation = self.policy.state.activeGeneration else {
                completion(.failure(HelperEnrollmentV1.ProtocolError.invalidBinding))
                return
            }
            self.routes.resetRouteAEnrollmentBinding(
                generation: generation,
                completion: completion
            )
        }
    }

    public func retryFailedSwitch() {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.policy.state.phase == .failed else {
                self.publishSnapshot()
                return
            }
            let failed = self.policy.state
            switch failed.failure {
            case .contradictoryRemoteEvidence,
                 .contradictoryLocalOwnerEvidence,
                 .impossibleLocalOwnerCount:
                self.bootDetail = "Неоднозначный владелец остаётся fail-closed; автоматический выбор namespace запрещён"
                self.publishSnapshot()
                return
            default:
                break
            }

            let targetMayExist = failed.failure == .targetStartFailed
            guard let drainRole = targetMayExist ? failed.targetRole : failed.sourceRole,
                  let drainGeneration = targetMayExist
                    ? failed.targetGeneration : failed.sourceGeneration else {
                self.bootDetail = "FAILED не содержит точного владельца для безопасного retry"
                self.publishSnapshot()
                return
            }
            let maximumGeneration = [
                failed.activeGeneration, failed.sourceGeneration, failed.targetGeneration
            ].compactMap { $0 }.max(by: { Self.sequenceLessThan($0, $1) }) ?? drainGeneration
            let nextEpoch = failed.epoch.next()
            let nextGeneration = maximumGeneration.next()
            let retryOrigin: Origin = targetMayExist ? .localOnlyRestore : (self.origin ?? .localOnlyRestore)
            let retryToken = retryOrigin == .remote
                ? (self.switchToken ?? Self.randomToken()) : Self.randomToken()

            self.runtimeFailure = nil
            self.cancelTargetStartWatchdog()
            self.peerReady = false
            self.telemetryRefreshPending = false
            self.releasedSource = nil
            self.pendingTransmit = nil
            self.bootDetail = "Явный retry: новый epoch, точный owner-zero и полный drain"
            self.reduce(nextOrigin: retryOrigin, nextToken: retryToken) { policy in
                switch retryOrigin {
                case .remote:
                    return policy.restoreDrainFromRemoteIntent(
                        sourceRole: drainRole,
                        desiredRole: failed.desiredRole ?? drainRole,
                        epoch: nextEpoch,
                        sourceGeneration: drainGeneration,
                        targetGeneration: nextGeneration,
                        nowMs: Self.nowMs(),
                        stopTimeoutMs: Self.stopTimeoutMs,
                        drainDurationMs: Self.drainDurationMs
                    )
                case .localOnlyRestore, .legacyMigration:
                    return policy.restoreDrainLocalOnly(
                        role: drainRole,
                        epoch: nextEpoch,
                        sourceGeneration: drainGeneration,
                        targetGeneration: nextGeneration,
                        nowMs: Self.nowMs(),
                        stopTimeoutMs: Self.stopTimeoutMs,
                        drainDurationMs: Self.drainDurationMs
                    )
                case .local:
                    return policy.restoreDrain(
                        sourceRole: drainRole,
                        desiredRole: failed.desiredRole ?? drainRole,
                        epoch: nextEpoch,
                        sourceGeneration: drainGeneration,
                        targetGeneration: nextGeneration,
                        nowMs: Self.nowMs(),
                        stopTimeoutMs: Self.stopTimeoutMs,
                        drainDurationMs: Self.drainDurationMs
                    )
                }
            }
        }
    }

    private func handle(_ event: HelperBleRuntimeCoordinator.Event) {
        onDiagnosticEvent?(Self.diagnosticDescription(event))
        if policy.state.phase == .closed, closedCleanup != nil {
            handleClosedCleanup(event)
            return
        }
        switch event {
        case .routeALocalOperational(let generation):
            handleRouteOperational(
                .helperPeripheralAndroidCentral,
                generation: generation
            )

        case .routeAReady(let generation):
            handleRouteReady(.helperPeripheralAndroidCentral, generation: generation)

        case .routeBLocalOperational(let generation):
            handleRouteOperational(
                .helperCentralAndroidPeripheral,
                generation: generation
            )

        case .routeBReady(_, _, let generation):
            handleRouteReady(.helperCentralAndroidPeripheral, generation: generation)

        case .sourceIngressFrozen(let epoch, let generation):
            reduce { policy in
                policy.onIngressFrozen(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperPeripheralAndroidCentral,
                    nowMs: Self.nowMs()
                )
            }

        case .routeBIngressFrozen(let epoch, let generation):
            reduce { policy in
                policy.onIngressFrozen(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperCentralAndroidPeripheral,
                    nowMs: Self.nowMs()
                )
            }

        case .sourceLocalTerminal(let epoch, let generation):
            reduce { policy in
                policy.onLocalTerminal(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperPeripheralAndroidCentral,
                    nowMs: Self.nowMs()
                )
            }

        case .routeBLocalTerminal(let epoch, let generation):
            reduce { policy in
                policy.onLocalTerminal(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperCentralAndroidPeripheral,
                    nowMs: Self.nowMs()
                )
            }

        case .sourceLocalOwnerCount(let count, let epoch, let generation):
            reduce { policy in
                policy.onLocalOwnerCount(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperPeripheralAndroidCentral,
                    ownerCount: count,
                    nowMs: Self.nowMs()
                )
            }

        case .routeBLocalOwnerCount(let count, let epoch, let generation):
            reduce { policy in
                policy.onLocalOwnerCount(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperCentralAndroidPeripheral,
                    ownerCount: count,
                    nowMs: Self.nowMs()
                )
            }

        case .sourceFrozenWithoutRemoteOwner(let epoch, let generation):
            // The exact restored F201 inventory was observed without a control owner for a
            // bounded turn. Commit this as one reducer input so no intermediate C/A can escape.
            guard policy.state.epoch == epoch,
                  policy.state.sourceGeneration == generation,
                  policy.state.sourceRole == .helperPeripheralAndroidCentral else { return }
            releasedSource = ReleasedSource(
                epoch: epoch,
                generation: generation,
                role: .helperPeripheralAndroidCentral
            )
            reduce { policy in
                policy.onIngressFrozenWithoutRemoteOwner(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperPeripheralAndroidCentral,
                    nowMs: Self.nowMs()
                )
            }

        case .routeBFrozenWithoutRemoteOwner(let epoch, let generation):
            guard policy.state.epoch == epoch,
                  policy.state.sourceGeneration == generation,
                  policy.state.sourceRole == .helperCentralAndroidPeripheral else { return }
            releasedSource = ReleasedSource(
                epoch: epoch,
                generation: generation,
                role: .helperCentralAndroidPeripheral
            )
            reduce { policy in
                policy.onIngressFrozenWithoutRemoteOwner(
                    epoch: epoch,
                    sourceGeneration: generation,
                    sourceRole: .helperCentralAndroidPeripheral,
                    nowMs: Self.nowMs()
                )
            }
        case .sourceUnprovableMigration(let epoch, let legacy, let generation):
            guard generation == policy.state.sourceGeneration,
                  epoch == nil || epoch == policy.state.epoch,
                  policy.state.sourceRole == .helperPeripheralAndroidCentral else { return }
            if origin == .legacyMigration { return }
            bootDetail = legacy
                ? "Legacy F04 восстановлен, но v2-владелец/ACK доказать нельзя"
                : "Восстановленная Route A не соответствует точному F201 v2"
            if policy.state.phase == .freezing {
                reduce { policy in
                    policy.onIngressFreezeFailed(
                        epoch: policy.state.epoch,
                        sourceGeneration: generation,
                        sourceRole: .helperPeripheralAndroidCentral
                    )
                }
            } else {
                failRuntime(bootDetail)
            }

        case .routeBUnprovableRestoration(let generation):
            guard generation == policy.state.sourceGeneration,
                  policy.state.sourceRole == .helperCentralAndroidPeripheral else { return }
            if origin == .legacyMigration { return }
            bootDetail = "Восстановленный Route B не прошёл installation-ID/F202 proof"
            if policy.state.phase == .freezing {
                reduce { policy in
                    policy.onIngressFreezeFailed(
                        epoch: policy.state.epoch,
                        sourceGeneration: generation,
                        sourceRole: .helperCentralAndroidPeripheral
                    )
                }
            } else {
                failRuntime(bootDetail)
            }

        case .sourceControlReceived(let frame, let generation):
            handleControl(frame, source: .helperPeripheralAndroidCentral, generation: generation)

        case .routeBControlReceived(let frame, let generation):
            handleControl(frame, source: .helperCentralAndroidPeripheral, generation: generation)

        case .routeACarRemoteFrame(let frame, let generation):
            guard peerReady, policy.state.phase == .active,
                  policy.state.activeRole == .helperPeripheralAndroidCentral,
                  policy.state.activeGeneration == generation else { return }
            onCarRemoteFrame?(frame)

        case .routeBCarRemoteFrame(let frame, let generation):
            guard peerReady, policy.state.phase == .active,
                  policy.state.activeRole == .helperCentralAndroidPeripheral,
                  policy.state.activeGeneration == generation else { return }
            onCarRemoteFrame?(frame)

        case .sourceControlAccepted(let frame, let epoch, let generation, let attempt):
            handleControlAccepted(
                frame,
                source: .helperPeripheralAndroidCentral,
                epoch: epoch,
                generation: generation,
                attempt: attempt
            )

        case .routeBControlAccepted(let frame, let epoch, let generation, let attempt):
            handleControlAccepted(
                frame,
                source: .helperCentralAndroidPeripheral,
                epoch: epoch,
                generation: generation,
                attempt: attempt
            )

        case .routeALostExactLink(let generation):
            recoverAttachedActiveOwner(
                role: .helperPeripheralAndroidCentral,
                generation: generation,
                reason: "Точный Route A control owner потерян"
            )

        case .routeALostFrozenExactLink(let epoch, let generation):
            handleFrozenRadioLoss(
                role: .helperPeripheralAndroidCentral,
                epoch: epoch,
                generation: generation
            )

        case .routeBLostExactLink(let generation):
            recoverReleasedActiveOwner(
                role: .helperCentralAndroidPeripheral,
                generation: generation,
                reason: "Точный Route B линк потерян"
            )

        case .routeBLostFrozenExactLink(let epoch, let generation):
            handleFrozenRadioLoss(
                role: .helperCentralAndroidPeripheral,
                epoch: epoch,
                generation: generation
            )

        case .routeFailed(let role, let reason, let generation):
            let current = (policy.state.activeRole == role && policy.state.activeGeneration == generation) ||
                (policy.state.sourceRole == role && policy.state.sourceGeneration == generation) ||
                (policy.state.targetRole == role && policy.state.targetGeneration == generation)
            guard current else { return }
            if let transmit = pendingTransmit,
               transmit.role == role,
               transmit.generation == generation,
               policy.state.phase != .failed {
                reduce { policy in
                    policy.onControlTransmitResult(
                        epoch: transmit.epoch,
                        sourceGeneration: transmit.generation,
                        sourceRole: transmit.role,
                        frame: transmit.frame,
                        attempt: transmit.attempt,
                        result: .terminalFailure,
                        nowMs: Self.nowMs()
                    )
                }
            } else if policy.state.phase == .freezing,
                      policy.state.sourceGeneration == generation,
                      let sourceRole = policy.state.sourceRole {
                reduce { policy in
                    policy.onIngressFreezeFailed(
                        epoch: policy.state.epoch,
                        sourceGeneration: generation,
                        sourceRole: sourceRole
                    )
                }
            } else if policy.state.phase == .starting,
                      policy.state.targetGeneration == generation,
                      let target = policy.state.targetRole {
                bootDetail = "Core Bluetooth не запустил локальный маршрут: \(reason); безопасный retry доступен"
                reduce { policy in
                    policy.onTargetStartFailed(
                        epoch: policy.state.epoch,
                        targetGeneration: generation,
                        targetRole: target
                    )
                }
            } else if policy.state.phase == .active,
                      policy.state.activeRole == role,
                      policy.state.activeGeneration == generation {
                if role == .helperPeripheralAndroidCentral {
                    recoverAttachedActiveOwner(
                        role: role,
                        generation: generation,
                        reason: "Route-local retry exhausted: \(reason)"
                    )
                } else {
                    recoverReleasedActiveOwner(
                        role: role,
                        generation: generation,
                        reason: "Route-local retry exhausted: \(reason)"
                    )
                }
            } else {
                failRuntime(reason)
            }
        }
    }

    private static func diagnosticDescription(
        _ event: HelperBleRuntimeCoordinator.Event
    ) -> String {
        switch event {
        case .routeALocalOperational:
            return "Route A: BLE-сервис опубликован и реклама запущена"
        case .routeAReady:
            return "Route A: точный CONTROL owner аутентифицирован; защищённый канал готов"
        case .routeALostExactLink:
            return "Route A: точный CONTROL owner потерян; запускается безопасное восстановление"
        case .routeALostFrozenExactLink:
            return "Route A: замороженный линк потерян во время переключения"
        case .sourceIngressFrozen:
            return "Route A: входящие данные заморожены перед сменой владельца"
        case .sourceLocalTerminal:
            return "Route A: Core Bluetooth owner полностью остановлен"
        case .sourceLocalOwnerCount(let count, _, _):
            return "Route A: подтверждён локальный owner count = \(count)"
        case .sourceFrozenWithoutRemoteOwner:
            return "Route A: подтверждено отсутствие удалённого CONTROL owner"
        case .sourceUnprovableMigration(_, let legacy, _):
            return legacy
                ? "Route A: восстановлен legacy F04 без доказуемого v2-владельца"
                : "Route A: восстановленная топология не прошла v2-проверку"
        case .sourceControlReceived:
            return "Route A: получен и проверен служебный CONTROL кадр"
        case .routeACarRemoteFrame:
            return "Route A: получен валидный C5 кадр автомобиля"
        case .sourceControlAccepted:
            return "Route A: служебный CONTROL кадр подтверждён Core Bluetooth"
        case .routeBLocalOperational:
            return "Route B: central-маршрут локально готов"
        case .routeBReady:
            return "Route B: peer proof, CONTROL и C5 подписки подтверждены"
        case .routeBIngressFrozen:
            return "Route B: входящие данные заморожены перед сменой владельца"
        case .routeBLocalTerminal:
            return "Route B: Core Bluetooth owner полностью остановлен"
        case .routeBLocalOwnerCount(let count, _, _):
            return "Route B: подтверждён локальный owner count = \(count)"
        case .routeBFrozenWithoutRemoteOwner:
            return "Route B: подтверждено отсутствие удалённого owner"
        case .routeBControlReceived:
            return "Route B: получен и проверен служебный CONTROL кадр"
        case .routeBCarRemoteFrame:
            return "Route B: получен валидный C5 кадр автомобиля"
        case .routeBControlAccepted:
            return "Route B: служебный CONTROL кадр подтверждён Core Bluetooth"
        case .routeBLostExactLink:
            return "Route B: точный линк потерян; запускается безопасное восстановление"
        case .routeBLostFrozenExactLink:
            return "Route B: замороженный линк потерян во время переключения"
        case .routeBUnprovableRestoration:
            return "Route B: восстановленный owner не прошёл installation proof"
        case .routeFailed(let role, let reason, _):
            let route = role == .helperPeripheralAndroidCentral ? "Route A" : "Route B"
            return "\(route): ошибка этапа — \(reason)"
        }
    }

    private func handleFrozenRadioLoss(
        role: BleRoleSwitchPolicy.Role,
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        guard policy.state.epoch == epoch,
              policy.state.sourceGeneration == generation,
              policy.state.sourceRole == role else { return }
        releasedSource = ReleasedSource(epoch: epoch, generation: generation, role: role)
        reduce { policy in
            policy.onRadioOrPowerLoss(
                epoch: epoch,
                sourceGeneration: generation,
                sourceRole: role,
                nowMs: Self.nowMs()
            )
        }
        // Radio teardown can beat the ordinary frozen callback. The atomic no-owner input fences
        // acquisition without attempting dead C/A and retains the committed radio evidence.
        reduce { policy in
            policy.onIngressFrozenWithoutRemoteOwner(
                epoch: epoch,
                sourceGeneration: generation,
                sourceRole: role,
                nowMs: Self.nowMs()
            )
        }
    }

    private func recoverAttachedActiveOwner(
        role: BleRoleSwitchPolicy.Role,
        generation: BleRoleSwitchPolicy.Sequence,
        reason: String
    ) {
        guard runtimeFailure == nil,
              policy.state.phase == .active,
              policy.state.activeRole == role,
              policy.state.activeGeneration == generation else { return }
        peerReady = false
        telemetryRefreshPending = false
        releasedSource = nil
        bootDetail = "\(reason): fencing + owner-zero + same-role drain"
        reduce(nextOrigin: .localOnlyRestore, nextToken: Self.randomToken()) { policy in
            policy.requestSameRoleRestart(
                nowMs: Self.nowMs(),
                stopTimeoutMs: Self.stopTimeoutMs,
                drainDurationMs: Self.drainDurationMs
            )
        }
    }

    private func handleRouteReady(
        _ role: BleRoleSwitchPolicy.Role,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        if let deferred = deferredRestorationFreeze,
           deferred.role == role, deferred.generation == generation {
            deferredRestorationFreeze = nil
            dispatch(deferred)
            return
        }
        if policy.state.phase == .starting,
           policy.state.targetRole == role,
           policy.state.targetGeneration == generation {
            reduce { policy in
                policy.onTargetActive(epoch: policy.state.epoch, targetGeneration: generation, targetRole: role)
            }
            // A restored exact owner may report peer readiness before the advertising callback
            // that normally reports local operational readiness. Preserve that stronger evidence
            // after targetActive clears the peer fence.
            if policy.state.phase == .active,
               policy.state.activeRole == role,
               policy.state.activeGeneration == generation {
                peerReady = true
                requestFreshTelemetrySample()
                bootDetail = "Точный v2 CONTROL owner подтверждён"
                publishSnapshot()
                enforceProductionRouteAIfNeeded()
            }
            return
        }
        guard policy.state.phase == .active,
              policy.state.activeRole == role,
              policy.state.activeGeneration == generation else { return }
        peerReady = true
        if telemetryGeneration != generation {
            telemetryGeneration = generation
            telemetrySequence = 0
        }
        requestFreshTelemetrySample()
        bootDetail = "Точный v2 control owner подтверждён"
        publishSnapshot()
        enforceProductionRouteAIfNeeded()
    }

    private func handleRouteOperational(
        _ role: BleRoleSwitchPolicy.Role,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        if policy.state.phase == .starting,
           policy.state.targetRole == role,
           policy.state.targetGeneration == generation {
            bootDetail = "Локальный BLE-маршрут запущен; ожидается точный Android CONTROL owner"
            reduce { policy in
                policy.onTargetActive(
                    epoch: policy.state.epoch,
                    targetGeneration: generation,
                    targetRole: role
                )
            }
            enforceProductionRouteAIfNeeded()
            return
        }
        guard policy.state.phase == .active,
              policy.state.activeRole == role,
              policy.state.activeGeneration == generation else { return }
        bootDetail = "Локальный BLE-маршрут запущен; ожидается точный Android CONTROL owner"
        publishSnapshot()
        enforceProductionRouteAIfNeeded()
    }

    /// Helper cannot observe HFP/A2DP or decide whether Classic is connected. In production it
    /// therefore keeps the restorable F201 peripheral lane available and lets Android drive the
    /// bounded Classic-to-ANCS recovery policy. A live, authenticated Route-A CONTROL owner is
    /// deliberately left untouched while Android waits for ANCS/Service Changed; only an actual
    /// unsubscribe, radio loss, or route failure enters the full drain/new-generation path.
    private func enforceProductionRouteAIfNeeded() {
        guard runtimeMode == .productionRouteA,
              runtimeFailure == nil,
              policy.state.phase == .active,
              policy.state.activeRole == .helperCentralAndroidPeripheral else { return }
        releasedSource = nil
        bootDetail = "Route B завершён как диагностический; переход на основной Route A"
        reduce(nextOrigin: .local, nextToken: Self.randomToken()) { policy in
            policy.requestSwitch(
                to: .helperPeripheralAndroidCentral,
                nowMs: Self.nowMs(),
                stopTimeoutMs: Self.stopTimeoutMs,
                drainDurationMs: Self.drainDurationMs
            )
        }
    }

    private func recoverReleasedActiveOwner(
        role: BleRoleSwitchPolicy.Role,
        generation: BleRoleSwitchPolicy.Sequence,
        reason: String
    ) {
        guard runtimeFailure == nil,
              policy.state.phase == .active,
              policy.state.activeRole == role,
              policy.state.activeGeneration == generation else { return }
        peerReady = false
        telemetryGeneration = nil
        telemetrySequence = 0
        telemetryRefreshPending = false
        let recoveryEpoch = policy.state.epoch.next()
        releasedSource = ReleasedSource(
            epoch: recoveryEpoch,
            generation: generation,
            role: role
        )
        bootDetail = "\(reason): same-role restart только через owner-zero и drain"
        reduce(nextOrigin: .localOnlyRestore, nextToken: Self.randomToken()) { policy in
            policy.requestSameRoleRestart(
                nowMs: Self.nowMs(),
                stopTimeoutMs: Self.stopTimeoutMs,
                drainDurationMs: Self.drainDurationMs
            )
        }
        guard policy.state.sourceRole == role,
              policy.state.sourceGeneration == generation,
              policy.state.epoch == recoveryEpoch else {
            releasedSource = nil
            return
        }
        let epoch = policy.state.epoch
        reduce { policy in
            policy.onIngressFrozen(
                epoch: epoch,
                sourceGeneration: generation,
                sourceRole: role,
                nowMs: Self.nowMs()
            )
        }
        reduce { policy in
            policy.onLocalTerminal(
                epoch: epoch,
                sourceGeneration: generation,
                sourceRole: role,
                nowMs: Self.nowMs()
            )
        }
        reduce { policy in
            policy.onLocalOwnerCount(
                epoch: epoch,
                sourceGeneration: generation,
                sourceRole: role,
                ownerCount: 0,
                nowMs: Self.nowMs()
            )
        }
    }

    private func handleControl(
        _ frame: IphoneBleWireProtocolV2.ControlFrame,
        source: BleRoleSwitchPolicy.Role,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        guard generation == (policy.state.activeGeneration ?? policy.state.sourceGeneration) else {
            return
        }
        switch frame.type {
        case .telemetryRefresh:
            guard policy.state.phase == .active,
                  policy.state.activeRole == source,
                  peerReady,
                  frame.mode == Self.mode(for: source) else { return }
            requestFreshTelemetrySample()

        case .roleClose:
            let target = Self.role(for: frame.mode)
            if policy.state.phase == .active {
                guard runtimeMode == .diagnosticDualRoute ||
                        target == .helperPeripheralAndroidCentral else {
                    bootDetail = "Remote Route-B intent отклонён production-политикой"
                    publishSnapshot()
                    return
                }
                guard source == policy.state.activeRole, target != source else {
                    failRuntime("Remote C запрашивает текущий режим или другого владельца")
                    return
                }
                reduce(nextOrigin: .remote, nextToken: frame.payload) { policy in
                    policy.requestSwitchFromRemoteIntent(
                        to: target,
                        nowMs: Self.nowMs(),
                        stopTimeoutMs: Self.stopTimeoutMs,
                        drainDurationMs: Self.drainDurationMs
                    )
                }
            } else if origin == .remote,
                      target == policy.state.targetRole,
                      frame.payload == switchToken,
                      let epoch = policy.state.sourceGeneration.map({ _ in policy.state.epoch }),
                      let sourceGeneration = policy.state.sourceGeneration,
                      let sourceRole = policy.state.sourceRole {
                reduce { policy in
                    policy.onDuplicateRemoteIntent(
                        epoch: epoch,
                        sourceGeneration: sourceGeneration,
                        sourceRole: sourceRole,
                        nowMs: Self.nowMs()
                    )
                }
            } else {
                failRuntime("Conflicting remote C target/token")
            }

        case .roleCloseAck:
            let target = Self.role(for: frame.mode)
            guard origin == .local,
                  frame.payload == switchToken,
                  target == policy.state.targetRole,
                  source == policy.state.sourceRole,
                  let sourceGeneration = policy.state.sourceGeneration else {
                failRuntime("A не повторяет exact local C target/token")
                return
            }
            reduce { policy in
                policy.onRemoteClosedEvidence(
                    epoch: policy.state.epoch,
                    sourceGeneration: sourceGeneration,
                    stoppedRole: source,
                    evidence: .confirmedAck,
                    nowMs: Self.nowMs()
                )
            }

        case .peerProof:
            break
        }
    }

    /// Coalescing is intentionally local to one outstanding source callback. Android owns the
    /// 30-second quiet policy; Helper merely guarantees that a burst/retry cannot create polling
    /// or parallel route work. A returned sample clears the flag in publishTelemetry.
    private func requestFreshTelemetrySample() {
        guard !telemetryRefreshPending, let callback = onTelemetryRefreshRequest else { return }
        telemetryRefreshPending = true
        callback()
    }

    private func handleControlAccepted(
        _ wireFrame: IphoneBleWireProtocolV2.ControlFrame,
        source: BleRoleSwitchPolicy.Role,
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        guard let expected = pendingTransmit,
              expected.epoch == epoch,
              expected.generation == generation,
              expected.role == source,
              expected.attempt == attempt,
              wireFrame.payload == switchToken,
              Self.role(for: wireFrame.mode) == policy.state.targetRole else { return }
        let reducerFrame: BleRoleSwitchPolicy.ControlFrame = wireFrame.type == .roleClose
            ? .closeRequest : .closeAck
        guard reducerFrame == expected.frame else { return }
        pendingTransmit = nil
        reduce { policy in
            policy.onControlTransmitResult(
                epoch: epoch,
                sourceGeneration: generation,
                sourceRole: source,
                frame: reducerFrame,
                attempt: attempt,
                result: .accepted,
                nowMs: Self.nowMs()
            )
        }
    }

    private func reduce(
        nextOrigin: Origin? = nil,
        nextToken: [UInt8]? = nil,
        _ operation: (inout BleRoleSwitchPolicy) -> BleRoleSwitchPolicy.Reduction
    ) {
        var candidate = policy
        let reduction = operation(&candidate)
        guard reduction.state != policy.state else {
            publishSnapshot()
            return
        }
        let committedOrigin = reduction.state.phase == .active ? nil : (nextOrigin ?? origin)
        let committedToken = reduction.state.phase == .active ? nil : (nextToken ?? switchToken)
        do {
            try Self.persist(
                policy: candidate,
                origin: committedOrigin,
                token: committedToken,
                url: snapshotURL,
                defaults: defaults
            )
        } catch {
            failRuntime("BRS2 write-ahead persistence failed: \(error)")
            return
        }
        policy = candidate
        origin = committedOrigin
        switchToken = committedToken
        if policy.state.phase == .active {
            releasedSource = nil
            pendingTransmit = nil
        }
        for effect in reduction.effects { dispatch(effect) }
        publishSnapshot()
    }

    private func dispatch(_ effect: BleRoleSwitchPolicy.Effect) {
        switch effect.type {
        case .freezeSourceIngress:
            peerReady = false
            telemetryGeneration = nil
            telemetrySequence = 0
            telemetryRefreshPending = false
            if releasedSource == ReleasedSource(
                epoch: effect.epoch,
                generation: effect.generation,
                role: effect.role
            ) { return }
            if effect.role == .helperPeripheralAndroidCentral {
                routes.freezeRouteA(epoch: effect.epoch, generation: effect.generation)
            } else {
                routes.freezeRouteB(epoch: effect.epoch, generation: effect.generation)
            }

        case .armStopTimeout:
            armStopTimeout(effect)

        case .stopLocalSource:
            guard releasedSource != ReleasedSource(
                epoch: effect.epoch,
                generation: effect.generation,
                role: effect.role
            ) else { return }
            if effect.role == .helperPeripheralAndroidCentral {
                routes.stopRouteA(epoch: effect.epoch, generation: effect.generation)
            } else {
                routes.stopRouteB(epoch: effect.epoch, generation: effect.generation)
            }

        case .requestRemoteStop, .acknowledgeRemoteStop:
            transmit(effect)

        case .scheduleControlRetry:
            scheduleControlRetry(effect)

        case .cancelControlRetry:
            retryTimerToken = nil

        case .verifyLocalOwners:
            // Each route emits an explicit count only after its Core Bluetooth manager/wrapper
            // has been released. Never synthesize zero from a requested close.
            break

        case .cancelStopTimeout:
            stopTimerToken = nil

        case .armDrainDeadline:
            armDrainDeadline(effect)

        case .cancelDrainDeadline:
            drainTimerToken = nil

        case .quiescentReached:
            reduce { policy in
                policy.beginTargetStart(
                    epoch: effect.epoch,
                    targetGeneration: effect.generation,
                    targetRole: effect.role
                )
            }

        case .startTarget:
            bootDetail = "Запуск нового локального Core Bluetooth маршрута"
            armTargetStartWatchdog(effect)
            startRoute(effect.role, generation: effect.generation, restorationEpoch: nil)

        case .targetActive:
            cancelTargetStartWatchdog()
            peerReady = false
            telemetryRefreshPending = false
            bootDetail = "Новый локальный маршрут активен; точный Android owner ещё не подключён"

        case .failClosed:
            cancelTargetStartWatchdog()
            runtimeFailure = policy.state.failure == .targetStartFailed
                ? bootDetail
                : "Fail-closed: \(policy.state.failure.rawValue)"
            peerReady = false
            telemetryRefreshPending = false
            if effect.role == .helperPeripheralAndroidCentral {
                routes.forceCloseRouteA(epoch: effect.epoch, generation: effect.generation)
            } else {
                routes.forceCloseRouteB(epoch: effect.epoch, generation: effect.generation)
            }

        case .closeAll:
            peerReady = false
            telemetryRefreshPending = false
            beginClosedCleanup(effect)
        }
    }

    private func beginClosedCleanup(_ effect: BleRoleSwitchPolicy.Effect) {
        guard closedCleanup == nil else { return }
        let routeAGeneration: BleRoleSwitchPolicy.Sequence
        let routeBGeneration: BleRoleSwitchPolicy.Sequence
        if effect.role == .helperPeripheralAndroidCentral {
            routeAGeneration = effect.generation
            routeBGeneration = effect.generation.next()
        } else {
            routeAGeneration = effect.generation.next()
            routeBGeneration = effect.generation
        }
        closedCleanup = ClosedCleanup(
            epoch: effect.epoch,
            routeAGeneration: routeAGeneration,
            routeBGeneration: routeBGeneration
        )
        routes.startClosedRestorationDrain(
            epoch: effect.epoch,
            routeAGeneration: routeAGeneration,
            routeBGeneration: routeBGeneration,
            routeBRequest: routeBStartRequest()
        )
        let timer = UUID()
        closedCleanupTimerToken = timer
        queue.asyncAfter(
            deadline: .now() + .milliseconds(Int(Self.closedCleanupTimeoutMs))
        ) { [weak self] in
            guard let self, self.closedCleanupTimerToken == timer,
                  let cleanup = self.closedCleanup else { return }
            self.closedCleanupTimerToken = nil
            self.routes.forceCloseRestorationNamespaces(
                epoch: cleanup.epoch,
                routeAGeneration: cleanup.routeAGeneration,
                routeBGeneration: cleanup.routeBGeneration
            )
            self.failRuntime(
                "CLOSED сохранён; restoration namespaces принудительно освобождены после bounded timeout"
            )
        }
    }

    private func handleClosedCleanup(_ event: HelperBleRuntimeCoordinator.Event) {
        guard var cleanup = closedCleanup else { return }
        switch event {
        case .routeAReady(let generation) where generation == cleanup.routeAGeneration:
            routes.freezeRouteA(epoch: cleanup.epoch, generation: generation)
        case .routeBReady(_, _, let generation) where generation == cleanup.routeBGeneration:
            routes.freezeRouteB(epoch: cleanup.epoch, generation: generation)
        case .sourceIngressFrozen(let epoch, let generation)
            where epoch == cleanup.epoch && generation == cleanup.routeAGeneration:
            routes.stopRouteA(epoch: epoch, generation: generation)
        case .routeBIngressFrozen(let epoch, let generation)
            where epoch == cleanup.epoch && generation == cleanup.routeBGeneration:
            routes.stopRouteB(epoch: epoch, generation: generation)
        case .sourceLocalTerminal(let epoch, let generation)
            where epoch == cleanup.epoch && generation == cleanup.routeAGeneration:
            cleanup.routeATerminal = true
        case .sourceLocalOwnerCount(let count, let epoch, let generation)
            where epoch == cleanup.epoch && generation == cleanup.routeAGeneration:
            cleanup.routeAOwnersZero = count == 0
        case .routeBLocalTerminal(let epoch, let generation)
            where epoch == cleanup.epoch && generation == cleanup.routeBGeneration:
            cleanup.routeBTerminal = true
        case .routeBLocalOwnerCount(let count, let epoch, let generation)
            where epoch == cleanup.epoch && generation == cleanup.routeBGeneration:
            cleanup.routeBOwnersZero = count == 0
        case .routeFailed(let role, let reason, let generation)
            where (role == .helperPeripheralAndroidCentral &&
                   generation == cleanup.routeAGeneration) ||
                  (role == .helperCentralAndroidPeripheral &&
                   generation == cleanup.routeBGeneration):
            routes.forceCloseRestorationNamespaces(
                epoch: cleanup.epoch,
                routeAGeneration: cleanup.routeAGeneration,
                routeBGeneration: cleanup.routeBGeneration
            )
            failRuntime("CLOSED restoration drain failed: \(reason)")
        default:
            break
        }
        closedCleanup = cleanup
        if cleanup.routeATerminal, cleanup.routeAOwnersZero,
           cleanup.routeBTerminal, cleanup.routeBOwnersZero {
            closedCleanupTimerToken = nil
            bootDetail = "CLOSED: оба restoration namespace terminal, ownerCount=0"
            publishSnapshot()
        }
    }

    private func transmit(_ effect: BleRoleSwitchPolicy.Effect) {
        guard let frame = effect.controlFrame,
              let token = switchToken,
              token.count == IphoneBleWireProtocolV2.controlPayloadBytes,
              let target = policy.state.targetRole else {
            failRuntime("C/A effect lacks persisted token/target")
            return
        }
        let descriptor = PendingTransmit(
            frame: frame,
            epoch: effect.epoch,
            generation: effect.generation,
            role: effect.role,
            attempt: effect.controlAttempt
        )
        pendingTransmit = descriptor
        let mode = Self.mode(for: target)
        if effect.role == .helperPeripheralAndroidCentral {
            if frame == .closeRequest {
                routes.sendRouteARoleClose(
                    targetMode: mode,
                    switchToken: token,
                    epoch: effect.epoch,
                    generation: effect.generation,
                    attempt: effect.controlAttempt
                )
            } else {
                routes.sendRouteARoleCloseAck(
                    targetMode: mode,
                    switchToken: token,
                    epoch: effect.epoch,
                    generation: effect.generation,
                    attempt: effect.controlAttempt
                )
            }
        } else if frame == .closeRequest {
            routes.sendRouteBRoleClose(
                targetMode: mode,
                switchToken: token,
                epoch: effect.epoch,
                generation: effect.generation,
                attempt: effect.controlAttempt
            )
        } else {
            routes.sendRouteBRoleCloseAck(
                targetMode: mode,
                switchToken: token,
                epoch: effect.epoch,
                generation: effect.generation,
                attempt: effect.controlAttempt
            )
        }
    }

    private func scheduleControlRetry(_ effect: BleRoleSwitchPolicy.Effect) {
        guard let frame = effect.controlFrame else { return }
        let token = UUID()
        retryTimerToken = token
        let now = Self.nowMs()
        let deadline = effect.deadlineMs ?? now
        let remaining = deadline > now ? deadline - now : 0
        let delay = min(Self.controlRetryDelayMs, remaining / 2)
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay))) { [weak self] in
            guard let self, self.retryTimerToken == token else { return }
            self.retryTimerToken = nil
            self.reduce { policy in
                policy.onControlTransmitRetry(
                    epoch: effect.epoch,
                    sourceGeneration: effect.generation,
                    sourceRole: effect.role,
                    frame: frame,
                    attempt: effect.controlAttempt,
                    nowMs: Self.nowMs()
                )
            }
        }
    }

    private func armStopTimeout(_ effect: BleRoleSwitchPolicy.Effect) {
        guard let deadline = effect.deadlineMs else { return }
        let token = UUID()
        stopTimerToken = token
        let now = Self.nowMs()
        let delay = deadline > now ? deadline - now : 0
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay))) { [weak self] in
            guard let self, self.stopTimerToken == token else { return }
            self.stopTimerToken = nil
            self.reduce { policy in
                policy.onStopTimeout(
                    epoch: effect.epoch,
                    sourceGeneration: effect.generation,
                    sourceRole: effect.role,
                    nowMs: Self.nowMs()
                )
            }
        }
    }

    private func armDrainDeadline(_ effect: BleRoleSwitchPolicy.Effect) {
        guard let deadline = effect.deadlineMs else { return }
        let token = UUID()
        drainTimerToken = token
        let now = Self.nowMs()
        let delay = deadline > now ? deadline - now : 0
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay))) { [weak self] in
            guard let self, self.drainTimerToken == token else { return }
            self.drainTimerToken = nil
            self.reduce { policy in
                policy.onDrainDeadline(
                    epoch: effect.epoch,
                    sourceGeneration: effect.generation,
                    sourceRole: effect.role,
                    nowMs: Self.nowMs()
                )
            }
        }
    }

    private func armTargetStartWatchdog(_ effect: BleRoleSwitchPolicy.Effect) {
        let token = UUID()
        targetStartTimerToken = token
        queue.asyncAfter(
            deadline: .now() + .milliseconds(Int(Self.targetStartTimeoutMs))
        ) { [weak self] in
            guard let self, self.targetStartTimerToken == token,
                  self.policy.state.phase == .starting,
                  self.policy.state.epoch == effect.epoch,
                  self.policy.state.targetGeneration == effect.generation,
                  self.policy.state.targetRole == effect.role else { return }
            self.bootDetail = "Core Bluetooth не подтвердил локальный запуск; безопасный retry доступен"
            self.reduce { policy in
                policy.onTargetStartFailed(
                    epoch: effect.epoch,
                    targetGeneration: effect.generation,
                    targetRole: effect.role
                )
            }
        }
    }

    private func cancelTargetStartWatchdog() {
        targetStartTimerToken = nil
    }

    private func startRoute(
        _ role: BleRoleSwitchPolicy.Role,
        generation: BleRoleSwitchPolicy.Sequence,
        restorationEpoch: BleRoleSwitchPolicy.Sequence?
    ) {
        peerReady = false
        telemetryRefreshPending = false
        if role == .helperPeripheralAndroidCentral {
            let disposition: HelperPeripheralRoute.RestorationDisposition
            if let restorationEpoch, origin == .legacyMigration {
                disposition = .drainLegacyMigration(epoch: restorationEpoch)
            } else if let restorationEpoch, origin == .localOnlyRestore {
                disposition = .drainLocalOnlyRestore(epoch: restorationEpoch)
            } else if let restorationEpoch {
                disposition = .drainInactiveRoute(epoch: restorationEpoch)
            } else {
                disposition = .resumeSelectedRoute
            }
            routes.startRouteA(generation: generation, restoration: disposition)
        } else {
            let disposition: HelperCentralRoute.RestorationDisposition
            if let restorationEpoch, origin == .legacyMigration {
                disposition = .drainLegacyMigration(epoch: restorationEpoch)
            } else if let restorationEpoch {
                disposition = .drainInactiveRoute(epoch: restorationEpoch)
            } else {
                disposition = .resumeSelectedRoute
            }
            routes.startRouteB(
                generation: generation,
                request: routeBStartRequest(),
                restoration: disposition
            )
        }
    }

    private func routeBStartRequest() -> HelperCentralRoute.StartRequest {
        let peripheral = defaults.string(forKey: "KX11ANCSHelper.v2.centralPeripheralID")
            .flatMap(UUID.init(uuidString:))
        let installation = defaults.string(forKey: "KX11ANCSHelper.v2.androidInstallationUUID")
            .flatMap(UUID.init(uuidString:))
        return .init(selectedPeripheralID: peripheral, expectedAndroidInstallationID: installation)
    }

    private func failRuntime(_ reason: String) {
        runtimeFailure = reason
        peerReady = false
        telemetryRefreshPending = false
        bootDetail = reason
        let targetIsLive = policy.state.phase == .starting
        let role = policy.state.activeRole ??
            (targetIsLive ? policy.state.targetRole : policy.state.sourceRole)
        let generation = policy.state.activeGeneration ??
            (targetIsLive ? policy.state.targetGeneration : policy.state.sourceGeneration)
        if let role, let generation {
            if role == .helperPeripheralAndroidCentral {
                routes.forceCloseRouteA(epoch: policy.state.epoch, generation: generation)
            } else {
                routes.forceCloseRouteB(epoch: policy.state.epoch, generation: generation)
            }
        }
        publishSnapshot()
    }

    private func publishSnapshot() {
        let state = policy.state
        let desired = state.desiredRole ?? state.activeRole ?? .helperPeripheralAndroidCentral
        let phase: Phase
        let active: BleRoleSwitchPolicy.Role?
        if runtimeFailure != nil || state.phase == .failed {
            phase = .failed
            active = nil
        } else if state.phase == .active {
            phase = .active
            active = state.activeRole
        } else {
            phase = .switching
            active = nil
        }
        let detail = runtimeFailure ?? "\(bootDetail) · reducer=\(state.phase.rawValue) · epoch=\(state.epoch)"
        let value = Snapshot(
            desiredRole: desired,
            activeRole: active,
            phase: phase,
            detail: detail,
            routeBDiagnosticsEnabled: runtimeMode == .diagnosticDualRoute
        )
        DispatchQueue.main.async { [weak self] in self?.onSnapshot?(value) }
    }

    private static func persist(
        policy: BleRoleSwitchPolicy,
        origin: Origin?,
        token: [UInt8]?,
        url: URL,
        defaults: UserDefaults
    ) throws {
        let s = policy.state
        guard let desiredRole = s.desiredRole ?? s.activeRole else {
            throw PersistenceError.incompleteDrainSnapshot
        }
        let snapshot = PersistedSnapshot(
            schema: PersistedSnapshot.schemaValue,
            reducerPhase: s.phase,
            desiredRole: desiredRole,
            activeRole: s.activeRole,
            activeGeneration: s.activeGeneration,
            sourceRole: s.sourceRole,
            sourceGeneration: s.sourceGeneration,
            targetRole: s.targetRole,
            targetGeneration: s.targetGeneration,
            epoch: s.epoch,
            failure: s.failure,
            origin: s.phase == .active ? nil : origin,
            switchToken: s.phase == .active ? nil : token
        )
        if s.phase != .active && s.phase != .closed {
            guard origin != nil, token?.count == IphoneBleWireProtocolV2.controlPayloadBytes else {
                throw PersistenceError.incompleteDrainSnapshot
            }
        }
        let encoded = try JSONEncoder().encode(snapshot)
        try durableAtomicWrite(encoded, to: url)
        guard let reread = try? Data(contentsOf: url), reread == encoded,
              let verified = try? JSONDecoder().decode(PersistedSnapshot.self, from: reread),
              verified == snapshot, isValid(verified) else {
            throw PersistenceError.verificationFailed
        }
        // Non-authoritative migration/debug hint. Runtime restoration always prefers the fsynced
        // Application Support record and treats a corrupt present record as fail-closed.
        defaults.set(encoded, forKey: snapshotKey)
    }

    private static func loadSnapshot(
        url: URL,
        defaults: UserDefaults
    ) throws -> PersistedSnapshot? {
        let data: Data
        if FileManager.default.fileExists(atPath: url.path) {
            guard let persisted = try? Data(contentsOf: url) else {
                throw PersistenceError.invalidPersistedSnapshot
            }
            data = persisted
        } else if let hint = defaults.object(forKey: snapshotKey) {
            guard let migrationHint = hint as? Data else {
                throw PersistenceError.invalidPersistedSnapshot
            }
            data = migrationHint
        } else {
            return nil
        }
        guard let snapshot = try? JSONDecoder().decode(PersistedSnapshot.self, from: data),
              snapshot.schema == PersistedSnapshot.schemaValue,
              isValid(snapshot) else {
            throw PersistenceError.invalidPersistedSnapshot
        }
        return snapshot
    }

    private static func loadLegacyRole(defaults: UserDefaults) throws -> BleRoleSwitchPolicy.Role? {
        guard let rawObject = defaults.object(forKey: legacyRoleKey) else { return nil }
        guard let raw = rawObject as? NSNumber else {
            throw PersistenceError.invalidLegacyRole
        }
        switch raw.intValue {
        case 0:
            return .helperPeripheralAndroidCentral
        case 1:
            return .helperCentralAndroidPeripheral
        default:
            throw PersistenceError.invalidLegacyRole
        }
    }

    private static func makeSnapshotURL() throws -> URL {
        guard let root = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else { throw PersistenceError.applicationSupportUnavailable }
        let directory = root.appendingPathComponent("KX11ANCSHelper-v2", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return directory.appendingPathComponent("role-switch-brs2.json", isDirectory: false)
    }

    private static func durableAtomicWrite(_ data: Data, to destination: URL) throws {
        let directory = destination.deletingLastPathComponent()
        let temporary = directory.appendingPathComponent(".brs2-\(UUID().uuidString).tmp")
        do {
            try data.write(to: temporary, options: .withoutOverwriting)
            try FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: temporary.path
            )
            let handle = try FileHandle(forWritingTo: temporary)
            try handle.synchronize()
            try handle.close()
            guard Darwin.rename(temporary.path, destination.path) == 0 else {
                throw PersistenceError.posix(errno)
            }
            let directoryDescriptor = Darwin.open(directory.path, O_RDONLY)
            guard directoryDescriptor >= 0 else { throw PersistenceError.posix(errno) }
            defer { Darwin.close(directoryDescriptor) }
            guard Darwin.fsync(directoryDescriptor) == 0 else {
                throw PersistenceError.posix(errno)
            }
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
    }

    private static func restorePolicy(from snapshot: PersistedSnapshot) throws -> (
        policy: BleRoleSwitchPolicy,
        origin: Origin,
        token: [UInt8],
        effects: [BleRoleSwitchPolicy.Effect]
    ) {
        guard isValid(snapshot) else { throw PersistenceError.invalidPersistedSnapshot }
        if snapshot.reducerPhase == .closed {
            guard let source = snapshot.sourceRole, let sourceGeneration = snapshot.sourceGeneration else {
                throw PersistenceError.invalidPersistedSnapshot
            }
            let maximumGeneration = [
                snapshot.activeGeneration, snapshot.sourceGeneration, snapshot.targetGeneration
            ].compactMap { $0 }.max(by: { Self.sequenceLessThan($0, $1) }) ?? sourceGeneration
            var closedPolicy = BleRoleSwitchPolicy(
                state: .active(
                    source,
                    lastEpoch: snapshot.epoch,
                    generation: maximumGeneration.next()
                )
            )
            let closed = closedPolicy.close()
            return (closedPolicy, .localOnlyRestore, randomToken(), closed.effects)
        }
        let targetMayExist = snapshot.reducerPhase == .starting ||
            (snapshot.reducerPhase == .failed && snapshot.failure == .targetStartFailed)
        let drainRole: BleRoleSwitchPolicy.Role
        let drainGeneration: BleRoleSwitchPolicy.Sequence
        let restoreOrigin: Origin
        if snapshot.reducerPhase == .active {
            guard let activeRole = snapshot.activeRole,
                  let activeGeneration = snapshot.activeGeneration else {
                throw PersistenceError.invalidPersistedSnapshot
            }
            drainRole = activeRole
            drainGeneration = activeGeneration
            restoreOrigin = .localOnlyRestore
        } else if targetMayExist {
            guard let targetRole = snapshot.targetRole,
                  let targetGeneration = snapshot.targetGeneration else {
                throw PersistenceError.invalidPersistedSnapshot
            }
            drainRole = targetRole
            drainGeneration = targetGeneration
            restoreOrigin = .localOnlyRestore
        } else {
            guard let sourceRole = snapshot.sourceRole,
                  let sourceGeneration = snapshot.sourceGeneration else {
                throw PersistenceError.invalidPersistedSnapshot
            }
            drainRole = sourceRole
            drainGeneration = sourceGeneration
            restoreOrigin = snapshot.origin ?? .localOnlyRestore
        }
        let maximumGeneration = [
            snapshot.activeGeneration, snapshot.sourceGeneration, snapshot.targetGeneration
        ].compactMap { $0 }.max(by: { Self.sequenceLessThan($0, $1) }) ?? drainGeneration
        let nextEpoch = snapshot.epoch.next()
        let nextGeneration = maximumGeneration.next()
        let token = restoreOrigin == .localOnlyRestore
            ? randomToken()
            : (snapshot.switchToken ?? randomToken())
        var policy = BleRoleSwitchPolicy(
            state: .active(drainRole, lastEpoch: snapshot.epoch, generation: drainGeneration)
        )
        let reduction: BleRoleSwitchPolicy.Reduction
        switch restoreOrigin {
        case .remote:
            reduction = policy.restoreDrainFromRemoteIntent(
                sourceRole: drainRole,
                desiredRole: snapshot.desiredRole,
                epoch: nextEpoch,
                sourceGeneration: drainGeneration,
                targetGeneration: nextGeneration,
                nowMs: nowMs(),
                stopTimeoutMs: stopTimeoutMs,
                drainDurationMs: drainDurationMs
            )
        case .localOnlyRestore, .legacyMigration:
            reduction = policy.restoreDrainLocalOnly(
                role: drainRole,
                epoch: nextEpoch,
                sourceGeneration: drainGeneration,
                targetGeneration: nextGeneration,
                nowMs: nowMs(),
                stopTimeoutMs: stopTimeoutMs,
                drainDurationMs: drainDurationMs
            )
        case .local:
            reduction = policy.restoreDrain(
                sourceRole: drainRole,
                desiredRole: snapshot.desiredRole,
                epoch: nextEpoch,
                sourceGeneration: drainGeneration,
                targetGeneration: nextGeneration,
                nowMs: nowMs(),
                stopTimeoutMs: stopTimeoutMs,
                drainDurationMs: drainDurationMs
            )
        }
        return (policy, restoreOrigin, token, reduction.effects)
    }

    private static func isValid(_ snapshot: PersistedSnapshot) -> Bool {
        func nonZero(_ value: BleRoleSwitchPolicy.Sequence?) -> Bool {
            value.map { !$0.isZero } ?? false
        }
        if snapshot.reducerPhase == .active {
            return snapshot.activeRole == snapshot.desiredRole &&
                nonZero(snapshot.activeGeneration) &&
                snapshot.sourceRole == nil && snapshot.targetRole == nil &&
                snapshot.origin == nil && snapshot.switchToken == nil
        }
        if snapshot.reducerPhase == .closed {
            return snapshot.sourceRole != nil && nonZero(snapshot.sourceGeneration)
        }
        guard snapshot.sourceRole != nil, nonZero(snapshot.sourceGeneration),
              snapshot.targetRole == snapshot.desiredRole, nonZero(snapshot.targetGeneration),
              snapshot.origin != nil,
              let token = snapshot.switchToken,
              token.count == IphoneBleWireProtocolV2.controlPayloadBytes,
              token.reduce(UInt8(0), |) != 0 else { return false }
        return true
    }

    private static func mode(for role: BleRoleSwitchPolicy.Role) -> IphoneBleWireProtocolV2.Mode {
        role == .helperPeripheralAndroidCentral ? .androidCentral : .androidPeripheral
    }

    private static func role(for mode: IphoneBleWireProtocolV2.Mode) -> BleRoleSwitchPolicy.Role {
        mode == .androidCentral ? .helperPeripheralAndroidCentral : .helperCentralAndroidPeripheral
    }

    private static func randomToken() -> [UInt8] {
        var raw = UUID().uuid
        return withUnsafeBytes(of: &raw) { Array($0) }
    }

    private static func nowMs() -> UInt64 {
        DispatchTime.now().uptimeNanoseconds / 1_000_000
    }

    private static func sequenceLessThan(
        _ lhs: BleRoleSwitchPolicy.Sequence,
        _ rhs: BleRoleSwitchPolicy.Sequence
    ) -> Bool {
        if lhs.description.count != rhs.description.count {
            return lhs.description.count < rhs.description.count
        }
        return lhs.description < rhs.description
    }

    private enum PersistenceError: Error {
        case incompleteDrainSnapshot
        case invalidPersistedSnapshot
        case applicationSupportUnavailable
        case verificationFailed
        case invalidLegacyRole
        case posix(Int32)
    }
}
