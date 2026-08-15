#!/usr/bin/env python3
"""Event-driven v52 telemetry refresh and generation-fencing gate."""

from pathlib import Path


ROOT = Path(__file__).resolve().parent


class SequenceGate:
    def __init__(self) -> None:
        self.generation: int | None = None
        self.sequence = 0
        self.ready = False

    def activate(self, generation: int) -> None:
        self.generation = generation
        self.sequence = 0
        self.ready = True

    def freeze(self) -> None:
        self.ready = False
        self.generation = None
        self.sequence = 0

    def sample(self, generation: int) -> int | None:
        if not self.ready or self.generation != generation:
            return None
        value = self.sequence
        self.sequence = (self.sequence + 1) & 0xFFFF
        return value


class RefreshGate:
    """Portable model of one outstanding main-thread source callback."""

    def __init__(self) -> None:
        self.pending = False
        self.requests = 0

    def request(self) -> None:
        if self.pending:
            return
        self.pending = True
        self.requests += 1

    def sample(self) -> None:
        self.pending = False

    def freeze(self) -> None:
        self.pending = False


def main() -> None:
    source = (ROOT / "HelperTelemetrySource.swift").read_text(encoding="utf-8")
    runtime = (ROOT / "HelperSwitchRuntimeCoordinator.swift").read_text(encoding="utf-8")
    view = (ROOT / "KX11ANCSHelper/ViewController.swift").read_text(encoding="utf-8")
    project = (ROOT / "KX11ANCSHelper.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
    route_a = (ROOT / "HelperPeripheralRoute.swift").read_text(encoding="utf-8")
    route_b = (ROOT / "HelperCentralRoute.swift").read_text(encoding="utf-8")
    wire = (ROOT / "IphoneBleWireProtocolV2.swift").read_text(encoding="utf-8")

    for marker in (
        "import CoreTelephony",
        "import Network",
        "UIDevice.current.isBatteryMonitoringEnabled = true",
        "NWPathMonitor()",
        "serviceCurrentRadioAccessTechnology",
        "UIApplication.shared.isProtectedDataAvailable",
        "public func requestFreshSample()",
        "pathMonitor.currentPath",
        "onSample?(HelperTelemetrySample(",
    ):
        assert marker in source, marker
    assert "Timer.scheduledTimer(withTimeInterval: 1" not in source
    assert "private var timer: Timer?" not in source
    assert "telephony?.currentRadioAccessTechnology" not in source
    assert "CoreBluetooth" not in source
    assert "HelperTelemetrySource.swift" in project
    assert "private var telemetrySource: HelperTelemetrySource?" in view
    assert "runtime?.publishTelemetry(sample)" in view
    assert "runtime.onTelemetryRefreshRequest" in view
    assert "telemetry?.requestFreshSample()" in view
    for marker in (
        "public func publishTelemetry(_ sample: HelperTelemetrySample)",
        "self.runtimeFailure == nil, self.peerReady",
        "self.policy.state.phase == .active",
        "self.telemetryGeneration != generation",
        "self.telemetrySequence &+= 1",
        "self.routes.publishRouteATelemetry",
        "self.routes.publishRouteBTelemetry",
        "telemetryGeneration = nil",
        "private var telemetryRefreshPending = false",
        "private func requestFreshTelemetrySample()",
        "case .telemetryRefresh:",
        "frame.mode == Self.mode(for: source)",
    ):
        assert marker in runtime, marker
    assert "routeReady" not in runtime
    assert "case telemetryRefresh = 0x52" in wire
    assert "public static func encodeTelemetryRefresh" in wire

    # Route A indications always drain role C/A first. Route B has exactly one ATT-with-response
    # write in flight and similarly selects queued role control before the latest telemetry.
    route_a_drain = route_a[route_a.index("private func drainNotifications"):
                            route_a.index("private func fail(")]
    assert route_a_drain.index("pendingControlNotification") < route_a_drain.index(
        "pendingTelemetry"
    )
    route_b_pump = route_b[route_b.index("private func pumpWriteQueue"):
                           route_b.index("private func emitFrozen")]
    assert "guard inFlightWrite == nil" in route_b_pump
    assert route_b_pump.index("queuedControlWrite") < route_b_pump.index("pendingTelemetry")
    assert "frame.type == .telemetryRefresh" in route_a
    assert "frame.mode == .androidCentral" in route_a
    assert "frame.type == .telemetryRefresh" in route_b
    assert "frame.mode == .androidPeripheral" in route_b

    gate = SequenceGate()
    gate.activate(10)
    assert [gate.sample(10), gate.sample(10)] == [0, 1]
    assert gate.sample(9) is None
    gate.freeze()
    assert gate.sample(10) is None
    gate.activate(11)
    assert gate.sample(11) == 0
    gate.sequence = 0xFFFF
    assert [gate.sample(11), gate.sample(11)] == [0xFFFF, 0]

    refresh = RefreshGate()
    refresh.request()
    refresh.request()
    assert refresh.requests == 1 and refresh.pending
    refresh.sample()
    refresh.request()
    assert refresh.requests == 2
    refresh.freeze()
    assert not refresh.pending

    print(
        "PASS: v52 event-driven telemetry, authenticated one-shot R refresh, control-first queues, "
        "single ATT write and generation fencing"
    )


if __name__ == "__main__":
    main()
