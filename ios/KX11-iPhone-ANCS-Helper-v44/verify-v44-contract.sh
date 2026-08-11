#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IOS_ROOT=$(CDPATH= cd -- "$ROOT/.." && pwd)
SOURCE="$ROOT/KX11ANCSHelper/ViewController.swift"
PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"

require_source() {
    grep -Fq "$1" "$SOURCE" || {
        echo "missing v44 contract marker: $1" >&2
        exit 1
    }
}

# The predecessor projects are immutable executable contracts, not prose copied into v44. v43's
# verifier already invokes v42, whose verifier invokes v41.
sh "$IOS_ROOT/KX11-iPhone-ANCS-Helper-v43/verify-v43-contract.sh"

[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 44;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 44.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]
require_source 'titleLabel.text = "KX11 ANCS HELPER v44"'
require_source 'v44: restored .connecting owner adopts one strictly newer HA1210 publication'
require_source 'managedIncomingPublicationProtocol: UInt8 = 2'
require_source 'generation == 0x2F04'
require_source 'nonce > 0, nonce < 0x00FF_FFFF'
require_source 'CONTRACT_V44_RESTORED_SYSTEM_CONNECTING_NEWER_PUBLICATION_ONLY'
require_source 'CONTRACT_V44_ADOPTION_PRECEDES_LAST_OBSERVED_MUTATION'
require_source 'CONTRACT_V44_RESTORATION_ADOPTION_DURABLE_BEFORE_CANCEL'
require_source 'CONTRACT_V44_RESTORATION_V1_SAME_OLDER_FOREIGN_ARE_READ_ONLY'
require_source 'CONTRACT_V44_RESTORATION_PUBLICATION_REOPENS_EXACTLY_ONCE'
require_source 'CONTRACT_V44_V43_SPENT_NONCE_SURVIVES_UPGRADE_AND_RESTART'
require_source 'CONTRACT_V44_SOLE_REOPEN_SURVIVES_POWER_WITHOUT_SECOND_CONNECT'
require_source 'CONTRACT_V44_SOLE_REOPEN_TOKEN_CAPTURE_FOLLOWS_ACTUAL_CONNECT'
require_source 'CONTRACT_V44_LATE_OLD_TERMINAL_CANNOT_DEMOTE_NEW_REOPEN'
require_source 'CONTRACT_V44_SYSTEM_AUTORECONNECT_OWNS_DISCONNECTED_SNAPSHOT'
require_source 'CONTRACT_V44_UNATTRIBUTED_LATE_DIDCONNECT_NEVER_SECOND_CANCELS'
require_source 'CONTRACT_V44_RESTORATION_BOUNDARY_DUPLICATE_SCAN_IS_BOUNDED_READ_ONLY'
require_source 'CONTRACT_V44_STALE_DUPLICATE_SCAN_WORK_CANNOT_MUTATE_FRESH_SLOT'
require_source 'centralRestorationPublicationScanRestartDelays: [TimeInterval] = [2, 5, 10, 30]'
require_source 'centralRestorationPublicationBoundaryBaselineNonce'
require_source 'centralRestorationPublicationBoundaryNotBeforeUptime'
require_source 'centralRestorationPublicationBoundaryTerminalGeneration'
require_source 'centralRestorationPublicationBoundaryInvalidationGeneration'
require_source 'centralLastActualIssuedConnectToken == nil'
require_source 'peripheral.state == .connecting'
require_source 'automaticPublicationAdoptionAlreadyConsumed('
require_source 'centralLegacyLastAutomaticPublicationAdoptionPreference'
require_source '"KX11ANCSHelper.lastAutomaticPublicationAdoption.v43"'
require_source '"KX11ANCSHelper.lastAutomaticPublicationAdoption.v44"'
require_source 'return defaults.synchronize()'

python3 - "$SOURCE" <<'PY'
from __future__ import annotations

import pathlib
import sys
from dataclasses import dataclass, field

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")


def section(start: str, end: str) -> str:
    a = source.index(start)
    b = source.index(end, a + len(start))
    return source[a:b]


# Source-order contracts: decide against the immutable boundary before ordinary observation mutates
# last-observed state; durable consume and every in-memory owner transition precede one cancel.
discovery = section(
    "func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral",
    "func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral)",
)
assert discovery.index("observeCentralRestorationPublicationAdoption") < discovery.index(
    "rememberExactPublicationIdentity"
)
assert discovery.index("CONTRACT_V44_ADOPTION_PRECEDES_LAST_OBSERVED_MUTATION") < discovery.index(
    "rememberExactPublicationIdentity"
)

adoption = section(
    "private func observeCentralRestorationPublicationAdoption",
    "private func loadCentralPublicationPersistence",
)
persist = adoption.index("persistAutomaticPublicationAdoption(")
adopted = adoption.index("centralRestorationPublicationAdoptedNonce = nonce", persist)
consumed = adoption.index("centralDeferredReclaimConsumed = true", adopted)
remembered = adoption.index("rememberExactPublicationIdentity", consumed)
cancel = adoption.index("cancelCentralConnectionSafely", remembered)
assert persist < adopted < consumed < remembered < cancel
assert adoption.count("cancelCentralConnectionSafely") == 1
assert "DispatchQueue.main.asyncAfter" not in adoption
assert "centralManager.connect" not in adoption
for required in (
    "peripheral.state == .connecting",
    "centralLastActualIssuedConnectToken == nil",
    "!centralDeferredReclaimIssuedConnectPending",
    "!centralManualReconnectPending",
    "centralHardResetReason == nil",
    "!centralRestorationReconnectPending",
    "!centralRestorationRecoveryAttempted",
    "centralRestoreOwnershipClaimCount == 0",
    "publicationNonceIsStrictlyNewer(nonce, than: baseline)",
    "observedAtUptime >= notBefore",
    "boundaryTerminal == centralDeferredReclaimTerminalGeneration",
    "centralPublicationInvalidationGeneration >= boundaryInvalidation",
    "!automaticPublicationAdoptionAlreadyConsumed(",
    "clearCentralDeferredConnectIntent()",
    "clearCentralRestorationRecovery()",
    "armCentralDeferredReclaimPostCancelObservation(peripheral)",
):
    assert required in adoption

will_restore = section(
    "func centralManager(_ central: CBCentralManager,\n                        willRestoreState",
    "func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral",
)
assert "if restored.state == .connecting" in will_restore
assert "beginCentralRestorationPublicationBoundary(restored)" in will_restore
assert will_restore.index("geelyPeripheral = restored") < will_restore.index(
    "beginCentralRestorationPublicationBoundary(restored)"
)

did_fail = section(
    "func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral",
    "func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,",
)
assert did_fail.index("centralDeferredReclaimPendingTerminal") < did_fail.index(
    "consumeCentralRestorationPublicationPostReopenTerminal"
) < did_fail.index("advanceCentralRestorationPublicationBoundaryAfterTerminal")

disconnect = section(
    "private func handleCentralDisconnect",
    "func centralManager(_ central: CBCentralManager,\n                        didUpdateANCSAuthorizationFor",
)
assert disconnect.index("centralDeferredReclaimPendingTerminal") < disconnect.index(
    "consumeCentralRestorationPublicationPostReopenTerminal"
) < disconnect.index("advanceCentralRestorationPublicationBoundaryAfterTerminal")

reopen = section(
    "private func reopenCentralDeferredReclaimAfterTerminal",
    "private func consumeCentralRestorationPublicationPostReopenTerminal",
)
assert reopen.index("centralRestorationPublicationReopenIssued = true") < reopen.index(
    "queueCentralConnectIntent"
)
assert reopen.count("queueCentralConnectIntent") == 1

post_reopen = section(
    "private func consumeCentralRestorationPublicationPostReopenTerminal",
    "Record delayed connect intent synchronously",
)
assert "queueCentralConnectIntent" not in post_reopen
assert "issueCentralConnect" not in post_reopen
assert "centralSystemAutoReconnectActive = true" in post_reopen
assert "centralRestorationPublicationReopenIssuedToken" in post_reopen
assert "issuedTerminal == centralDeferredReclaimTerminalGeneration" in post_reopen
assert "peripheral.state == .connecting" in post_reopen
assert post_reopen.index("peripheral.state == .connecting") < post_reopen.index(
    "centralOwnerConfiguredForAncs = false"
)

route = section("private func startCentralRouteIfPossible", "private func restoredOwnerHasSystemLinkProof")
assert route.index("consumeCentralDeferredConnectIfPossible()") < route.index(
    "holdCentralRestorationPublicationAfterSoleReopenIfNeeded()"
)
power_gate = section(
    "private func holdCentralRestorationPublicationAfterSoleReopenIfNeeded",
    "private func restoredOwnerHasSystemLinkProof",
)
assert "centralDeferredReclaimActive" in power_gate
assert "centralDeferredReclaimConsumed" in power_gate
assert "centralRestorationPublicationReopenPhase" in power_gate
assert "case .disconnected:" in power_gate
assert "case .connecting, .connected, .disconnecting:" in power_gate
assert power_gate.index("centralSystemAutoReconnectActive") < power_gate.index(
    "case .disconnected:"
)
assert "issueCentralConnect" not in power_gate
assert "queueCentralConnectIntent" not in power_gate
assert "centralManager.connect" not in power_gate
scan = section(
    "private func cancelCentralRestorationPublicationReadOnlyScan",
    "private func restoredOwnerHasSystemLinkProof",
)
assert "CBCentralManagerScanOptionAllowDuplicatesKey: true" in scan
assert "centralRestorationPublicationScanWindow" in scan
assert "centralRestorationPublicationScanRestartDelays" in scan
assert "scheduleCentralRestorationPublicationReadOnlyScanRestart" in scan
assert "DispatchQueue.main.asyncAfter" in scan
assert "cancelCentralConnectionSafely" not in scan
assert "issueCentralConnect" not in scan
assert "centralManager.connect" not in scan
assert "queueCentralConnectIntent" not in scan
assert "centralRestorationPublicationScanGeneration &+= 1" in scan
scan_window = section(
    "private func startCentralRestorationPublicationReadOnlyScan",
    "private func scheduleCentralRestorationPublicationReadOnlyScanRestart",
)
assert scan_window.index(
    "centralRestorationPublicationScanGeneration == scanGeneration"
) < scan_window.index("centralRestorationPublicationScanWindowWorkItem = nil")
assert scan_window.index(
    "centralRestorationPublicationScanWindowToken == windowToken"
) < scan_window.index("centralRestorationPublicationScanWindowToken = nil")
scan_restart = section(
    "private func scheduleCentralRestorationPublicationReadOnlyScanRestart",
    "private func restoredOwnerHasSystemLinkProof",
)
assert scan_restart.index(
    "centralRestorationPublicationScanGeneration == scanGeneration"
) < scan_restart.index("centralRestorationPublicationScanRestartWorkItem = nil")
assert scan_restart.index(
    "centralRestorationPublicationScanRestartToken == restartToken"
) < scan_restart.index("centralRestorationPublicationScanRestartToken = nil")
connecting_gate = route.index("centralRestorationPublicationOwns(peripheral)")
legacy_restore = route.index("if centralRestoredPendingOwner", connecting_gate)
assert connecting_gate < legacy_restore
assert "RESTORE ЖДЁТ НОВЫЙ NONCE" in route[connecting_gate:legacy_restore]
assert "startCentralRestorationPublicationReadOnlyScan(peripheral)" in route[
    connecting_gate:legacy_restore
]
assert "startCentralScan()" not in route[connecting_gate:legacy_restore]

restore_probe = section(
    "private func armRestoredOwnerProofObservation",
    "private func scheduleRestoredOwnerRecovery",
)
assert "!centralRestorationPublicationBoundaryActive" in restore_probe
restore_schedule = section(
    "private func scheduleRestoredOwnerRecovery",
    "private func clearCentralRestorationRecovery",
)
assert "!centralRestorationPublicationBoundaryActive" in restore_schedule

manual = section("@objc private func resetTapped()", "@objc private func roleChanged()")
assert manual.index("clearCentralRestorationPublicationBoundary()") < manual.index(
    "clearCentralDeferredReclaimLineage()"
)
stop = section("private func stopCentralRoute", "private func clearCentralRuntime")
assert "clearCentralRestorationPublicationBoundary()" in stop
advance_boundary = section(
    "private func advanceCentralRestorationPublicationBoundaryAfterTerminal",
    "private func clearCentralRestorationPublicationBoundary",
)
assert advance_boundary.index("cancelCentralRestorationPublicationReadOnlyScan") < advance_boundary.index(
    "centralRestorationPublicationBoundaryNotBeforeUptime ="
)
assert "startCentralRestorationPublicationReadOnlyScan(peripheral)" in advance_boundary
clear_boundary = section(
    "private func clearCentralRestorationPublicationBoundary",
    "private func centralRestorationPublicationOwns",
)
assert "cancelCentralRestorationPublicationReadOnlyScan" in clear_boundary

capture = section(
    "private func captureCentralActualIssuedConnect",
    "private func consumeCentralAutomaticRootOrigin",
)
assert capture.index("centralLastActualIssuedConnectToken =") < capture.index(
    "CONTRACT_V44_SOLE_REOPEN_TOKEN_CAPTURE_FOLLOWS_ACTUAL_CONNECT"
)
did_connect = section(
    "func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral)",
    "func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral",
)
assert "matchesSoleRestorationPublicationReopen" in did_connect
assert "centralRestorationPublicationReopenPhase == .exhausted" in did_connect
assert "Unattributed late didConnect quarantined" in did_connect
assert "centralRestorationPublicationReopenPhase = .connected" in did_connect
assert did_connect.index("centralRestorationPublicationReopenPhase = .connected") < did_connect.index(
    "continueCentralConnected(peripheral)"
)
full_green = section(
    "private func rearmCentralDestructiveRecoveryAfterFullProof",
    "private func waitForFreshCentralF04Publication",
)
assert "centralRestorationPublicationReopenPhase == .connected" in full_green
assert "clearCentralRestorationPublicationBoundary()" in full_green
adoption_lookup = section(
    "private func automaticPublicationAdoptionAlreadyConsumed",
    "private func persistAutomaticPublicationAdoption",
)
assert "centralLastAutomaticPublicationAdoptionPreference" in adoption_lookup
assert "centralLegacyLastAutomaticPublicationAdoptionPreference" in adoption_lookup
assert "if consumedNonce == nonce { return true }" in adoption_lookup
manager_state = section(
    "func centralManagerDidUpdateState",
    "func centralManager(_ central: CBCentralManager,\n                        willRestoreState",
)
assert "preserveSoleSystemReconnectAcrossPower" in manager_state
assert "centralRestorationPublicationReopenPhase == .requestIssued" in manager_state


MAX_NONCE = 0xFFFFFE


def newer(candidate: int, current: int) -> bool:
    forward = ((candidate - 1) + MAX_NONCE - (current - 1)) % MAX_NONCE
    return 0 < forward < MAX_NONCE // 2


@dataclass
class Durable:
    v43: tuple[str, int] | None = None
    v44: tuple[str, int] | None = None


@dataclass
class Contract:
    durable: Durable
    owner: str = "saved-owner"
    exact_wrapper: str = "restored-wrapper"
    baseline: int | None = 7
    current: int | None = 7
    boundary_active: bool = True
    boundary_terminal: int = 1
    terminal: int = 1
    boundary_invalidation: int = 0
    invalidation: int = 0
    powered: bool = True
    state: str = "connecting"
    issued_token: int | None = None
    issued_pending: bool = False
    manual: bool = False
    hard_reset: bool = False
    restoration_reconnect: bool = False
    restoration_attempted: bool = False
    restore_claims: int = 0
    deferred_cancel: bool = False
    root_owner: str | None = None
    root_consumed: bool = False
    root_pending_terminal: bool = False
    adoption_nonce: int | None = None
    reopen_issued: bool = False
    reopen_phase: str = "none"
    reopen_token: int | None = None
    pending_intent: bool = False
    actual_connect_count: int = 0
    owner_configured: bool = True
    system_auto: bool = False
    cancel_count: int = 0
    reopen_count: int = 0
    order: list[str] = field(default_factory=list)

    def spent(self, nonce: int) -> bool:
        return self.durable.v44 == (self.owner, nonce) or self.durable.v43 == (
            self.owner, nonce
        )

    def observe(
        self,
        *,
        protocol: int = 2,
        generation: int = 0x2F04,
        nonce: int = 8,
        owner: str = "saved-owner",
        wrapper: str = "restored-wrapper",
        after_boundary: bool = True,
        persist_ok: bool = True,
    ) -> bool:
        if not (
            self.boundary_active
            and owner == self.owner
            and wrapper == self.exact_wrapper
            and self.adoption_nonce is None
            and not self.reopen_issued
            and self.state == "connecting"
            and self.powered
            and self.issued_token is None
            and not self.issued_pending
            and not self.manual
            and not self.hard_reset
            and not self.restoration_reconnect
            and not self.restoration_attempted
            and self.restore_claims == 0
            and not self.deferred_cancel
            and protocol == 2
            and generation == 0x2F04
            and 0 < nonce < 0xFFFFFF
            and self.baseline is not None
            and newer(nonce, self.baseline)
            and after_boundary
            and self.boundary_terminal == self.terminal
            and self.invalidation >= self.boundary_invalidation
            and not self.spent(nonce)
        ):
            return False
        if self.current is not None and nonce != self.current and not newer(nonce, self.current):
            return False
        if self.root_owner not in (None, owner) or self.root_consumed:
            return False
        self.root_owner = owner
        if not persist_ok:
            self.order.append("persist-failed")
            return False
        self.durable.v44 = (owner, nonce)
        self.order.append("persist")
        self.adoption_nonce = nonce
        self.root_consumed = True
        self.root_pending_terminal = True
        self.order.append("consume")
        self.current = nonce
        self.invalidation += 1
        self.cancel_count += 1
        self.order.append("cancel")
        return True

    def terminal_boundary(self, *, owner: str = "saved-owner", exact: bool = True) -> bool:
        if not (
            self.root_consumed
            and self.root_pending_terminal
            and owner == self.owner
            and exact
            and not self.reopen_issued
        ):
            return False
        self.root_pending_terminal = False
        self.reopen_issued = True
        self.reopen_phase = "intent"
        self.pending_intent = True
        self.reopen_count += 1
        self.order.append("reopen")
        return True

    def materialize_reopen(self) -> bool:
        if not (
            self.reopen_phase == "intent"
            and self.pending_intent
            and self.powered
            and self.state == "disconnected"
        ):
            return False
        self.pending_intent = False
        self.actual_connect_count += 1
        self.issued_token = self.actual_connect_count
        self.reopen_token = self.issued_token
        self.reopen_phase = "request"
        self.state = "connecting"
        return True

    def post_reopen_terminal(self, system_reconnecting: bool = False) -> None:
        if not self.reopen_issued:
            return
        if self.reopen_phase == "intent":
            return
        if self.reopen_phase in ("request", "connected") and (
            system_reconnecting or self.state in ("connecting", "connected", "disconnecting")
        ):
            self.owner_configured = True
            if system_reconnecting:
                self.system_auto = True
            return
        if self.reopen_phase in ("request", "connected") and self.state == "disconnected":
            self.reopen_phase = "exhausted"
            self.owner_configured = False

    def powered_on_route(self) -> str:
        if self.pending_intent:
            return "materialized" if self.materialize_reopen() else "intent-waits"
        if self.reopen_issued and self.adoption_nonce is not None and self.root_consumed:
            if self.system_auto:
                self.owner_configured = True
                return "spent-gate"
            if self.reopen_phase in ("request", "connected") and self.state == "disconnected":
                self.reopen_phase = "exhausted"
                self.owner_configured = False
            return "spent-gate"
        self.actual_connect_count += 1
        return "ordinary-connect"

    def did_connect(self) -> bool:
        if not (
            self.reopen_phase in ("request", "exhausted")
            and self.reopen_token is not None
            and self.reopen_token == self.issued_token
        ):
            return False
        self.owner_configured = True
        self.state = "connected"
        self.reopen_phase = "connected"
        return True


@dataclass
class ReadOnlyScan:
    boundary: int = 1
    connecting: bool = True
    active: bool = False
    generation: int = 0
    next_token: int = 0
    window_token: int | None = None
    window_boundary: int | None = None
    restart_pending: bool = False
    restart_token: int | None = None
    start_count: int = 0
    stop_count: int = 0
    cancel_count: int = 0
    connect_count: int = 0

    def mint(self) -> int:
        self.next_token += 1
        return self.next_token

    def start(self) -> tuple[int, int, int] | None:
        if not self.connecting or self.active or self.restart_pending:
            return None
        self.active = True
        self.window_boundary = self.boundary
        self.window_token = self.mint()
        self.start_count += 1
        return (self.generation, self.window_token, self.window_boundary)

    def timeout(
        self, callback: tuple[int, int, int]
    ) -> tuple[int, int, int] | None:
        captured_generation, captured_token, captured_boundary = callback
        if not (
            self.generation == captured_generation
            and self.window_token == captured_token
            and self.window_boundary == captured_boundary
            and self.boundary == captured_boundary
        ):
            return None
        self.active = False
        self.window_token = None
        self.window_boundary = None
        self.stop_count += 1
        self.restart_pending = True
        self.restart_token = self.mint()
        return (self.generation, self.restart_token, self.boundary)

    def restart(
        self, callback: tuple[int, int, int]
    ) -> tuple[int, int, int] | None:
        captured_generation, captured_token, captured_boundary = callback
        if not (
            self.restart_pending
            and self.generation == captured_generation
            and self.restart_token == captured_token
            and self.boundary == captured_boundary
        ):
            return None
        self.restart_pending = False
        self.restart_token = None
        return self.start()

    def advance_boundary(
        self,
    ) -> tuple[
        tuple[int, int, int] | None,
        tuple[int, int, int] | None,
        tuple[int, int, int] | None,
    ]:
        old_window = (
            (self.generation, self.window_token, self.window_boundary)
            if self.window_token is not None and self.window_boundary is not None
            else None
        )
        old_restart = (
            (self.generation, self.restart_token, self.boundary)
            if self.restart_token is not None
            else None
        )
        if self.active:
            self.active = False
            self.stop_count += 1
        self.generation += 1
        self.window_token = None
        self.window_boundary = None
        self.restart_pending = False
        self.restart_token = None
        self.boundary += 1
        fresh_window = self.start()
        return old_window, old_restart, fresh_window


# The observed 000007 -> 000008 restoration sequence persists and consumes before one cancel.
durable = Durable()
trace = Contract(durable)
assert trace.observe(nonce=8)
assert trace.order == ["persist", "consume", "cancel"]
assert trace.cancel_count == 1
assert trace.terminal_boundary()
assert trace.reopen_count == 1
assert not trace.terminal_boundary()
trace.post_reopen_terminal()
assert trace.reopen_count == 1 and trace.cancel_count == 1

# Baseline N can be coalesced first; bounded AllowDuplicates restart still observes N+1 from the
# same exact wrapper and grants one adoption. Same/older duplicates remain storm-free/read-only.
coalesced = Contract(Durable())
scan_cycle = ReadOnlyScan()
first_window = scan_cycle.start()
assert first_window is not None
assert scan_cycle.active
assert not coalesced.observe(nonce=7)
first_restart = scan_cycle.timeout(first_window)
assert first_restart is not None
assert not scan_cycle.active and scan_cycle.restart_pending
second_window = scan_cycle.restart(first_restart)
assert second_window is not None
assert scan_cycle.active and scan_cycle.start_count == 2
assert coalesced.observe(nonce=8)
scan_cycle.active = False  # production adoption synchronously closes its read-only scan.
assert coalesced.cancel_count == 1

duplicates = Contract(Durable())
for value in (7, 7, 6, 7, 6):
    assert not duplicates.observe(nonce=value)
assert duplicates.cancel_count == 0 and duplicates.reopen_count == 0

# scan-start -> terminal/restoration boundary advance synchronously stops the old window and
# rearms the new one. A stale old timeout cannot leave or close the fresh scan; timers never
# increment destructive cancel/connect counters.
advanced_scan = ReadOnlyScan()
initial_window = advanced_scan.start()
assert initial_window is not None
old_window, old_restart, fresh_window = advanced_scan.advance_boundary()
assert old_window == initial_window and old_restart is None and fresh_window is not None
assert advanced_scan.active and advanced_scan.window_boundary == advanced_scan.boundary
assert advanced_scan.stop_count == 1 and advanced_scan.start_count == 2
assert advanced_scan.timeout(old_window) is None
assert advanced_scan.active and advanced_scan.window_boundary == advanced_scan.boundary
assert advanced_scan.cancel_count == 0 and advanced_scan.connect_count == 0

# A canceled restart closure is equally unable to clear a newer generation's fresh window slot
# or open an overlapping scan cycle after a boundary advance.
stale_restart_scan = ReadOnlyScan()
stale_window = stale_restart_scan.start()
assert stale_window is not None
stale_restart = stale_restart_scan.timeout(stale_window)
assert stale_restart is not None and stale_restart_scan.restart_pending
old_window, old_restart, fresh_window = stale_restart_scan.advance_boundary()
assert old_window is None and old_restart == stale_restart and fresh_window is not None
fresh_token = stale_restart_scan.window_token
assert stale_restart_scan.restart(stale_restart) is None
assert stale_restart_scan.active and stale_restart_scan.window_token == fresh_token
assert stale_restart_scan.start_count == 2 and stale_restart_scan.stop_count == 1
assert stale_restart_scan.cancel_count == 0 and stale_restart_scan.connect_count == 0

# Callback loss after cancel may use read-only disconnected state, but remains the same one reopen.
state_probe = Contract(Durable())
assert state_probe.observe()
state_probe.state = "disconnected"
assert state_probe.terminal_boundary()
assert not state_probe.terminal_boundary()

# Read-only disconnected -> sole intent -> actual reopen -> late old terminal -> didConnect.
# The stale terminal sees the current request as connecting and cannot demote RequiresANCS.
late_old_terminal = Contract(Durable())
assert late_old_terminal.observe()
late_old_terminal.state = "disconnected"
assert late_old_terminal.terminal_boundary()
assert late_old_terminal.materialize_reopen()
late_old_terminal.post_reopen_terminal()
assert late_old_terminal.reopen_phase == "request"
assert late_old_terminal.owner_configured
assert late_old_terminal.did_connect()
assert late_old_terminal.reopen_phase == "connected"
assert late_old_terminal.actual_connect_count == 1

# Power-off after the actual sole reopen preserves the spent gate. A disconnected wrapper is
# exhausted/read-only on poweredOn; connecting/connected wrappers wait for their callback.
power_after_actual = Contract(Durable())
assert power_after_actual.observe()
power_after_actual.state = "disconnected"
assert power_after_actual.terminal_boundary()
assert power_after_actual.materialize_reopen()
assert power_after_actual.actual_connect_count == 1
power_after_actual.powered = False
power_after_actual.state = "disconnected"
power_after_actual.powered = True
assert power_after_actual.powered_on_route() == "spent-gate"
assert power_after_actual.reopen_phase == "exhausted"
assert power_after_actual.actual_connect_count == 1
assert power_after_actual.did_connect()  # delayed original callback wins; never second-cancel.
assert power_after_actual.reopen_phase == "connected"
assert power_after_actual.actual_connect_count == 1 and power_after_actual.cancel_count == 1
for current_state in ("connecting", "connected"):
    waiting = Contract(Durable())
    assert waiting.observe()
    waiting.state = "disconnected"
    assert waiting.terminal_boundary()
    assert waiting.materialize_reopen()
    waiting.state = current_state
    waiting.powered = False
    waiting.powered = True
    assert waiting.powered_on_route() == "spent-gate"
    assert waiting.actual_connect_count == 1

# An iOS 17 isReconnecting terminal owns even a transient disconnected snapshot across power.
# The spent gate waits, retains RequiresANCS, and the later didConnect is accepted with no extra
# Helper connect or cancel.
system_auto = Contract(Durable())
assert system_auto.observe()
system_auto.state = "disconnected"
assert system_auto.terminal_boundary()
assert system_auto.materialize_reopen()
system_auto.state = "disconnected"
system_auto.post_reopen_terminal(system_reconnecting=True)
assert system_auto.system_auto and system_auto.owner_configured
system_auto.powered = False
system_auto.powered = True
assert system_auto.powered_on_route() == "spent-gate"
assert system_auto.reopen_phase == "request"
assert system_auto.owner_configured
assert system_auto.actual_connect_count == 1
assert system_auto.cancel_count == 1
assert system_auto.did_connect()
assert system_auto.actual_connect_count == 1 and system_auto.cancel_count == 1

# Same tuple is spent across restart, including migration from v43's shared automatic record.
restart = Contract(durable)
assert not restart.observe(nonce=8)
legacy_restart = Contract(Durable(v43=("saved-owner", 8)))
assert not legacy_restart.observe(nonce=8)

# Fail-closed storage ordering: no durable record means no consume and no cancel.
storage = Contract(Durable())
assert not storage.observe(persist_ok=False)
assert storage.cancel_count == 0 and not storage.root_consumed
assert storage.order == ["persist-failed"]

# All ineligible evidence is read-only.
mutations = (
    {"protocol": 1},
    {"generation": 0x2F05},
    {"nonce": 7},                       # same boundary nonce
    {"nonce": 6},                       # older
    {"owner": "foreign"},
    {"wrapper": "address-equal-copy"},
    {"after_boundary": False},
)
for kwargs in mutations:
    candidate = Contract(Durable())
    assert not candidate.observe(**kwargs)
    assert candidate.cancel_count == 0 and candidate.reopen_count == 0

for field_name, value in (
    ("powered", False),
    ("state", "connected"),
    ("issued_token", 1),
    ("issued_pending", True),
    ("manual", True),
    ("hard_reset", True),
    ("restoration_reconnect", True),
    ("restoration_attempted", True),
    ("restore_claims", 1),
    ("deferred_cancel", True),
    ("root_consumed", True),
):
    candidate = Contract(Durable())
    setattr(candidate, field_name, value)
    assert not candidate.observe(), field_name
    assert candidate.cancel_count == 0

no_baseline = Contract(Durable(), baseline=None, current=None)
assert not no_baseline.observe()
wrong_terminal = Contract(Durable(), boundary_terminal=1, terminal=2)
assert not wrong_terminal.observe()
rollback_after_newer_observation = Contract(Durable(), baseline=7, current=9)
assert not rollback_after_newer_observation.observe(nonce=8)

# UInt24 ring wrap is strict: FFFFFE -> 000001 is newer; reverse rollback is not.
wrapped = Contract(Durable(), baseline=0xFFFFFE, current=0xFFFFFE)
assert wrapped.observe(nonce=1)
rollback = Contract(Durable(), baseline=1, current=1)
assert not rollback.observe(nonce=0xFFFFFE)

# An existing unconsumed ordinary root is adopted rather than creating a competing cancel owner.
shared_root = Contract(Durable(), root_owner="saved-owner")
assert shared_root.observe()
assert shared_root.cancel_count == 1
assert shared_root.root_owner == "saved-owner" and shared_root.root_consumed

# Power/late callbacks never create a second cancel or reopen.
late = Contract(Durable())
assert late.observe()
late.powered = False
assert not late.observe(nonce=9)
late.powered = True
assert late.terminal_boundary()
late.state = "connected"  # matching didConnect wins; no recovery action follows.
assert not late.observe(nonce=9)
assert late.cancel_count == 1 and late.reopen_count == 1
PY

echo "Helper v44 restoration-publication adoption and preserved v43/v42/v41 contracts passed"
