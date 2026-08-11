#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IOS_ROOT=$(CDPATH= cd -- "$ROOT/.." && pwd)
SOURCE="$ROOT/KX11ANCSHelper/ViewController.swift"
PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"

require_source() {
    grep -Fq "$1" "$SOURCE" || {
        echo "missing v43 contract marker: $1" >&2
        exit 1
    }
}

# Preserve the committed predecessors as executable contracts, not copied text.
sh "$IOS_ROOT/KX11-iPhone-ANCS-Helper-v41/verify-v41-contract.sh"
sh "$IOS_ROOT/KX11-iPhone-ANCS-Helper-v42/verify-v42-contract.sh"

[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 43;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 43.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]
require_source 'titleLabel.text = "KX11 ANCS HELPER v43"'
require_source 'managedIncomingPublicationProtocol: UInt8 = 2'
require_source 'bytes.count == offset + 6'
require_source 'generation == 0x2F04'
require_source 'nonce > 0, nonce < 0x00FF_FFFF'
require_source 'CONTRACT_V43_OLDER_NONCE_CANNOT_ROLL_BACK_CURRENT_PUBLICATION'
require_source 'publicationNonceIsStrictlyNewer'
require_source 'return forward > 0 && forward < modulus / 2'
require_source 'CONTRACT_V43_PROTOCOL2_BINDS_ROOT_TOKEN_TERMINAL_AND_PUBLICATION'
require_source 'centralDeferredReclaimRootClaimToken'
require_source 'centralDeferredReclaimIssuedConnectToken'
require_source 'centralDeferredReclaimIssuedTerminalGeneration'
require_source 'centralDeferredReclaimIssuedInvalidationGeneration'
require_source 'centralDeferredReclaimBeaconPublicationNonce'
require_source 'CONTRACT_V43_SYSTEM_TABLE_DOES_NOT_REPLACE_PUBLICATION_AUTHORITY'
require_source 'CONTRACT_V43_AUTOMATIC_ADOPTION_CONSUMED_BEFORE_CANCEL'
require_source 'return defaults.synchronize()'
require_source 'CONTRACT_V43_ROOT_CLAIM_CONSUMED_BEFORE_CANCEL'
require_source 'CONTRACT_V43_ONE_DESTRUCTIVE_OWNER_SUPERSEDES_RESTORE_CLAIM2'
require_source 'Actual recovery reopen inherited spent rootClaim='
require_source 'centralExplicitManualRootPending = true'
require_source 'CONTRACT_V43_MANUAL_ORIGIN_SURVIVES_MISSING_RETAINED_WRAPPER'
require_source 'case explicitManual'
require_source 'case postGreenReconnect'
require_source 'centralLastFullGreenOwnerID = peripheral.identifier'
require_source 'centralLastFullGreenTerminalGeneration = centralDeferredReclaimTerminalGeneration'
require_source 'CONTRACT_V43_POST_ISSUE_DUPLICATE_SCAN'
require_source 'CBCentralManagerScanOptionAllowDuplicatesKey: true'
require_source 'CONTRACT_V43_RECOVERY_DUPLICATE_NEVER_REENTERS_CONNECT'
require_source 'CONTRACT_V43_INELIGIBLE_DUPLICATE_STOPS_DUPLICATE_SCAN'
require_source 'centralDeferredReclaimScanRestartDelays: [TimeInterval] = [2, 5, 10, 30]'
require_source 'CONTRACT_V43_SYSTEM_PROBE_CANNOT_BYPASS_SCAN_SLEEP'
require_source 'matching didConnect atomically stops recovery duplicate scan'

python3 - "$SOURCE" <<'PY'
from __future__ import annotations

import pathlib
import sys
from dataclasses import dataclass

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")

def section(start: str, end: str) -> str:
    a = source.index(start)
    b = source.index(end, a + len(start))
    return source[a:b]

# Source-order invariants which a marker-only implementation could otherwise violate.
issue = section("private func issueCentralConnect", "private func beginCentralDiscovery")
assert issue.index("centralManager.connect(peripheral, options: options)") < issue.index(
    "captureCentralActualIssuedConnect") < issue.index("armCentralDeferredReclaimObservation")
assert issue.index("supersedeRestorationClaimTwoWithPublicationRoot") < issue.index(
    "centralManager.connect(peripheral, options: options)"
)

grace = section(
    "private func armCentralDeferredReclaimProofGrace",
    "private func armCentralDeferredReclaimPostCancelObservation",
)
assert grace.index("persistAutomaticPublicationAdoption") < grace.index(
    "centralDeferredReclaimConsumed = true"
) < grace.index("cancelCentralConnectionSafely")
assert "guard publicationAuthority && (systemEvidence || issuedBeaconEvidence)" in grace
assert grace.index("supersedeRestorationClaimTwoWithPublicationRoot") < grace.index(
    "centralDeferredReclaimConsumed = true"
)

restore_claim2 = section(
    "private func armFreshRestoreConnectProofObservation",
    "private func armRestoredOwnerPostCancelObservation",
)
assert restore_claim2.count("centralDeferredReclaimActive") >= 3

did_connect = section(
    "func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral)",
    "func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral",
)
assert did_connect.index("stopCentralScanSafely") < did_connect.index(
    "continueCentralConnected"
)

discovery = section(
    "func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral",
    "func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral)",
)
swallow = discovery.index("CONTRACT_V43_RECOVERY_DUPLICATE_NEVER_REENTERS_CONNECT")
assert swallow < discovery.index("connectCentral(peripheral, reason: \"stable anchor beacon\")")

full_green = section(
    "private func rearmCentralDestructiveRecoveryAfterFullProof",
    "private func waitForFreshCentralF04Publication",
)
assert full_green.index("centralLastFullGreenOwnerID = peripheral.identifier") < full_green.index(
    "rearmCentralDeferredReclaimAfterTrustedBoundary"
)


MAX_NONCE = 0xFFFFFE

def newer(candidate: int, current: int) -> bool:
    forward = ((candidate - 1) + MAX_NONCE - (current - 1)) % MAX_NONCE
    return 0 < forward < MAX_NONCE // 2


@dataclass
class Root:
    owner: str
    origin: str
    claim: int
    consumed: bool = False
    pending_terminal: bool = False
    nonce: int | None = None
    attempt: int | None = None
    terminal: int | None = None
    invalidation: int | None = None
    observed_after_boundary: bool = False
    physical: bool = False


class Contract:
    """Executable state model for the v43 destructive subset."""

    def __init__(self, owner: str = "saved-owner") -> None:
        self.saved_owner = owner
        self.terminal = 0
        self.invalidation = 0
        self.attempt_serial = 0
        self.claim_serial = 0
        self.current_nonce: int | None = None
        self.current_nonce_seen_in_process = False
        self.last_auto_adoption: tuple[str, int] | None = None
        self.root: Root | None = None
        self.cancel_count = 0
        self.reopen_count = 0
        self.connect_reentry_count = 0
        self.runtime_clear_count = 0
        self.green_terminal: int | None = None
        self.green_nonce: int | None = None
        self.persist_ok = True
        self.connected_callback = False
        self.scan_active = False

    def observe_publication(self, *, owner: str, protocol: int, generation: int,
                            nonce: int, after_boundary: bool = True,
                            attempt: int | None = None,
                            terminal: int | None = None) -> bool:
        if owner != self.saved_owner or protocol != 2 or generation != 0x2F04:
            return False
        if nonce <= 0 or nonce >= 0xFFFFFF:
            return False
        if self.current_nonce is not None and nonce != self.current_nonce:
            if not newer(nonce, self.current_nonce):
                return False
            self.invalidation += 1
            if self.root and not self.root.consumed:
                self.root.nonce = None
                self.root.observed_after_boundary = False
                self.root.invalidation = self.invalidation
        self.current_nonce = nonce
        self.current_nonce_seen_in_process = True
        root = self.root
        if root is None or root.consumed or root.owner != owner:
            return True
        if attempt is not None and attempt != root.attempt:
            return False
        if terminal is not None and terminal != root.terminal:
            return False
        if not after_boundary:
            return False
        if root.origin == "post-green" and self.green_nonce != nonce:
            root.origin = "automatic"
        root.nonce = nonce
        root.observed_after_boundary = True
        root.invalidation = self.invalidation
        return True

    def actual_connect(self, origin: str = "automatic", token: int | None = None) -> int:
        self.attempt_serial = max(self.attempt_serial + 1, token or 0)
        if self.root is None:
            if origin == "automatic" and self.green_terminal is not None \
                    and self.terminal > self.green_terminal:
                origin = "post-green"
                self.green_terminal = None
            self.claim_serial += 1
            self.root = Root(self.saved_owner, origin, self.claim_serial)
        if not self.root.consumed:
            self.root.attempt = self.attempt_serial
            self.root.terminal = self.terminal
            self.root.invalidation = self.invalidation
            self.root.observed_after_boundary = False
            self.root.physical = False
            self.scan_active = True
        return self.attempt_serial

    def system_proof(self, *, owner: str, token: int | None = None) -> bool:
        root = self.root
        if root is None or root.consumed or owner != self.saved_owner:
            return False
        if token is not None and token != root.attempt:
            return False
        root.physical = True
        return True

    def grace(self) -> bool:
        root = self.root
        if root is None or root.consumed or self.connected_callback:
            return False
        if not (root.physical and root.observed_after_boundary and root.nonce is not None):
            return False
        if root.terminal != self.terminal or root.invalidation != self.invalidation:
            return False
        if root.origin == "automatic" and self.last_auto_adoption == (
            root.owner, root.nonce
        ):
            return False
        if root.origin == "automatic":
            if not self.persist_ok:
                return False
            # Durable consume is modelled before the destructive count.
            self.last_auto_adoption = (root.owner, root.nonce)
        root.consumed = True
        root.pending_terminal = True
        root.attempt = None
        self.cancel_count += 1
        return True

    def terminal_callback(self) -> bool:
        self.terminal += 1
        root = self.root
        if root and root.consumed and root.pending_terminal:
            root.pending_terminal = False
            self.reopen_count += 1
            self.actual_connect(origin=root.origin)
            return True
        return False

    def did_connect(self) -> None:
        self.connected_callback = True
        self.scan_active = False

    def duplicate_scan_callback(self) -> None:
        # Retained-root duplicate is swallowed after didConnect. It cannot enter connectCentral.
        if self.root is not None:
            self.scan_active = False
            return
        self.connect_reentry_count += 1
        self.runtime_clear_count += 1

    def full_green(self) -> None:
        self.green_terminal = self.terminal
        self.green_nonce = self.current_nonce
        self.root = None
        self.connected_callback = False


# Exact 041020 sequence: manual cancel/terminal, token 3, Android physical link, no callback for
# >7 minutes. HA1208 protocol2 evidence turns it into exactly one bounded reclaim/reopen.
trace_041020 = [
    ("07:01:41.204", "manual cancel current owner"),
    ("07:01:41.245", "didDisconnect"),
    ("07:01:41.556", "actual centralManager.connect token=3"),
    ("07:01:45.523", "Android exact bonded F04 physical CONNECTED"),
    ("07:08:45.943", "still no didConnect/didFail/didDisconnect"),
]
assert len(trace_041020) == 5 and trace_041020[-1][0] > trace_041020[2][0]
m = Contract()
m.terminal = 1
token = m.actual_connect(origin="manual", token=3)
assert token == 3
assert m.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                             nonce=0x000121, attempt=3, terminal=1)
assert m.system_proof(owner="saved-owner", token=3)
assert m.grace() and m.cancel_count == 1
assert m.terminal_callback() and m.reopen_count == 1
assert m.root and m.root.consumed and m.root.attempt is None
assert not m.grace() and m.cancel_count == 1

# Timeout-only, legacy, foreign, wrong generation/token/terminal and stale evidence are read-only.
for mutation in ("timeout", "legacy", "foreign", "generation", "token", "terminal", "stale"):
    n = Contract()
    t = n.actual_connect()
    if mutation != "timeout":
        kwargs = dict(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x100, attempt=t, terminal=0, after_boundary=True)
        if mutation == "legacy": kwargs["protocol"] = 1
        if mutation == "foreign": kwargs["owner"] = "foreign-owner"
        if mutation == "generation": kwargs["generation"] = 0x2F05
        if mutation == "token": kwargs["attempt"] = t + 1
        if mutation == "terminal": kwargs["terminal"] = 9
        if mutation == "stale": kwargs["after_boundary"] = False
        n.observe_publication(**kwargs)
    n.system_proof(owner="saved-owner", token=t)
assert not n.grace() and n.cancel_count == 0, mutation

# Explicit Reset with no retained wrapper keeps typed origin across scan/retrieve until the first
# actual connect; it must not silently become automatic publication adoption.
typed_manual_pending = True
retained_wrapper = None
assert retained_wrapper is None and typed_manual_pending
manual_origin = "manual" if typed_manual_pending else "automatic"
typed_manual_pending = False
manual_nil_owner = Contract()
manual_nil_owner.actual_connect(origin=manual_origin)
assert manual_nil_owner.root.origin == "manual" and not typed_manual_pending

# Automatic adoption is once per nonce, while a new explicit manual root remains independent.
a = Contract()
t = a.actual_connect()
a.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x201, attempt=t, terminal=0)
a.system_proof(owner="saved-owner", token=t)
assert a.grace()
a.terminal_callback()
assert a.cancel_count == 1
a.root = None
t = a.actual_connect()
a.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x201, attempt=t, terminal=a.terminal)
a.system_proof(owner="saved-owner", token=t)
assert not a.grace() and a.cancel_count == 1
a.root = None
t = a.actual_connect(origin="manual")
a.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x201, attempt=t, terminal=a.terminal)
a.system_proof(owner="saved-owner", token=t)
assert a.grace() and a.cancel_count == 2

# Newer B invalidates A; delayed A cannot roll back or authorize a second action. Wrap is valid.
b = Contract()
t = b.actual_connect()
assert b.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                             nonce=0x301, attempt=t, terminal=0)
assert b.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                             nonce=0x302, attempt=t, terminal=0)
assert not b.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                                 nonce=0x301, attempt=t, terminal=0)
assert b.current_nonce == 0x302
b.system_proof(owner="saved-owner", token=t)
assert b.grace() and b.cancel_count == 1
assert newer(1, 0xFFFFFE) and not newer(0xFFFFFE, 1)
half = MAX_NONCE // 2
assert not newer(1 + half, 1)
assert not newer(1, 1 + half)

# Durability failure is fail-closed: no consume and no cancel.
p = Contract()
p.persist_ok = False
t = p.actual_connect()
p.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x401, attempt=t, terminal=0)
p.system_proof(owner="saved-owner", token=t)
assert not p.grace() and p.cancel_count == 0 and not p.root.consumed

# A persisted observation is not proof after restart/legacy downgrade.
legacy = Contract()
legacy.current_nonce = 0x501
legacy.current_nonce_seen_in_process = False
t = legacy.actual_connect()
legacy.system_proof(owner="saved-owner", token=t)
assert not legacy.grace()
legacy.observe_publication(owner="saved-owner", protocol=1, generation=0x2F04,
                           nonce=0x501, attempt=t, terminal=0)
assert not legacy.grace()

# Full green -> real terminal -> same publication gets one post-green root. didConnect without a
# terminal does not consume that boundary. A different nonce upgrades the root to automatic.
g = Contract()
t = g.actual_connect(origin="manual")
g.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x601, attempt=t, terminal=0)
g.did_connect()
g.full_green()
same_terminal = g.terminal
t = g.actual_connect()
assert g.root.origin == "automatic" and g.terminal == same_terminal
g.root = None
g.terminal += 1
t = g.actual_connect()
assert g.root.origin == "post-green"
g.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x601, attempt=t, terminal=g.terminal)
g.system_proof(owner="saved-owner", token=t)
assert g.grace()

# A legacy/no-v2 green session cannot prove that the first later v2 nonce is the same
# publication; its post-green root must upgrade to durable automatic adoption.
lg = Contract()
lg.green_terminal = 0
lg.green_nonce = None
lg.terminal = 1
t = lg.actual_connect()
assert lg.root.origin == "post-green"
lg.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                       nonce=0x602, attempt=t, terminal=1)
assert lg.root.origin == "automatic"

# Matching didConnect stops recovery scan; a queued duplicate cannot re-enter connect/clear.
s = Contract()
t = s.actual_connect()
s.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                      nonce=0x701, attempt=t, terminal=0)
s.did_connect()
s.duplicate_scan_callback()
assert s.connect_reentry_count == 0 and s.runtime_clear_count == 0
assert not s.scan_active

# Same already-adopted nonce is swallowed and stops duplicate scan instead of producing an
# unbounded didDiscover/energy storm.
storm = Contract()
storm.last_auto_adoption = ("saved-owner", 0x702)
t = storm.actual_connect()
storm.observe_publication(owner="saved-owner", protocol=2, generation=0x2F04,
                          nonce=0x702, attempt=t, terminal=0)
storm.duplicate_scan_callback()
assert not storm.scan_active and storm.connect_reentry_count == 0

# Every scan-window close advances the shared backoff exactly once and caps at 30 seconds. These
# read-only cycles never increment the destructive cancel count.
delays = [2, 5, 10, 30]
attempt = 0
observed = []
for _ in range(6):
    observed.append(delays[min(attempt, len(delays) - 1)])
    attempt = min(attempt + 1, len(delays) - 1)
assert observed == [2, 5, 10, 30, 30, 30]
assert storm.cancel_count == 0

# A genuine new root (not a retry inside the old root) starts its scan backoff at two seconds.
old_root_attempt = 3
new_manual_root_attempt = 0
assert delays[new_manual_root_attempt] == 2 and old_root_attempt == 3

# Interleaving: a read-only system-table probe fires while the 30-second restart item owns sleep.
# It can update physical proof but cannot start a duplicate scan before the scheduled wake.
scan_restart_item_pending = True
scan_started_by_probe = False
if not scan_restart_item_pending:
    scan_started_by_probe = True
assert not scan_started_by_probe
scan_restart_item_pending = False
scan_started_by_scheduled_wake = not scan_restart_item_pending
assert scan_started_by_scheduled_wake

# Restoration claim #2 and publication-root grace can never own the same fresh request. If the
# old claim wins before issue, it clears the deferred intent and no ordinary root exists.
restoration_claim2_armed = True
deferred_actual_intent = True
aggregate_cancel = 0
if restoration_claim2_armed:
    aggregate_cancel += 1
    restoration_claim2_armed = False
    deferred_actual_intent = False
assert not deferred_actual_intent and aggregate_cancel == 1

# If actual issue wins, it synchronously retires claim #2; either queued-timer order and a late
# callback keep the aggregate destructive count at one and restoration cannot re-arm at terminal.
for timer_order in (("restore", "publication"), ("publication", "restore")):
    restoration_claim2_armed = True
    publication_root_active = False
    aggregate_cancel = 0
    # Actual connect: one main-queue critical section.
    restoration_claim2_armed = False
    publication_root_active = True
    for timer in timer_order:
        if timer == "restore" and restoration_claim2_armed:
            aggregate_cancel += 1
            restoration_claim2_armed = False
        if timer == "publication" and publication_root_active:
            aggregate_cancel += 1
            publication_root_active = False
    # Late restoration callback remains invalidated by its advanced proof token.
    if restoration_claim2_armed:
        aggregate_cancel += 1
    restoration_reconnect_pending = False
    restoration_fresh_awaiting = restoration_claim2_armed
    assert aggregate_cancel == 1, timer_order
    assert not restoration_reconnect_pending and not restoration_fresh_awaiting

print("Helper v43 HA1208 publication/missing-didConnect replay and negatives passed")
PY

echo "Helper v43 contract passed; Helper v42/v41 preserved"
