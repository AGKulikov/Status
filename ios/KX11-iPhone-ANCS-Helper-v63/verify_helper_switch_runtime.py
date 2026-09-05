#!/usr/bin/env python3
"""Static app gate plus cross-layer callback-inversion replays for Helper v63."""

from __future__ import annotations

import importlib.util
import json
from dataclasses import dataclass, field
from pathlib import Path
import plistlib
import re
import sys
import tempfile

sys.dont_write_bytecode = True


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def read(name: str) -> str:
    return (ROOT / name).read_text(encoding="utf-8")


def load_policy_model():
    path = ROOT / "verify_role_switch_policy.py"
    spec = importlib.util.spec_from_file_location("v54_switch_model", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass
class TargetStartupWatchdogModel:
    """Deterministic model of the v54 local-start/peer-ready boundary."""

    target_generation: int
    watchdog_token: str
    deadline_ms: int
    phase: str = "starting"
    local_operational: bool = False
    peer_ready: bool = False
    failure: str | None = None
    forwarded_telemetry: list[str] = field(default_factory=list)

    def on_watchdog(self, token: str, generation: int, now_ms: int) -> str:
        if (
            self.phase != "starting"
            or token != self.watchdog_token
            or generation != self.target_generation
        ):
            return "stale"
        if now_ms < self.deadline_ms:
            return "notDue"
        self.phase = "failed"
        self.failure = "targetStartFailed"
        self.watchdog_token = ""
        self.peer_ready = False
        return "failed"

    def on_local_operational(self, generation: int) -> str:
        if self.phase != "starting" or generation != self.target_generation:
            return "stale"
        self.phase = "active"
        self.local_operational = True
        self.watchdog_token = ""
        return "active"

    def on_exact_peer_ready(self, generation: int, exact_owner: bool) -> str:
        if (
            self.phase != "active"
            or not self.local_operational
            or not exact_owner
            or generation != self.target_generation
        ):
            return "stale"
        self.peer_ready = True
        return "ready"

    def publish_telemetry(self, generation: int, value: str) -> bool:
        if (
            self.phase != "active"
            or not self.local_operational
            or not self.peer_ready
            or generation != self.target_generation
        ):
            return False
        self.forwarded_telemetry.append(value)
        return True


@dataclass
class RouteBFailureReleaseModel:
    """A Route-B failure is observable only after its exact local wrapper is gone."""

    generation: int
    connected: bool = True
    owner_count: int = 1
    pending_failure: str | None = None
    events: list[str] = field(default_factory=list)

    def fail(self, reason: str, generation: int) -> str:
        if generation != self.generation or self.owner_count == 0:
            return "stale"
        self.pending_failure = reason
        if self.connected:
            self.events.append("cancelRequested")
        else:
            self.release(generation=generation, exact_owner=True)
        return "pendingRelease"

    def release(self, generation: int, exact_owner: bool) -> str:
        if generation != self.generation or not exact_owner or self.owner_count == 0:
            return "stale"
        self.connected = False
        self.owner_count = 0
        self.events.append("ownerReleased")
        if self.pending_failure is not None:
            self.events.append(f"routeFailed:{self.pending_failure}")
            self.pending_failure = None
        return "released"


@dataclass
class ProductionRouteModeModel:
    """Portable replay of the production-only Route-A selection boundary."""

    active_role: str = "A"
    generation: int = 1
    diagnostic_route_b: bool = False
    phase: str = "active"
    effects: list[str] = field(default_factory=list)

    def select(self, role: str) -> str:
        if role == "B" and not self.diagnostic_route_b:
            return "diagnosticOnly"
        if role == self.active_role:
            return "coalesced"
        self.phase = "freezing"
        self.effects.append(f"drain:{self.active_role}->target:{role}")
        self.generation += 1
        return "switching"

    def on_local_operational(self) -> str:
        if not self.diagnostic_route_b and self.active_role == "B" and self.phase == "active":
            return self.select("A")
        return "steady"

    def on_live_wait_ancs(self, exact_control_owner: bool) -> str:
        if self.active_role == "A" and self.phase == "active" and exact_control_owner:
            return "keepOwnerWaitServiceChanged"
        return "notApplicable"

    def on_exact_disconnect(self) -> str:
        assert self.phase == "active"
        self.phase = "freezing"
        self.generation += 1
        self.effects.append("sameRoleFullDrain")
        return "recovering"


def verify_app_and_single_owner() -> None:
    runtime = read("HelperSwitchRuntimeCoordinator.swift")
    owner = read("HelperBleRuntimeCoordinator.swift")
    route_a = read("HelperPeripheralRoute.swift")
    route_b = read("HelperCentralRoute.swift")
    view = read("KX11ANCSHelper/ViewController.swift")
    app_delegate = read("KX11ANCSHelper/AppDelegate.swift")
    scene_delegate = read("KX11ANCSHelper/SceneDelegate.swift")
    project = read("KX11ANCSHelper.xcodeproj/project.pbxproj")
    readme = read("README.md")
    release = read("RELEASE.txt")

    assert ROOT.name == "KX11-iPhone-ANCS-Helper-v63"
    assert "KX11 ANCS Helper v63" in readme
    assert "KX11 ANCS Helper v63" in release

    for filename in (
        "BleRoleSwitchPolicy.swift",
        "IphoneBleWireProtocolV2.swift",
        "HelperEnrollmentV1.swift",
        "HelperPeripheralRoute.swift",
        "HelperCentralRoute.swift",
        "HelperBleRuntimeCoordinator.swift",
        "HelperSwitchRuntimeCoordinator.swift",
        "HelperTelemetrySource.swift",
        "AppDelegate.swift",
        "SceneDelegate.swift",
        "ViewController.swift",
        "CarControlUI.swift",
        "ANCSConnectionJournal.swift",
        "CarRemoteProtocolV1.swift",
        "NatroLiveActivityShared.swift",
        "NatroLiveActivityManager.swift",
        "LiveActivitySettingsViewController.swift",
        "NatroShortcuts.swift",
        "NatroLiveActivityWidget.swift",
    ):
        assert filename in project, f"Xcode project omits {filename}"

    assert "HelperSwitchRuntimeCoordinator()" in view
    assert "CBPeripheralManager" not in view and "CBCentralManager" not in view
    assert "private let routes: HelperBleRuntimeCoordinator" in runtime
    assert "private let routeA: HelperPeripheralRoute.Coordinator" in owner
    assert "private let routeB: HelperCentralRoute.Coordinator" in owner
    assert runtime.count("CBPeripheralManager(") == 0
    assert runtime.count("CBCentralManager(") == 0
    assert route_a.count("CBPeripheralManager(delegate:") == 1
    assert route_b.count("CBCentralManager(delegate:") == 1
    assert "let rootViewController = ViewController()" in app_delegate
    assert "_ = rootViewController.view" in app_delegate
    assert "UIWindow(frame: UIScreen.main.bounds)" not in app_delegate
    assert "UIWindow(windowScene: windowScene)" in scene_delegate
    assert "appDelegate.rootViewController" in scene_delegate
    assert "#if targetEnvironment(simulator)" in view
    assert "renderSimulatorUnavailable()" in view
    assert "Simulator intentionally does not start a BLE owner" in view

    simulator_gate = view.index("#if targetEnvironment(simulator)")
    runtime_construction = view.index("HelperSwitchRuntimeCoordinator()")
    assert simulator_gate < runtime_construction

    # Durable BRS2 is written before the candidate reducer becomes live/effects dispatch.
    persist_at = runtime.index("try Self.persist(\n                policy: candidate")
    assign_at = runtime.index("policy = candidate", persist_at)
    dispatch_at = runtime.index("for effect in reduction.effects", assign_at)
    assert persist_at < assign_at < dispatch_at
    for token in (
        'static let schemaValue = "BRS2"',
        "restoreDrainFromRemoteIntent",
        "restoreDrainLocalOnly",
        "targetMayExist",
        "deferredRestorationFreeze",
        "case .routeBFrozenWithoutRemoteOwner",
        "controlAttempt",
        "attempt: effect.controlAttempt",
        "onIngressFrozenWithoutRemoteOwner",
        'legacyRoleKey = "KX11ANCSHelper.bleRole.v12"',
        "case legacyMigration",
        "drainLegacyMigration",
        "startClosedRestorationDrain",
        "forceCloseRestorationNamespaces",
        "durableAtomicWrite",
        "try handle.synchronize()",
        "Darwin.rename",
        "Darwin.fsync",
        "quarantinedCorruption",
        "recoverAttachedActiveOwner",
        "case .routeALostExactLink",
        "let current = (policy.state.activeRole == role",
    ):
        assert token in runtime, f"runtime misses {token}"
    assert "self.routeReady, self.policy.state.phase == .active" not in runtime
    assert "let now = Self.nowMs()\n        let delay = deadline > now ? deadline - now : 0" in runtime
    assert runtime.count("deadline - Self.nowMs()") == 0
    assert "reread == encoded" in runtime and "policy = candidate" in runtime

    with (ROOT / "KX11ANCSHelper/Info.plist").open("rb") as handle:
        plist = plistlib.load(handle)
    assert set(plist["UIBackgroundModes"]) == {"bluetooth-central", "bluetooth-peripheral"}
    assert "NSBluetoothAlwaysUsageDescription" in plist
    scene_manifest = plist["UIApplicationSceneManifest"]
    assert scene_manifest["UIApplicationSupportsMultipleScenes"] is False
    scene_configs = scene_manifest["UISceneConfigurations"]["UIWindowSceneSessionRoleApplication"]
    assert scene_configs[0]["UISceneDelegateClassName"] == "$(PRODUCT_MODULE_NAME).SceneDelegate"
    assert "PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;" in project
    assert "CURRENT_PROJECT_VERSION = 63;" in project
    assert "MARKETING_VERSION = 63.0;" in project
    for marker in (
        "candidate, not a claim that reconnects are already field-stable",
        "does **not** delete the system",
        "Route B (iPhone Central / Android Peripheral",
        "waits for the GATT Service Changed indication",
        "does **not** delete the system",
        "If the user force-quits Helper",
        "pairs with the Natro C4-enrollment runtime",
    ):
        assert marker in readme, f"README omits truthful release marker: {marker}"
    for marker in (
        "Build: 63",
        "Marketing version: 63.0",
        "Status: source candidate; Apple-SDK CI and physical-device checks are release gates",
        "Do not call this candidate stable",
    ):
        assert marker in release, f"RELEASE omits truthful release marker: {marker}"

    expected_files = {
        "BleRoleSwitchPolicy.swift",
        "HelperBleRuntimeCoordinator.swift",
        "HelperCentralRoute.swift",
        "HelperEnrollmentV1.swift",
        "HelperPeripheralRoute.swift",
        "HelperSwitchRuntimeCoordinator.swift",
        "HelperTelemetrySource.swift",
        "IphoneBleWireProtocolV2.swift",
        "CarRemoteProtocolV1.swift",
        "NatroLiveActivityShared.swift",
        "enrollment_v1_vectors.json",
        "KX11ANCSHelper.xcodeproj/project.pbxproj",
        "KX11ANCSHelper.xcodeproj/xcshareddata/xcschemes/KX11ANCSHelper.xcscheme",
        "KX11ANCSHelper/AppDelegate.swift",
        "KX11ANCSHelper/SceneDelegate.swift",
        "KX11ANCSHelper/Info.plist",
        "KX11ANCSHelper/ViewController.swift",
        "KX11ANCSHelper/CarControlUI.swift",
        "KX11ANCSHelper/ANCSConnectionJournal.swift",
        "KX11ANCSHelper/Assets.xcassets/Contents.json",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/Contents.json",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-40.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-58.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-60.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-80.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-87.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-120.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-180.png",
        "KX11ANCSHelper/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png",
        "KX11ANCSHelper/KX11ANCSHelper.entitlements",
        "KX11ANCSHelper/LiveActivitySettingsViewController.swift",
        "KX11ANCSHelper/NatroLiveActivityManager.swift",
        "KX11ANCSHelper/NatroShortcuts.swift",
        "NatroLiveActivityExtension/Info.plist",
        "NatroLiveActivityExtension/Monjaro.png",
        "NatroLiveActivityExtension/NatroLiveActivityExtension.entitlements",
        "NatroLiveActivityExtension/NatroLiveActivityWidget.swift",
        "README.md",
        "RELEASE.txt",
        "verify-v63-contract.sh",
        "verify_enrollment_v1.py",
        "verify_helper_central_route.py",
        "verify_helper_peripheral_route.py",
        "verify_helper_switch_runtime.py",
        "verify_helper_telemetry.py",
        "verify_live_activity_v63.py",
        "verify_car_remote_v1.py",
        "verify_role_switch_policy.py",
        "verify_wire_protocol.py",
    }
    actual_files = {
        str(path.relative_to(ROOT))
        for path in ROOT.rglob("*")
        if path.is_file()
    }
    assert actual_files == expected_files, (
        f"missing={sorted(expected_files - actual_files)}",
        f"unexpected={sorted(actual_files - expected_files)}",
    )

    stale_identity = "v" + "49"
    for verifier in [*ROOT.glob("verify*.py"), *ROOT.glob("verify*.sh")]:
        assert stale_identity not in verifier.read_text(encoding="utf-8"), (
            f"v54 verifier retains stale predecessor identity: {verifier.name}"
        )


def verify_xpc_source_contracts() -> None:
    runtime = read("HelperSwitchRuntimeCoordinator.swift")
    owner = read("HelperBleRuntimeCoordinator.swift")
    route_b = read("HelperCentralRoute.swift")

    # The target-start deadline is independent of the old-route STOP and drain deadlines. A
    # CoreBluetooth/XPC manager that never produces a callback must become visible FAILED rather
    # than leaving the selector permanently locked in reducer=starting.
    for marker in (
        "targetStartTimeoutMs",
        "targetStartTimerToken",
        "armTargetStartWatchdog",
        "cancelTargetStartWatchdog",
        "policy.onTargetStartFailed",
    ):
        assert marker in runtime, f"target-start watchdog contract misses {marker}"
    start_target = runtime[runtime.index("case .startTarget"):
                           runtime.index("case .targetActive")]
    assert "armTargetStartWatchdog" in start_target
    target_active = runtime[runtime.index("case .targetActive"):
                            runtime.index("case .failClosed")]
    assert "cancelTargetStartWatchdog" in target_active
    watchdog = runtime[runtime.index("private func armTargetStartWatchdog"):
                       runtime.index("private func cancelTargetStartWatchdog")]
    for marker in (
        "self.targetStartTimerToken == token",
        "self.policy.state.phase == .starting",
        "self.policy.state.epoch == effect.epoch",
        "self.policy.state.targetGeneration == effect.generation",
        "self.policy.state.targetRole == effect.role",
    ):
        assert marker in watchdog, f"stale target-start watchdog fence misses {marker}"
    before_reduce = watchdog[:watchdog.index("self.reduce { policy in")]
    assert "self.targetStartTimerToken = nil" not in before_reduce

    # Local CoreBluetooth operation and authenticated Android peer readiness are deliberately
    # different events. Local operation completes reducer STARTING; only the exact peer event opens
    # telemetry. This keeps role selection usable while Android has not connected yet.
    for marker in ("routeALocalOperational", "routeBLocalOperational"):
        assert marker in owner, f"BLE owner misses local-operational event {marker}"
        assert f"case .{marker}" in runtime, f"switch owner does not consume {marker}"
    assert "private var peerReady = false" in runtime
    assert "routeReady" not in runtime
    publish = runtime[runtime.index("public func publishTelemetry"):
                      runtime.index("public func retryFailedSwitch")]
    assert "self.peerReady" in publish
    assert "self.policy.state.phase == .active" in publish
    operational_handler = runtime[runtime.index("private func handleRouteOperational"):
                                  runtime.index("private func recoverReleasedActiveOwner")]
    assert "policy.onTargetActive" in operational_handler
    # Do not cancel before the write-ahead reducer commit. The persisted targetActive effect owns
    # cancellation; a persistence failure must leave the watchdog able to close STARTING.
    before_reduce = operational_handler[:operational_handler.index("reduce { policy in")]
    assert "cancelTargetStartWatchdog" not in before_reduce
    assert "peerReady = true" not in operational_handler
    peer_handler = runtime[runtime.index("private func handleRouteReady"):
                           runtime.index("private func handleRouteOperational")]
    assert "peerReady = true" in peer_handler

    # Route B may ask CoreBluetooth to cancel a connected wrapper, but it cannot announce failure
    # until that exact wrapper/manager has been released. Otherwise the switch owner can synthesize
    # owner-zero and allocate the next generation beside the old XPC owner.
    assert "pendingFailureReason" in route_b
    assert "emitPendingFailureAfterOwnerRelease" in route_b
    failure = route_b[route_b.index("private func failAndDrain"):
                      route_b.index("private func releaseUnconnectedOwner")]
    assert "pendingFailureReason" in failure
    assert "didFail" not in failure
    assert "peripheral.state == .disconnecting" in failure
    release = route_b[route_b.index("private func releaseUnconnectedOwner"):
                      route_b.index("private func releaseAfterExactDisconnect")]
    assert "emitPendingFailureAfterOwnerRelease" in release
    emitter = route_b[route_b.index("private func emitPendingFailureAfterOwnerRelease"):]
    assert "didFail" in emitter

    # Unprovable restoration must fail the durable reducer, not only set an out-of-band UI error.
    # Otherwise late cleanup callbacks could advance FREEZING to a live target while retry remains
    # unusable. Both namespaces use ingressFreezeFailed under exact source ownership.
    route_a_unprovable = runtime[runtime.index("case .sourceUnprovableMigration"):
                                 runtime.index("case .routeBUnprovableRestoration")]
    route_b_unprovable = runtime[runtime.index("case .routeBUnprovableRestoration"):
                                 runtime.index("case .sourceControlReceived")]
    for block in (route_a_unprovable, route_b_unprovable):
        assert "policy.state.phase == .freezing" in block
        assert "policy.onIngressFreezeFailed" in block


def verify_production_route_a_policy() -> None:
    runtime = read("HelperSwitchRuntimeCoordinator.swift")
    route_a = read("HelperPeripheralRoute.swift")
    view = read("KX11ANCSHelper/ViewController.swift")

    for marker in (
        "case productionRouteA",
        "case diagnosticDualRoute",
        'experimentalRouteBKey = "KX11ANCSHelper.experimentalRouteB"',
        "defaults.bool(forKey: Self.experimentalRouteBKey)",
        "enforceProductionRouteAIfNeeded",
        "role == .helperPeripheralAndroidCentral",
        "policy.state.activeRole == .helperCentralAndroidPeripheral",
        "to: .helperPeripheralAndroidCentral",
        "waits for ANCS/Service Changed",
        "Remote Route-B intent отклонён production-политикой",
    ):
        assert marker in runtime, f"production Route-A contract misses {marker}"
    assert runtime.count("enforceProductionRouteAIfNeeded()") >= 4
    assert "snapshot.routeBDiagnosticsEnabled" in view
    assert "roleControl.isHidden = !snapshot.routeBDiagnosticsEnabled" in view
    assert "forSegmentAt: 1" in view

    advertisement = route_a[route_a.index("private func startUUIDOnlyAdvertisement"):
                            route_a.index("private func bindRestoredService")]
    assert "CBAdvertisementDataServiceUUIDsKey: [HelperPeripheralRoute.serviceUUID]" in advertisement
    assert "HelperCentralRoute.serviceUUID" not in advertisement
    assert "CBAdvertisementDataLocalNameKey" not in advertisement
    assert "CBAdvertisementDataManufacturerDataKey" not in advertisement

    production = ProductionRouteModeModel(active_role="A")
    assert production.select("B") == "diagnosticOnly"
    assert production.phase == "active" and production.effects == []
    assert production.on_live_wait_ancs(exact_control_owner=True) == (
        "keepOwnerWaitServiceChanged"
    )
    assert production.effects == []
    assert production.on_exact_disconnect() == "recovering"
    assert production.generation == 2
    assert production.effects == ["sameRoleFullDrain"]

    migrated = ProductionRouteModeModel(active_role="B", generation=8)
    assert migrated.on_local_operational() == "switching"
    assert migrated.phase == "freezing" and migrated.generation == 9
    assert migrated.effects == ["drain:B->target:A"]

    diagnostic = ProductionRouteModeModel(active_role="A", diagnostic_route_b=True)
    assert diagnostic.select("B") == "switching"
    assert diagnostic.effects == ["drain:A->target:B"]


def verify_xpc_recovery_replays() -> None:
    # Executable no-callback deadline and stale-token/generation replays.
    hung = TargetStartupWatchdogModel(
        target_generation=8,
        watchdog_token="start-8",
        deadline_ms=5_000,
    )
    assert hung.on_watchdog("start-8", 8, 4_999) == "notDue"
    assert hung.phase == "starting" and hung.failure is None
    assert hung.on_watchdog("start-8", 8, 5_000) == "failed"
    assert hung.phase == "failed" and hung.failure == "targetStartFailed"
    assert not hung.publish_telemetry(8, "must-not-escape")

    operational = TargetStartupWatchdogModel(
        target_generation=9,
        watchdog_token="start-9",
        deadline_ms=10_000,
    )
    assert operational.on_exact_peer_ready(9, exact_owner=True) == "stale"
    assert operational.on_local_operational(9) == "active"
    assert operational.phase == "active" and not operational.peer_ready
    assert operational.on_watchdog("start-9", 9, 10_000) == "stale"
    assert not operational.publish_telemetry(9, "before-peer")
    assert operational.on_exact_peer_ready(8, exact_owner=True) == "stale"
    assert operational.on_exact_peer_ready(9, exact_owner=False) == "stale"
    assert operational.on_exact_peer_ready(9, exact_owner=True) == "ready"
    assert operational.publish_telemetry(9, "after-peer")
    assert operational.forwarded_telemetry == ["after-peer"]

    replacement = TargetStartupWatchdogModel(
        target_generation=10,
        watchdog_token="start-10",
        deadline_ms=20_000,
    )
    assert replacement.on_watchdog("start-9", 9, 99_000) == "stale"
    assert replacement.phase == "starting"
    assert replacement.on_watchdog("start-10", 9, 99_000) == "stale"
    assert replacement.on_watchdog("start-10", 10, 20_000) == "failed"

    # Route-B connected failure stays pending across stale callbacks and is emitted exactly once,
    # after exact local owner release. An already-unconnected failure observes the same ordering.
    connected = RouteBFailureReleaseModel(generation=12)
    assert connected.fail("XPC connection invalid", generation=12) == "pendingRelease"
    assert connected.owner_count == 1
    assert connected.events == ["cancelRequested"]
    assert connected.release(generation=11, exact_owner=True) == "stale"
    assert connected.release(generation=12, exact_owner=False) == "stale"
    assert connected.events == ["cancelRequested"]
    assert connected.release(generation=12, exact_owner=True) == "released"
    assert connected.owner_count == 0
    assert connected.events == [
        "cancelRequested",
        "ownerReleased",
        "routeFailed:XPC connection invalid",
    ]
    assert connected.release(generation=12, exact_owner=True) == "stale"

    unconnected = RouteBFailureReleaseModel(generation=13, connected=False)
    assert unconnected.fail("manager callback timeout", generation=13) == "pendingRelease"
    assert unconnected.events == [
        "ownerReleased",
        "routeFailed:manager callback timeout",
    ]


def verify_route_p0_guards() -> None:
    route_a = read("HelperPeripheralRoute.swift")
    route_b = read("HelperCentralRoute.swift")

    # CONTROL is an encrypted write+indicate path; notify is telemetry-only.
    assert "properties: [.write, .indicate]" in route_a
    assert "control.properties == [.write, .indicate]" in route_a
    assert "control.properties.contains(.indicate)" in route_b
    assert "control.properties.contains(.notify)" not in route_b

    # Late subscriptions cannot reopen the source after FREEZE.
    assert "A late/duplicate CCCD callback cannot reopen ingress" in route_a
    delayed_gate = route_a.index("A late/duplicate CCCD callback cannot reopen ingress")
    active_write = route_a.index("lifecycle = .active(generation: generation)", delayed_gate)
    assert delayed_gate < active_write

    # Client-first callback inversion is retained until STOP and telemetry owner zero. It must not
    # route the established freezing case through the 250ms recovery observation.
    freezing_case = re.search(
        r"case \.freezing\(let epoch, let frozenGeneration\).*?"
        r"earlyControlUnsubscribeEpoch = epoch",
        route_a,
        re.S,
    )
    assert freezing_case, "freezing unsubscribe is not retained"
    assert "earlyControlUnsubscribeEpoch == epoch" in route_a
    assert "finishStopIfExactBoundaryReached()" in route_a
    assert "absolute stop deadline" in route_a

    # Exact duplicate C can cause only same-token A while the old stopping owner still exists.
    for source in (route_a, route_b):
        assert "stopping" in source
        assert "only exact idempotent A" in source
        assert "type == .roleCloseAck" in source

    # Drain-only restoration never scans for a replacement and all incompatible restored owners
    # are canceled/fenced instead of claiming zero after closing only the first wrapper.
    assert "case drainInactiveRoute" in route_b
    assert "if restorationDrainEpoch != nil" in route_b
    assert "scheduleEmptyRestorationObservation" in route_b
    assert "unprovableRestoredOwners" in route_b
    assert "for candidate in restored where" in route_b
    assert "central.cancelPeripheralConnection(candidate)" in route_b
    assert "didFreezeWithoutRemoteOwner" in route_b
    assert "didFreezeWithoutRemoteOwner: epoch" in route_a
    assert "didFreezeWithoutRemoteOwner: epoch" in route_b
    assert "forceCloseRestorationNamespace" in route_a
    assert "forceCloseRestorationNamespace" in route_b


def verify_persistence_and_upgrade_replays() -> None:
    runtime = read("HelperSwitchRuntimeCoordinator.swift")

    # The authoritative pathname ignores an unrenamed/torn temp file. A corrupt authoritative
    # record is quarantined to CLOSED; it can never fall through to the fresh Route-A default.
    def classify(path: Path) -> str:
        if not path.exists():
            return "missing"
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return "closed"
        return "valid" if record.get("schema") == "BRS2" else "closed"

    valid_old = {"schema": "BRS2", "reducerPhase": "active"}
    valid_new = {"schema": "BRS2", "reducerPhase": "freezing"}
    with tempfile.TemporaryDirectory() as raw:
        directory = Path(raw)
        destination = directory / "role-switch-brs2.json"
        temporary = directory / ".brs2-crash.tmp"
        destination.write_text(json.dumps(valid_old), encoding="utf-8")
        temporary.write_bytes(b'{"schema":"BRS2"')
        assert classify(destination) == "valid"  # crash before rename: old is drained on restore
        destination.write_text(json.dumps(valid_new), encoding="utf-8")
        assert classify(destination) == "valid"  # crash after rename: committed token/origin wins
        destination.write_bytes(b'{"schema":"BRS')
        assert classify(destination) == "closed"
        destination.write_text(json.dumps({"schema": "BRS1"}), encoding="utf-8")
        assert classify(destination) == "closed"

    # A legacy role preference or terminal Helper-56 snapshot is repaired to production Route A
    # before start. The new process owns no live manager, and Route A still requires exact CONTROL
    # proof, so first launch no longer freezes an owner that never existed.
    assert "case 0:\n            return .helperPeripheralAndroidCentral" in runtime
    assert "case 1:\n            return .helperCentralAndroidPeripheral" in runtime
    assert "persisted.reducerPhase == .failed || persisted.reducerPhase == .closed" in runtime
    assert "legacy-настройка перенесена в основной Route A" in runtime
    persist = runtime.index("bootDetail = \"Helper 63: legacy-настройка")
    constructor_end = runtime.index("routes.onEvent", persist)
    assert persist < constructor_end
    assert "throw PersistenceError.invalidLegacyRole" in runtime

    # The visible FAILED retry is an actual write-ahead transition. It allocates a fresh epoch
    # and generation, reclaims the attributable namespace, and refuses to guess contradictory
    # ownership. Route-local terminal exhaustion is escalated to the same top-level drain.
    retry = runtime[runtime.index("public func retryFailedSwitch()"):
                    runtime.index("private func handle(", runtime.index("public func retryFailedSwitch()"))]
    ambiguous = retry.index("case .contradictoryRemoteEvidence")
    retry_transition = retry.index("let nextEpoch = failed.epoch.next()")
    clear_failure = retry.index("self.runtimeFailure = nil")
    assert ambiguous < retry_transition < clear_failure
    assert "let nextGeneration = maximumGeneration.next()" in retry
    assert "policy.restoreDrainFromRemoteIntent" in retry
    assert "policy.restoreDrainLocalOnly" in retry
    assert "policy.restoreDrain(" in retry
    assert "Закройте и снова откройте Helper" not in retry

    route_failed = runtime[runtime.index("case .routeFailed(let role"):
                           runtime.index("private func handleFrozenRadioLoss")]
    assert "policy.state.phase == .active" in route_failed
    assert "recoverAttachedActiveOwner" in route_failed
    assert "recoverReleasedActiveOwner" in route_failed


def verify_callback_inversions() -> None:
    m = load_policy_model()

    # Remote C -> A accepted -> STOP; exact duplicate C after acceptance may re-A but cannot revoke
    # the already accepted commitment. Terminal and owner-zero may then arrive in either order.
    p = m.Policy(m.Role.A)
    p.request(m.Role.B, 1_000, 100, 20, remote=True)
    assert p.ingress(1, 1, m.Role.A, 1_001).effects == ["acknowledgeRemoteStop"]
    assert p.tx_result(1, 1, m.Role.A, m.Frame.A, 1, "ACCEPTED", 1_002).effects == [
        "stopLocalSource"
    ]
    duplicate = p.duplicate_remote(1, 1, m.Role.A, 1_003)
    assert duplicate.effects == ["acknowledgeRemoteStop"]
    assert p.state.control_accepted and p.state.local_stop_requested
    assert p.owners(1, 1, m.Role.A, 0, 1_004).effects == []
    result = p.terminal(1, 1, m.Role.A, 1_005)
    assert result.effects[-2:] == ["cancelStopTimeout", "armDrainDeadline"]

    # Local C: adapter acceptance never authorizes stop. Inbound exact A does; then an early
    # terminal callback before owner-zero remains safely waiting.
    p = m.Policy(m.Role.B)
    p.request(m.Role.A, 2_000, 100, 20)
    p.ingress(1, 1, m.Role.B, 2_001)
    accepted = p.tx_result(1, 1, m.Role.B, m.Frame.C, 1, "ACCEPTED", 2_002)
    assert accepted.effects == [] and not p.state.local_stop_requested
    assert p.remote(1, 1, m.Role.B, m.Evidence.ACK, 2_003).effects == ["stopLocalSource"]
    p.terminal(1, 1, m.Role.B, 2_004)
    assert p.state.phase == m.Phase.LOCAL and not p.state.local_owners_zero
    p.owners(1, 1, m.Role.B, 0, 2_005)
    assert p.state.phase == m.Phase.DRAINING

    # Fresh install/no peer: acquisition is fenced and the atomic no-owner callback switches A->B
    # without ever emitting C. This is the route-selection escape path before transport READY.
    p = m.Policy(m.Role.A)
    p.request(m.Role.B, 3_000, 100, 20)
    offline = p.ingress_without_owner(1, 1, m.Role.A, 3_001)
    assert offline.effects == ["stopLocalSource"]
    assert p.state.remote_evidence == m.Evidence.NO_OWNER
    assert p.state.control_attempt == 0

    # didDisconnect can beat the ordinary frozen callback. Radio evidence is committed first;
    # atomic no-owner freeze retains it and produces no dead C/A attempt.
    p = m.Policy(m.Role.B)
    p.request(m.Role.A, 4_000, 100, 20)
    p.radio(1, 1, m.Role.B, 4_001)
    lost = p.ingress_without_owner(1, 1, m.Role.B, 4_002)
    assert lost.effects == [] and p.state.remote_evidence == m.Evidence.RADIO


def main() -> None:
    verify_app_and_single_owner()
    verify_xpc_source_contracts()
    verify_production_route_a_policy()
    verify_xpc_recovery_replays()
    verify_route_p0_guards()
    verify_persistence_and_upgrade_replays()
    verify_callback_inversions()
    print(
        "PASS: v63 app/BRS2 persistence + first-launch repair + target-start XPC watchdog + local-operational/"
        "peer-ready fence + Route-B release-before-failure + restoration/callback inversions"
    )


if __name__ == "__main__":
    main()
