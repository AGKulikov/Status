#!/usr/bin/env python3
"""Source gates for the clean Route-A Helper Peripheral coordinator."""

from pathlib import Path


ROOT = Path(__file__).parent


class StopBoundaryModel:
    """Executable replay of the subscriber gates implemented by Route A."""

    def __init__(self) -> None:
        self.control: str | None = None
        self.telemetry: set[str] = set()
        self.expected: str | None = None
        self.control_unsubscribed = False
        self.terminal = False
        self.failed = False
        self.observing_zero = False

    def subscribe_control(self, central: str) -> None:
        if self.expected is not None or self.control not in (None, central):
            self.failed = True
            return
        if any(item != central for item in self.telemetry):
            self.failed = True
            return
        self.control = central

    def subscribe_telemetry(self, central: str) -> None:
        if self.control != central:
            self.failed = True
            return
        self.telemetry.add(central)

    def stop(self) -> None:
        if self.control is None:
            self.observing_zero = True
            return
        if any(item != self.control for item in self.telemetry):
            self.failed = True
            return
        self.expected = self.control

    def settle_zero_observation(self) -> None:
        if self.observing_zero and self.control is None and not self.telemetry:
            self.terminal = True
        elif self.observing_zero:
            self.failed = True

    def unsubscribe_control(self, central: str) -> None:
        if central != self.expected:
            return
        self.control_unsubscribed = True
        self.control = None
        self._finish()

    def unsubscribe_telemetry(self, central: str) -> None:
        self.telemetry.discard(central)
        self._finish()

    def _finish(self) -> None:
        self.terminal = (
            self.expected is not None
            and self.control_unsubscribed
            and self.control is None
            and not self.telemetry
        )


def verify_stop_boundary_replays() -> None:
    no_control = StopBoundaryModel()
    no_control.stop()
    assert no_control.observing_zero and not no_control.terminal
    no_control.settle_zero_observation()
    assert no_control.terminal and not no_control.failed

    telemetry_first = StopBoundaryModel()
    telemetry_first.subscribe_telemetry("android")
    assert telemetry_first.failed

    multiple = StopBoundaryModel()
    multiple.subscribe_control("android-1")
    multiple.subscribe_control("android-2")
    assert multiple.failed

    exact = StopBoundaryModel()
    exact.subscribe_control("android")
    exact.subscribe_telemetry("android")
    exact.stop()
    exact.unsubscribe_control("stale-central")
    assert not exact.terminal
    exact.unsubscribe_control("android")
    assert not exact.terminal
    exact.unsubscribe_telemetry("android")
    assert exact.terminal and not exact.failed


def main() -> None:
    route = (ROOT / "HelperPeripheralRoute.swift").read_text(encoding="utf-8")
    owner = (ROOT / "HelperBleRuntimeCoordinator.swift").read_text(encoding="utf-8")

    assert route.count("CBPeripheralManager(delegate:") == 1
    assert "CBCentralManager" not in route
    assert "CBAdvertisementDataLocalNameKey" not in route
    assert "CBAdvertisementDataManufacturerDataKey" not in route
    assert "startAdvertising([" in route
    assert "CBAdvertisementDataServiceUUIDsKey" in route
    advertisement = route.split("private func startUUIDOnlyAdvertisement", 1)[1].split(
        "private func bindRestoredService", 1
    )[0]
    assert "CBAdvertisementDataServiceUUIDsKey: [HelperPeripheralRoute.serviceUUID]" in advertisement
    assert "HelperCentralRoute.serviceUUID" not in advertisement
    assert "CBAdvertisementDataLocalNameKey" not in advertisement
    assert "CBAdvertisementDataManufacturerDataKey" not in advertisement
    assert "readEncryptionRequired" in route
    assert "writeEncryptionRequired" in route
    assert "properties: [.write, .indicate]" in route
    assert "control.properties == [.write, .indicate]" in route
    assert "properties: [.write, .notify]" not in route
    assert "CBPeripheralManagerOptionRestoreIdentifierKey" in route
    assert "CBPeripheralManagerRestoredStateServicesKey" in route
    assert '"ru.natro.kx11ancshelper.peripheral.stable"' in route
    assert '"KX11ANCSHelper.v2.installationUUID"' in route
    assert ".v50.installationUUID" not in route
    assert "installationDefaultsKey" in route
    assert "encodePeerProof" in route
    assert "mode: .androidCentral" in route
    assert "didBecomeTerminal" in route
    assert "didObserveLocalOwnerCount" in route
    assert "didBecomeOperational" in route
    assert "routeALocalOperational" in owner
    assert "self.manager == nil ? 0 : 1" in route
    assert "controlSubscriberID: UUID?" in route
    assert "expectedControlUnsubscribeID: UUID?" in route
    assert "exactControlUnsubscribeObserved" in route
    assert "telemetry subscription arrived without its control owner" in route
    assert "multiple v2 control subscribers violate single owner" in route
    assert "finishStopIfExactBoundaryReached" in route
    assert "beginLiveZeroControlObservation" in route
    assert "restorationObservationGrace" in route
    assert "didFreezeWithoutRemoteOwner" in route
    assert "didLoseExactLink" in route
    assert "releaseUnexpectedOwner" in route
    assert "didEncounterUnprovableMigration" in route
    assert "legacyF04ServiceUUID" in route
    stop_body = route.split("public func stop(", 1)[1].split(
        "public func forceCloseRestorationNamespace", 1
    )[0]
    assert "releaseAllOwners" not in stop_body
    assert "expectedControlUnsubscribeID = controlID" in stop_body
    assert "stop before exact ingress-frozen barrier" in route
    assert "didReceiveControl" in route
    assert "didAcceptOutboundControl" in route
    assert "decodeControl" in route
    assert "frame.type == .telemetryRefresh" in route
    assert "frame.mode == .androidCentral" in route
    assert 'preconditionFailure("R is inbound-only on Route A")' in route
    assert "sendRoleClose" in route
    assert "sendRoleCloseAck" in route
    # Plain C4 must never return an ATT authentication/authorization/encryption error: Android 9
    # may interpret one as permission to start SMP before the two SAS confirmations. The
    # enrollment handler deliberately uses requestNotSupported for fail-closed, non-auth rejects.
    c4_handlers = route.split("private func handleEnrollmentWrite", 1)[1].split(
        "private func clearEnrollmentSession", 1
    )[0]
    assert ".requestNotSupported" in c4_handlers
    assert ".insufficientAuthentication" not in c4_handlers
    assert ".insufficientAuthorization" not in c4_handlers
    assert ".insufficientEncryption" not in c4_handlers
    assert ".insufficientEncryptionKeySize" not in c4_handlers
    assert "stop attempted before outbound C/A acceptance" in route
    assert "case .advertising(let issuedGeneration) = lifecycle" in route
    assert "a delayed start completion cannot reopen frozen ingress" in route
    advertising_callback = route.split(
        "public func peripheralManagerDidStartAdvertising", 1
    )[1].split("public func peripheralManager(\n        _ peripheral: CBPeripheralManager,\n        didReceiveRead", 1)[0]
    assert "emitOperationalOnce" in advertising_callback

    # Route construction is capability-gated and held only by the top-level owner.
    assert "public struct RouteOwnerToken" in owner
    assert "fileprivate init()" in owner
    assert "private let routeA: HelperPeripheralRoute.Coordinator" in owner
    assert "ownerToken: HelperBleRuntimeCoordinator.RouteOwnerToken" in route

    # The new source is coordinator-only, not a copy of the legacy UI/runtime monolith.
    assert "UIViewController" not in route + owner
    assert "ViewController" not in route + owner
    assert "iPhone_ANCS" not in route + owner
    assert "Geely_ANCS" not in route + owner

    verify_stop_boundary_replays()
    print(
        "PASS: Route A is one CBPeripheralManager owner, UUID-only, encrypted, restored, "
        "generation-gated, local-operational/peer-ready split, exact-unsubscribe drained, "
        "migration-classified, top-level-owned, strict frozen-owner C/A, and active-owner R adapted"
    )


if __name__ == "__main__":
    main()
