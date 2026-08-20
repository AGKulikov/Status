import CoreBluetooth
import Foundation

/// Route B only: iPhone Helper Central / Android Peripheral.
///
/// The Helper never parses ANCS in this topology. `RequiresANCS` exposes the iPhone ANCS provider
/// to Android's exact reverse client on the same physical link. This namespace owns one
/// CBCentralManager and no Peripheral-role object or relay service.
public enum HelperCentralRoute {
    public static let serviceUUID = CBUUID(string: "D2D9E4C0-47F1-4E44-A8BB-A932FD5AF202")
    public static let peerProofUUID = CBUUID(string: "D2D9E4C1-47F1-4E44-A8BB-A932FD5AF200")
    public static let controlUUID = CBUUID(string: "D2D9E4C2-47F1-4E44-A8BB-A932FD5AF200")
    public static let telemetryUUID = CBUUID(string: "D2D9E4C3-47F1-4E44-A8BB-A932FD5AF200")
    public static let carRemoteUUID = CBUUID(string: "D2D9E4C5-47F1-4E44-A8BB-A932FD5AF200")

    public struct Configuration {
        public var restorationIdentifier: String
        public var restorationPeripheralKey: String
        public var restorationPeerProofKey: String
        public var defaults: UserDefaults

        public init(
            restorationIdentifier: String = "ru.natro.kx11ancshelper.central.stable",
            restorationPeripheralKey: String = "KX11ANCSHelper.v2.centralPeripheralID",
            restorationPeerProofKey: String = "KX11ANCSHelper.v2.androidInstallationUUID",
            defaults: UserDefaults = .standard
        ) {
            self.restorationIdentifier = restorationIdentifier
            self.restorationPeripheralKey = restorationPeripheralKey
            self.restorationPeerProofKey = restorationPeerProofKey
            self.defaults = defaults
        }
    }

    public struct StartRequest: Equatable {
        /// Core Bluetooth's stable app-scoped identifier, never a BLE address or local name.
        public let selectedPeripheralID: UUID?
        public let expectedAndroidInstallationID: UUID?

        public init(
            selectedPeripheralID: UUID?,
            expectedAndroidInstallationID: UUID?
        ) {
            self.selectedPeripheralID = selectedPeripheralID
            self.expectedAndroidInstallationID = expectedAndroidInstallationID
        }
    }

    public enum RestorationDisposition: Equatable {
        case resumeSelectedRoute
        case drainInactiveRoute(epoch: BleRoleSwitchPolicy.Sequence)
        case drainLegacyMigration(epoch: BleRoleSwitchPolicy.Sequence)
    }

    public enum Lifecycle: Equatable {
        case idle
        case waitingForRadio(generation: BleRoleSwitchPolicy.Sequence)
        case scanning(generation: BleRoleSwitchPolicy.Sequence)
        case connecting(generation: BleRoleSwitchPolicy.Sequence)
        case discoveringService(generation: BleRoleSwitchPolicy.Sequence)
        case discoveringCharacteristics(generation: BleRoleSwitchPolicy.Sequence)
        case readingPeerProof(generation: BleRoleSwitchPolicy.Sequence)
        case subscribingControl(generation: BleRoleSwitchPolicy.Sequence)
        case writingHello(generation: BleRoleSwitchPolicy.Sequence)
        case subscribingCarRemote(generation: BleRoleSwitchPolicy.Sequence)
        case active(generation: BleRoleSwitchPolicy.Sequence)
        case freezing(epoch: BleRoleSwitchPolicy.Sequence, generation: BleRoleSwitchPolicy.Sequence)
        case stopping(epoch: BleRoleSwitchPolicy.Sequence, generation: BleRoleSwitchPolicy.Sequence)
        case migrationDrain(generation: BleRoleSwitchPolicy.Sequence)
        case terminal(epoch: BleRoleSwitchPolicy.Sequence, generation: BleRoleSwitchPolicy.Sequence)
        case failed(generation: BleRoleSwitchPolicy.Sequence, reason: String)
    }

    public protocol Observer: AnyObject {
        func helperCentralRoute(
            _ route: Coordinator,
            didBecomeReady androidInstallationID: UUID,
            peripheralID: UUID,
            generation: BleRoleSwitchPolicy.Sequence
        )
        /// The local central manager is powered on and has entered its scan/retrieve lane. Exact
        /// peer proof and CONTROL readiness are reported separately by didBecomeReady.
        func helperCentralRoute(
            _ route: Coordinator,
            didBecomeOperational generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didFreeze epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didReceiveControl frame: IphoneBleWireProtocolV2.ControlFrame,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didReceiveCarRemoteFrame frame: Data,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didAcceptOutboundControl frame: IphoneBleWireProtocolV2.ControlFrame,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didBecomeTerminal epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didObserveLocalOwnerCount count: Int,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didFreezeWithoutRemoteOwner epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didLoseExactLink generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didLoseFrozenExactLink epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didEncounterUnprovableRestoration generation: BleRoleSwitchPolicy.Sequence
        )
        func helperCentralRoute(
            _ route: Coordinator,
            didFail reason: String,
            generation: BleRoleSwitchPolicy.Sequence
        )
    }

    public final class Coordinator: NSObject {
        private static let managerStartupTimeout = DispatchTimeInterval.seconds(8)
        private static let failedOwnerReleaseTimeout = DispatchTimeInterval.seconds(5)
        private enum WriteKind: Equatable {
            case hello
            case telemetry
            case carRemote
            case roleControl(
                frame: IphoneBleWireProtocolV2.ControlFrame,
                epoch: BleRoleSwitchPolicy.Sequence,
                attempt: BleRoleSwitchPolicy.Sequence
            )
        }

        private struct PendingWrite: Equatable {
            let kind: WriteKind
            let data: Data
        }

        public weak var observer: Observer?
        public let installationID: UUID

        private let queue: DispatchQueue
        private let configuration: Configuration
        private let hello: Data
        private var lifecycle: Lifecycle = .idle
        private var generation: BleRoleSwitchPolicy.Sequence?
        private var request: StartRequest?
        private var manager: CBCentralManager?
        private var peripheral: CBPeripheral?
        private var service: CBService?
        private var peerProofCharacteristic: CBCharacteristic?
        private var controlCharacteristic: CBCharacteristic?
        private var telemetryCharacteristic: CBCharacteristic?
        private var carRemoteCharacteristic: CBCharacteristic?
        private var learnedAndroidInstallationID: UUID?
        private var acceptingTelemetry = false
        private var deadlineToken: UUID?
        private var restorationCallbackObserved = false
        private var inFlightWrite: PendingWrite?
        private var queuedControlWrite: PendingWrite?
        private var pendingTelemetry: Data?
        private var queuedCarRemoteFrames: [Data] = []
        private var freezeCallbackPending = false
        private var stopAfterControlAcceptance: (
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )?
        private var cancelIssued = false
        private var outboundCloseExpectation: IphoneBleWireProtocolV2.ControlFrame?
        private var inboundCloseIntent: IphoneBleWireProtocolV2.ControlFrame?
        private var lastAcceptedControlWrite: PendingWrite?
        private var unprovableRestoredOwners: [UUID: CBPeripheral] = [:]
        private var restorationDisposition: RestorationDisposition = .resumeSelectedRoute
        private var restorationObservationToken: UUID?
        private var operationalEmittedGeneration: BleRoleSwitchPolicy.Sequence?
        private var managerStartupTimerToken: UUID?
        private var failedOwnerReleaseTimerToken: UUID?
        private var pendingFailureReason: String?

        internal init(
            ownerToken: HelperBleRuntimeCoordinator.RouteOwnerToken,
            installationID: UUID,
            configuration: Configuration
        ) throws {
            _ = ownerToken
            self.installationID = installationID
            self.configuration = configuration
            self.queue = DispatchQueue(
                label: "ru.natro.kx11ancshelper.v53.routeB.central",
                qos: .userInitiated
            )
            self.hello = Data(try IphoneBleWireProtocolV2.encodePeerProof(
                mode: .androidPeripheral,
                installationID: Self.canonicalBytes(installationID),
                telemetrySupported: true,
                ancsSupported: true
            ))
            super.init()
        }

        public func start(
            generation: BleRoleSwitchPolicy.Sequence,
            request: StartRequest,
            restoration: RestorationDisposition = .resumeSelectedRoute
        ) {
            precondition(!generation.isZero, "a live route generation must be non-zero")
            queue.async { [weak self] in
                self?.startOnQueue(
                    generation: generation,
                    request: request,
                    restoration: restoration
                )
            }
        }

        public func publishTelemetry(
            _ telemetry: IphoneBleWireProtocolV2.Telemetry,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation, self.acceptingTelemetry else {
                    return
                }
                do {
                    self.pendingTelemetry = Data(try IphoneBleWireProtocolV2.encodeTelemetry(
                        batteryPercent: telemetry.batteryPercent,
                        externalPower: telemetry.externalPower,
                        chargeState: telemetry.chargeState,
                        network: telemetry.network,
                        locked: telemetry.locked,
                        sequence: telemetry.sequence
                    ))
                    self.pumpWriteQueue()
                } catch {
                    self.failAndDrain("invalid telemetry: \(error)", generation: generation)
                }
            }
        }

        public func sendCarRemoteFrame(
            _ frame: Data,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, frame.count == CarRemoteProtocolV1.frameBytes,
                      self.generation == generation,
                      case .active(let activeGeneration) = self.lifecycle,
                      activeGeneration == generation,
                      self.carRemoteCharacteristic != nil else { return }
                if self.queuedCarRemoteFrames.count >= 64 {
                    self.queuedCarRemoteFrames.removeFirst()
                }
                self.queuedCarRemoteFrames.append(frame)
                self.pumpWriteQueue()
            }
        }

        public func freezeIngress(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                if case .freezing(let frozenEpoch, let frozenGeneration) = self.lifecycle,
                   frozenEpoch == epoch, frozenGeneration == generation {
                    return
                }
                if case .active(let activeGeneration) = self.lifecycle,
                   activeGeneration == generation {
                    self.acceptingTelemetry = false
                    self.pendingTelemetry = nil
                    self.queuedCarRemoteFrames.removeAll(keepingCapacity: false)
                    self.lifecycle = .freezing(epoch: epoch, generation: generation)
                    if self.inFlightWrite?.kind == .telemetry
                        || self.inFlightWrite?.kind == .carRemote {
                        self.freezeCallbackPending = true
                    } else {
                        self.emitFrozen(epoch: epoch, generation: generation)
                    }
                    return
                }

                // Scanning/connecting/discovery has no authenticated v2 CONTROL owner yet. Fence
                // acquisition, report the typed atomic no-owner boundary, and locally drain the
                // candidate instead of deadlocking role selection until a peer happens to appear.
                self.acceptingTelemetry = false
                self.pendingTelemetry = nil
                self.queuedCarRemoteFrames.removeAll(keepingCapacity: false)
                self.lifecycle = .freezing(epoch: epoch, generation: generation)
                self.deadlineToken = nil
                manager?.stopScan()
                self.observer?.helperCentralRoute(
                    self,
                    didFreezeWithoutRemoteOwner: epoch,
                    generation: generation
                )
                if let manager = self.manager, let peripheral = self.peripheral,
                   (peripheral.state == .connected || peripheral.state == .connecting ||
                    peripheral.state == .disconnecting) {
                    self.lifecycle = .stopping(epoch: epoch, generation: generation)
                    self.cancelIssued = true
                    if peripheral.state != .disconnecting {
                        manager.cancelPeripheralConnection(peripheral)
                    }
                } else {
                    self.releaseAfterExactDisconnect(
                        epoch: epoch,
                        generation: generation,
                        unexpected: false
                    )
                }
            }
        }

        public func sendRoleClose(
            targetMode: IphoneBleWireProtocolV2.Mode,
            switchToken: [UInt8],
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        ) {
            enqueueRoleControl(
                type: .roleClose,
                targetMode: targetMode,
                switchToken: switchToken,
                epoch: epoch,
                generation: generation,
                attempt: attempt
            )
        }

        public func sendRoleCloseAck(
            targetMode: IphoneBleWireProtocolV2.Mode,
            switchToken: [UInt8],
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        ) {
            enqueueRoleControl(
                type: .roleCloseAck,
                targetMode: targetMode,
                switchToken: switchToken,
                epoch: epoch,
                generation: generation,
                attempt: attempt
            )
        }

        public func stop(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                if case .stopping(let stoppingEpoch, let stoppingGeneration) = self.lifecycle,
                   stoppingEpoch == epoch, stoppingGeneration == generation {
                    // Pre-auth no-owner freeze owns its client cancel immediately. The reducer's
                    // subsequent STOP is an exact idempotent confirmation, never a second cancel.
                    return
                }
                guard case .freezing(let frozenEpoch, let frozenGeneration) = self.lifecycle,
                      frozenEpoch == epoch, frozenGeneration == generation else {
                    self.failAndDrain(
                        "stop before exact Route-B ingress-frozen barrier",
                        generation: generation
                    )
                    return
                }
                self.lifecycle = .stopping(epoch: epoch, generation: generation)
                if self.queuedControlWrite != nil || self.isCriticalControlWriteInFlight {
                    self.stopAfterControlAcceptance = (epoch, generation)
                    return
                }
                self.issueDeterministicClientClose(epoch: epoch, generation: generation)
            }
        }

        /// CLOSED-tombstone bounded fallback. It cancels any restored client wrapper and releases
        /// the manager locally; no target route is allowed to start after this terminal cleanup.
        public func forceCloseRestorationNamespace(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                if let manager = self.manager, let peripheral = self.peripheral,
                   (peripheral.state == .connected || peripheral.state == .connecting) {
                    manager.cancelPeripheralConnection(peripheral)
                }
                self.unprovableRestoredOwners.removeAll(keepingCapacity: false)
                self.releaseUnconnectedOwner(generation: generation)
                self.lifecycle = .terminal(epoch: epoch, generation: generation)
                self.observer?.helperCentralRoute(
                    self,
                    didBecomeTerminal: epoch,
                    generation: generation
                )
                self.observer?.helperCentralRoute(
                    self,
                    didObserveLocalOwnerCount: 0,
                    epoch: epoch,
                    generation: generation
                )
            }
        }

        private func startOnQueue(
            generation: BleRoleSwitchPolicy.Sequence,
            request: StartRequest,
            restoration: RestorationDisposition
        ) {
            guard manager == nil else {
                if self.generation == generation { return }
                failAndDrain("second CBCentralManager owner denied", generation: generation)
                return
            }
            self.generation = generation
            self.request = request
            self.restorationDisposition = restoration
            self.restorationObservationToken = nil
            self.lifecycle = .waitingForRadio(generation: generation)
            self.restorationCallbackObserved = false
            self.acceptingTelemetry = false
            self.cancelIssued = false
            self.deadlineToken = nil
            self.inFlightWrite = nil
            self.queuedControlWrite = nil
            self.pendingTelemetry = nil
            self.queuedCarRemoteFrames.removeAll(keepingCapacity: false)
            self.freezeCallbackPending = false
            self.stopAfterControlAcceptance = nil
            self.lastAcceptedControlWrite = nil
            self.inboundCloseIntent = nil
            self.outboundCloseExpectation = nil
            self.learnedAndroidInstallationID = nil
            self.unprovableRestoredOwners.removeAll(keepingCapacity: false)
            self.operationalEmittedGeneration = nil
            self.managerStartupTimerToken = nil
            self.failedOwnerReleaseTimerToken = nil
            self.pendingFailureReason = nil
            let options: [String: Any] = [
                CBCentralManagerOptionRestoreIdentifierKey: configuration.restorationIdentifier,
                CBCentralManagerOptionShowPowerAlertKey: true
            ]
            manager = CBCentralManager(delegate: self, queue: queue, options: options)
            armManagerStartupWatchdog(generation: generation)
        }

        private var restorationDrainEpoch: BleRoleSwitchPolicy.Sequence? {
            if case let .drainInactiveRoute(epoch) = restorationDisposition { return epoch }
            if case let .drainLegacyMigration(epoch) = restorationDisposition { return epoch }
            return nil
        }

        private var permitsLegacyMigrationDrain: Bool {
            if case .drainLegacyMigration = restorationDisposition { return true }
            return false
        }

        private func scheduleEmptyRestorationObservation(
            on manager: CBCentralManager,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard restorationDrainEpoch != nil, restorationObservationToken == nil else { return }
            let token = UUID()
            restorationObservationToken = token
            queue.asyncAfter(deadline: .now() + .milliseconds(250)) { [weak self, weak manager] in
                guard let self, let manager, self.manager === manager,
                      self.generation == generation,
                      self.restorationObservationToken == token,
                      !self.restorationCallbackObserved,
                      self.peripheral == nil else { return }
                self.restorationObservationToken = nil
                self.finishRestorationNoOwner(generation: generation)
            }
        }

        private func finishRestorationNoOwner(generation: BleRoleSwitchPolicy.Sequence) {
            guard let epoch = restorationDrainEpoch, self.generation == generation else { return }
            restorationObservationToken = nil
            observer?.helperCentralRoute(
                self,
                didFreezeWithoutRemoteOwner: epoch,
                generation: generation
            )
            releaseUnconnectedOwner(generation: generation)
            lifecycle = .terminal(epoch: epoch, generation: generation)
            observer?.helperCentralRoute(self, didBecomeTerminal: epoch, generation: generation)
            observer?.helperCentralRoute(
                self,
                didObserveLocalOwnerCount: 0,
                epoch: epoch,
                generation: generation
            )
        }

        private func finishLegacyRestorationDrain(generation: BleRoleSwitchPolicy.Sequence) {
            guard permitsLegacyMigrationDrain,
                  let epoch = restorationDrainEpoch,
                  self.generation == generation else { return }
            unprovableRestoredOwners.removeAll(keepingCapacity: false)
            releaseUnconnectedOwner(generation: generation)
            observer?.helperCentralRoute(
                self,
                didFreezeWithoutRemoteOwner: epoch,
                generation: generation
            )
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                self.lifecycle = .terminal(epoch: epoch, generation: generation)
                self.observer?.helperCentralRoute(
                    self,
                    didBecomeTerminal: epoch,
                    generation: generation
                )
                self.observer?.helperCentralRoute(
                    self,
                    didObserveLocalOwnerCount: self.manager == nil ? 0 : 1,
                    epoch: epoch,
                    generation: generation
                )
            }
        }

        private func beginAcquisition(on manager: CBCentralManager) {
            guard let generation, peripheral == nil else { return }
            emitOperationalOnce(generation: generation)
            if let selected = request?.selectedPeripheralID {
                let exact = manager.retrievePeripherals(withIdentifiers: [selected])
                    .filter { $0.identifier == selected }
                if exact.count == 1, let candidate = exact.first {
                    connect(candidate, on: manager, generation: generation)
                    return
                }
            }
            lifecycle = .scanning(generation: generation)
            manager.scanForPeripherals(
                withServices: [HelperCentralRoute.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
            // Absence of the Android peer is not a local Core Bluetooth failure. Scanning may
            // remain idle indefinitely while the locally-operational role stays selectable.
        }

        private func connect(
            _ candidate: CBPeripheral,
            on manager: CBCentralManager,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard peripheral == nil else { return }
            manager.stopScan()
            deadlineToken = nil
            peripheral = candidate
            candidate.delegate = self
            lifecycle = .connecting(generation: generation)
            manager.connect(candidate, options: [
                CBConnectPeripheralOptionRequiresANCS: true,
                CBConnectPeripheralOptionNotifyOnConnectionKey: false,
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: false
            ])
            armDeadline(seconds: 15, generation: generation, reason: "Route-B connect timeout")
        }

        private func enqueueRoleControl(
            type: IphoneBleWireProtocolV2.ControlType,
            targetMode: IphoneBleWireProtocolV2.Mode,
            switchToken: [UInt8],
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation, !attempt.isZero else { return }
                let isFrozenOwner: Bool
                if case .freezing(let frozenEpoch, let frozenGeneration) = self.lifecycle {
                    isFrozenOwner = frozenEpoch == epoch && frozenGeneration == generation
                } else if case .stopping(let stoppingEpoch, let stoppingGeneration) = self.lifecycle {
                    isFrozenOwner = stoppingEpoch == epoch &&
                        stoppingGeneration == generation && type == .roleCloseAck
                } else {
                    isFrozenOwner = false
                }
                guard isFrozenOwner, targetMode == .androidCentral else {
                    self.failAndDrain(
                        "outbound C/A requires exact frozen Route-B owner",
                        generation: generation
                    )
                    return
                }
                do {
                    let bytes: [UInt8]
                    switch type {
                    case .roleClose:
                        bytes = try IphoneBleWireProtocolV2.encodeRoleClose(
                            targetMode: targetMode,
                            switchToken: switchToken
                        )
                    case .roleCloseAck:
                        bytes = try IphoneBleWireProtocolV2.encodeRoleCloseAck(
                            targetMode: targetMode,
                            switchToken: switchToken
                        )
                    case .peerProof:
                        preconditionFailure("Route-B H is emitted only during authentication")
                    case .telemetryRefresh:
                        preconditionFailure("R is inbound-only on Route B")
                    }
                    guard let frame = IphoneBleWireProtocolV2.decodeControl(bytes) else {
                        self.failAndDrain("locally encoded C/A failed strict decode", generation: generation)
                        return
                    }
                    if type == .roleClose {
                        if let expected = self.outboundCloseExpectation, expected != frame {
                            self.failAndDrain("conflicting local C token/target", generation: generation)
                            return
                        }
                        self.outboundCloseExpectation = frame
                    } else {
                        guard self.inboundCloseIntent == self.frameAsRequest(frame) else {
                            self.failAndDrain("A does not echo the persisted inbound C", generation: generation)
                            return
                        }
                    }
                    let pending = PendingWrite(
                        kind: .roleControl(frame: frame, epoch: epoch, attempt: attempt),
                        data: Data(bytes)
                    )
                    if case .stopping = self.lifecycle {
                        guard let accepted = self.lastAcceptedControlWrite,
                              case .roleControl(let acceptedFrame, let acceptedEpoch, _) = accepted.kind,
                              acceptedEpoch == epoch,
                              acceptedFrame.type == .roleCloseAck,
                              acceptedFrame.mode == frame.mode,
                              acceptedFrame.payload == frame.payload else {
                            self.failAndDrain(
                                "stopping Route B permits only exact idempotent A",
                                generation: generation
                            )
                            return
                        }
                    }
                    if let queued = self.queuedControlWrite {
                        guard queued == pending else {
                            self.failAndDrain("conflicting outbound Route-B control write", generation: generation)
                            return
                        }
                        return
                    }
                    self.queuedControlWrite = pending
                    self.pumpWriteQueue()
                } catch {
                    self.failAndDrain("invalid outbound C/A: \(error)", generation: generation)
                }
            }
        }

        private func pumpWriteQueue() {
            guard inFlightWrite == nil, let peripheral else { return }
            let pending: PendingWrite
            let characteristic: CBCharacteristic
            if let control = queuedControlWrite, let controlCharacteristic {
                pending = control
                characteristic = controlCharacteristic
                queuedControlWrite = nil
            } else if let data = queuedCarRemoteFrames.first,
                      let carRemoteCharacteristic,
                      case .active = lifecycle {
                pending = PendingWrite(kind: .carRemote, data: data)
                characteristic = carRemoteCharacteristic
                queuedCarRemoteFrames.removeFirst()
            } else if acceptingTelemetry, let data = pendingTelemetry,
                      let telemetryCharacteristic {
                pending = PendingWrite(kind: .telemetry, data: data)
                characteristic = telemetryCharacteristic
                pendingTelemetry = nil
            } else {
                return
            }
            inFlightWrite = pending
            peripheral.writeValue(pending.data, for: characteristic, type: .withResponse)
            if let generation {
                armDeadline(seconds: 8, generation: generation, reason: "Route-B ATT write timeout")
            }
        }

        private func emitFrozen(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard !freezeCallbackPending else {
                freezeCallbackPending = false
                observer?.helperCentralRoute(self, didFreeze: epoch, generation: generation)
                return
            }
            observer?.helperCentralRoute(self, didFreeze: epoch, generation: generation)
        }

        private var isCriticalControlWriteInFlight: Bool {
            guard let inFlightWrite else { return false }
            if case .roleControl = inFlightWrite.kind { return true }
            return false
        }

        private func issueDeterministicClientClose(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard !cancelIssued, let manager, let peripheral else { return }
            cancelIssued = true
            acceptingTelemetry = false
            pendingTelemetry = nil
            queuedCarRemoteFrames.removeAll(keepingCapacity: false)
            deadlineToken = nil
            lifecycle = .stopping(epoch: epoch, generation: generation)
            manager.cancelPeripheralConnection(peripheral)
            // The wrapper remains owned until this exact peripheral reaches didDisconnect.
        }

        private func armDeadline(
            seconds: TimeInterval,
            generation: BleRoleSwitchPolicy.Sequence,
            reason: String
        ) {
            let token = UUID()
            deadlineToken = token
            queue.asyncAfter(deadline: .now() + seconds) { [weak self] in
                guard let self, self.deadlineToken == token,
                      self.generation == generation else { return }
                self.failAndDrain(reason, generation: generation)
            }
        }

        private func failAndDrain(
            _ reason: String,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard self.generation == generation else { return }
            guard pendingFailureReason == nil else { return }
            lifecycle = .failed(generation: generation, reason: reason)
            acceptingTelemetry = false
            pendingTelemetry = nil
            queuedControlWrite = nil
            queuedCarRemoteFrames.removeAll(keepingCapacity: false)
            deadlineToken = nil
            managerStartupTimerToken = nil
            pendingFailureReason = reason
            if let manager, let peripheral,
               peripheral.state == .connected || peripheral.state == .connecting ||
               peripheral.state == .disconnecting {
                if peripheral.state != .disconnecting, !cancelIssued {
                    cancelIssued = true
                    manager.cancelPeripheralConnection(peripheral)
                }
                armFailedOwnerReleaseWatchdog(generation: generation)
            } else {
                releaseUnconnectedOwner(generation: generation)
                emitPendingFailureAfterOwnerRelease(generation: generation)
            }
        }

        private func releaseUnconnectedOwner(generation: BleRoleSwitchPolicy.Sequence) {
            managerStartupTimerToken = nil
            failedOwnerReleaseTimerToken = nil
            manager?.stopScan()
            peripheral?.delegate = nil
            manager?.delegate = nil
            peripheral = nil
            manager = nil
            service = nil
            peerProofCharacteristic = nil
            controlCharacteristic = nil
            telemetryCharacteristic = nil
            carRemoteCharacteristic = nil
            learnedAndroidInstallationID = nil
            acceptingTelemetry = false
            deadlineToken = nil
            inFlightWrite = nil
            queuedControlWrite = nil
            pendingTelemetry = nil
            queuedCarRemoteFrames.removeAll(keepingCapacity: false)
            freezeCallbackPending = false
            stopAfterControlAcceptance = nil
            lastAcceptedControlWrite = nil
            inboundCloseIntent = nil
            outboundCloseExpectation = nil
            restorationObservationToken = nil
            cancelIssued = false
        }

        private func emitOperationalOnce(generation: BleRoleSwitchPolicy.Sequence) {
            guard operationalEmittedGeneration != generation else { return }
            operationalEmittedGeneration = generation
            managerStartupTimerToken = nil
            observer?.helperCentralRoute(self, didBecomeOperational: generation)
        }

        private func emitPendingFailureAfterOwnerRelease(
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard self.generation == generation, manager == nil,
                  peripheral == nil, let reason = pendingFailureReason else { return }
            pendingFailureReason = nil
            failedOwnerReleaseTimerToken = nil
            observer?.helperCentralRoute(self, didFail: reason, generation: generation)
        }

        private func armManagerStartupWatchdog(generation: BleRoleSwitchPolicy.Sequence) {
            let token = UUID()
            managerStartupTimerToken = token
            queue.asyncAfter(deadline: .now() + Self.managerStartupTimeout) { [weak self] in
                guard let self, self.managerStartupTimerToken == token,
                      self.generation == generation else { return }
                self.managerStartupTimerToken = nil
                self.failAndDrain(
                    "Core Bluetooth central manager did not become responsive (possible XPC reset)",
                    generation: generation
                )
            }
        }

        private func armFailedOwnerReleaseWatchdog(
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            let token = UUID()
            failedOwnerReleaseTimerToken = token
            queue.asyncAfter(deadline: .now() + Self.failedOwnerReleaseTimeout) { [weak self] in
                guard let self, self.failedOwnerReleaseTimerToken == token,
                      self.generation == generation,
                      self.pendingFailureReason != nil else { return }
                self.failedOwnerReleaseTimerToken = nil
                // The Core Bluetooth client wrapper is no longer trusted after the daemon/XPC
                // deadline. Retire the sole local owner before reporting failure upstream; the
                // switch reducer may only allocate a fresh generation after that evidence.
                self.releaseUnconnectedOwner(generation: generation)
                self.emitPendingFailureAfterOwnerRelease(generation: generation)
            }
        }

        private func releaseAfterExactDisconnect(
            epoch: BleRoleSwitchPolicy.Sequence?,
            generation: BleRoleSwitchPolicy.Sequence,
            unexpected: Bool
        ) {
            releaseUnconnectedOwner(generation: generation)
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                if let epoch {
                    self.lifecycle = .terminal(epoch: epoch, generation: generation)
                    self.observer?.helperCentralRoute(
                        self,
                        didBecomeTerminal: epoch,
                        generation: generation
                    )
                    self.observer?.helperCentralRoute(
                        self,
                        didObserveLocalOwnerCount: self.manager == nil ? 0 : 1,
                        epoch: epoch,
                        generation: generation
                    )
                } else if unexpected {
                    self.observer?.helperCentralRoute(self, didLoseExactLink: generation)
                }
            }
        }

        private func validatePeerProof(_ data: Data) -> UUID? {
            guard let frame = IphoneBleWireProtocolV2.decodeControl(Array(data)),
                  frame.type == .peerProof,
                  frame.mode == .androidPeripheral,
                  frame.ancsSupported,
                  frame.telemetrySupported,
                  let installation = Self.uuid(fromCanonicalBytes: frame.payload) else {
                return nil
            }
            if let expected = request?.expectedAndroidInstallationID,
               expected != installation {
                return nil
            }
            return installation
        }

        private func persistCompatibleRestoration(
            peripheralID: UUID,
            androidInstallationID: UUID
        ) {
            configuration.defaults.set(
                peripheralID.uuidString.lowercased(),
                forKey: configuration.restorationPeripheralKey
            )
            configuration.defaults.set(
                androidInstallationID.uuidString.lowercased(),
                forKey: configuration.restorationPeerProofKey
            )
        }

        private func restorationIsCompatible(_ candidate: CBPeripheral) -> Bool {
            guard configuration.defaults.string(
                    forKey: configuration.restorationPeripheralKey
                  )?.lowercased() == candidate.identifier.uuidString.lowercased() else {
                return false
            }
            if let selected = request?.selectedPeripheralID, selected != candidate.identifier {
                return false
            }
            if let expected = request?.expectedAndroidInstallationID {
                return configuration.defaults.string(
                    forKey: configuration.restorationPeerProofKey
                )?.lowercased() == expected.uuidString.lowercased()
            }
            return true
        }

        private static func canonicalBytes(_ uuid: UUID) -> [UInt8] {
            var raw = uuid.uuid
            return withUnsafeBytes(of: &raw) { Array($0) }
        }

        private static func uuid(fromCanonicalBytes bytes: [UInt8]) -> UUID? {
            guard bytes.count == 16 else { return nil }
            return UUID(uuid: (
                bytes[0], bytes[1], bytes[2], bytes[3],
                bytes[4], bytes[5], bytes[6], bytes[7],
                bytes[8], bytes[9], bytes[10], bytes[11],
                bytes[12], bytes[13], bytes[14], bytes[15]
            ))
        }

        private func frameAsRequest(
            _ acknowledgement: IphoneBleWireProtocolV2.ControlFrame
        ) -> IphoneBleWireProtocolV2.ControlFrame? {
            guard acknowledgement.type == .roleCloseAck else { return nil }
            let bytes = try? IphoneBleWireProtocolV2.encodeRoleClose(
                targetMode: acknowledgement.mode,
                switchToken: acknowledgement.payload
            )
            return bytes.flatMap(IphoneBleWireProtocolV2.decodeControl)
        }
    }
}

extension HelperCentralRoute.Coordinator: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central === manager, let generation else { return }
        switch central.state {
        case .poweredOn:
            managerStartupTimerToken = nil
            if case .resumeSelectedRoute = restorationDisposition,
               restorationCallbackObserved, peripheral != nil {
                emitOperationalOnce(generation: generation)
            }
            if restorationDrainEpoch != nil {
                if peripheral == nil, !restorationCallbackObserved {
                    scheduleEmptyRestorationObservation(on: central, generation: generation)
                }
            } else if peripheral == nil {
                if !restorationCallbackObserved {
                    // Let the one-shot restoration callback bind its exact wrapper before any
                    // retrieve/scan acquisition is permitted to create a competing candidate.
                    queue.async { [weak self, weak central] in
                        guard let self, let central, self.manager === central,
                              self.generation == generation,
                              !self.restorationCallbackObserved,
                              self.peripheral == nil,
                              case .waitingForRadio(let waitingGeneration) = self.lifecycle,
                              waitingGeneration == generation else { return }
                        self.beginAcquisition(on: central)
                    }
                } else {
                    beginAcquisition(on: central)
                }
            }
        case .unauthorized:
            failAndDrain("Bluetooth central role is unauthorized", generation: generation)
        case .unsupported:
            failAndDrain("Bluetooth central role is unsupported", generation: generation)
        case .poweredOff, .resetting:
            switch lifecycle {
            case .freezing(let epoch, let frozenGeneration) where frozenGeneration == generation:
                observer?.helperCentralRoute(
                    self,
                    didLoseFrozenExactLink: epoch,
                    generation: generation
                )
                releaseAfterExactDisconnect(
                    epoch: epoch,
                    generation: generation,
                    unexpected: false
                )
            case .stopping(let epoch, let stoppingGeneration) where stoppingGeneration == generation:
                observer?.helperCentralRoute(
                    self,
                    didLoseFrozenExactLink: epoch,
                    generation: generation
                )
                releaseAfterExactDisconnect(
                    epoch: epoch,
                    generation: generation,
                    unexpected: false
                )
            case .active(let activeGeneration) where activeGeneration == generation:
                releaseAfterExactDisconnect(
                    epoch: nil,
                    generation: generation,
                    unexpected: true
                )
            default:
                if let epoch = restorationDrainEpoch {
                    observer?.helperCentralRoute(
                        self,
                        didLoseFrozenExactLink: epoch,
                        generation: generation
                    )
                    releaseAfterExactDisconnect(
                        epoch: epoch,
                        generation: generation,
                        unexpected: false
                    )
                } else {
                    deadlineToken = nil
                    peripheral?.delegate = nil
                    peripheral = nil
                    service = nil
                    peerProofCharacteristic = nil
                    controlCharacteristic = nil
                    telemetryCharacteristic = nil
                    carRemoteCharacteristic = nil
                    learnedAndroidInstallationID = nil
                    lifecycle = .waitingForRadio(generation: generation)
                    if central.state == .resetting {
                        armManagerStartupWatchdog(generation: generation)
                    } else {
                        failAndDrain("Bluetooth is powered off", generation: generation)
                    }
                }
            }
        case .unknown:
            break
        @unknown default:
            failAndDrain("unknown central manager state", generation: generation)
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        willRestoreState dict: [String: Any]
    ) {
        guard central === manager, let generation,
              case .waitingForRadio(let waitingGeneration) = lifecycle,
              waitingGeneration == generation else { return }
        restorationCallbackObserved = true
        restorationObservationToken = nil
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        if restored.isEmpty, restorationDrainEpoch != nil {
            finishRestorationNoOwner(generation: generation)
            return
        }
        guard restored.count == 1, let candidate = restored.first,
              restorationIsCompatible(candidate) else {
            lifecycle = .migrationDrain(generation: generation)
            observer?.helperCentralRoute(
                self,
                didEncounterUnprovableRestoration: generation
            )
            unprovableRestoredOwners = Dictionary(
                uniqueKeysWithValues: restored.map { ($0.identifier, $0) }
            )
            for candidate in restored where
                candidate.state == .connected || candidate.state == .connecting {
                central.cancelPeripheralConnection(candidate)
            }
            unprovableRestoredOwners = unprovableRestoredOwners.filter {
                $0.value.state == .connected || $0.value.state == .connecting ||
                    $0.value.state == .disconnecting
            }
            if unprovableRestoredOwners.isEmpty {
                if permitsLegacyMigrationDrain {
                    finishLegacyRestorationDrain(generation: generation)
                } else {
                    releaseUnconnectedOwner(generation: generation)
                }
            }
            return
        }
        peripheral = candidate
        candidate.delegate = self
        if candidate.state == .connected {
            if restorationDrainEpoch == nil, central.state == .poweredOn {
                emitOperationalOnce(generation: generation)
            }
            lifecycle = .discoveringService(generation: generation)
            candidate.discoverServices([HelperCentralRoute.serviceUUID])
            armDeadline(seconds: 8, generation: generation, reason: "restored F202 discovery timeout")
        } else if candidate.state == .connecting {
            if restorationDrainEpoch == nil, central.state == .poweredOn {
                emitOperationalOnce(generation: generation)
            }
            lifecycle = .connecting(generation: generation)
            armDeadline(seconds: 15, generation: generation, reason: "restored connect timeout")
        } else if candidate.state == .disconnecting {
            if let epoch = restorationDrainEpoch {
                lifecycle = .stopping(epoch: epoch, generation: generation)
                cancelIssued = true
                observer?.helperCentralRoute(
                    self,
                    didFreezeWithoutRemoteOwner: epoch,
                    generation: generation
                )
            } else {
                failAndDrain(
                    "restored selected Route-B wrapper was already disconnecting",
                    generation: generation
                )
            }
        } else {
            peripheral = nil
            if restorationDrainEpoch != nil {
                finishRestorationNoOwner(generation: generation)
            } else {
                beginAcquisition(on: central)
            }
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover candidate: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        _ = advertisementData
        _ = RSSI
        guard central === manager, let generation,
              case .scanning(let scanGeneration) = lifecycle,
              scanGeneration == generation else { return }
        if let selected = request?.selectedPeripheralID,
           selected != candidate.identifier {
            return
        }
        connect(candidate, on: central, generation: generation)
    }

    public func centralManager(_ central: CBCentralManager, didConnect candidate: CBPeripheral) {
        guard central === manager, candidate === peripheral, let generation else { return }
        if case .stopping(_, let stoppingGeneration) = lifecycle,
           stoppingGeneration == generation {
            if !cancelIssued {
                cancelIssued = true
                central.cancelPeripheralConnection(candidate)
            }
            return
        }
        guard case .connecting(let connectingGeneration) = lifecycle,
              connectingGeneration == generation else { return }
        deadlineToken = nil
        lifecycle = .discoveringService(generation: generation)
        candidate.discoverServices([HelperCentralRoute.serviceUUID])
        armDeadline(seconds: 8, generation: generation, reason: "F202 service discovery timeout")
    }

    public func centralManager(
        _ central: CBCentralManager,
        didFailToConnect candidate: CBPeripheral,
        error: Error?
    ) {
        guard central === manager, candidate === peripheral, let generation else { return }
        if case .failed(let failedGeneration, _) = lifecycle,
           failedGeneration == generation {
            _ = error
            releaseUnconnectedOwner(generation: generation)
            emitPendingFailureAfterOwnerRelease(generation: generation)
            return
        }
        if case .stopping(let epoch, let stoppingGeneration) = lifecycle,
           stoppingGeneration == generation {
            _ = error
            releaseAfterExactDisconnect(
                epoch: epoch,
                generation: generation,
                unexpected: false
            )
            return
        }
        if restorationDrainEpoch != nil {
            _ = error
            peripheral = nil
            finishRestorationNoOwner(generation: generation)
            return
        }
        failAndDrain("Route-B connect failed: \(String(describing: error))", generation: generation)
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral candidate: CBPeripheral,
        error: Error?
    ) {
        if central === manager, unprovableRestoredOwners.removeValue(
            forKey: candidate.identifier
        ) != nil {
            if unprovableRestoredOwners.isEmpty, let generation {
                if permitsLegacyMigrationDrain {
                    finishLegacyRestorationDrain(generation: generation)
                } else {
                    releaseUnconnectedOwner(generation: generation)
                }
            }
            return
        }
        guard central === manager, candidate === peripheral, let generation else { return }
        deadlineToken = nil
        if case .failed(let failedGeneration, _) = lifecycle,
           failedGeneration == generation {
            _ = error
            releaseUnconnectedOwner(generation: generation)
            emitPendingFailureAfterOwnerRelease(generation: generation)
        } else if case .stopping(let epoch, let stoppingGeneration) = lifecycle,
           stoppingGeneration == generation {
            releaseAfterExactDisconnect(
                epoch: epoch,
                generation: generation,
                unexpected: false
            )
        } else if case .freezing(let epoch, let frozenGeneration) = lifecycle,
                  frozenGeneration == generation {
            _ = error
            observer?.helperCentralRoute(
                self,
                didLoseFrozenExactLink: epoch,
                generation: generation
            )
            releaseAfterExactDisconnect(
                epoch: epoch,
                generation: generation,
                unexpected: false
            )
        } else {
            _ = error
            if restorationDrainEpoch != nil {
                peripheral = nil
                finishRestorationNoOwner(generation: generation)
                return
            }
            releaseAfterExactDisconnect(
                epoch: nil,
                generation: generation,
                unexpected: true
            )
        }
    }
}

extension HelperCentralRoute.Coordinator: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === self.peripheral, let generation,
              case .discoveringService(let discoveryGeneration) = lifecycle,
              discoveryGeneration == generation else { return }
        guard error == nil,
              let services = peripheral.services,
              services.count == 1,
              let service = services.first,
              service.uuid == HelperCentralRoute.serviceUUID else {
            failAndDrain("F202 service inventory rejected", generation: generation)
            return
        }
        deadlineToken = nil
        self.service = service
        lifecycle = .discoveringCharacteristics(generation: generation)
        peripheral.discoverCharacteristics(
            [
                HelperCentralRoute.peerProofUUID,
                HelperCentralRoute.controlUUID,
                HelperCentralRoute.telemetryUUID,
                HelperCentralRoute.carRemoteUUID
            ],
            for: service
        )
        armDeadline(seconds: 8, generation: generation, reason: "F202 characteristic timeout")
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard peripheral === self.peripheral, service === self.service, let generation,
              case .discoveringCharacteristics(let discoveryGeneration) = lifecycle,
              discoveryGeneration == generation else { return }
        let characteristics = service.characteristics ?? []
        guard error == nil,
              characteristics.count == 4,
              let proof = characteristics.first(where: {
                  $0.uuid == HelperCentralRoute.peerProofUUID
              }), proof.properties.contains(.read),
              let control = characteristics.first(where: {
                  $0.uuid == HelperCentralRoute.controlUUID
              }), control.properties.contains(.write), control.properties.contains(.indicate),
              let telemetry = characteristics.first(where: {
                  $0.uuid == HelperCentralRoute.telemetryUUID
              }), telemetry.properties.contains(.write),
              let carRemote = characteristics.first(where: {
                  $0.uuid == HelperCentralRoute.carRemoteUUID
              }), carRemote.properties.contains(.write),
              carRemote.properties.contains(.indicate) else {
            failAndDrain("F202 characteristic contract rejected", generation: generation)
            return
        }
        deadlineToken = nil
        peerProofCharacteristic = proof
        controlCharacteristic = control
        telemetryCharacteristic = telemetry
        carRemoteCharacteristic = carRemote
        lifecycle = .readingPeerProof(generation: generation)
        peripheral.readValue(for: proof)
        armDeadline(seconds: 8, generation: generation, reason: "Android H read timeout")
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === self.peripheral, let generation else { return }
        if characteristic === carRemoteCharacteristic {
            guard case .active(let activeGeneration) = lifecycle,
                  activeGeneration == generation,
                  error == nil, let value = characteristic.value,
                  CarRemoteProtocolV1.decode(value) != nil else { return }
            observer?.helperCentralRoute(
                self,
                didReceiveCarRemoteFrame: value,
                generation: generation
            )
            return
        }
        if characteristic === peerProofCharacteristic {
            if case .stopping(_, let stoppingGeneration) = lifecycle,
               stoppingGeneration == generation {
                // A pre-auth offline freeze already canceled this candidate. Late proof data can
                // neither revive discovery nor replace the exact stopping epoch.
                return
            }
            guard case .readingPeerProof(let proofGeneration) = lifecycle,
                  proofGeneration == generation,
                  error == nil,
                  let value = characteristic.value,
                  let installation = validatePeerProof(value),
                  let controlCharacteristic else {
                failAndDrain("Android peer proof rejected", generation: generation)
                return
            }
            deadlineToken = nil
            learnedAndroidInstallationID = installation
            lifecycle = .subscribingControl(generation: generation)
            peripheral.setNotifyValue(true, for: controlCharacteristic)
            armDeadline(seconds: 8, generation: generation, reason: "control CCCD timeout")
            return
        }
        guard characteristic === controlCharacteristic, error == nil,
              let value = characteristic.value,
              let frame = IphoneBleWireProtocolV2.decodeControl(Array(value)),
              frame.type != .peerProof else {
            if characteristic === controlCharacteristic {
                failAndDrain("malformed Route-B control notification", generation: generation)
            }
            return
        }
        let validMode = frame.type == .telemetryRefresh
            ? frame.mode == .androidPeripheral
            : frame.mode == .androidCentral
        guard validMode else {
            failAndDrain("Route-B control mode/type mismatch", generation: generation)
            return
        }
        if frame.type == .telemetryRefresh {
            guard case .active(let activeGeneration) = lifecycle,
                  activeGeneration == generation else { return }
        }
        if frame.type == .roleClose {
            if let accepted = inboundCloseIntent, accepted != frame {
                failAndDrain("conflicting inbound Route-B C", generation: generation)
                return
            }
            inboundCloseIntent = frame
        } else if frame.type == .roleCloseAck {
            guard let expected = outboundCloseExpectation,
                  expected.mode == frame.mode,
                  expected.payload == frame.payload else {
                failAndDrain("Route-B A does not echo local C", generation: generation)
                return
            }
        }
        observer?.helperCentralRoute(self, didReceiveControl: frame, generation: generation)
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === self.peripheral, let generation else { return }
        if characteristic === carRemoteCharacteristic {
            guard case .subscribingCarRemote(let subscriptionGeneration) = lifecycle,
                  subscriptionGeneration == generation,
                  error == nil, characteristic.isNotifying,
                  let androidID = learnedAndroidInstallationID else {
                failAndDrain("car remote C5 CCCD rejected", generation: generation)
                return
            }
            deadlineToken = nil
            lifecycle = .active(generation: generation)
            acceptingTelemetry = true
            persistCompatibleRestoration(
                peripheralID: peripheral.identifier,
                androidInstallationID: androidID
            )
            observer?.helperCentralRoute(
                self,
                didBecomeReady: androidID,
                peripheralID: peripheral.identifier,
                generation: generation
            )
            pumpWriteQueue()
            return
        }
        guard characteristic === controlCharacteristic,
              case .subscribingControl(let subscriptionGeneration) = lifecycle,
              subscriptionGeneration == generation else { return }
        guard error == nil, characteristic.isNotifying else {
            failAndDrain("control CCCD rejected", generation: generation)
            return
        }
        deadlineToken = nil
        lifecycle = .writingHello(generation: generation)
        let pending = PendingWrite(kind: .hello, data: hello)
        inFlightWrite = pending
        peripheral.writeValue(hello, for: characteristic, type: .withResponse)
        armDeadline(seconds: 8, generation: generation, reason: "Helper H write timeout")
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === self.peripheral, let generation,
              let completed = inFlightWrite else { return }
        let expectedCharacteristic: CBCharacteristic?
        switch completed.kind {
        case .hello, .roleControl:
            expectedCharacteristic = controlCharacteristic
        case .telemetry:
            expectedCharacteristic = telemetryCharacteristic
        case .carRemote:
            expectedCharacteristic = carRemoteCharacteristic
        }
        guard characteristic === expectedCharacteristic else { return }
        if case .hello = completed.kind {
            if case .stopping(_, let stoppingGeneration) = lifecycle,
               stoppingGeneration == generation {
                deadlineToken = nil
                inFlightWrite = nil
                return
            }
        }
        deadlineToken = nil
        inFlightWrite = nil
        guard error == nil else {
            failAndDrain("Route-B ATT write rejected: \(String(describing: error))", generation: generation)
            return
        }
        switch completed.kind {
        case .hello:
            guard case .writingHello(let helloGeneration) = lifecycle,
                  helloGeneration == generation,
                  let carRemoteCharacteristic else {
                failAndDrain("stale Helper H completion", generation: generation)
                return
            }
            lifecycle = .subscribingCarRemote(generation: generation)
            peripheral.setNotifyValue(true, for: carRemoteCharacteristic)
            armDeadline(seconds: 8, generation: generation,
                        reason: "car remote C5 CCCD timeout")
        case .telemetry:
            if freezeCallbackPending,
               case .freezing(let epoch, let frozenGeneration) = lifecycle,
               frozenGeneration == generation {
                emitFrozen(epoch: epoch, generation: generation)
            }
        case .carRemote:
            if freezeCallbackPending,
               case .freezing(let epoch, let frozenGeneration) = lifecycle,
               frozenGeneration == generation {
                emitFrozen(epoch: epoch, generation: generation)
            }
        case .roleControl(let frame, let epoch, let attempt):
            lastAcceptedControlWrite = completed
            observer?.helperCentralRoute(
                self,
                didAcceptOutboundControl: frame,
                epoch: epoch,
                generation: generation,
                attempt: attempt
            )
        }
        pumpWriteQueue()
        if let stop = stopAfterControlAcceptance,
           queuedControlWrite == nil,
           !isCriticalControlWriteInFlight {
            stopAfterControlAcceptance = nil
            issueDeterministicClientClose(
                epoch: stop.epoch,
                generation: stop.generation
            )
        }
    }
}
