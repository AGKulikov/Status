import CoreBluetooth
import Foundation

/// Route A only: iPhone Helper Peripheral / Android Central.
///
/// This namespace owns no Central-role manager and contains no Route-B state. Its manager, service,
/// characteristics, restoration record, subscribers, and callback generation are route-local.
public enum HelperPeripheralRoute {
    public static let serviceUUID = CBUUID(string: "D2D9E4C0-47F1-4E44-A8BB-A932FD5AF201")
    public static let peerProofUUID = CBUUID(string: "D2D9E4C1-47F1-4E44-A8BB-A932FD5AF200")
    public static let controlUUID = CBUUID(string: "D2D9E4C2-47F1-4E44-A8BB-A932FD5AF200")
    public static let telemetryUUID = CBUUID(string: "D2D9E4C3-47F1-4E44-A8BB-A932FD5AF200")
    private static let legacyF04ServiceUUID = CBUUID(
        string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04"
    )

    public enum RestorationDisposition: Equatable {
        case resumeSelectedRoute
        case drainInactiveRoute(epoch: BleRoleSwitchPolicy.Sequence)
        case drainLocalOnlyRestore(epoch: BleRoleSwitchPolicy.Sequence)
        case drainLegacyMigration(epoch: BleRoleSwitchPolicy.Sequence)
    }

    public enum Lifecycle: Equatable {
        case idle
        case waitingForRadio(generation: BleRoleSwitchPolicy.Sequence)
        case publishing(generation: BleRoleSwitchPolicy.Sequence)
        case advertising(generation: BleRoleSwitchPolicy.Sequence)
        case awaitingControlSubscription(generation: BleRoleSwitchPolicy.Sequence)
        case active(generation: BleRoleSwitchPolicy.Sequence)
        case migrationDrain(generation: BleRoleSwitchPolicy.Sequence, legacyUnprovable: Bool)
        case freezing(epoch: BleRoleSwitchPolicy.Sequence, generation: BleRoleSwitchPolicy.Sequence)
        case stopping(epoch: BleRoleSwitchPolicy.Sequence, generation: BleRoleSwitchPolicy.Sequence)
        case terminal(epoch: BleRoleSwitchPolicy.Sequence, generation: BleRoleSwitchPolicy.Sequence)
        case failed(generation: BleRoleSwitchPolicy.Sequence, reason: String)
    }

    public struct Configuration {
        public var restorationIdentifier: String
        public var installationDefaultsKey: String
        public var defaults: UserDefaults

        public init(
            restorationIdentifier: String = "ru.natro.kx11ancshelper.peripheral.stable",
            installationDefaultsKey: String = "KX11ANCSHelper.v2.installationUUID",
            defaults: UserDefaults = .standard
        ) {
            self.restorationIdentifier = restorationIdentifier
            self.installationDefaultsKey = installationDefaultsKey
            self.defaults = defaults
        }
    }

    public enum SetupError: Error, Equatable {
        case installationIdentityCouldNotBePersisted
    }

    public protocol Observer: AnyObject {
        func helperPeripheralRoute(
            _ route: Coordinator,
            didBecomeReady generation: BleRoleSwitchPolicy.Sequence
        )
        /// The local GATT database is published and advertising is running. This is deliberately
        /// separate from didBecomeReady, which still requires one exact encrypted CONTROL owner.
        func helperPeripheralRoute(
            _ route: Coordinator,
            didBecomeOperational generation: BleRoleSwitchPolicy.Sequence
        )
        func helperPeripheralRoute(
            _ route: Coordinator,
            didFreeze epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperPeripheralRoute(
            _ route: Coordinator,
            didBecomeTerminal epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperPeripheralRoute(
            _ route: Coordinator,
            didObserveLocalOwnerCount count: Int,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        /// Freeze completed with no exact remote control owner after bounded restoration fencing.
        func helperPeripheralRoute(
            _ route: Coordinator,
            didFreezeWithoutRemoteOwner epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        func helperPeripheralRoute(
            _ route: Coordinator,
            didLoseExactLink generation: BleRoleSwitchPolicy.Sequence
        )
        func helperPeripheralRoute(
            _ route: Coordinator,
            didLoseFrozenExactLink epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        /// Legacy F04 was reclaimed locally, but cannot prove a v2 peer close/ACK.
        func helperPeripheralRoute(
            _ route: Coordinator,
            didEncounterUnprovableMigration epoch: BleRoleSwitchPolicy.Sequence?,
            legacyF04: Bool,
            generation: BleRoleSwitchPolicy.Sequence
        )
        /// Parsed C/A from the exact encrypted control owner. No ACK is sent here.
        func helperPeripheralRoute(
            _ route: Coordinator,
            didReceiveControl frame: IphoneBleWireProtocolV2.ControlFrame,
            generation: BleRoleSwitchPolicy.Sequence
        )
        /// Core Bluetooth accepted the exact outbound C/A indication for transmission.
        func helperPeripheralRoute(
            _ route: Coordinator,
            didAcceptOutboundControl frame: IphoneBleWireProtocolV2.ControlFrame,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        )
        func helperPeripheralRoute(
            _ route: Coordinator,
            didFail reason: String,
            generation: BleRoleSwitchPolicy.Sequence
        )
    }

    public final class Coordinator: NSObject {
        private static let restorationObservationGrace = DispatchTimeInterval.milliseconds(250)
        private static let managerStartupTimeout = DispatchTimeInterval.seconds(8)
        private static let publicationTimeout = DispatchTimeInterval.seconds(8)

        private enum RestoredTopology: Equatable {
            case exactV2
            case legacyF04
            case incompatibleUnprovable
            case empty
        }

        private struct PendingControlNotification: Equatable {
            let data: Data
            let frame: IphoneBleWireProtocolV2.ControlFrame
            let epoch: BleRoleSwitchPolicy.Sequence
            let generation: BleRoleSwitchPolicy.Sequence
            let attempt: BleRoleSwitchPolicy.Sequence
        }

        public weak var observer: Observer?
        public let installationID: UUID

        private let queue: DispatchQueue
        private let configuration: Configuration
        private let peerProof: Data
        private var lifecycle: Lifecycle = .idle
        private var generation: BleRoleSwitchPolicy.Sequence?
        private var restorationDisposition: RestorationDisposition = .resumeSelectedRoute
        private var manager: CBPeripheralManager?
        private var service: CBMutableService?
        private var peerProofCharacteristic: CBMutableCharacteristic?
        private var controlCharacteristic: CBMutableCharacteristic?
        private var telemetryCharacteristic: CBMutableCharacteristic?
        private var currentTelemetry: Data?
        private var pendingTelemetry: Data?
        private var pendingControlNotification: PendingControlNotification?
        private var lastAcceptedControlNotification: PendingControlNotification?
        private var inboundCloseIntent: IphoneBleWireProtocolV2.ControlFrame?
        private var acceptingIngress = false
        private var controlSubscriberID: UUID?
        private var telemetrySubscribers = Set<UUID>()
        private var expectedControlUnsubscribeID: UUID?
        private var exactControlUnsubscribeObserved = false
        private var earlyControlUnsubscribeID: UUID?
        private var earlyControlUnsubscribeEpoch: BleRoleSwitchPolicy.Sequence?
        private var restorationCallbackObserved = false
        private var restorationObservationToken: UUID?
        private var restorationWasExactV2 = false
        private var restorationWasLegacy = false
        private var pendingRestoredTopology: RestoredTopology?
        private var servicePublicationIssued = false
        private var liveZeroControlEpoch: BleRoleSwitchPolicy.Sequence?
        private var liveZeroObservationSettled = false
        private var readyEmittedGeneration: BleRoleSwitchPolicy.Sequence?
        private var operationalEmittedGeneration: BleRoleSwitchPolicy.Sequence?
        private var managerStartupTimerToken: UUID?
        private var publicationTimerToken: UUID?

        /// Only HelperBleRuntimeCoordinator can mint the owner token accepted here.
        internal init(
            ownerToken: HelperBleRuntimeCoordinator.RouteOwnerToken,
            configuration: Configuration
        ) throws {
            _ = ownerToken
            self.configuration = configuration
            self.queue = DispatchQueue(
                label: "ru.natro.kx11ancshelper.v48.routeA.peripheral",
                qos: .userInitiated
            )
            self.installationID = try Self.loadOrCreateInstallationID(configuration)
            let installationBytes = Self.canonicalBytes(installationID)
            self.peerProof = Data(try IphoneBleWireProtocolV2.encodePeerProof(
                mode: .androidCentral,
                installationID: installationBytes,
                telemetrySupported: true,
                ancsSupported: true
            ))
            super.init()
        }

        public func start(
            generation: BleRoleSwitchPolicy.Sequence,
            restoration: RestorationDisposition = .resumeSelectedRoute
        ) {
            precondition(!generation.isZero, "a live route generation must be non-zero")
            queue.async { [weak self] in
                self?.startOnQueue(generation: generation, restoration: restoration)
            }
        }

        public func freezeIngress(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                guard let manager = self.manager else {
                    self.lifecycle = .terminal(epoch: epoch, generation: generation)
                    self.observer?.helperPeripheralRoute(
                        self,
                        didFreezeWithoutRemoteOwner: epoch,
                        generation: generation
                    )
                    self.observer?.helperPeripheralRoute(
                        self,
                        didBecomeTerminal: epoch,
                        generation: generation
                    )
                    self.observer?.helperPeripheralRoute(
                        self,
                        didObserveLocalOwnerCount: 0,
                        epoch: epoch,
                        generation: generation
                    )
                    return
                }
                if case .freezing(let frozenEpoch, let frozenGeneration) = self.lifecycle,
                   frozenEpoch == epoch, frozenGeneration == generation {
                    return
                }
                if case .active(let activeGeneration) = self.lifecycle,
                   activeGeneration == generation,
                   self.controlSubscriberID != nil {
                    self.acceptingIngress = false
                    manager.stopAdvertising()
                    self.pendingTelemetry = nil
                    self.lifecycle = .freezing(epoch: epoch, generation: generation)
                    self.observer?.helperPeripheralRoute(
                        self,
                        didFreeze: epoch,
                        generation: generation
                    )
                    return
                }

                // The selected route may still be advertising/waiting for its first exact control
                // owner. A role selection must remain possible in that state. Fence every ingress
                // path first, then use the bounded zero-owner observation; it emits the atomic
                // frozen-without-owner callback and releases this manager itself.
                guard self.controlSubscriberID == nil else {
                    self.fail("freeze found a control owner outside active", generation: generation)
                    return
                }
                if self.restorationObservationToken != nil,
                   self.liveZeroControlEpoch == nil {
                    // An ordinary owner-loss observation was already fenced. Attach this exact
                    // switch epoch so its completion becomes the atomic no-owner freeze instead
                    // of starting a second observation or timing out silently.
                    self.liveZeroControlEpoch = epoch
                    return
                }
                self.acceptingIngress = false
                manager.stopAdvertising()
                self.pendingTelemetry = nil
                if manager.state == .poweredOn {
                    self.beginLiveZeroControlObservation(epoch: epoch, generation: generation)
                } else {
                    self.lifecycle = .freezing(epoch: epoch, generation: generation)
                    self.observer?.helperPeripheralRoute(
                        self,
                        didFreezeWithoutRemoteOwner: epoch,
                        generation: generation
                    )
                    self.releaseAllOwners(epoch: epoch, generation: generation)
                }
            }
        }

        public func stop(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                if self.liveZeroControlEpoch == epoch,
                   self.restorationObservationToken != nil {
                    return
                }
                guard case .freezing(let frozenEpoch, let frozenGeneration) = self.lifecycle,
                      frozenEpoch == epoch,
                      frozenGeneration == generation else {
                    self.fail("stop before exact ingress-frozen barrier", generation: generation)
                    return
                }
                guard let controlID = self.controlSubscriberID else {
                    if self.earlyControlUnsubscribeEpoch == epoch,
                       let earlyID = self.earlyControlUnsubscribeID {
                        guard self.pendingControlNotification == nil else {
                            self.fail(
                                "early peer close raced an unaccepted outbound C/A",
                                generation: generation
                            )
                            return
                        }
                        self.lifecycle = .stopping(epoch: epoch, generation: generation)
                        self.expectedControlUnsubscribeID = earlyID
                        self.exactControlUnsubscribeObserved = true
                        self.finishStopIfExactBoundaryReached()
                        return
                    }
                    self.beginLiveZeroControlObservation(
                        epoch: epoch,
                        generation: generation
                    )
                    return
                }
                guard self.telemetrySubscribers.allSatisfy({ $0 == controlID }) else {
                    self.fail(
                        "telemetry subscriber does not own the control channel",
                        generation: generation
                    )
                    return
                }
                guard self.pendingControlNotification == nil else {
                    self.fail(
                        "stop attempted before outbound C/A acceptance",
                        generation: generation
                    )
                    return
                }
                self.lifecycle = .stopping(epoch: epoch, generation: generation)
                self.expectedControlUnsubscribeID = controlID
                self.exactControlUnsubscribeObserved = false
                // Android is the deterministic first closer. Keep the ATT server alive until the
                // exact control CCCD disappears and every telemetry subscription is gone. The
                // reducer's inclusive stop deadline fails closed if either callback never comes.
            }
        }

        /// CLOSED-tombstone escape hatch. No target can follow this operation, so after the
        /// bounded restoration window the local manager is force-released even if an old central
        /// never delivers its unsubscribe callback.
        public func forceCloseRestorationNamespace(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                self.releaseAllOwners(epoch: epoch, generation: generation)
            }
        }

        public func publishTelemetry(
            _ telemetry: IphoneBleWireProtocolV2.Telemetry,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            queue.async { [weak self] in
                guard let self, self.generation == generation, self.acceptingIngress else { return }
                do {
                    let frame = try IphoneBleWireProtocolV2.encodeTelemetry(
                        batteryPercent: telemetry.batteryPercent,
                        externalPower: telemetry.externalPower,
                        chargeState: telemetry.chargeState,
                        network: telemetry.network,
                        locked: telemetry.locked,
                        sequence: telemetry.sequence
                    )
                    let data = Data(frame)
                    self.currentTelemetry = data
                    self.pendingTelemetry = data
                    self.drainNotifications()
                } catch {
                    self.fail("invalid telemetry: \(error)", generation: generation)
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
                guard isFrozenOwner, targetMode == .androidPeripheral,
                      self.controlSubscriberID != nil else {
                    self.fail(
                        "outbound C/A requires exact frozen Route-A control owner",
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
                        preconditionFailure("peer proof is read-only on Route A")
                    }
                    guard let frame = IphoneBleWireProtocolV2.decodeControl(bytes) else {
                        self.fail("locally encoded C/A failed strict decode", generation: generation)
                        return
                    }
                    if case .stopping = self.lifecycle {
                        guard let accepted = self.lastAcceptedControlNotification,
                              accepted.frame.type == .roleCloseAck,
                              accepted.frame.mode == frame.mode,
                              accepted.frame.payload == frame.payload else {
                            self.fail(
                                "stopping Route A permits only exact idempotent A",
                                generation: generation
                            )
                            return
                        }
                    }
                    let pending = PendingControlNotification(
                        data: Data(bytes),
                        frame: frame,
                        epoch: epoch,
                        generation: generation,
                        attempt: attempt
                    )
                    if let existing = self.pendingControlNotification {
                        guard existing == pending else {
                            self.fail("conflicting outbound C/A while one is pending", generation: generation)
                            return
                        }
                        return
                    }
                    self.pendingControlNotification = pending
                    self.drainNotifications()
                } catch {
                    self.fail("invalid outbound C/A: \(error)", generation: generation)
                }
            }
        }

        private func startOnQueue(
            generation: BleRoleSwitchPolicy.Sequence,
            restoration: RestorationDisposition
        ) {
            guard manager == nil else {
                if self.generation == generation { return }
                fail("second CBPeripheralManager owner denied", generation: generation)
                return
            }
            self.generation = generation
            self.restorationDisposition = restoration
            self.restorationCallbackObserved = false
            self.restorationObservationToken = nil
            self.restorationWasExactV2 = false
            self.restorationWasLegacy = false
            self.pendingRestoredTopology = nil
            self.servicePublicationIssued = false
            self.liveZeroControlEpoch = nil
            self.liveZeroObservationSettled = false
            self.readyEmittedGeneration = nil
            self.operationalEmittedGeneration = nil
            self.managerStartupTimerToken = nil
            self.publicationTimerToken = nil
            self.currentTelemetry = nil
            self.pendingTelemetry = nil
            self.pendingControlNotification = nil
            self.lastAcceptedControlNotification = nil
            self.inboundCloseIntent = nil
            self.controlSubscriberID = nil
            self.telemetrySubscribers.removeAll(keepingCapacity: false)
            self.expectedControlUnsubscribeID = nil
            self.exactControlUnsubscribeObserved = false
            self.earlyControlUnsubscribeID = nil
            self.earlyControlUnsubscribeEpoch = nil
            if case .resumeSelectedRoute = restoration {
                self.acceptingIngress = true
            } else {
                self.acceptingIngress = false
            }
            self.lifecycle = .waitingForRadio(generation: generation)
            let options: [String: Any] = [
                CBPeripheralManagerOptionRestoreIdentifierKey: configuration.restorationIdentifier,
                CBPeripheralManagerOptionShowPowerAlertKey: true
            ]
            manager = CBPeripheralManager(delegate: self, queue: queue, options: options)
            armManagerStartupWatchdog(generation: generation)
        }

        private func publishFreshService(on manager: CBPeripheralManager) {
            guard !servicePublicationIssued else { return }
            servicePublicationIssued = true
            let proof = CBMutableCharacteristic(
                type: HelperPeripheralRoute.peerProofUUID,
                properties: [.read],
                value: nil,
                permissions: [.readEncryptionRequired]
            )
            let control = CBMutableCharacteristic(
                type: HelperPeripheralRoute.controlUUID,
                properties: [.write, .indicate],
                value: nil,
                permissions: [.writeEncryptionRequired]
            )
            let telemetry = CBMutableCharacteristic(
                type: HelperPeripheralRoute.telemetryUUID,
                properties: [.read, .notify],
                value: nil,
                permissions: [.readEncryptionRequired]
            )
            let service = CBMutableService(type: HelperPeripheralRoute.serviceUUID, primary: true)
            service.characteristics = [proof, control, telemetry]
            self.service = service
            self.peerProofCharacteristic = proof
            self.controlCharacteristic = control
            self.telemetryCharacteristic = telemetry
            if let generation { lifecycle = .publishing(generation: generation) }
            manager.removeAllServices()
            manager.add(service)
            armPublicationWatchdog(
                generation: generation,
                reason: "Core Bluetooth did not confirm F201 service publication"
            )
        }

        private func startUUIDOnlyAdvertisement(on manager: CBPeripheralManager) {
            guard case .resumeSelectedRoute = restorationDisposition else { return }
            if manager.isAdvertising {
                if let generation {
                    lifecycle = controlSubscriberID == nil
                        ? .awaitingControlSubscription(generation: generation)
                        : .active(generation: generation)
                    emitOperationalOnce(generation: generation)
                }
                return
            }
            if let generation { lifecycle = .advertising(generation: generation) }
            manager.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [HelperPeripheralRoute.serviceUUID]
            ])
            armPublicationWatchdog(
                generation: generation,
                reason: "Core Bluetooth did not confirm F201 advertising"
            )
        }

        private func bindRestoredService(from dictionary: [String: Any]) -> RestoredTopology {
            guard let services = dictionary[CBPeripheralManagerRestoredStateServicesKey]
                    as? [CBMutableService], !services.isEmpty else {
                return .empty
            }
            if services.contains(where: { $0.uuid == HelperPeripheralRoute.legacyF04ServiceUUID }) {
                return .legacyF04
            }
            guard services.count == 1,
                  let restored = services.first,
                  restored.uuid == HelperPeripheralRoute.serviceUUID else {
                return .incompatibleUnprovable
            }
            let characteristics = restored.characteristics ?? []
            guard characteristics.count == 3,
                  let proof = characteristics.first(where: {
                      $0.uuid == HelperPeripheralRoute.peerProofUUID
                  })
                    as? CBMutableCharacteristic,
                  let control = characteristics.first(where: {
                      $0.uuid == HelperPeripheralRoute.controlUUID
                  })
                    as? CBMutableCharacteristic,
                  let telemetry = characteristics.first(where: {
                      $0.uuid == HelperPeripheralRoute.telemetryUUID
                  })
                    as? CBMutableCharacteristic,
                  characteristics.filter({
                      $0.uuid == HelperPeripheralRoute.peerProofUUID
                  }).count == 1,
                  characteristics.filter({
                      $0.uuid == HelperPeripheralRoute.controlUUID
                  }).count == 1,
                  characteristics.filter({
                      $0.uuid == HelperPeripheralRoute.telemetryUUID
                  }).count == 1 else {
                return .incompatibleUnprovable
            }
            guard proof.properties == [.read],
                  proof.permissions == [.readEncryptionRequired],
                  control.properties == [.write, .indicate],
                  control.permissions == [.writeEncryptionRequired],
                  telemetry.properties == [.read, .notify],
                  telemetry.permissions == [.readEncryptionRequired] else {
                return .incompatibleUnprovable
            }
            let restoredControlIDs = Set(
                (control.subscribedCentrals ?? []).map(\.identifier)
            )
            let restoredTelemetryIDs = Set(
                (telemetry.subscribedCentrals ?? []).map(\.identifier)
            )
            guard restoredControlIDs.count <= 1,
                  restoredTelemetryIDs.isSubset(of: restoredControlIDs) else {
                if let generation {
                    fail(
                        "restored telemetry owner lacks one exact control subscription",
                        generation: generation
                    )
                }
                return .incompatibleUnprovable
            }
            service = restored
            peerProofCharacteristic = proof
            controlCharacteristic = control
            telemetryCharacteristic = telemetry
            controlSubscriberID = restoredControlIDs.first
            telemetrySubscribers = restoredTelemetryIDs
            servicePublicationIssued = true
            restorationWasExactV2 = true
            return .exactV2
        }

        private var restorationDrainEpoch: BleRoleSwitchPolicy.Sequence? {
            if case let .drainInactiveRoute(epoch) = restorationDisposition { return epoch }
            if case let .drainLocalOnlyRestore(epoch) = restorationDisposition { return epoch }
            if case let .drainLegacyMigration(epoch) = restorationDisposition { return epoch }
            return nil
        }

        private var permitsEmptyLocalOnlyDrain: Bool {
            if case .drainLocalOnlyRestore = restorationDisposition { return true }
            if case .drainLegacyMigration = restorationDisposition { return true }
            return false
        }

        private var permitsLegacyMigrationDrain: Bool {
            if case .drainLegacyMigration = restorationDisposition { return true }
            return false
        }

        private func beginRestorationMigrationDrain(
            on peripheral: CBPeripheralManager,
            topology: RestoredTopology
        ) {
            guard restorationObservationToken == nil, let generation else { return }
            guard peripheral.state == .poweredOn else {
                pendingRestoredTopology = topology
                lifecycle = .waitingForRadio(generation: generation)
                return
            }
            pendingRestoredTopology = nil
            let exactV2 = topology == .exactV2
            let legacyF04 = topology == .legacyF04
            let emptyLocalOnly = topology == .empty && permitsEmptyLocalOnlyDrain
            let legacyLocalOnly = !exactV2 && topology != .empty && permitsLegacyMigrationDrain
            restorationWasExactV2 = exactV2
            restorationWasLegacy = legacyF04
            lifecycle = .migrationDrain(
                generation: generation,
                legacyUnprovable: !exactV2
            )
            acceptingIngress = false
            peripheral.stopAdvertising()
            peripheral.removeAllServices()
            service = nil
            peerProofCharacteristic = nil
            controlCharacteristic = nil
            telemetryCharacteristic = nil
            pendingControlNotification = nil
            servicePublicationIssued = false

            if !exactV2 && !emptyLocalOnly {
                observer?.helperPeripheralRoute(
                    self,
                    didEncounterUnprovableMigration: restorationDrainEpoch,
                    legacyF04: legacyF04,
                    generation: generation
                )
            }

            let token = UUID()
            restorationObservationToken = token
            queue.asyncAfter(deadline: .now() + Self.restorationObservationGrace) {
                [weak self, weak peripheral] in
                guard let self, let peripheral,
                      self.manager === peripheral,
                      self.generation == generation,
                      self.restorationObservationToken == token else {
                    return
                }
                self.restorationObservationToken = nil

                // Only a byte-exact restored F201 service with no control CCCD before and after
                // stop/remove earns NO_ESTABLISHED_V2_OWNER. Legacy F04 never earns this proof.
                if exactV2 {
                    guard self.controlSubscriberID == nil,
                          self.telemetrySubscribers.isEmpty else {
                        self.fail(
                            "restored v2 owner changed during the observation grace",
                            generation: generation
                        )
                        return
                    }
                    if let epoch = self.restorationDrainEpoch {
                        self.observer?.helperPeripheralRoute(
                            self,
                            didFreezeWithoutRemoteOwner: epoch,
                            generation: generation
                        )
                    }
                }

                if emptyLocalOnly, let epoch = self.restorationDrainEpoch {
                    self.observer?.helperPeripheralRoute(
                        self,
                        didFreezeWithoutRemoteOwner: epoch,
                        generation: generation
                    )
                }

                if legacyLocalOnly, let epoch = self.restorationDrainEpoch {
                    self.observer?.helperPeripheralRoute(
                        self,
                        didFreezeWithoutRemoteOwner: epoch,
                        generation: generation
                    )
                }

                if let epoch = self.restorationDrainEpoch {
                    self.releaseAllOwners(epoch: epoch, generation: generation)
                } else if peripheral.state == .poweredOn {
                    // Same-topology recovery is allowed only after the migration settle turn.
                    self.publishFreshService(on: peripheral)
                } else {
                    self.lifecycle = .waitingForRadio(generation: generation)
                }
            }
        }

        private func freezeRestoredOwnerForDrain(
            on peripheral: CBPeripheralManager,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard peripheral.state == .poweredOn else {
                pendingRestoredTopology = .exactV2
                lifecycle = .waitingForRadio(generation: generation)
                return
            }
            if case .freezing(let frozenEpoch, let frozenGeneration) = lifecycle,
               frozenEpoch == epoch, frozenGeneration == generation {
                return
            }
            guard controlSubscriberID != nil else {
                beginRestorationMigrationDrain(on: peripheral, topology: .exactV2)
                return
            }
            acceptingIngress = false
            peripheral.stopAdvertising()
            lifecycle = .freezing(epoch: epoch, generation: generation)
            observer?.helperPeripheralRoute(self, didFreeze: epoch, generation: generation)
        }

        /// A real control unsubscribe proves that the live v2 ATT owner disappeared, but Core
        /// Bluetooth can deliver the telemetry unsubscribe one callback later. Reclaim the
        /// service and observe one bounded settle window. Recovery always releases the old manager
        /// and lets the top-level same-role reducer allocate a fresh generation.
        private func beginLiveZeroControlObservation(
            epoch: BleRoleSwitchPolicy.Sequence?,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard self.generation == generation,
                  restorationObservationToken == nil,
                  let peripheral = manager else { return }
            liveZeroControlEpoch = epoch
            liveZeroObservationSettled = false
            lifecycle = .migrationDrain(generation: generation, legacyUnprovable: false)
            acceptingIngress = false
            peripheral.stopAdvertising()
            peripheral.removeAllServices()
            service = nil
            peerProofCharacteristic = nil
            controlCharacteristic = nil
            telemetryCharacteristic = nil
            pendingControlNotification = nil
            servicePublicationIssued = false

            let token = UUID()
            restorationObservationToken = token
            queue.asyncAfter(deadline: .now() + Self.restorationObservationGrace) {
                [weak self, weak peripheral] in
                guard let self, let peripheral,
                      self.manager === peripheral,
                      self.generation == generation,
                      self.restorationObservationToken == token else { return }
                self.restorationObservationToken = nil
                guard self.controlSubscriberID == nil else {
                    self.fail(
                        "v2 control owner reappeared during bounded teardown",
                        generation: generation
                    )
                    return
                }
                // The bounded turn proves that no new control owner was adopted. Releasing this
                // manager below invalidates any telemetry CCCD whose unsubscribe callback vanished.
                self.liveZeroObservationSettled = true
                self.finishLiveZeroControlObservationIfReady(
                    on: peripheral,
                    generation: generation
                )
            }
        }

        private func finishLiveZeroControlObservationIfReady(
            on _: CBPeripheralManager,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            guard liveZeroObservationSettled,
                  controlSubscriberID == nil else { return }
            if let epoch = liveZeroControlEpoch {
                // This observation began after the exact control owner disappeared. Releasing the
                // manager is the authoritative local owner-zero boundary even if Core Bluetooth
                // omits the now-stale telemetry CCCD callback.
                observer?.helperPeripheralRoute(
                    self,
                    didFreezeWithoutRemoteOwner: epoch,
                    generation: generation
                )
                releaseAllOwners(epoch: epoch, generation: generation)
            } else {
                releaseUnexpectedOwner(generation: generation)
            }
        }

        private func releaseUnexpectedOwner(generation: BleRoleSwitchPolicy.Sequence) {
            managerStartupTimerToken = nil
            publicationTimerToken = nil
            let retiring = manager
            retiring?.stopAdvertising()
            retiring?.removeAllServices()
            retiring?.delegate = nil
            manager = nil
            service = nil
            peerProofCharacteristic = nil
            controlCharacteristic = nil
            telemetryCharacteristic = nil
            pendingTelemetry = nil
            currentTelemetry = nil
            pendingControlNotification = nil
            lastAcceptedControlNotification = nil
            inboundCloseIntent = nil
            controlSubscriberID = nil
            telemetrySubscribers.removeAll(keepingCapacity: false)
            restorationObservationToken = nil
            liveZeroControlEpoch = nil
            liveZeroObservationSettled = false
            acceptingIngress = false
            lifecycle = .idle
        }

        private func releaseAllOwners(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        ) {
            managerStartupTimerToken = nil
            publicationTimerToken = nil
            let retiring = manager
            retiring?.stopAdvertising()
            retiring?.removeAllServices()
            retiring?.delegate = nil
            manager = nil
            service = nil
            peerProofCharacteristic = nil
            controlCharacteristic = nil
            telemetryCharacteristic = nil
            pendingTelemetry = nil
            currentTelemetry = nil
            pendingControlNotification = nil
            lastAcceptedControlNotification = nil
            inboundCloseIntent = nil
            controlSubscriberID = nil
            telemetrySubscribers.removeAll(keepingCapacity: false)
            expectedControlUnsubscribeID = nil
            exactControlUnsubscribeObserved = false
            earlyControlUnsubscribeID = nil
            earlyControlUnsubscribeEpoch = nil
            restorationObservationToken = nil
            liveZeroControlEpoch = nil
            liveZeroObservationSettled = false
            acceptingIngress = false

            // A queue turn separates object release from the two independent reducer evidences.
            queue.async { [weak self] in
                guard let self, self.generation == generation else { return }
                self.lifecycle = .terminal(epoch: epoch, generation: generation)
                self.observer?.helperPeripheralRoute(
                    self,
                    didBecomeTerminal: epoch,
                    generation: generation
                )
                self.observer?.helperPeripheralRoute(
                    self,
                    didObserveLocalOwnerCount: self.manager == nil ? 0 : 1,
                    epoch: epoch,
                    generation: generation
                )
            }
        }

        private func finishStopIfExactBoundaryReached() {
            guard case .stopping(let epoch, let stoppingGeneration) = lifecycle,
                  generation == stoppingGeneration,
                  exactControlUnsubscribeObserved,
                  controlSubscriberID == nil,
                  telemetrySubscribers.isEmpty else {
                return
            }
            releaseAllOwners(epoch: epoch, generation: stoppingGeneration)
        }

        private func drainNotifications() {
            guard let manager, let controlID = controlSubscriberID else { return }
            if let pending = pendingControlNotification,
               let controlCharacteristic {
                let exactCentrals = (controlCharacteristic.subscribedCentrals ?? []).filter {
                    $0.identifier == controlID
                }
                guard exactCentrals.count == 1 else { return }
                if manager.updateValue(
                    pending.data,
                    for: controlCharacteristic,
                    onSubscribedCentrals: exactCentrals
                ) {
                    pendingControlNotification = nil
                    lastAcceptedControlNotification = pending
                    observer?.helperPeripheralRoute(
                        self,
                        didAcceptOutboundControl: pending.frame,
                        epoch: pending.epoch,
                        generation: pending.generation,
                        attempt: pending.attempt
                    )
                }
                return
            }
            guard let telemetryCharacteristic,
                  telemetrySubscribers == [controlID], let telemetry = pendingTelemetry else {
                return
            }
            let exactCentrals = (telemetryCharacteristic.subscribedCentrals ?? []).filter {
                $0.identifier == controlID
            }
            guard exactCentrals.count == 1 else { return }
            if manager.updateValue(
                telemetry,
                for: telemetryCharacteristic,
                onSubscribedCentrals: exactCentrals
            ) {
                pendingTelemetry = nil
            }
        }

        private func fail(_ reason: String, generation: BleRoleSwitchPolicy.Sequence) {
            managerStartupTimerToken = nil
            publicationTimerToken = nil
            lifecycle = .failed(generation: generation, reason: reason)
            acceptingIngress = false
            restorationObservationToken = nil
            pendingRestoredTopology = nil
            manager?.stopAdvertising()
            observer?.helperPeripheralRoute(self, didFail: reason, generation: generation)
        }

        private func emitReadyOnce(generation: BleRoleSwitchPolicy.Sequence) {
            guard readyEmittedGeneration != generation else { return }
            readyEmittedGeneration = generation
            observer?.helperPeripheralRoute(self, didBecomeReady: generation)
        }

        private func emitOperationalOnce(generation: BleRoleSwitchPolicy.Sequence) {
            guard operationalEmittedGeneration != generation else { return }
            operationalEmittedGeneration = generation
            managerStartupTimerToken = nil
            publicationTimerToken = nil
            observer?.helperPeripheralRoute(self, didBecomeOperational: generation)
        }

        private func armManagerStartupWatchdog(generation: BleRoleSwitchPolicy.Sequence) {
            let token = UUID()
            managerStartupTimerToken = token
            queue.asyncAfter(deadline: .now() + Self.managerStartupTimeout) { [weak self] in
                guard let self, self.managerStartupTimerToken == token,
                      self.generation == generation else { return }
                self.managerStartupTimerToken = nil
                self.fail(
                    "Core Bluetooth peripheral manager did not become responsive (possible XPC reset)",
                    generation: generation
                )
            }
        }

        private func armPublicationWatchdog(
            generation: BleRoleSwitchPolicy.Sequence?,
            reason: String
        ) {
            guard let generation else { return }
            let token = UUID()
            publicationTimerToken = token
            queue.asyncAfter(deadline: .now() + Self.publicationTimeout) { [weak self] in
                guard let self, self.publicationTimerToken == token,
                      self.generation == generation else { return }
                self.publicationTimerToken = nil
                self.fail(reason, generation: generation)
            }
        }

        private func prepareServiceForRadioRecovery() {
            publicationTimerToken = nil
            acceptingIngress = false
            service = nil
            peerProofCharacteristic = nil
            controlCharacteristic = nil
            telemetryCharacteristic = nil
            servicePublicationIssued = false
            pendingRestoredTopology = nil
            currentTelemetry = nil
            pendingTelemetry = nil
            pendingControlNotification = nil
            controlSubscriberID = nil
            telemetrySubscribers.removeAll(keepingCapacity: false)
        }

        private static func loadOrCreateInstallationID(
            _ configuration: Configuration
        ) throws -> UUID {
            if let value = configuration.defaults.string(forKey: configuration.installationDefaultsKey),
               let existing = UUID(uuidString: value),
               canonicalBytes(existing).reduce(UInt8(0), |) != 0 {
                return existing
            }
            var generated = UUID()
            while canonicalBytes(generated).reduce(UInt8(0), |) == 0 { generated = UUID() }
            configuration.defaults.set(
                generated.uuidString.lowercased(),
                forKey: configuration.installationDefaultsKey
            )
            guard let reread = configuration.defaults.string(
                    forKey: configuration.installationDefaultsKey
                  ), UUID(uuidString: reread) == generated else {
                throw SetupError.installationIdentityCouldNotBePersisted
            }
            return generated
        }

        private static func canonicalBytes(_ uuid: UUID) -> [UInt8] {
            var raw = uuid.uuid
            return withUnsafeBytes(of: &raw) { Array($0) }
        }
    }
}

extension HelperPeripheralRoute.Coordinator: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral === manager, let generation else { return }
        switch peripheral.state {
        case .poweredOn:
            managerStartupTimerToken = nil
            guard restorationObservationToken == nil else { return }
            if let pendingTopology = pendingRestoredTopology {
                pendingRestoredTopology = nil
                if pendingTopology == .exactV2, controlSubscriberID != nil {
                    if let epoch = restorationDrainEpoch {
                        freezeRestoredOwnerForDrain(
                            on: peripheral,
                            epoch: epoch,
                            generation: generation
                        )
                    } else {
                        lifecycle = .active(generation: generation)
                        acceptingIngress = true
                        emitReadyOnce(generation: generation)
                        startUUIDOnlyAdvertisement(on: peripheral)
                        drainNotifications()
                    }
                } else {
                    beginRestorationMigrationDrain(on: peripheral, topology: pendingTopology)
                }
                return
            }
            if let epoch = restorationDrainEpoch {
                if restorationWasExactV2, service != nil {
                    freezeRestoredOwnerForDrain(
                        on: peripheral,
                        epoch: epoch,
                        generation: generation
                    )
                } else if !restorationCallbackObserved {
                    // Give willRestoreState one serialized callback turn before concluding that
                    // this process owns only a fresh, empty manager. This is local cleanup only;
                    // it is never promoted to remote-owner evidence.
                    queue.async { [weak self, weak peripheral] in
                        guard let self, let peripheral,
                              self.manager === peripheral,
                              !self.restorationCallbackObserved else { return }
                        self.beginRestorationMigrationDrain(on: peripheral, topology: .empty)
                    }
                }
            } else if service == nil {
                if !restorationCallbackObserved {
                    // Give Core Bluetooth's one-shot restoration callback a serialized turn before
                    // publishing fresh F201 objects. A delayed callback can never be merged beside
                    // an already-published service graph.
                    queue.async { [weak self, weak peripheral] in
                        guard let self, let peripheral,
                              self.manager === peripheral,
                              self.generation == generation,
                              !self.restorationCallbackObserved,
                              self.service == nil,
                              case .waitingForRadio(let waitingGeneration) = self.lifecycle,
                              waitingGeneration == generation else { return }
                        self.publishFreshService(on: peripheral)
                    }
                } else {
                    publishFreshService(on: peripheral)
                }
            } else {
                if controlSubscriberID != nil {
                    lifecycle = .active(generation: generation)
                    acceptingIngress = true
                    emitReadyOnce(generation: generation)
                }
                startUUIDOnlyAdvertisement(on: peripheral)
            }
        case .unauthorized:
            fail("Bluetooth peripheral role is unauthorized", generation: generation)
        case .unsupported:
            fail("Bluetooth peripheral role is unsupported", generation: generation)
        case .poweredOff, .resetting:
            let frozenEpoch: BleRoleSwitchPolicy.Sequence?
            switch lifecycle {
            case .freezing(let epoch, let frozenGeneration) where frozenGeneration == generation:
                frozenEpoch = epoch
            case .stopping(let epoch, let stoppingGeneration) where stoppingGeneration == generation:
                frozenEpoch = epoch
            case .migrationDrain where liveZeroControlEpoch != nil:
                frozenEpoch = liveZeroControlEpoch
            case .migrationDrain where restorationDrainEpoch != nil:
                frozenEpoch = restorationDrainEpoch
            default:
                frozenEpoch = restorationDrainEpoch
            }
            if let epoch = frozenEpoch {
                observer?.helperPeripheralRoute(
                    self,
                    didLoseFrozenExactLink: epoch,
                    generation: generation
                )
                releaseAllOwners(epoch: epoch, generation: generation)
            } else if case .active = lifecycle {
                releaseUnexpectedOwner(generation: generation)
                observer?.helperPeripheralRoute(self, didLoseExactLink: generation)
            } else {
                prepareServiceForRadioRecovery()
                lifecycle = .waitingForRadio(generation: generation)
                if peripheral.state == .resetting {
                    armManagerStartupWatchdog(generation: generation)
                } else {
                    // A powered-off callback proves that the daemon answered. The top-level
                    // target watchdog remains the bounded, visible wait for the user to restore
                    // Bluetooth; do not mislabel this as an XPC failure.
                    managerStartupTimerToken = nil
                }
            }
        case .unknown:
            break
        @unknown default:
            fail("unknown peripheral manager state", generation: generation)
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        willRestoreState dict: [String: Any]
    ) {
        guard peripheral === manager, let generation,
              case .waitingForRadio(let waitingGeneration) = lifecycle,
              waitingGeneration == generation else { return }
        restorationCallbackObserved = true
        let topology = bindRestoredService(from: dict)
        if case .failed = lifecycle { return }
        if peripheral.state != .poweredOn {
            pendingRestoredTopology = topology
            lifecycle = .waitingForRadio(generation: generation)
            return
        }
        switch topology {
        case .exactV2:
            if let epoch = restorationDrainEpoch {
                freezeRestoredOwnerForDrain(
                    on: peripheral,
                    epoch: epoch,
                    generation: generation
                )
            } else if controlSubscriberID == nil {
                beginRestorationMigrationDrain(on: peripheral, topology: .exactV2)
            } else {
                lifecycle = .active(generation: generation)
                acceptingIngress = true
                emitReadyOnce(generation: generation)
                startUUIDOnlyAdvertisement(on: peripheral)
                drainNotifications()
            }
        case .legacyF04, .incompatibleUnprovable:
            beginRestorationMigrationDrain(on: peripheral, topology: topology)
        case .empty:
            if restorationDrainEpoch != nil {
                beginRestorationMigrationDrain(on: peripheral, topology: .empty)
            } else if peripheral.state == .poweredOn {
                publishFreshService(on: peripheral)
            }
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didAdd service: CBService,
        error: Error?
    ) {
        guard peripheral === manager,
              let expectedService = self.service,
              service === expectedService,
              let generation else { return }
        guard error == nil else {
            fail("service publication failed: \(String(describing: error))", generation: generation)
            return
        }
        publicationTimerToken = nil
        startUUIDOnlyAdvertisement(on: peripheral)
    }

    public func peripheralManagerDidStartAdvertising(
        _ peripheral: CBPeripheralManager,
        error: Error?
    ) {
        guard peripheral === manager, let generation else { return }
        guard case .advertising(let issuedGeneration) = lifecycle,
              issuedGeneration == generation else {
            // stopAdvertising has no completion callback. Lifecycle+generation is the exact
            // attempt token, so a delayed start completion cannot reopen frozen ingress.
            return
        }
        guard error == nil else {
            fail("advertising failed: \(String(describing: error))", generation: generation)
            return
        }
        publicationTimerToken = nil
        acceptingIngress = true
        lifecycle = controlSubscriberID == nil
            ? .awaitingControlSubscription(generation: generation)
            : .active(generation: generation)
        emitOperationalOnce(generation: generation)
        if controlSubscriberID != nil { emitReadyOnce(generation: generation) }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveRead request: CBATTRequest
    ) {
        guard peripheral === manager, acceptingIngress else {
            peripheral.respond(to: request, withResult: .unlikelyError)
            return
        }
        let value: Data?
        if request.characteristic.uuid == HelperPeripheralRoute.peerProofUUID {
            value = peerProof
        } else if request.characteristic.uuid == HelperPeripheralRoute.telemetryUUID {
            value = currentTelemetry
        } else {
            peripheral.respond(to: request, withResult: .readNotPermitted)
            return
        }
        guard let value, request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        guard peripheral === manager, let generation else { return }
        guard requests.count == 1, let request = requests.first else {
            for request in requests {
                peripheral.respond(to: request, withResult: .unlikelyError)
            }
            return
        }
        guard request.characteristic.uuid == HelperPeripheralRoute.controlUUID,
              request.central.identifier == controlSubscriberID else {
            peripheral.respond(to: request, withResult: .writeNotPermitted)
            return
        }
        guard request.offset == 0, let value = request.value,
              let frame = IphoneBleWireProtocolV2.decodeControl(Array(value)),
              frame.type != .peerProof,
              frame.mode == .androidPeripheral else {
            peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
            return
        }
        switch lifecycle {
        case .active(let activeGeneration) where activeGeneration == generation:
            if frame.type == .roleClose {
                if let existing = inboundCloseIntent, existing != frame {
                    peripheral.respond(to: request, withResult: .unlikelyError)
                    fail("conflicting inbound Route-A C", generation: generation)
                    return
                }
                inboundCloseIntent = frame
            }
            peripheral.respond(to: request, withResult: .success)
            observer?.helperPeripheralRoute(
                self,
                didReceiveControl: frame,
                generation: generation
            )
        case .freezing(_, let frozenGeneration) where frozenGeneration == generation:
            if frame.type == .roleClose {
                if let existing = inboundCloseIntent, existing != frame {
                    peripheral.respond(to: request, withResult: .unlikelyError)
                    fail("conflicting inbound Route-A C", generation: generation)
                    return
                }
                inboundCloseIntent = frame
            }
            peripheral.respond(to: request, withResult: .success)
            observer?.helperPeripheralRoute(
                self,
                didReceiveControl: frame,
                generation: generation
            )
        case .stopping(_, let stoppingGeneration) where stoppingGeneration == generation:
            guard frame.type == .roleClose, inboundCloseIntent == frame else {
                peripheral.respond(to: request, withResult: .unlikelyError)
                return
            }
            peripheral.respond(to: request, withResult: .success)
            observer?.helperPeripheralRoute(
                self,
                didReceiveControl: frame,
                generation: generation
            )
        default:
            peripheral.respond(to: request, withResult: .unlikelyError)
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        guard peripheral === manager, let generation else { return }
        if characteristic.uuid == HelperPeripheralRoute.controlUUID {
            switch lifecycle {
            case .awaitingControlSubscription, .advertising, .active:
                break
            case .freezing, .stopping, .migrationDrain, .terminal, .failed:
                // A late/duplicate CCCD callback cannot reopen ingress after the freeze barrier.
                return
            default:
                fail("control subscribed before Route-A publication was ready", generation: generation)
                return
            }
            guard expectedControlUnsubscribeID == nil else {
                fail("control subscribed while exact stop was pending", generation: generation)
                return
            }
            if let existing = controlSubscriberID, existing != central.identifier {
                fail("multiple v2 control subscribers violate single owner", generation: generation)
                return
            }
            guard telemetrySubscribers.allSatisfy({ $0 == central.identifier }) else {
                fail("control owner conflicts with telemetry owner", generation: generation)
                return
            }
            let wasReady = readyEmittedGeneration == generation
            controlSubscriberID = central.identifier
            lifecycle = .active(generation: generation)
            acceptingIngress = true
            if !wasReady { emitReadyOnce(generation: generation) }
        } else if characteristic.uuid == HelperPeripheralRoute.telemetryUUID {
            switch lifecycle {
            case .active:
                break
            case .freezing, .stopping, .migrationDrain, .terminal, .failed:
                return
            default:
                fail("telemetry subscribed outside active Route A", generation: generation)
                return
            }
            guard controlSubscriberID == central.identifier else {
                fail("telemetry subscription arrived without its control owner", generation: generation)
                return
            }
            telemetrySubscribers.insert(central.identifier)
        }
        drainNotifications()
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        guard peripheral === manager, let generation else { return }
        if characteristic.uuid == HelperPeripheralRoute.controlUUID {
            if let expected = expectedControlUnsubscribeID {
                guard central.identifier == expected else { return }
                exactControlUnsubscribeObserved = true
                controlSubscriberID = nil
                finishStopIfExactBoundaryReached()
                return
            }
            guard controlSubscriberID == central.identifier else { return }
            controlSubscriberID = nil
            acceptingIngress = false
            switch lifecycle {
            case .freezing(let epoch, let frozenGeneration) where frozenGeneration == generation:
                // The client-first close may beat the serialized STOP effect. Preserve the exact
                // unsubscribe and wait for STOP plus telemetry-owner zero up to the reducer's
                // absolute stop deadline; no arbitrary short grace can invalidate this path.
                earlyControlUnsubscribeID = central.identifier
                earlyControlUnsubscribeEpoch = epoch
                exactControlUnsubscribeObserved = true
            case .active, .awaitingControlSubscription, .advertising:
                beginLiveZeroControlObservation(epoch: nil, generation: generation)
                observer?.helperPeripheralRoute(self, didLoseExactLink: generation)
            default:
                fail("control owner disappeared in an invalid lifecycle", generation: generation)
            }
        } else if characteristic.uuid == HelperPeripheralRoute.telemetryUUID {
            guard telemetrySubscribers.contains(central.identifier) else { return }
            telemetrySubscribers.remove(central.identifier)
            finishStopIfExactBoundaryReached()
            if case .migrationDrain = lifecycle, let manager {
                finishLiveZeroControlObservationIfReady(
                    on: manager,
                    generation: generation
                )
            }
        }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        guard peripheral === manager else { return }
        drainNotifications()
    }
}
