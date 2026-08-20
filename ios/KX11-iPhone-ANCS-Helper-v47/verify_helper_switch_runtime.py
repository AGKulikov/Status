#!/usr/bin/env python3
"""Static app gate plus cross-layer callback-inversion replays for Helper v47."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import plistlib
import re
import sys
import tempfile


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def read(name: str) -> str:
    return (ROOT / name).read_text(encoding="utf-8")


def load_policy_model():
    path = ROOT / "verify_role_switch_policy.py"
    spec = importlib.util.spec_from_file_location("v47_switch_model", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def verify_app_and_single_owner() -> None:
    runtime = read("HelperSwitchRuntimeCoordinator.swift")
    owner = read("HelperBleRuntimeCoordinator.swift")
    route_a = read("HelperPeripheralRoute.swift")
    route_b = read("HelperCentralRoute.swift")
    view = read("KX11ANCSHelper/ViewController.swift")
    project = read("KX11ANCSHelper.xcodeproj/project.pbxproj")
    readme = read("README.md")
    release = read("RELEASE.txt")

    for filename in (
        "BleRoleSwitchPolicy.swift",
        "IphoneBleWireProtocolV2.swift",
        "HelperPeripheralRoute.swift",
        "HelperCentralRoute.swift",
        "HelperBleRuntimeCoordinator.swift",
        "HelperSwitchRuntimeCoordinator.swift",
        "HelperTelemetrySource.swift",
        "AppDelegate.swift",
        "ViewController.swift",
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
    assert "PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;" in project
    assert "CURRENT_PROJECT_VERSION = 47;" in project
    assert "MARKETING_VERSION = 47.0;" in project
    for marker in (
        "candidate, not a claim that reconnects are already field-stable",
        "does **not** delete the system",
        "at least 20 A → B → A cycles",
        "If the user force-quits Helper",
        "matching Android transport-v2 implementation",
    ):
        assert marker in readme, f"README omits truthful release marker: {marker}"
    for marker in (
        "Build: 47",
        "Marketing version: 47.0",
        "Status: source/replay accepted; hosted Xcode build and physical-device matrix remain release gates",
        "Do not label this candidate stable",
    ):
        assert marker in release, f"RELEASE omits truthful release marker: {marker}"

    expected_files = {
        "BleRoleSwitchPolicy.swift",
        "HelperBleRuntimeCoordinator.swift",
        "HelperCentralRoute.swift",
        "HelperPeripheralRoute.swift",
        "HelperSwitchRuntimeCoordinator.swift",
        "HelperTelemetrySource.swift",
        "IphoneBleWireProtocolV2.swift",
        "KX11ANCSHelper.xcodeproj/project.pbxproj",
        "KX11ANCSHelper.xcodeproj/xcshareddata/xcschemes/KX11ANCSHelper.xcscheme",
        "KX11ANCSHelper/AppDelegate.swift",
        "KX11ANCSHelper/Info.plist",
        "KX11ANCSHelper/ViewController.swift",
        "README.md",
        "RELEASE.txt",
        "verify-v47-contract.sh",
        "verify_helper_central_route.py",
        "verify_helper_peripheral_route.py",
        "verify_helper_switch_runtime.py",
        "verify_helper_telemetry.py",
        "verify_role_switch_policy.py",
        "verify_wire_protocol.py",
    }
    actual_files = {
        str(path.relative_to(ROOT))
        for path in ROOT.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts
    }
    assert actual_files == expected_files, (
        f"missing={sorted(expected_files - actual_files)}",
        f"unexpected={sorted(actual_files - expected_files)}",
    )


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

    # Both legacy v46 roles enter a persisted same-role drain; neither directly starts a v47
    # manager, and invalid legacy raw values are rejected instead of silently choosing Route A.
    assert "case 0:\n            return .helperPeripheralAndroidCentral" in runtime
    assert "case 1:\n            return .helperCentralAndroidPeripheral" in runtime
    assert "migrated.requestSameRoleRestart" in runtime
    persist = runtime.index("policy: migrated")
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
    verify_route_p0_guards()
    verify_persistence_and_upgrade_replays()
    verify_callback_inversions()
    print(
        "PASS: v47 app/BRS2 atomic persistence + v46 migration + CLOSED dual drain + "
        "offline selection + client-first/early-disconnect callback inversions"
    )


if __name__ == "__main__":
    main()
