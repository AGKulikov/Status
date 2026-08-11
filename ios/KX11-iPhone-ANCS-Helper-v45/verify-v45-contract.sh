#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IOS_ROOT=$(CDPATH= cd -- "$ROOT/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$IOS_ROOT/.." && pwd)
PREVIOUS="$IOS_ROOT/KX11-iPhone-ANCS-Helper-v44"
SOURCE="$ROOT/KX11ANCSHelper/ViewController.swift"
PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"
README="$ROOT/README.md"
RELEASE="$ROOT/RELEASE.txt"
WORKFLOW="$REPO_ROOT/.github/workflows/verify-helper-v45.yml"

require_file() {
    file=$1
    marker=$2
    grep -Fq "$marker" "$file" || {
        echo "missing v45 contract marker in $file: $marker" >&2
        exit 1
    }
}

# The immutable predecessor verifier recursively executes v43, v42 and v41. v45 adds a paired
# protocol; it does not weaken or replace any recovery/adoption contract below it.
sh "$PREVIOUS/verify-v44-contract.sh"

[ -x "$ROOT/verify-v45-contract.sh" ]
[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 45;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 45.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]

require_file "$SOURCE" 'titleLabel.text = "KX11 ANCS v45 · HA1211"'
require_file "$SOURCE" 'v45 HA1211: P/Q → B3 → READY → L/Q → A/Q; exact-tuple Q is durable'
require_file "$SOURCE" 'private let centralPairChallengeOpcode: UInt8 = 0x50'
require_file "$SOURCE" 'private let centralLinkBoundOpcode: UInt8 = 0x4C'
require_file "$SOURCE" 'private let centralAncsSubscribedOpcode: UInt8 = 0x41'
require_file "$SOURCE" 'private let centralProofFrameLength = 17'
require_file "$SOURCE" '"ru.natro.kx11ancshelper.peripheral.stable"'
require_file "$SOURCE" '"ru.natro.kx11ancshelper.central.stable"'
require_file "$SOURCE" '"KX11ANCSHelper.lastAutomaticPublicationAdoption.v44"'

require_file "$README" 'Helper v45 is paired exactly with Status Widget `v2.8.2-ha1211`.'
require_file "$README" '`[0x50][Q]` (`P/Q`)'
require_file "$README" '`[0x4C][Q]` (`L/Q`)'
require_file "$README" '`[0x41][Q]` (`A/Q`)'
require_file "$README" 'set + synchronize + strict reread'
require_file "$RELEASE" 'KX11 ANCS Helper v45'
require_file "$RELEASE" 'Build: 45'
require_file "$RELEASE" 'Marketing version: 45.0'
require_file "$RELEASE" 'Matched Android build: Status Widget v2.8.2-ha1211'
require_file "$WORKFLOW" 'Verify iPhone Helper v45 · HA1211'
require_file "$WORKFLOW" 'sh ios/KX11-iPhone-ANCS-Helper-v45/verify-v45-contract.sh'

python3 - "$PREVIOUS" "$ROOT" "$WORKFLOW" <<'PY'
from __future__ import annotations

from dataclasses import dataclass, replace
import pathlib
import re
import sys

previous = pathlib.Path(sys.argv[1])
current = pathlib.Path(sys.argv[2])
workflow = pathlib.Path(sys.argv[3])
source_path = current / "KX11ANCSHelper/ViewController.swift"
source = source_path.read_text(encoding="utf-8")
old_source = (previous / "KX11ANCSHelper/ViewController.swift").read_text(encoding="utf-8")


def require(text: str, marker: str, label: str = "source") -> None:
    assert marker in text, f"missing {label} marker: {marker}"


def ordered(text: str, *markers: str) -> None:
    cursor = -1
    for marker in markers:
        position = text.find(marker, cursor + 1)
        assert position >= 0, f"missing ordered marker: {marker}"
        assert position > cursor, f"out-of-order marker: {marker}"
        cursor = position


def block(signature: str) -> str:
    start = source.find(signature)
    assert start >= 0, f"missing block signature: {signature}"
    opening = source.find("{", start)
    assert opening >= 0
    depth = 0
    index = opening
    state = "code"
    while index < len(source):
        char = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if char == "/" and nxt == "/":
                state = "line"
                index += 2
                continue
            if char == "/" and nxt == "*":
                state = "comment"
                index += 2
                continue
            if source.startswith('"""', index):
                state = "multistring"
                index += 3
                continue
            if char == '"':
                state = "string"
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return source[start : index + 1]
        elif state == "line":
            if char == "\n":
                state = "code"
        elif state == "comment":
            if char == "*" and nxt == "/":
                state = "code"
                index += 2
                continue
        elif state == "string":
            if char == "\\":
                index += 2
                continue
            if char == '"':
                state = "code"
        elif state == "multistring":
            if source.startswith('"""', index):
                state = "code"
                index += 3
                continue
        index += 1
    raise AssertionError(f"unterminated block: {signature}")


# Basic Swift lexical delimiter check (imports need Xcode, unavailable in the direct verifier).
stack: list[tuple[str, int]] = []
pairs = {")": "(", "]": "[", "}": "{"}
state = "code"
line = 1
index = 0
while index < len(source):
    char = source[index]
    nxt = source[index + 1] if index + 1 < len(source) else ""
    if char == "\n":
        line += 1
    if state == "code":
        if char == "/" and nxt == "/":
            state = "line"
            index += 2
            continue
        if char == "/" and nxt == "*":
            state = "comment"
            index += 2
            continue
        if source.startswith('"""', index):
            state = "multistring"
            index += 3
            continue
        if char == '"':
            state = "string"
        elif char in "([{":
            stack.append((char, line))
        elif char in ")]}":
            assert stack and stack[-1][0] == pairs[char], (
                f"delimiter mismatch at line {line}: {char}, stack={stack[-4:]}"
            )
            stack.pop()
    elif state == "line":
        if char == "\n":
            state = "code"
    elif state == "comment":
        if char == "*" and nxt == "/":
            state = "code"
            index += 2
            continue
    elif state == "string":
        if char == "\\":
            index += 2
            continue
        if char == '"':
            state = "code"
    elif state == "multistring":
        if source.startswith('"""', index):
            state = "code"
            index += 3
            continue
    index += 1
assert state == "code" and not stack, f"unterminated Swift lexical state={state}, stack={stack}"


# Frozen files and in-place update identity.
for relative in ("KX11ANCSHelper/AppDelegate.swift", "KX11ANCSHelper/Info.plist"):
    assert (current / relative).read_bytes() == (previous / relative).read_bytes(), relative

old_project = (previous / "KX11ANCSHelper.xcodeproj/project.pbxproj").read_text()
new_project = (current / "KX11ANCSHelper.xcodeproj/project.pbxproj").read_text()
expected_project = old_project.replace(
    "CURRENT_PROJECT_VERSION = 44;", "CURRENT_PROJECT_VERSION = 45;"
).replace("MARKETING_VERSION = 44.0;", "MARKETING_VERSION = 45.0;")
assert new_project == expected_project, "project delta exceeds exact 44→45 version bump"

expected_files = {
    "KX11ANCSHelper.xcodeproj/project.pbxproj",
    "KX11ANCSHelper/AppDelegate.swift",
    "KX11ANCSHelper/Info.plist",
    "KX11ANCSHelper/ViewController.swift",
    "README.md",
    "RELEASE.txt",
    "verify-v45-contract.sh",
}
actual_files = {
    str(path.relative_to(current)) for path in current.rglob("*") if path.is_file()
}
assert actual_files == expected_files, (
    f"unexpected v45 file set: missing={expected_files-actual_files}, "
    f"extra={actual_files-expected_files}"
)


# Exact binary frames and cryptographic/durable generation.
for marker in (
    "private let centralPairChallengeOpcode: UInt8 = 0x50",
    "private let centralLinkBoundOpcode: UInt8 = 0x4C",
    "private let centralAncsSubscribedOpcode: UInt8 = 0x41",
    "private let centralPairChallengeLength = 16",
    "private let centralProofFrameLength = 17",
    "import Security",
):
    require(source, marker)

durable = block("private func durableCentralPairChallenge(")
ordered(
    durable,
    "SecRandomCopyBytes(kSecRandomDefault, centralPairChallengeLength, baseAddress)",
    "defaults.set(record, forKey: centralPairChallengePreference)",
    "let synchronized = defaults.synchronize()",
    "let verified = storedCentralPairChallengeRecord(defaults)",
    "guard synchronized,",
    "constantTimeChallengeMatches(verified.challenge, challenge)",
    "return verified.challenge",
)
require(durable, "previousRecord != nil")
require(durable, "malformed; fail closed without B2 write")
assert "writeValue(" not in durable

stored = block("private func storedCentralPairChallengeRecord(")
for marker in (
    "fields.count == 3",
    "fields[1].count == 6",
    "recordedNonce > 0, recordedNonce < 0x00FF_FFFF",
    "recordedChallenge.count == centralPairChallengeLength",
):
    require(stored, marker)

constant_time = block("private func constantTimeChallengeMatches(")
require(constant_time, "for index in 0..<centralPairChallengeLength")
require(constant_time, "difference |= leftBytes[index] ^ rightBytes[index]")
assert "== right" not in constant_time and ".elementsEqual" not in constant_time

write_pair = block("private func writeCentralPair(")
ordered(
    write_pair,
    "let challenge = ensureCentralPairChallenge(peripheral)",
    "var frame = Data([centralPairChallengeOpcode])",
    "frame.append(challenge)",
    "frame.count == centralProofFrameLength",
    "frame.count <= 20",
    "peripheral.writeValue(frame, for: control, type: .withResponse)",
)
assert 'data(using: .utf8)' not in write_pair
assert '"PAIR"' not in write_pair
assert "\\(challenge" not in source and "append(challengeHex" not in source

proof_decoder = block("private func centralProofChallenge(")
ordered(
    proof_decoder,
    "value.count == centralProofFrameLength",
    "value.first == opcode",
    "Data(value.dropFirst())",
    "challenge.count == centralPairChallengeLength",
)


# LINK-BOUND accepts only the exact current owner/transcript and mutates alias lineage alone.
request_gate = block("private func centralProofRequestIsCurrent(")
for marker in (
    "request.offset == 0",
    "owner.state == .connected",
    "centralOwnerConfiguredForAncs",
    "request.central.identifier == owner.identifier",
    "telemetrySubscribers.contains(owner.identifier)",
    "request.characteristic === relayLineage.characteristic",
    "centralPairTranscriptMatches(owner)",
    "constantTimeChallengeMatches(currentChallenge, challenge)",
    "centralSecureLinkReady",
    "centralHandshake == .ready",
    "centralAncsReadyWriteIssued",
    "centralHelperConfirmed",
    "centralB4Subscribed",
):
    require(request_gate, marker)

bind_alias = block("private func bindCentralAlias(")
for marker in (
    "centralAliasBound = true",
    "centralAliasBoundChallenge = currentChallenge",
    "centralAliasBoundOwnerID = peripheral.identifier",
    "centralAliasBoundService = centralPairChallengeService",
    "centralAliasBoundRelayGeneration = centralPairChallengeRelayGeneration",
):
    require(bind_alias, marker)
for forbidden in (
    "confirmCentralAncsReady",
    "refreshCentralReadiness",
    "rearmCentralDestructiveRecovery",
    "cancelPeripheralConnection",
    "centralManager.connect",
    "writeCentralPair",
    "centralAncsAccessProven = true",
    "centralAncsCccdConfirmed = true",
):
    assert forbidden not in bind_alias, f"alias bind has forbidden side effect: {forbidden}"

receive = block("func peripheralManager(_ peripheral: CBPeripheralManager,\n                           didReceiveWrite requests: [CBATTRequest])")
legacy_marker = "// Legacy ASCII commands are diagnostic/bootstrap compatibility"
managed, legacy = receive.split(legacy_marker, 1)
for marker in (
    "opcode: centralLinkBoundOpcode",
    "opcode: centralAncsSubscribedOpcode",
    "centralProofRequestIsCurrent(request, challenge: challenge)",
    "!centralAncsAccessProven",
    "!centralAncsCccdConfirmed",
    "bindCentralAlias(owner, challenge: challenge)",
    "centralAliasBindingMatches(owner, challenge: challenge)",
    "confirmCentralAncsReady(\"F05/B4 A/Q after exact LINK-BOUND\")",
):
    require(managed, marker)
assert 'command == "PAIR"' not in managed and 'command == "ANCS"' not in managed
require(legacy, 'role == .peripheral')
require(legacy, 'command == "PAIR"')
require(legacy, 'command == "ANCS"')

confirm = block("private func confirmCentralAncsReady(")
ordered(
    confirm,
    "let challenge = centralPairChallenge",
    "centralAliasBindingMatches(peripheral, challenge: challenge)",
    "centralAncsCccdConfirmed = true",
    "centralAncsAccessProven = true",
)
ready = block("private func centralReadyForGreen()")
require(ready, "centralAliasBindingMatches(")
require(ready, "&& exactAliasBound")


# Restoration is load-only for the exact willRestore wrapper and first exact F05 generation.
current_nonce = block("private func currentCentralTranscriptPublicationNonce(")
assert "rehydrateCentralPairPublicationIfEligible" not in current_nonce
ensure = block("private func ensureCentralPairChallenge(")
ordered(
    ensure,
    "clearCentralPairTranscript(reason: \"new exact owner/F04 publication transcript\")",
    "currentCentralTranscriptPublicationNonce(peripheral)",
    "?? rehydrateCentralPairPublicationIfEligible(peripheral)",
    "durableCentralPairChallenge(",
    "centralPairChallenge = challenge",
)

rehydrate = block("private func rehydrateCentralPairPublicationIfEligible(")
for marker in (
    "centralPairRestorationCapabilityGeneration",
    "restoredOwner === peripheral",
    "peripheral.state == .connected",
    "service !==",  # absent by design; checked below with exact invalidated-object predicate
):
    if marker == "service !==":
        continue
    require(rehydrate, marker)
for marker in (
    "peripheral.services?.contains(where: { $0 === service }) == true",
    "characteristics.contains(where: { $0 === control })",
    "characteristics.contains(where: { $0 === secure })",
    "characteristics.contains(where: { $0 === wake })",
    "centralPairRestorationRelayCharacteristic",
    "centralPairRestorationRelayGeneration == relayLineage.generation",
    "durable.ownerID == peripheral.identifier",
    "centralLastObservedPublicationNonce == durable.publicationNonce",
    "centralPairRestorationCapabilityGeneration = nil",
    "clearCentralAliasBinding()",
):
    require(rehydrate, marker)
for forbidden in (
    "SecRandomCopyBytes",
    "defaults.set(",
    "cancelPeripheralConnection",
    "centralManager.connect",
):
    assert forbidden not in rehydrate, f"rehydrate has forbidden mutation: {forbidden}"
assert re.search(r"centralLastObservedPublicationNonce\s*=(?!=)", rehydrate) is None
assert re.search(r"centralDeferredReclaimConsumed\s*=(?!=)", rehydrate) is None

will_restore = block("func centralManager(_ central: CBCentralManager,\n                        willRestoreState dict: [String: Any])")
require(will_restore, "restored.state == .connected || restored.state == .connecting")
require(will_restore, "issueCentralPairRestorationCapability(restored)")

issue_cap = block("private func issueCentralPairRestorationCapability(")
ordered(
    issue_cap,
    "centralPairRestorationGeneration &+= 1",
    "centralPairRestorationOwner = peripheral",
    "bindFirstCentralPairRestorationRelayIfEligible()",
)
bind_relay = block("private func bindFirstCentralPairRestorationRelayIfEligible()")
for marker in (
    "centralPairRestorationCapabilityGeneration",
    "centralPairRestorationRelayCharacteristic == nil",
    "exactCurrentRelayLineage()",
    "centralPairRestorationRelayCharacteristic = relayLineage.characteristic",
    "centralPairRestorationRelayGeneration = relayLineage.generation",
    "centralPairRestorationRelaySubscriberIDs = telemetrySubscribers",
):
    require(bind_relay, marker)

pm_restore = block("func peripheralManager(_ peripheral: CBPeripheralManager,\n                           willRestoreState dict: [String: Any])")
ordered(
    pm_restore,
    "centralPairPeripheralRestoreCallbackObserved = true",
    "centralPairRestorationRelayProvisional",
    "clearProvisionalCentralPairRelayBinding()",
    "preserveCentralPairRestorationCapability:",
    "bindFirstCentralPairRestorationRelayIfEligible()",
)

clear_f05 = block("private func clearPublishedService(")
for marker in (
    "allowCentralPairRelayRebind",
    "preserveCentralPairRestorationCapability",
    "centralPairTranscriptMatches(owner)",
    "clearCentralAliasBinding()",
    "centralPairChallengeRelayCharacteristic = nil",
    "centralPairAwaitingRelayRebind = true",
):
    require(clear_f05, marker)

publish_f05 = block("private func publishServiceIfPossible()")
for marker in (
    "continueAwaitingRelayConstruction",
    "centralPairAwaitingRelayRebindHasExactPublication()",
    "no second clear and no Pair replay",
):
    require(publish_f05, marker)

awaiting_rebind = block("private func centralPairAwaitingRelayRebindHasExactPublication()")
for marker in (
    "centralPairAwaitingRelayRebind",
    "centralPairChallengeOwnerID == peripheral.identifier",
    "centralPairChallengeTerminalGeneration",
    "centralPairChallengeInvalidationGeneration",
    "storedCentralPairChallengeRecord()",
    "durable.ownerID == peripheral.identifier",
    "durable.publicationNonce == nonce",
    "constantTimeChallengeMatches(durable.challenge, challenge)",
    "freshPublication || restoredPublication",
):
    require(awaiting_rebind, marker)

rebind = block("private func rebindCurrentCentralPairRelayIfEligible()")
for marker in (
    "centralPairChallengePublicationNonce",
    "storedCentralPairChallengeRecord()",
    "durable.ownerID == peripheral.identifier",
    "durable.publicationNonce == nonce",
    "constantTimeChallengeMatches(durable.challenge, challenge)",
    "telemetrySubscribers == Set([peripheral.identifier])",
    "freshPublication || restoredPublication",
    "centralPairChallengeRelayCharacteristic = relayLineage.characteristic",
    "centralPairRehydratedRelayCharacteristic = relayLineage.characteristic",
    "centralPairRehydratedRelayGeneration = relayLineage.generation",
):
    require(rebind, marker)
for forbidden in ("writeCentralPair", "cancelPeripheralConnection", "centralManager.connect"):
    assert forbidden not in rebind, f"F05 rebind replays/destructs: {forbidden}"


# Reset boundaries and storm negatives.
unsubscribe = block("func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,\n                           didUnsubscribeFrom characteristic: CBCharacteristic)")
ordered(
    unsubscribe,
    "clearCentralPairTranscript(reason: \"current owner B4 unsubscribe\")",
    "centralB4Subscribed = false",
    "centralAncsCccdConfirmed = false",
    "centralAncsAccessProven = false",
)
for forbidden in (
    "writeCentralPair",
    "restartCentralPairHandshake",
    "cancelPeripheralConnection",
    "centralManager.connect",
    "issueCentralConnect",
    "DispatchQueue.main.asyncAfter",
    "rearmCentralDestructiveRecovery",
):
    assert forbidden not in unsubscribe, f"unsubscribe storm primitive: {forbidden}"

did_add = block("func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService,")
assert "restartCentralPairHandshake" not in did_add
require(did_add, "resumeCentralPairAfterFirstRelayPublication(owner)")
require(did_add, "rebindCurrentCentralPairRelayIfEligible()")

for signature, marker in (
    ("private func stopCentralRoute(cancelConnection: Bool)",
     "clearCentralPairRestorationCapability()"),
    ("private func issueCentralConnect(_ peripheral: CBPeripheral, reason: String)",
     "clearCentralPairTranscript(reason: \"app-issued RequiresANCS connect\")"),
    ("private func recordCentralDeferredReclaimTerminalBoundary()",
     "clearCentralPairTranscript(reason: \"exact Central terminal boundary\")"),
    ("func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService])",
     "clearCentralPairRestorationCapability()"),
):
    require(block(signature), marker, signature)

central_state = block("func centralManagerDidUpdateState(_ central: CBCentralManager)")
ordered(
    central_state,
    "guard central.state == .poweredOn else",
    "clearCentralPairRestorationCapability()",
    "clearCentralPairTranscript(reason: \"Central radio unavailable\")",
)
peripheral_state = block("func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager)")
require(peripheral_state, "clearPublishedService(allowCentralPairRelayRebind: false)")
stop_service = block("private func stopService()")
require(stop_service, "clearPublishedService(allowCentralPairRelayRebind: false)")
role_change = block("@objc private func roleChanged()")
require(role_change, "stopAllBleRoutes()")
manual_reset = block("@objc private func resetTapped()")
require(manual_reset, "clearCentralPairTranscript(reason: \"explicit manual reconnect\")")
connect_owner = block("private func connectCentral(_ peripheral: CBPeripheral, reason: String)")
require(connect_owner, "clearCentralRuntime(keepPeripheral: true)")
did_add_hard_errors = block(
    "func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService,"
)
assert did_add_hard_errors.count(
    "clearPublishedService(allowCentralPairRelayRebind: false)"
) == 2

did_fail = block("func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,")
ordered(
    did_fail,
    "consumeCentralRestorationPublicationPostReopenTerminal(",
    "recordCentralDeferredReclaimTerminalBoundary()",
)
disconnect = block("private func handleCentralDisconnect(_ peripheral: CBPeripheral, isReconnecting: Bool,")
ordered(
    disconnect,
    "consumeCentralRestorationPublicationPostReopenTerminal(",
    "recordCentralDeferredReclaimTerminalBoundary()",
)

# v45 proof code adds no destructive/retry/timer primitive relative to frozen v44.
for primitive in (
    "manager.cancelPeripheralConnection(",
    "centralManager.connect(",
    "DispatchQueue.main.asyncAfter(",
    "resetCentralLink(",
    "scheduleCentralReconnect(",
    "issueCentralConnect(",
):
    assert source.count(primitive) == old_source.count(primitive), (
        primitive, old_source.count(primitive), source.count(primitive)
    )


# Executable protocol replay model: persistence/crash, frame gates, reset and restoration orders.
Q1 = bytes(range(16))
Q2 = bytes(range(16, 32))


@dataclass
class Record:
    owner: str
    nonce: int
    q: bytes


class Store:
    def __init__(self, record: Record | str | None = None) -> None:
        self.record = record
        self.persist_attempts = 0

    def get(self, owner: str, nonce: int, candidate: bytes, persist_ok: bool = True):
        if self.record is not None:
            if not isinstance(self.record, Record) or len(self.record.q) != 16:
                return None
            if (self.record.owner, self.record.nonce) == (owner, nonce):
                return self.record.q
        self.persist_attempts += 1
        if not persist_ok:
            return None
        written = Record(owner, nonce, candidate)
        self.record = written
        reread = self.record
        if not isinstance(reread, Record):
            return None
        if (reread.owner, reread.nonce, reread.q) != (owner, nonce, candidate):
            return None
        return reread.q


store = Store()
assert store.get("owner-A", 7, Q1) == Q1 and store.persist_attempts == 1
# Crash after P response: a fresh process sees the same single record and must emit the same Q.
restored_store = Store(store.record)
assert restored_store.get("owner-A", 7, Q2) == Q1
assert restored_store.persist_attempts == 0
# Proven owner/nonce change cannot reuse Q.
assert restored_store.get("owner-A", 8, Q2) == Q2
assert restored_store.get("owner-B", 8, Q1) == Q1
assert Store("malformed").get("owner-A", 7, Q2) is None
failed_store = Store()
pair_writes: list[bytes] = []
failed_q = failed_store.get("owner-A", 7, Q1, persist_ok=False)
if failed_q is not None:
    pair_writes.append(bytes([0x50]) + failed_q)
assert pair_writes == []


@dataclass(frozen=True)
class Gates:
    offset: int = 0
    length: int = 17
    opcode: int = 0x4C
    owner: bool = True
    subscriber: bool = True
    relay: bool = True
    publication: bool = True
    b3: bool = True
    ready: bool = True
    ready_write: bool = True
    helper: bool = True
    b4: bool = True
    q: bool = True


def link_accept(g: Gates, alias: bool, ancs: bool) -> tuple[bool, bool, bool]:
    valid = all((
        g.offset == 0, g.length == 17, g.opcode == 0x4C, g.owner, g.subscriber,
        g.relay, g.publication, g.b3, g.ready, g.ready_write, g.helper, g.b4, g.q,
        not ancs,
    ))
    if not valid:
        return False, alias, ancs
    return True, True, ancs


base = Gates()
accepted, alias, ancs = link_accept(base, False, False)
assert (accepted, alias, ancs) == (True, True, False)
# Same-lineage duplicate L/Q is idempotent before A/Q.
assert link_accept(base, alias, ancs) == (True, True, False)
for field, value in (
    ("offset", 1), ("length", 16), ("opcode", 0x41), ("owner", False),
    ("subscriber", False), ("relay", False), ("publication", False),
    ("b3", False), ("ready", False), ("ready_write", False),
    ("helper", False), ("b4", False), ("q", False),
):
    rejected = replace(base, **{field: value})
    assert link_accept(rejected, False, False) == (False, False, False), field


def ancs_accept(alias_bound: bool, gates: Gates) -> bool:
    return alias_bound and all((
        gates.offset == 0, gates.length == 17, gates.opcode == 0x41, gates.owner,
        gates.subscriber, gates.relay, gates.publication, gates.b3, gates.ready,
        gates.ready_write, gates.helper, gates.b4, gates.q,
    ))


a_gates = replace(base, opcode=0x41)
assert not ancs_accept(False, a_gates)
assert ancs_accept(True, a_gates)
assert link_accept(base, True, True)[0] is False

for opcode in (0x50, 0x4C, 0x41):
    frame = bytes([opcode]) + Q1
    assert len(frame) == 17 and len(frame) <= 20


class RestoreEpisode:
    def __init__(self, durable_q: bytes) -> None:
        self.cap = False
        self.relay = None
        self.provisional = False
        self.q = durable_q
        self.p_count = 0
        self.alias = False

    def central_restore(self) -> None:
        self.cap = True
        if self.relay is not None:
            self.provisional = True

    def f05(self, generation: int, peripheral_restore: bool = False) -> None:
        if peripheral_restore and self.cap and self.provisional:
            self.relay = None
            self.provisional = False
        if self.cap and self.relay is None:
            self.relay = generation
            self.provisional = not peripheral_restore
        elif self.relay is None:
            self.relay = generation

    def pair(self) -> bytes:
        assert self.cap and self.relay is not None
        self.cap = False
        self.alias = False
        self.p_count += 1
        return bytes([0x50]) + self.q

    def replace_f05_after_pair(self, generation: int) -> None:
        self.relay = generation
        self.alias = False
        # P/B3/READY is not replayed.

    def peripheral_restore_without_f05_after_pair(self) -> None:
        # First clear retains Q/F04 and marks one awaiting construction. The subsequent fresh
        # construction must not perform a second clear or emit another P.
        self.relay = None
        self.alias = False


# Central restore → first fresh/provisional F05 → P → later Peripheral-restored F05.
episode_a = RestoreEpisode(Q1)
episode_a.central_restore()
episode_a.f05(1, peripheral_restore=False)
assert episode_a.pair() == bytes([0x50]) + Q1
episode_a.peripheral_restore_without_f05_after_pair()
assert episode_a.q == Q1 and episode_a.p_count == 1 and not episode_a.alias
episode_a.replace_f05_after_pair(2)
assert episode_a.q == Q1 and episode_a.p_count == 1 and not episode_a.alias
# Peripheral-restored F05 → Central restore.
episode_b = RestoreEpisode(Q1)
episode_b.f05(9, peripheral_restore=True)
episode_b.central_restore()
assert episode_b.pair() == bytes([0x50]) + Q1
assert episode_b.p_count == 1 and not episode_b.alias
# No F05 restoration: the first fresh didAdd is a valid one-shot baseline.
episode_c = RestoreEpisode(Q1)
episode_c.central_restore()
episode_c.f05(11)
assert episode_c.pair() == bytes([0x50]) + Q1
assert episode_c.p_count == 1

workflow_text = workflow.read_text(encoding="utf-8")
assert "Compile Helper v45" in workflow_text
assert "xcodebuild" in workflow_text
assert "verify-v45-contract.sh" in workflow_text

print("Helper v45 HA1211 durable P/L/A-Q and preserved v44→v41 contracts passed")
PY
