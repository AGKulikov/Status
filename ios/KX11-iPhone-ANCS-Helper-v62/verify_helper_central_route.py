#!/usr/bin/env python3
"""Source and replay gates for clean Route-B Helper Central coordinator."""

from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).parent


@dataclass
class ClientCloseModel:
    phase: str = "active"
    telemetry_in_flight: bool = False
    frozen_callback: bool = False
    control_in_flight: bool = False
    stop_requested: bool = False
    cancel_issued: bool = False
    terminal: bool = False
    owners: int = 1

    def freeze(self) -> None:
        assert self.phase == "active"
        self.phase = "freezing"
        self.frozen_callback = not self.telemetry_in_flight

    def telemetry_written(self) -> None:
        assert self.telemetry_in_flight
        self.telemetry_in_flight = False
        if self.phase == "freezing":
            self.frozen_callback = True

    def send_control(self) -> None:
        assert self.phase == "freezing" and self.frozen_callback
        self.control_in_flight = True

    def stop(self) -> None:
        assert self.phase == "freezing"
        self.phase = "stopping"
        if self.control_in_flight:
            self.stop_requested = True
        else:
            self.cancel_issued = True

    def control_written(self) -> None:
        assert self.control_in_flight
        self.control_in_flight = False
        if self.stop_requested:
            self.cancel_issued = True

    def stale_disconnect(self) -> None:
        # A callback from any other wrapper/generation is an exact no-op.
        pass

    def exact_disconnect(self) -> None:
        assert self.phase == "stopping" and self.cancel_issued
        self.owners = 0
        self.terminal = True


def verify_client_first_close_replays() -> None:
    direct = ClientCloseModel()
    direct.freeze()
    assert direct.frozen_callback
    direct.stop()
    assert direct.cancel_issued and not direct.terminal
    direct.stale_disconnect()
    assert not direct.terminal and direct.owners == 1
    direct.exact_disconnect()
    assert direct.terminal and direct.owners == 0

    queued = ClientCloseModel(telemetry_in_flight=True)
    queued.freeze()
    assert not queued.frozen_callback
    queued.telemetry_written()
    queued.send_control()
    queued.stop()
    assert queued.stop_requested and not queued.cancel_issued
    queued.control_written()
    assert queued.cancel_issued and not queued.terminal
    queued.exact_disconnect()
    assert queued.terminal and queued.owners == 0


def main() -> None:
    route = (ROOT / "HelperCentralRoute.swift").read_text(encoding="utf-8")
    owner = (ROOT / "HelperBleRuntimeCoordinator.swift").read_text(encoding="utf-8")

    assert route.count("CBCentralManager(delegate:") == 1
    assert "CBPeripheralManager" not in route
    assert "CBMutableService" not in route
    assert "CBAdvertisementDataLocalNameKey" not in route
    assert "F05" not in route
    assert "D2D9E4C0-47F1-4E44-A8BB-A932FD5AF202" in route
    assert '"ru.natro.kx11ancshelper.central.stable"' in route
    assert "scanForPeripherals(" in route
    assert "withServices: [HelperCentralRoute.serviceUUID]" in route
    assert "CBConnectPeripheralOptionRequiresANCS: true" in route
    assert "mode: .androidPeripheral" in route
    assert "telemetrySupported: true" in route
    assert "ancsSupported: true" in route
    assert "frame.mode == .androidPeripheral" in route
    assert "frame.type == .telemetryRefresh" in route
    assert 'preconditionFailure("R is inbound-only on Route B")' in route
    assert "control.properties.contains(.indicate)" in route
    assert "let controlCharacteristic else" in route
    assert "setNotifyValue(true, for: controlCharacteristic)" in route
    assert "writeValue(pending.data, for: characteristic, type: .withResponse)" in route
    assert "writeValue(hello, for: characteristic, type: .withResponse)" in route
    assert "targetMode == .androidCentral" in route
    assert "cancelPeripheralConnection(peripheral)" in route
    assert "didDisconnectPeripheral candidate" in route
    assert "releaseAfterExactDisconnect" in route
    assert "didBecomeOperational" in route
    assert "routeBLocalOperational" in owner
    assert "A pre-auth offline freeze already canceled this candidate" in route
    assert "case .stopping(let epoch, let stoppingGeneration) = lifecycle" in route
    assert "case .hello = completed.kind" in route
    assert "didObserveLocalOwnerCount: self.manager == nil ? 0 : 1" in route
    assert "AncsConsumer" not in route
    assert "NotificationAccumulator" not in route
    assert "advertisementNonce" not in route
    assert "localName" not in route

    # A connected Route-B wrapper remains a real owner until its exact disconnect/release. Store
    # route-local failure first; emit it only from the post-release path.
    assert "pendingFailureReason" in route
    assert "emitPendingFailureAfterOwnerRelease" in route
    freeze = route[route.index("public func freezeIngress"):
                   route.index("public func sendRoleClose")]
    assert "peripheral.state == .disconnecting" in freeze
    assert "if peripheral.state != .disconnecting" in freeze
    restoration = route[route.index("willRestoreState dict"):
                        route.index("didDiscover candidate")]
    assert restoration.count(".state == .disconnecting") >= 2
    assert "didFreezeWithoutRemoteOwner" in restoration
    assert "restored selected Route-B wrapper was already disconnecting" in restoration
    failure = route.split("private func failAndDrain", 1)[1].split(
        "private func releaseUnconnectedOwner", 1
    )[0]
    assert "pendingFailureReason" in failure
    assert "didFail" not in failure
    release = route.split("private func releaseUnconnectedOwner", 1)[1].split(
        "private func releaseAfterExactDisconnect", 1
    )[0]
    assert "emitPendingFailureAfterOwnerRelease" in release

    assert "private let routeB: HelperCentralRoute.Coordinator" in owner
    assert "ownerToken: HelperBleRuntimeCoordinator.RouteOwnerToken" in route
    assert "installationID: routeA.installationID" in owner
    assert "UIViewController" not in route

    verify_client_first_close_replays()
    print(
        "PASS: Route B is one RequiresANCS CBCentralManager owner, mutual-H authenticated, "
        "indication-controlled, local-operational/peer-ready split, ATT-with-response serialized, "
        "R refresh, and failure emitted only after exact owner release"
    )


if __name__ == "__main__":
    main()
