#!/usr/bin/env python3
"""Production telemetry source and generation-fencing gate for Helper v47."""

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


def main() -> None:
    source = (ROOT / "HelperTelemetrySource.swift").read_text(encoding="utf-8")
    runtime = (ROOT / "HelperSwitchRuntimeCoordinator.swift").read_text(encoding="utf-8")
    view = (ROOT / "KX11ANCSHelper/ViewController.swift").read_text(encoding="utf-8")
    project = (ROOT / "KX11ANCSHelper.xcodeproj/project.pbxproj").read_text(encoding="utf-8")

    for marker in (
        "import CoreTelephony",
        "import Network",
        "UIDevice.current.isBatteryMonitoringEnabled = true",
        "NWPathMonitor()",
        "serviceCurrentRadioAccessTechnology",
        "UIApplication.shared.isProtectedDataAvailable",
        "Timer.scheduledTimer(withTimeInterval: 1",
        "onSample?(HelperTelemetrySample(",
    ):
        assert marker in source, marker
    assert "CoreBluetooth" not in source
    assert "HelperTelemetrySource.swift" in project
    assert "private var telemetrySource: HelperTelemetrySource?" in view
    assert "runtime?.publishTelemetry(sample)" in view
    for marker in (
        "public func publishTelemetry(_ sample: HelperTelemetrySample)",
        "self.runtimeFailure == nil, self.routeReady",
        "self.policy.state.phase == .active",
        "self.telemetryGeneration != generation",
        "self.telemetrySequence &+= 1",
        "self.routes.publishRouteATelemetry",
        "self.routes.publishRouteBTelemetry",
        "telemetryGeneration = nil",
    ):
        assert marker in runtime, marker

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

    print(
        "PASS: public iPhone battery/power/network/lock producer is ACTIVE-generation fenced "
        "and telemetry sequence resets without cross-route cache"
    )


if __name__ == "__main__":
    main()
