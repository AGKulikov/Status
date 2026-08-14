import Foundation

/// Sole construction/ownership boundary for Helper BLE routes.
///
/// Route A and Route B have disjoint coordinator namespaces. UI code sees only this owner and
/// cannot create a route-local Core Bluetooth manager directly.
public final class HelperBleRuntimeCoordinator {
    public struct RouteOwnerToken {
        fileprivate init() {}
    }

    public enum Event: Equatable {
        case routeALocalOperational(generation: BleRoleSwitchPolicy.Sequence)
        case routeAReady(generation: BleRoleSwitchPolicy.Sequence)
        case routeALostExactLink(generation: BleRoleSwitchPolicy.Sequence)
        case routeALostFrozenExactLink(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceIngressFrozen(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceLocalTerminal(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceLocalOwnerCount(
            Int,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceFrozenWithoutRemoteOwner(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceUnprovableMigration(
            epoch: BleRoleSwitchPolicy.Sequence?,
            legacyF04: Bool,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceControlReceived(
            IphoneBleWireProtocolV2.ControlFrame,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case sourceControlAccepted(
            IphoneBleWireProtocolV2.ControlFrame,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        )
        case routeBLocalOperational(generation: BleRoleSwitchPolicy.Sequence)
        case routeBReady(
            androidInstallationID: UUID,
            peripheralID: UUID,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBIngressFrozen(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBLocalTerminal(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBLocalOwnerCount(
            Int,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBFrozenWithoutRemoteOwner(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBControlReceived(
            IphoneBleWireProtocolV2.ControlFrame,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBControlAccepted(
            IphoneBleWireProtocolV2.ControlFrame,
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence,
            attempt: BleRoleSwitchPolicy.Sequence
        )
        case routeBLostExactLink(generation: BleRoleSwitchPolicy.Sequence)
        case routeBLostFrozenExactLink(
            epoch: BleRoleSwitchPolicy.Sequence,
            generation: BleRoleSwitchPolicy.Sequence
        )
        case routeBUnprovableRestoration(generation: BleRoleSwitchPolicy.Sequence)
        case routeFailed(
            role: BleRoleSwitchPolicy.Role,
            reason: String,
            generation: BleRoleSwitchPolicy.Sequence
        )
    }

    public var onEvent: ((Event) -> Void)?
    public var installationID: UUID { routeA.installationID }

    private let routeA: HelperPeripheralRoute.Coordinator
    private let routeB: HelperCentralRoute.Coordinator

    public init(
        routeAConfiguration: HelperPeripheralRoute.Configuration = .init(),
        routeBConfiguration: HelperCentralRoute.Configuration = .init()
    ) throws {
        let ownerToken = RouteOwnerToken()
        let routeA = try HelperPeripheralRoute.Coordinator(
            ownerToken: ownerToken,
            configuration: routeAConfiguration
        )
        let routeB = try HelperCentralRoute.Coordinator(
            ownerToken: ownerToken,
            installationID: routeA.installationID,
            configuration: routeBConfiguration
        )
        self.routeA = routeA
        self.routeB = routeB
        routeA.observer = self
        routeB.observer = self
    }

    public func startRouteA(
        generation: BleRoleSwitchPolicy.Sequence,
        restoration: HelperPeripheralRoute.RestorationDisposition = .resumeSelectedRoute
    ) {
        routeA.start(generation: generation, restoration: restoration)
    }

    public func freezeRouteA(
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.freezeIngress(epoch: epoch, generation: generation)
    }

    public func stopRouteA(
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.stop(epoch: epoch, generation: generation)
    }

    public func publishRouteATelemetry(
        _ telemetry: IphoneBleWireProtocolV2.Telemetry,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.publishTelemetry(telemetry, generation: generation)
    }

    public func sendRouteARoleClose(
        targetMode: IphoneBleWireProtocolV2.Mode,
        switchToken: [UInt8],
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.sendRoleClose(
            targetMode: targetMode,
            switchToken: switchToken,
            epoch: epoch,
            generation: generation,
            attempt: attempt
        )
    }

    /// Call only after the remote C target/token has been durably persisted and the matching
    /// route ingress-freeze callback has been accepted by the switch reducer.
    public func sendRouteARoleCloseAck(
        targetMode: IphoneBleWireProtocolV2.Mode,
        switchToken: [UInt8],
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.sendRoleCloseAck(
            targetMode: targetMode,
            switchToken: switchToken,
            epoch: epoch,
            generation: generation,
            attempt: attempt
        )
    }

    public func startRouteB(
        generation: BleRoleSwitchPolicy.Sequence,
        request: HelperCentralRoute.StartRequest,
        restoration: HelperCentralRoute.RestorationDisposition = .resumeSelectedRoute
    ) {
        routeB.start(generation: generation, request: request, restoration: restoration)
    }

    public func freezeRouteB(
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeB.freezeIngress(epoch: epoch, generation: generation)
    }

    public func stopRouteB(
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeB.stop(epoch: epoch, generation: generation)
    }

    public func publishRouteBTelemetry(
        _ telemetry: IphoneBleWireProtocolV2.Telemetry,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeB.publishTelemetry(telemetry, generation: generation)
    }

    public func sendRouteBRoleClose(
        targetMode: IphoneBleWireProtocolV2.Mode,
        switchToken: [UInt8],
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        routeB.sendRoleClose(
            targetMode: targetMode,
            switchToken: switchToken,
            epoch: epoch,
            generation: generation,
            attempt: attempt
        )
    }

    public func sendRouteBRoleCloseAck(
        targetMode: IphoneBleWireProtocolV2.Mode,
        switchToken: [UInt8],
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        routeB.sendRoleCloseAck(
            targetMode: targetMode,
            switchToken: switchToken,
            epoch: epoch,
            generation: generation,
            attempt: attempt
        )
    }

    /// Reclaims both stable Core Bluetooth restoration namespaces for a durable CLOSED tombstone.
    /// These are drain-only managers; neither route is permitted to acquire a replacement peer.
    public func startClosedRestorationDrain(
        epoch: BleRoleSwitchPolicy.Sequence,
        routeAGeneration: BleRoleSwitchPolicy.Sequence,
        routeBGeneration: BleRoleSwitchPolicy.Sequence,
        routeBRequest: HelperCentralRoute.StartRequest
    ) {
        routeA.start(
            generation: routeAGeneration,
            restoration: .drainLegacyMigration(epoch: epoch)
        )
        routeB.start(
            generation: routeBGeneration,
            request: routeBRequest,
            restoration: .drainLegacyMigration(epoch: epoch)
        )
    }

    public func forceCloseRestorationNamespaces(
        epoch: BleRoleSwitchPolicy.Sequence,
        routeAGeneration: BleRoleSwitchPolicy.Sequence,
        routeBGeneration: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.forceCloseRestorationNamespace(epoch: epoch, generation: routeAGeneration)
        routeB.forceCloseRestorationNamespace(epoch: epoch, generation: routeBGeneration)
    }

    public func forceCloseRouteA(
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeA.forceCloseRestorationNamespace(epoch: epoch, generation: generation)
    }

    public func forceCloseRouteB(
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        routeB.forceCloseRestorationNamespace(epoch: epoch, generation: generation)
    }
}

extension HelperBleRuntimeCoordinator: HelperPeripheralRoute.Observer {
    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didBecomeOperational generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeALocalOperational(generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didBecomeReady generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeAReady(generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didFreeze epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceIngressFrozen(epoch: epoch, generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didBecomeTerminal epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceLocalTerminal(epoch: epoch, generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didObserveLocalOwnerCount count: Int,
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceLocalOwnerCount(
            count,
            epoch: epoch,
            generation: generation
        ))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didReceiveControl frame: IphoneBleWireProtocolV2.ControlFrame,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceControlReceived(frame, generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didAcceptOutboundControl frame: IphoneBleWireProtocolV2.ControlFrame,
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceControlAccepted(
            frame,
            epoch: epoch,
            generation: generation,
            attempt: attempt
        ))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didFreezeWithoutRemoteOwner epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceFrozenWithoutRemoteOwner(epoch: epoch, generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didLoseExactLink generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeALostExactLink(generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didLoseFrozenExactLink epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeALostFrozenExactLink(epoch: epoch, generation: generation))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didEncounterUnprovableMigration epoch: BleRoleSwitchPolicy.Sequence?,
        legacyF04: Bool,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.sourceUnprovableMigration(
            epoch: epoch,
            legacyF04: legacyF04,
            generation: generation
        ))
    }

    public func helperPeripheralRoute(
        _ route: HelperPeripheralRoute.Coordinator,
        didFail reason: String,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeFailed(
            role: .helperPeripheralAndroidCentral,
            reason: reason,
            generation: generation
        ))
    }
}

extension HelperBleRuntimeCoordinator: HelperCentralRoute.Observer {
    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didBecomeOperational generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBLocalOperational(generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didBecomeReady androidInstallationID: UUID,
        peripheralID: UUID,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBReady(
            androidInstallationID: androidInstallationID,
            peripheralID: peripheralID,
            generation: generation
        ))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didFreeze epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBIngressFrozen(epoch: epoch, generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didReceiveControl frame: IphoneBleWireProtocolV2.ControlFrame,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBControlReceived(frame, generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didAcceptOutboundControl frame: IphoneBleWireProtocolV2.ControlFrame,
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence,
        attempt: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBControlAccepted(
            frame,
            epoch: epoch,
            generation: generation,
            attempt: attempt
        ))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didBecomeTerminal epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBLocalTerminal(epoch: epoch, generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didObserveLocalOwnerCount count: Int,
        epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBLocalOwnerCount(
            count,
            epoch: epoch,
            generation: generation
        ))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didFreezeWithoutRemoteOwner epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBFrozenWithoutRemoteOwner(epoch: epoch, generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didLoseExactLink generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBLostExactLink(generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didLoseFrozenExactLink epoch: BleRoleSwitchPolicy.Sequence,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBLostFrozenExactLink(epoch: epoch, generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didEncounterUnprovableRestoration generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeBUnprovableRestoration(generation: generation))
    }

    public func helperCentralRoute(
        _ route: HelperCentralRoute.Coordinator,
        didFail reason: String,
        generation: BleRoleSwitchPolicy.Sequence
    ) {
        onEvent?(.routeFailed(
            role: .helperCentralAndroidPeripheral,
            reason: reason,
            generation: generation
        ))
    }
}
