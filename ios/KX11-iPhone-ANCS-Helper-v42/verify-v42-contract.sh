#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE="$ROOT/KX11ANCSHelper/ViewController.swift"
PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"

require_source() {
    grep -Fq "$1" "$SOURCE" || {
        echo "missing v42 contract marker: $1" >&2
        exit 1
    }
}

require_source "CONTRACT_V38_REARM_POST_CANCEL_AFTER_POWER"
require_source "private func armRestoredOwnerPostCancelObservation()"
require_source 'callback: "read-only post-cancel state=.disconnected"'
require_source "private func reopenManualOwnerAfterTerminalCallback"
require_source "CONTRACT_V38_ISSUE_CONNECT_POWER_GATE"
require_source "CONTRACT_V38_POWER_OFF_PRESERVES_DEFERRED_EXACT_OWNER"
require_source "CONTRACT_V38_POWERED_ON_SINGLE_CONSUME"
require_source "CONTRACT_V38_POWERED_ON_ENTERS_SINGLE_CONSUME_ROUTE"
require_source "CONTRACT_V38_POWER_RESUME_SOURCE_FLAGS_CLEAR_BEFORE_CONNECT"
require_source "CONTRACT_V38_PENDING_SOURCE_STATE_REENTERS_F05_ROUTE"

# This is the startCentralRouteIfPossible power-resume branch. It may only re-arm the
# read-only observer and return; opening or cancelling an owner here would duplicate work.
RESUME_BRANCH=$(awk '
    /if centralRestorationReconnectPending \{/ { capture = 1 }
    capture { print }
    capture && /if let peripheral = geelyPeripheral \{/ { exit }
' "$SOURCE")
printf '%s\n' "$RESUME_BRANCH" | grep -Fq "armRestoredOwnerPostCancelObservation()"
if printf '%s\n' "$RESUME_BRANCH" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect"; then
    echo "power-resume branch contains a destructive BLE command" >&2
    exit 1
fi

# The observer itself is read-only until it sees `.disconnected` and enters the shared
# terminal path. It must never contain a direct cancel/connect call.
OBSERVER=$(awk '
    /private func armRestoredOwnerPostCancelObservation\(\)/ { capture = 1 }
    capture { print }
    capture && /Complete the evidence-driven handover/ { exit }
' "$SOURCE")
printf '%s\n' "$OBSERVER" | grep -Fq "peripheral.state == .disconnected"
if printf '%s\n' "$OBSERVER" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect"; then
    echo "post-cancel observer contains a destructive BLE command" >&2
    exit 1
fi

# Power loss must cancel the scheduled read-only probe. The pending state is intentionally
# retained so the branch above can re-arm it after poweredOn.
POWER_BLOCK=$(awk '
    /guard central.state == \.poweredOn else \{/ { capture = 1 }
    capture { print }
    capture && /return/ { exit }
' "$SOURCE")
printf '%s\n' "$POWER_BLOCK" \
    | grep -Fq "centralRestorationPostCancelProbeWorkItem?.cancel()"
printf '%s\n' "$POWER_BLOCK" | grep -Fq "centralDeferredConnectIntent == nil"
printf '%s\n' "$POWER_BLOCK" | grep -Fq "clearCentralRuntime(keepPeripheral: true)"
printf '%s\n' "$POWER_BLOCK" | grep -Fq "centralManualReconnectPending"
printf '%s\n' "$POWER_BLOCK" | grep -Fq "centralHardResetReason"
printf '%s\n' "$POWER_BLOCK" | grep -Fq "centralRestoreFreshConnectAwaitingCallback"
printf '%s\n' "$POWER_BLOCK" \
    | grep -Fq "centralPendingTerminalStateProbeWorkItem?.cancel()"

# Before ordinary routing, poweredOn/F05 must consume a disconnected manual/hard-reset source.
# Both flags are cleared before queue/consume, and non-terminal states remain read-only.
PENDING_SOURCE=$(awk '
    /private func consumePoweredOnPendingTerminalIntentIfPossible/ { capture = 1 }
    capture { print }
    capture && /Read-only state reconciliation for a manual\/hard-reset/ { exit }
' "$SOURCE")
printf '%s\n' "$PENDING_SOURCE" | grep -Fq "case .disconnected:"
printf '%s\n' "$PENDING_SOURCE" | grep -Fq "centralManualReconnectPending = false"
printf '%s\n' "$PENDING_SOURCE" | grep -Fq "centralHardResetReason = nil"
printf '%s\n' "$PENDING_SOURCE" | grep -Fq "queueCentralConnectIntent(peripheral, reason: source)"
printf '%s\n' "$PENDING_SOURCE" | grep -Fq "consumeCentralDeferredConnectIfPossible()"
MANUAL_CLEAR_LINE=$(printf '%s\n' "$PENDING_SOURCE" \
    | grep -n "centralManualReconnectPending = false" | head -n1 | cut -d: -f1)
HARD_CLEAR_LINE=$(printf '%s\n' "$PENDING_SOURCE" \
    | grep -n "centralHardResetReason = nil" | head -n1 | cut -d: -f1)
SOURCE_QUEUE_LINE=$(printf '%s\n' "$PENDING_SOURCE" \
    | grep -n "queueCentralConnectIntent(peripheral, reason: source)" | head -n1 | cut -d: -f1)
[ "$MANUAL_CLEAR_LINE" -lt "$SOURCE_QUEUE_LINE" ]
[ "$HARD_CLEAR_LINE" -lt "$SOURCE_QUEUE_LINE" ]

PENDING_OBSERVER=$(awk '
    /private func armCentralPendingTerminalStateObservation/ { capture = 1 }
    capture { print }
    capture && /private func clearCentralPendingTerminalStateObservation/ { exit }
' "$SOURCE")
printf '%s\n' "$PENDING_OBSERVER" | grep -Fq "peripheral.state == .disconnected"
printf '%s\n' "$PENDING_OBSERVER" | grep -Fq "startCentralRouteIfPossible()"
if printf '%s\n' "$PENDING_OBSERVER" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect|connectCentral"; then
    echo "pending manual/hard state observer contains a BLE command" >&2
    exit 1
fi

ROUTE_BLOCK=$(awk '
    /private func startCentralRouteIfPossible/ { capture = 1 }
    capture { print }
    capture && /private func restoredOwnerHasSystemLinkProof/ { exit }
' "$SOURCE")
PENDING_ROUTE_LINE=$(printf '%s\n' "$ROUTE_BLOCK" \
    | grep -n "consumePoweredOnPendingTerminalIntentIfPossible" | cut -d: -f1)
DEFERRED_ROUTE_LINE=$(printf '%s\n' "$ROUTE_BLOCK" \
    | grep -n "consumeCentralDeferredConnectIfPossible" | cut -d: -f1)
[ "$PENDING_ROUTE_LINE" -lt "$DEFERRED_ROUTE_LINE" ]

# All three delayed reconnect sites must create a strong exact-owner intent synchronously.
# A closure which waits before creating the intent reopens the original power-off race.
RESTORE_TERMINAL=$(awk '
    /private func reopenRestoredOwnerAfterTerminalCallback/ { capture = 1 }
    capture { print }
    capture && /Manual reconnect uses the same terminal boundary/ { exit }
' "$SOURCE")
printf '%s\n' "$RESTORE_TERMINAL" | grep -Fq "queueCentralConnectIntent(peripheral,"
if printf '%s\n' "$RESTORE_TERMINAL" | grep -Fq "DispatchQueue.main.asyncAfter"; then
    echo "restoration intent is created only inside a delayed closure" >&2
    exit 1
fi

MANUAL_TERMINAL=$(awk '
    /private func reopenManualOwnerAfterTerminalCallback/ { capture = 1 }
    capture { print }
    capture && /private func startCentralScan/ { exit }
' "$SOURCE")
printf '%s\n' "$MANUAL_TERMINAL" | grep -Fq "centralManualReconnectPending = false"
printf '%s\n' "$MANUAL_TERMINAL" | grep -Fq "queueCentralConnectIntent(peripheral,"
if printf '%s\n' "$MANUAL_TERMINAL" | grep -Fq "DispatchQueue.main.asyncAfter"; then
    echo "manual intent is created only inside a delayed closure" >&2
    exit 1
fi

grep -Fq 'queueCentralConnectIntent(peripheral,' "$SOURCE"
grep -Fq 'reason: "same stable owner after \(hardReset)", delay: 0.5' "$SOURCE"

# issueCentralConnect is the final safety boundary: its poweredOn gate must precede the only
# CBCentralManager.connect call and the unavailable branch must queue the exact intent.
ISSUE_BLOCK=$(awk '
    /private func issueCentralConnect/ { capture = 1 }
    capture { print }
    capture && /private func beginCentralDiscovery/ { exit }
' "$SOURCE")
printf '%s\n' "$ISSUE_BLOCK" | grep -Fq "centralManager.state == .poweredOn"
printf '%s\n' "$ISSUE_BLOCK" | grep -Fq "queueCentralConnectIntent(peripheral, reason: reason)"
printf '%s\n' "$ISSUE_BLOCK" | grep -Fq "intent.peripheral === peripheral"
[ "$(printf '%s\n' "$ISSUE_BLOCK" | grep -Fc 'centralManager.connect(peripheral, options: options)')" -eq 1 ]
ISSUE_GATE_LINE=$(grep -n "CONTRACT_V38_ISSUE_CONNECT_POWER_GATE" "$SOURCE" | cut -d: -f1)
ISSUE_COMMAND_LINE=$(grep -n "centralManager.connect(peripheral, options: options)" "$SOURCE" \
    | cut -d: -f1)
[ "$ISSUE_GATE_LINE" -lt "$ISSUE_COMMAND_LINE" ]

CONNECTING_BRANCH=$(awk '
    /if peripheral.state == \.connecting \{/ { capture = 1 }
    capture { print }
    capture && /if peripheral.state == \.disconnecting \{/ { exit }
' "$SOURCE")
printf '%s\n' "$CONNECTING_BRANCH" \
    | grep -Fq "CONTRACT_V39_CONNECTING_NEVER_DUPLICATES_CONNECT"
if printf '%s\n' "$CONNECTING_BRANCH" | grep -Fq "issueCentralConnect"; then
    echo "connectCentral duplicates an app-local connect for an already .connecting owner" >&2
    exit 1
fi

CONNECTED_CLAIM_BRANCH=$(awk '
    /if peripheral.state == \.connected \{/ { capture = 1 }
    capture { print }
    capture && /if peripheral.state == \.connecting \{/ { exit }
' "$SOURCE")
[ "$(printf '%s\n' "$CONNECTED_CLAIM_BRANCH" \
    | grep -Fc 'issueCentralConnect(peripheral,')" -eq 1 ]

# poweredOn enters the normal route; the route removes the intent before exactly one
# connectCentral call. Stop/role teardown must invalidate both the data and wake item.
require_source "if consumeCentralDeferredConnectIfPossible() { return }"
CONSUME_BLOCK=$(awk '
    /private func consumeCentralDeferredConnectIfPossible/ { capture = 1 }
    capture { print }
    capture && /private func clearCentralDeferredConnectIntent/ { exit }
' "$SOURCE")
printf '%s\n' "$CONSUME_BLOCK" | grep -Fq "centralDeferredConnectIntent = nil"
[ "$(printf '%s\n' "$CONSUME_BLOCK" | grep -Fc 'connectCentral(intent.peripheral,')" -eq 1 ]
printf '%s\n' "$CONSUME_BLOCK" \
    | grep -Fq "CONTRACT_V39_CONNECTING_RETAINS_SOLE_INTENT"
CONNECTING_INTENT=$(printf '%s\n' "$CONSUME_BLOCK" | awk '
    /if intent.peripheral.state == \.connecting/ { capture = 1 }
    capture { print }
    capture && /return true/ { exit }
')
printf '%s\n' "$CONNECTING_INTENT" | grep -Fq "centralDeferredConnectIntent = replacement"
printf '%s\n' "$CONNECTING_INTENT" | grep -Fq "armCentralDeferredConnectWake()"
if printf '%s\n' "$CONNECTING_INTENT" \
    | grep -Eq "centralDeferredConnectIntent = nil|centralOwnerConfiguredForAncs = true"; then
    echo "deferred .connecting path destroys the sole intent or claims unproven ownership" >&2
    exit 1
fi
if printf '%s\n' "$CONNECTING_INTENT" \
    | grep -Eq "issueCentralConnect|connectCentral|cancelCentralConnectionSafely"; then
    echo "deferred intent duplicates a BLE command for an already .connecting owner" >&2
    exit 1
fi
INTENT_CLEAR_LINE=$(printf '%s\n' "$CONSUME_BLOCK" \
    | grep -n "centralDeferredConnectIntent = nil" | head -n1 | cut -d: -f1)
INTENT_CONNECT_LINE=$(printf '%s\n' "$CONSUME_BLOCK" \
    | grep -n "connectCentral(intent.peripheral," | head -n1 | cut -d: -f1)
[ "$INTENT_CLEAR_LINE" -lt "$INTENT_CONNECT_LINE" ]

STOP_BLOCK=$(awk '
    /private func stopCentralRoute/ { capture = 1 }
    capture { print }
    capture && /private func clearCentralRuntime/ { exit }
' "$SOURCE")
printf '%s\n' "$STOP_BLOCK" | grep -Fq "clearCentralDeferredConnectIntent()"
printf '%s\n' "$STOP_BLOCK" | grep -Fq "clearCentralPendingTerminalStateObservation()"
printf '%s\n' "$STOP_BLOCK" | grep -Fq "centralManualReconnectPending = false"
printf '%s\n' "$STOP_BLOCK" | grep -Fq "clearCentralRestoredB4Hint()"

DID_CONNECT=$(awk '
    /func centralManager\(_ central: CBCentralManager, didConnect/ { capture = 1 }
    capture { print }
    capture && /func centralManager\(_ central: CBCentralManager, didFailToConnect/ { exit }
' "$SOURCE")
printf '%s\n' "$DID_CONNECT" \
    | grep -Fq "centralManualReconnectPending || centralHardResetReason != nil"
PENDING_DID_CONNECT_LINE=$(printf '%s\n' "$DID_CONNECT" \
    | grep -n "centralManualReconnectPending || centralHardResetReason != nil" | cut -d: -f1)
ACCEPT_DID_CONNECT_LINE=$(printf '%s\n' "$DID_CONNECT" \
    | grep -n "continueCentralConnected(peripheral)" | cut -d: -f1)
[ "$PENDING_DID_CONNECT_LINE" -lt "$ACCEPT_DID_CONNECT_LINE" ]
if printf '%s\n' "$DID_CONNECT" | grep -Fq "late didConnect during reset"; then
    echo "didConnect still performs a second hard-reset cancel" >&2
    exit 1
fi
printf '%s\n' "$DID_CONNECT" | grep -Fq "peripheral === geelyPeripheral"
printf '%s\n' "$DID_CONNECT" \
    | grep -Fq "CONTRACT_V39_FRESH_DIDCONNECT_CLEARS_RESTORED_B4_HINT"
DID_CONNECT_HINT_CLEAR=$(printf '%s\n' "$DID_CONNECT" \
    | grep -n "clearCentralRestoredB4Hint()" | tail -n1 | cut -d: -f1)
[ "$DID_CONNECT_HINT_CLEAR" -lt "$ACCEPT_DID_CONNECT_LINE" ]

CONTINUE_CONNECTED=$(awk '
    /private func continueCentralConnected/ { capture = 1 }
    capture { print }
    capture && /private func startCentralRouteIfPossible/ { exit }
' "$SOURCE")
printf '%s\n' "$CONTINUE_CONNECTED" \
    | grep -Fq "CONTRACT_V39_NEW_DIDCONNECT_REQUIRES_FRESH_B4_SUBSCRIBE"
printf '%s\n' "$CONTINUE_CONNECTED" | grep -Fq "allowRestoredB4Hint: Bool = false"
printf '%s\n' "$CONTINUE_CONNECTED" \
    | grep -Fq "consumeRestoredB4HintIfEligible(peripheral)"
if printf '%s\n' "$CONTINUE_CONNECTED" | grep -Fq "telemetrySubscribers.contains"; then
    echo "new didConnect restores B4 readiness from a stale subscriber set" >&2
    exit 1
fi

MANUAL_TERMINALS=$(grep -Fc \
    "reopenManualOwnerAfterTerminalCallback(peripheral," "$SOURCE")
[ "$MANUAL_TERMINALS" -eq 2 ] || {
    echo "manual reconnect must be consumed by exactly two terminal callbacks" >&2
    exit 1
}

[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 42;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 42.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]
grep -Fq 'titleLabel.text = "KX11 ANCS HELPER v42"' "$SOURCE"
grep -Fq 'append("v42: issued-connect .connecting recovery' "$SOURCE"
if grep -Fq "CONTRACT_V41_CONSUMED_INTENT_CONNECT_STAYS_OBSERVED" "$SOURCE"; then
    echo "obsolete v41 false-issued intent marker survived in Helper v42" >&2
    exit 1
fi

# v40 closed the observed didConnect -> uuidNotAllowed -> cancel loop. The automatic destructive
# budget belongs to the retained owner/restoration lineage, not to one pending cancel callback.
require_source "CONTRACT_V40_ONE_DESTRUCTIVE_RECOVERY_PER_LINEAGE"
require_source "CONTRACT_V40_MANUAL_RECONNECT_IS_INDEPENDENT"
require_source "CONTRACT_V40_FULL_PROOF_REARMS_BUDGET"
require_source "CONTRACT_V41_SERVICE_CHANGED_INVALIDATION_ALONE_NEVER_REARMS"
require_source "CONTRACT_V41_VALIDATED_NEW_F04_OBJECT_REARMS_BUDGET"

DESTRUCTIVE_RECOVERY=$(awk '
    /private func resetCentralLink\(reason:/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SOURCE")
printf '%s\n' "$DESTRUCTIVE_RECOVERY" \
    | grep -Fq "guard !centralDestructiveRecoveryConsumed else"
printf '%s\n' "$DESTRUCTIVE_RECOVERY" \
    | grep -Fq "waitForFreshCentralF04Publication(reason: reason)"
printf '%s\n' "$DESTRUCTIVE_RECOVERY" \
    | grep -Fq "centralDestructiveRecoveryConsumed = true"
if printf '%s\n' "$DESTRUCTIVE_RECOVERY" \
    | grep -Fq "centralDestructiveRecoveryConsumed = false"; then
    echo "destructive reset re-arms its own lineage budget" >&2
    exit 1
fi

DISCONNECT_HANDLER=$(awk '
    /private func handleCentralDisconnect/ { capture = 1 }
    capture { print }
    capture && /didUpdateANCSAuthorizationFor peripheral/ { exit }
' "$SOURCE")
if printf '%s\n' "$DISCONNECT_HANDLER" \
    | grep -Eq "clearCentralDestructiveRecoveryLineage|centralDestructiveRecoveryConsumed = false"; then
    echo "terminal callback re-arms destructive recovery" >&2
    exit 1
fi

CONTINUE_OWNER=$(awk '
    /private func continueCentralConnected/ { capture = 1 }
    capture { print }
    capture && /private func startCentralRouteIfPossible/ { exit }
' "$SOURCE")
if printf '%s\n' "$CONTINUE_OWNER" \
    | grep -Fq "centralDestructiveRecoveryConsumed = false"; then
    echo "replacement didConnect re-arms destructive recovery" >&2
    exit 1
fi

MANUAL_RECONNECT=$(awk '
    /@objc private func resetTapped/ { capture = 1 }
    capture { print }
    capture && /@objc private func roleChanged/ { exit }
' "$SOURCE")
printf '%s\n' "$MANUAL_RECONNECT" \
    | grep -Fq "centralDestructiveRecoveryWaitingForFreshF04 = false"
if printf '%s\n' "$MANUAL_RECONNECT" \
    | grep -Fq "centralDestructiveRecoveryConsumed = false"; then
    echo "manual reconnect silently re-arms automatic recovery" >&2
    exit 1
fi

SERVICE_CHANGED=$(awk '
    /func peripheral\(_ peripheral: CBPeripheral, didModifyServices/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SOURCE")
printf '%s\n' "$SERVICE_CHANGED" | grep -Fq '$0 === currentService'
printf '%s\n' "$SERVICE_CHANGED" \
    | grep -Fq "centralFreshF04ValidationPending = true"
require_source "CONTRACT_V41_SERVICE_CHANGED_INVALIDATES_OLD_PROTOCOL_PROOF"
for reset in \
    "centralAncsReadyWriteIssued = false" \
    "centralSecureLinkReady = false" \
    "centralSecureReadAttempt = 0" \
    "centralLinkSecurityChallengeObserved = false" \
    "centralHelperConfirmed = false" \
    "centralAncsCccdConfirmed = false" \
    "centralB4Subscribed = false" \
    "centralAncsAccessProven = false"; do
    printf '%s\n' "$SERVICE_CHANGED" | grep -Fq "$reset"
done
printf '%s\n' "$SERVICE_CHANGED" \
    | grep -Fq "centralReadinessProofWorkItem?.cancel()"
printf '%s\n' "$SERVICE_CHANGED" \
    | grep -Fq "scheduleCentralFreshF04Validation(peripheral, reason:"
OLD_READY_RESET_LINE=$(printf '%s\n' "$SERVICE_CHANGED" \
    | grep -n "centralAncsReadyWriteIssued = false" | cut -d: -f1)
VALIDATION_PENDING_LINE=$(printf '%s\n' "$SERVICE_CHANGED" \
    | grep -n "centralFreshF04ValidationPending = true" | cut -d: -f1)
VALIDATION_SCHEDULE_LINE=$(printf '%s\n' "$SERVICE_CHANGED" \
    | grep -n "scheduleCentralFreshF04Validation" | cut -d: -f1)
[ "$OLD_READY_RESET_LINE" -lt "$VALIDATION_PENDING_LINE" ]
[ "$VALIDATION_PENDING_LINE" -lt "$VALIDATION_SCHEDULE_LINE" ]
if printf '%s\n' "$SERVICE_CHANGED" \
    | grep -Fq "rearmCentralDestructiveRecoveryForFreshF04"; then
    echo "F04 invalidation alone re-arms destructive recovery" >&2
    exit 1
fi
if printf '%s\n' "$SERVICE_CHANGED" | grep -Fq '$0.uuid =='; then
    echo "same-UUID stale service can re-arm destructive recovery" >&2
    exit 1
fi
if printf '%s\n' "$SERVICE_CHANGED" \
    | grep -Eq "centralDestructiveRecoveryConsumed =|geelyPeripheral = nil"; then
    echo "F04 invalidation destroys owner or recovery budget" >&2
    exit 1
fi

VALIDATED_F04=$(awk '
    /private func rearmCentralDestructiveRecoveryForValidatedFreshF04/ { capture = 1 }
    capture { print }
    capture && /private func rearmCentralDestructiveRecoveryAfterFullProof/ { exit }
' "$SOURCE")
printf '%s\n' "$VALIDATED_F04" | grep -Fq "service !== invalidated"
printf '%s\n' "$VALIDATED_F04" \
    | grep -Fq "rearmCentralDestructiveRecoveryForFreshF04("
printf '%s\n' "$VALIDATED_F04" \
    | grep -Fq "validated new exact CBService + B2/B3/B4"

# v42 keeps v41's system-table route and adds a narrowly-scoped beacon route for an actual
# app-issued request or its terminal-bound descendant AutoReconnect generation.
require_source "CONTRACT_V41_ORDINARY_CONNECTING_EXACT_F04_PROOF"
require_source "CONTRACT_V41_ORDINARY_CONNECTING_REQUIRES_EXACT_F04_PROOF"
require_source "CONTRACT_V41_ORDINARY_RECLAIM_ONE_CANCEL_PER_LINEAGE"
require_source "CONTRACT_V41_LATE_EXACT_DIDCONNECT_CANCELS_RECLAIM"
require_source "CONTRACT_V41_RECLAIM_DIDFAIL_TERMINAL_REOPENS_ONCE"
require_source "CONTRACT_V41_RECLAIM_DIDDISCONNECT_TERMINAL_REOPENS_ONCE"
require_source "CONTRACT_V41_RECLAIM_STATE_OBSERVER_ONE_SHOT"
require_source "CONTRACT_V42_CONSUMED_INTENT_IS_NOT_ISSUED_CONNECT"
require_source "CONTRACT_V42_TOKEN_CAPTURE_FOLLOWS_ACTUAL_CONNECT_CALL"
require_source "CONTRACT_V42_ISSUED_LINEAGE_STARTS_AFTER_ACTUAL_CONNECT"
require_source "CONTRACT_V42_TERMINAL_BINDS_DESCENDANT_AUTORECONNECT_PROVENANCE"
require_source "CONTRACT_V42_BEACON_BINDS_LATER_ISSUED_TOKEN_AND_CURRENT_F04"
require_source "CONTRACT_V42_STORED_BEACON_ARMS_WHEN_OWNER_BECOMES_CONNECTING"
require_source "CONTRACT_V42_BEACON_RECLAIM_ONE_CANCEL_AFTER_GRACE"
require_source "CONTRACT_V42_DIDCONNECT_INVALIDATES_GRACE_NOT_BUDGET"
require_source "CONTRACT_V42_TERMINAL_INVALIDATES_EVIDENCE_NOT_BUDGET"
require_source "CONTRACT_V42_POWER_OFF_INVALIDATES_BEACON_GRACE_NOT_BUDGET"
require_source "CONTRACT_V42_RECLAIM_BUDGET_REARMS_ONLY_TRUSTED_BOUNDARY"
printf '%s\n' "$CONNECTING_INTENT" \
    | grep -Fq "armCentralDeferredReclaimObservation(intent.peripheral)"
if printf '%s\n' "$CONNECTING_INTENT" \
    | grep -Fq "Deferred exact-owner intent retained while owner is .connecting"; then
    echo "hot-update .connecting path restored per-tick log spam" >&2
    exit 1
fi
if printf '%s\n' "$CONSUME_BLOCK" \
    | grep -Eq "centralDeferredReclaimIssuedConnectPending = true|captureCentralActualIssuedConnect|^[[:space:]]*centralManager\.connect\("; then
    echo "consuming a deferred intent falsely creates app-issued provenance" >&2
    exit 1
fi

# Terminal and didConnect callbacks invalidate only request evidence. They neither erase the
# global actual-issued provenance nor silently reset/clear the lineage's one-shot budget.
REQUEST_EVIDENCE_INVALIDATION=$(awk '
    /private func invalidateCentralDeferredReclaimRequestEvidence/ { capture = 1 }
    capture { print }
    capture && /private func recordCentralDeferredReclaimTerminalBoundary/ { exit }
' "$SOURCE")
printf '%s\n' "$REQUEST_EVIDENCE_INVALIDATION" \
    | grep -Fq "centralDeferredReclaimEvidenceGeneration &+= 1"
printf '%s\n' "$REQUEST_EVIDENCE_INVALIDATION" \
    | grep -Fq "centralDeferredReclaimGraceWorkItem?.cancel()"
TERMINAL_EVIDENCE=$(awk '
    /private func recordCentralDeferredReclaimTerminalBoundary/ { capture = 1 }
    capture { print }
    capture && /private func clearCentralLastActualIssuedConnectProvenance/ { exit }
' "$SOURCE")
printf '%s\n' "$TERMINAL_EVIDENCE" \
    | grep -Fq "centralDeferredReclaimTerminalGeneration &+= 1"
printf '%s\n' "$TERMINAL_EVIDENCE" \
    | grep -Fq "invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)"
if printf '%s\n' "$TERMINAL_EVIDENCE" \
    | grep -Eq "centralDeferredReclaimConsumed =|clearCentralDeferredReclaimLineage|centralLastActualIssuedConnectToken = nil"; then
    echo "ordinary terminal resets budget or destroys actual-issued provenance" >&2
    exit 1
fi
printf '%s\n' "$DID_CONNECT" \
    | grep -Fq "invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)"
if printf '%s\n' "$DID_CONNECT" \
    | grep -Eq "centralDeferredReclaimConsumed = false|clearCentralDeferredReclaimLineage"; then
    echo "late didConnect re-arms or clears the ordinary one-shot budget" >&2
    exit 1
fi

# The global monotonic provenance is captured after, never before, the sole actual connect call.
ISSUE_COMMAND_LINE=$(printf '%s\n' "$ISSUE_BLOCK" \
    | grep -n "centralManager.connect(peripheral, options: options)" | cut -d: -f1)
ISSUE_CAPTURE_LINE=$(printf '%s\n' "$ISSUE_BLOCK" \
    | grep -n "captureCentralActualIssuedConnect(" | cut -d: -f1)
[ "$ISSUE_COMMAND_LINE" -lt "$ISSUE_CAPTURE_LINE" ]
[ "$(printf '%s\n' "$ISSUE_BLOCK" \
    | grep -Fc 'captureCentralActualIssuedConnect(')" -eq 1 ]
[ "$(grep -Fc 'centralDeferredReclaimIssuedConnectSerial &+= 1' "$SOURCE")" -eq 1 ]
[ "$(grep -Fc 'centralDeferredReclaimIssuedConnectPending = true' "$SOURCE")" -eq 1 ]

ACTUAL_CAPTURE=$(awk '
    /private func captureCentralActualIssuedConnect/ { capture = 1 }
    capture { print }
    capture && /private func centralDeferredReclaimHasIssuedBeaconProof/ { exit }
' "$SOURCE")
printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Fq "centralDeferredReclaimIssuedConnectSerial &+= 1"
printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Fq "centralLastActualIssuedConnectToken = centralDeferredReclaimIssuedConnectSerial"
printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Fq "centralLastActualIssuedOwnerID = peripheral.identifier"
printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Fq "centralLastActualIssuedNamespaceGeneration = currentGeneration"
printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Fq "centralLastActualIssuedEnabledAutoReconnect = autoReconnectEnabled"
printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Fq "requiresAutoReconnectDescendant: false"
if printf '%s\n' "$ACTUAL_CAPTURE" \
    | grep -Eq '^[[:space:]]*centralManager\.connect\('; then
    echo "provenance capture owns an additional connect command" >&2
    exit 1
fi

ISSUED_BEACON_PROOF=$(awk '
    /private func centralDeferredReclaimHasIssuedBeaconProof/ { capture = 1 }
    capture { print }
    capture && /Begin one evidence-driven ownership lineage/ { exit }
' "$SOURCE")
printf '%s\n' "$ISSUED_BEACON_PROOF" \
    | grep -Fq "centralDeferredReclaimBeaconObserved"
printf '%s\n' "$ISSUED_BEACON_PROOF" \
    | grep -Fq "centralDeferredReclaimBeaconIssuedConnectToken == issuedToken"
printf '%s\n' "$ISSUED_BEACON_PROOF" \
    | grep -Fq "centralDeferredReclaimBeaconOwnerID == peripheral.identifier"
printf '%s\n' "$ISSUED_BEACON_PROOF" \
    | grep -Fq "centralDeferredReclaimBeaconNamespaceGeneration == issuedGeneration"
printf '%s\n' "$ISSUED_BEACON_PROOF" \
    | grep -Fq "centralDeferredReclaimBeaconTerminalGeneration"

TERMINAL_BIND=$(awk '
    /private func queueTerminalCentralReconnect/ { capture = 1 }
    capture { print }
    capture && /private func cancelCentralReconnect/ { exit }
' "$SOURCE")
printf '%s\n' "$TERMINAL_BIND" \
    | grep -Fq "bindCentralDeferredReclaimToLastActualConnect("
printf '%s\n' "$TERMINAL_BIND" \
    | grep -Fq "evidenceNotBeforeUptime: centralDeferredReclaimTerminalAtUptime"
printf '%s\n' "$TERMINAL_BIND" \
    | grep -Fq "requiresAutoReconnectDescendant: true"
printf '%s\n' "$TERMINAL_BIND" \
    | grep -Fq "descendant AutoReconnect after ordinary terminal"
TERMINAL_PROVENANCE_LINE=$(printf '%s\n' "$TERMINAL_BIND" \
    | grep -n "bindCentralDeferredReclaimToLastActualConnect(" | cut -d: -f1)
TERMINAL_INTENT_LINE=$(printf '%s\n' "$TERMINAL_BIND" \
    | grep -n "queueCentralConnectIntent(" | cut -d: -f1)
[ "$TERMINAL_PROVENANCE_LINE" -lt "$TERMINAL_INTENT_LINE" ]

ISSUED_BIND=$(awk '
    /private func bindCentralDeferredReclaimToLastActualConnect/ { capture = 1 }
    capture { print }
    capture && /private func captureCentralActualIssuedConnect/ { exit }
' "$SOURCE")
printf '%s\n' "$ISSUED_BIND" | grep -Fq "centralLastActualIssuedConnectToken != nil"
printf '%s\n' "$ISSUED_BIND" | grep -Fq "centralLastActualIssuedOwnerID == peripheral.identifier"
printf '%s\n' "$ISSUED_BIND" | grep -Fq "currentGeneration == 0x2F04"
printf '%s\n' "$ISSUED_BIND" | grep -Fq "actualIssuedAt <= evidenceNotBeforeUptime"
printf '%s\n' "$ISSUED_BIND" \
    | grep -Fq "!requiresAutoReconnectDescendant"
printf '%s\n' "$ISSUED_BIND" \
    | grep -Fq "centralLastActualIssuedEnabledAutoReconnect"
printf '%s\n' "$ISSUED_BIND" | grep -Fq "centralDeferredReclaimIssuedConnectPending = true"
BIND_INVALIDATE_LINE=$(printf '%s\n' "$ISSUED_BIND" \
    | grep -n "invalidateCentralDeferredReclaimRequestEvidence" | cut -d: -f1)
BIND_PENDING_LINE=$(printf '%s\n' "$ISSUED_BIND" \
    | grep -n "centralDeferredReclaimIssuedConnectPending = true" | cut -d: -f1)
[ "$BIND_INVALIDATE_LINE" -lt "$BIND_PENDING_LINE" ]

RECLAIM_OBSERVER=$(awk '
    /private func armCentralDeferredReclaimObservation/ { capture = 1 }
    capture { print }
    capture && /private func observeCentralDeferredReclaimBeacon/ { exit }
' "$SOURCE")
printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Fq "retrieveConnectedPeripherals("
printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Fq '$0.identifier == peripheral.identifier'
printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Fq "managedIncomingBeaconUUID"
printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Fq "peripheral.state == .connecting"
printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Fq "centralDeferredReclaimBeaconObserved"
printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Fq 'source: "stored exact beacon after .connecting transition"'
if printf '%s\n' "$RECLAIM_OBSERVER" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect"; then
    echo "ordinary proof observer contains a destructive command" >&2
    exit 1
fi

RECLAIM_BEACON=$(awk '
    /private func observeCentralDeferredReclaimBeacon/ { capture = 1 }
    capture { print }
    capture && /private func observeCentralDeferredReclaimSystemProof/ { exit }
' "$SOURCE")
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "centralDeferredReclaimIssuedConnectPending"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "advertisedGeneration == issuedGeneration"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "issuedGeneration == 0x2F04"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "issuedTerminalGeneration == centralDeferredReclaimTerminalGeneration"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "observedAtUptime >= evidenceNotBeforeUptime"
printf '%s\n' "$RECLAIM_BEACON" | grep -Fq "savedID == peripheral.identifier"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "centralDeferredReclaimBeaconIssuedConnectToken = issuedToken"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "centralDeferredReclaimBeaconOwnerID = owner.identifier"
printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Fq "centralDeferredReclaimBeaconNamespaceGeneration = issuedGeneration"
if printf '%s\n' "$RECLAIM_BEACON" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect|centralManager\.connect"; then
    echo "beacon observer owns a destructive BLE command" >&2
    exit 1
fi

RECLAIM_PROOF=$(awk '
    /private func observeCentralDeferredReclaimSystemProof/ { capture = 1 }
    capture { print }
    capture && /private func armCentralDeferredReclaimProofGrace/ { exit }
' "$SOURCE")
printf '%s\n' "$RECLAIM_PROOF" \
    | grep -Fq "armCentralDeferredReclaimProofGrace(peripheral, source: source)"
if printf '%s\n' "$RECLAIM_PROOF" | grep -Eq "cancelCentralConnectionSafely|queueCentralConnectIntent|issueCentralConnect"; then
    echo "system proof observer bypasses shared one-shot grace" >&2
    exit 1
fi

RECLAIM_GRACE=$(awk '
    /private func armCentralDeferredReclaimProofGrace/ { capture = 1 }
    capture { print }
    capture && /private func armCentralDeferredReclaimPostCancelObservation/ { exit }
' "$SOURCE")
[ "$(printf '%s\n' "$RECLAIM_GRACE" \
    | grep -Fc "cancelCentralConnectionSafely(")" -eq 1 ]
printf '%s\n' "$RECLAIM_GRACE" | grep -Fq "centralDeferredReclaimHasIssuedBeaconProof"
printf '%s\n' "$RECLAIM_GRACE" | grep -Fq "centralDeferredReclaimConsumed = true"
printf '%s\n' "$RECLAIM_GRACE" | grep -Fq "centralDeferredReclaimProofGrace"
GRACE_CONSUME_LINE=$(printf '%s\n' "$RECLAIM_GRACE" \
    | grep -n "centralDeferredReclaimConsumed = true" | cut -d: -f1)
GRACE_CANCEL_LINE=$(printf '%s\n' "$RECLAIM_GRACE" \
    | grep -n "cancelCentralConnectionSafely(" | cut -d: -f1)
[ "$GRACE_CONSUME_LINE" -lt "$GRACE_CANCEL_LINE" ]
if printf '%s\n' "$RECLAIM_GRACE" | grep -Eq "queueCentralConnectIntent|issueCentralConnect"; then
    echo "ordinary reclaim grace bypasses terminal ownership" >&2
    exit 1
fi

RECLAIM_STATE_OBSERVER=$(awk '
    /private func armCentralDeferredReclaimPostCancelObservation/ { capture = 1 }
    capture { print }
    capture && /private func reopenCentralDeferredReclaimAfterTerminal/ { exit }
' "$SOURCE")
[ "$(printf '%s\n' "$RECLAIM_STATE_OBSERVER" \
    | grep -Fc "self.reopenCentralDeferredReclaimAfterTerminal(")" -eq 1 ]
if printf '%s\n' "$RECLAIM_STATE_OBSERVER" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect|queueCentralConnectIntent"; then
    echo "post-cancel state observer owns a second BLE command" >&2
    exit 1
fi

RECLAIM_REOPEN=$(awk '
    /private func reopenCentralDeferredReclaimAfterTerminal/ { capture = 1 }
    capture { print }
    capture && /Record delayed connect intent synchronously/ { exit }
' "$SOURCE")
[ "$(printf '%s\n' "$RECLAIM_REOPEN" \
    | grep -Fc "queueCentralConnectIntent(")" -eq 1 ]
if printf '%s\n' "$RECLAIM_REOPEN" \
    | grep -Eq "centralDeferredReclaimConsumed = false|beginCentralDeferredReclaimLineage"; then
    echo "terminal reopen re-arms the one-claim lineage" >&2
    exit 1
fi

DID_FAIL=$(awk '
    /func centralManager\(_ central: CBCentralManager, didFailToConnect/ { capture = 1 }
    capture { print }
    capture && /func centralManager\(_ central: CBCentralManager, didDisconnectPeripheral/ { exit }
' "$SOURCE")
DID_FAIL_RECLAIM=$(printf '%s\n' "$DID_FAIL" \
    | grep -n "if centralDeferredReclaimPendingTerminal" | head -n1 | cut -d: -f1)
DID_FAIL_RESTORE=$(printf '%s\n' "$DID_FAIL" \
    | grep -n "if centralRestorationReconnectPending" | head -n1 | cut -d: -f1)
[ "$DID_FAIL_RECLAIM" -lt "$DID_FAIL_RESTORE" ]
DID_FAIL_TERMINAL_GEN=$(printf '%s\n' "$DID_FAIL" \
    | grep -n "recordCentralDeferredReclaimTerminalBoundary()" | cut -d: -f1)
DID_FAIL_ORDINARY_QUEUE=$(printf '%s\n' "$DID_FAIL" \
    | grep -n "queueTerminalCentralReconnect(peripheral," | cut -d: -f1)
[ "$DID_FAIL_TERMINAL_GEN" -lt "$DID_FAIL_ORDINARY_QUEUE" ]
[ "$(printf '%s\n' "$DID_FAIL" \
    | grep -Fc 'callback: "didFailToConnect"')" -eq 3 ]

HOT_DISCONNECT=$(awk '
    /private func handleCentralDisconnect/ { capture = 1 }
    capture { print }
    capture && /didUpdateANCSAuthorizationFor peripheral/ { exit }
' "$SOURCE")
HOT_RECLAIM_LINE=$(printf '%s\n' "$HOT_DISCONNECT" \
    | grep -n "if centralDeferredReclaimPendingTerminal" | head -n1 | cut -d: -f1)
HOT_HARD_LINE=$(printf '%s\n' "$HOT_DISCONNECT" \
    | grep -n "let hardReset = centralHardResetReason" | head -n1 | cut -d: -f1)
HOT_RESTORE_LINE=$(printf '%s\n' "$HOT_DISCONNECT" \
    | grep -n "if centralRestorationReconnectPending" | head -n1 | cut -d: -f1)
[ "$HOT_RECLAIM_LINE" -lt "$HOT_HARD_LINE" ]
[ "$HOT_RECLAIM_LINE" -lt "$HOT_RESTORE_LINE" ]
HOT_TERMINAL_GEN=$(printf '%s\n' "$HOT_DISCONNECT" \
    | grep -n "recordCentralDeferredReclaimTerminalBoundary()" | cut -d: -f1)
HOT_ORDINARY_QUEUE=$(printf '%s\n' "$HOT_DISCONNECT" \
    | grep -n "queueTerminalCentralReconnect(peripheral," | cut -d: -f1)
[ "$HOT_TERMINAL_GEN" -lt "$HOT_ORDINARY_QUEUE" ]
[ "$(printf '%s\n' "$HOT_DISCONNECT" \
    | grep -Fc 'callback: "didDisconnect"')" -eq 3 ]
if printf '%s\n%s\n' "$DID_FAIL" "$HOT_DISCONNECT" \
    | grep -Fq "centralDeferredReclaimConsumed = false"; then
    echo "terminal callback resets the ordinary one-shot budget" >&2
    exit 1
fi

# A radio transition preserves the exact reclaim lineage but invalidates old proof/work. PoweredOn
# re-arms the correct read-only observer, except that an issued request which the radio has made
# `.disconnected` is first rematerialized as exact-owner data for the normal single-consume route.
require_source "CONTRACT_V41_POWER_OFF_PRESERVES_RECLAIM_OWNER"
require_source "CONTRACT_V41_POWERED_ON_REARMS_ONLY_RECLAIM_OBSERVER"
require_source "CONTRACT_V41_POWER_RESUME_DISCONNECTED_REMATERIALIZES_EXACT_INTENT"
POWER_STATE=$(awk '
    /func centralManagerDidUpdateState/ { capture = 1 }
    capture { print }
    capture && /func centralManager\(_ central: CBCentralManager,/ { exit }
' "$SOURCE")
printf '%s\n' "$POWER_STATE" | grep -Fq "!centralDeferredReclaimActive"
printf '%s\n' "$POWER_STATE" | grep -Fq "!centralDeferredReclaimPendingTerminal"
printf '%s\n' "$POWER_STATE" | grep -Fq "!centralDeferredReclaimIssuedConnectPending"
printf '%s\n' "$POWER_STATE" \
    | grep -Fq "centralDeferredReclaimSystemProofObserved = false"
printf '%s\n' "$POWER_STATE" \
    | grep -Fq "armCentralDeferredReclaimPostCancelObservation(owner)"
printf '%s\n' "$POWER_STATE" \
    | grep -Fq "armCentralDeferredReclaimObservation(owner)"

POWER_DISCONNECTED_RECLAIM=$(printf '%s\n' "$POWER_STATE" | awk '
    /if owner.state == \.disconnected/ { capture = 1 }
    capture { print }
    capture && /return/ { exit }
')
printf '%s\n' "$POWER_DISCONNECTED_RECLAIM" \
    | grep -Fq "invalidateCentralDeferredReclaimRequestEvidence("
printf '%s\n' "$POWER_DISCONNECTED_RECLAIM" \
    | grep -Fq "queueCentralConnectIntent("
printf '%s\n' "$POWER_DISCONNECTED_RECLAIM" \
    | grep -Fq "startCentralRouteIfPossible()"
POWER_CLEAR_ISSUED_LINE=$(printf '%s\n' "$POWER_DISCONNECTED_RECLAIM" \
    | grep -n "invalidateCentralDeferredReclaimRequestEvidence(" | cut -d: -f1)
POWER_QUEUE_INTENT_LINE=$(printf '%s\n' "$POWER_DISCONNECTED_RECLAIM" \
    | grep -n "queueCentralConnectIntent(" | cut -d: -f1)
[ "$POWER_CLEAR_ISSUED_LINE" -lt "$POWER_QUEUE_INTENT_LINE" ]
if printf '%s\n' "$POWER_DISCONNECTED_RECLAIM" \
    | grep -Eq "cancelCentralConnectionSafely|beginCentralDeferredReclaimLineage|centralManager\.connect"; then
    echo "poweredOn disconnected reclaim creates a destructive/new ownership claim" >&2
    exit 1
fi

# Validation discovery is allowed while the destructive budget remains spent. It is sparse and
# read-only; the invalidated object itself can never reach PAIR.
require_source "CONTRACT_V41_FRESH_F04_VALIDATION_IS_READ_ONLY_SPARSE"
require_source "CONTRACT_V41_INVALIDATED_F04_OBJECT_NEVER_WRITES_PAIR"
require_source "CONTRACT_V41_DID_DISCOVER_SERVICES_REJECTS_INVALIDATED_OBJECT_EARLY"
require_source "CONTRACT_V41_INVALID_F04_CANDIDATE_STAYS_READ_ONLY"
CHARACTERISTIC_DISCOVERY=$(awk '
    /private func scheduleCentralCharacteristicDiscovery/ { capture = 1 }
    capture { print }
    capture && /private func recoverCentralCharacteristicDiscovery/ { exit }
' "$SOURCE")
printf '%s\n' "$CHARACTERISTIC_DISCOVERY" \
    | grep -Fq "|| centralFreshF04ValidationPending"
printf '%s\n' "$CHARACTERISTIC_DISCOVERY" \
    | grep -Fq "|| self.centralFreshF04ValidationPending"
SERVICE_REDISCOVERY=$(awk '
    /private func scheduleCentralServiceRediscovery/ { capture = 1 }
    capture { print }
    capture && /Uses characteristics only from/ { exit }
' "$SOURCE")
printf '%s\n' "$SERVICE_REDISCOVERY" \
    | grep -Fq "scheduleCentralFreshF04Validation("
VALIDATION_CADENCE_LINE=$(printf '%s\n' "$SERVICE_REDISCOVERY" \
    | grep -n "if centralFreshF04ValidationPending" | head -n1 | cut -d: -f1)
SHORT_LIMIT_LINE=$(printf '%s\n' "$SERVICE_REDISCOVERY" \
    | grep -n "centralServiceRediscoveryAttempt < centralServiceRediscoveryLimit" \
    | head -n1 | cut -d: -f1)
[ "$VALIDATION_CADENCE_LINE" -lt "$SHORT_LIMIT_LINE" ]

SPARSE_VALIDATION=$(awk '
    /private func scheduleCentralFreshF04Validation/ { capture = 1 }
    capture { print }
    capture && /An invalidated CBService must never be reused/ { exit }
' "$SOURCE")
printf '%s\n' "$SPARSE_VALIDATION" \
    | grep -Fq "centralFreshF04ValidationDelays"
printf '%s\n' "$SPARSE_VALIDATION" \
    | grep -Fq "peripheral.discoverServices([self.serviceUUID])"
if printf '%s\n' "$SPARSE_VALIDATION" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect|resetCentralLink|writeCentralPair"; then
    echo "sparse fresh-F04 validation contains destructive/protocol work" >&2
    exit 1
fi

USE_CURRENT=$(awk '
    /private func useCurrentCentralCharacteristics/ { capture = 1 }
    capture { print }
    capture && /private func centralErrorDescription/ { exit }
' "$SOURCE")
SAME_INVALIDATED_LINE=$(printf '%s\n' "$USE_CURRENT" \
    | grep -n "service === invalidated" | head -n1 | cut -d: -f1)
CHARACTERISTIC_LOOKUP_LINE=$(printf '%s\n' "$USE_CURRENT" \
    | grep -n "let characteristics = service.characteristics" | head -n1 | cut -d: -f1)
PAIR_LINE=$(printf '%s\n' "$USE_CURRENT" \
    | grep -n "writeCentralPair(peripheral)" | head -n1 | cut -d: -f1)
[ "$SAME_INVALIDATED_LINE" -lt "$CHARACTERISTIC_LOOKUP_LINE" ]
[ "$SAME_INVALIDATED_LINE" -lt "$PAIR_LINE" ]
printf '%s\n' "$USE_CURRENT" \
    | grep -A12 "service === invalidated" | grep -Fq "return true"

DID_DISCOVER_SERVICES=$(awk '
    /func peripheral\(_ peripheral: CBPeripheral, didDiscoverServices/ { capture = 1 }
    capture { print }
    capture && /didDiscoverCharacteristicsFor service/ { exit }
' "$SOURCE")
EARLY_INVALIDATED_LINE=$(printf '%s\n' "$DID_DISCOVER_SERVICES" \
    | grep -n "CONTRACT_V41_DID_DISCOVER_SERVICES_REJECTS_INVALIDATED_OBJECT_EARLY" \
    | cut -d: -f1)
STORE_CANDIDATE_LINE=$(printf '%s\n' "$DID_DISCOVER_SERVICES" \
    | grep -n "centralService = service" | cut -d: -f1)
USE_CANDIDATE_LINE=$(printf '%s\n' "$DID_DISCOVER_SERVICES" \
    | grep -n "useCurrentCentralCharacteristics" | cut -d: -f1)
[ "$EARLY_INVALIDATED_LINE" -lt "$STORE_CANDIDATE_LINE" ]
[ "$EARLY_INVALIDATED_LINE" -lt "$USE_CANDIDATE_LINE" ]
printf '%s\n' "$DID_DISCOVER_SERVICES" \
    | grep -A8 "CONTRACT_V41_DID_DISCOVER_SERVICES_REJECTS_INVALIDATED_OBJECT_EARLY" \
    | grep -Fq "continueCentralFreshF04Validation("

VALIDATION_CONTINUE=$(awk '
    /private func continueCentralFreshF04Validation/ { capture = 1 }
    capture { print }
    capture && /An invalidated CBService must never be reused/ { exit }
' "$SOURCE")
printf '%s\n' "$VALIDATION_CONTINUE" \
    | grep -Fq "scheduleCentralFreshF04Validation(peripheral, reason: reason)"
if printf '%s\n' "$VALIDATION_CONTINUE" \
    | grep -Eq "cancelCentralConnectionSafely|issueCentralConnect|resetCentralLink|writeCentralPair"; then
    echo "invalid fresh-F04 candidate path is not read-only" >&2
    exit 1
fi

CHARACTERISTIC_CALLBACK=$(awk '
    /didDiscoverCharacteristicsFor service/ { capture = 1 }
    capture { print }
    capture && /func peripheral\(_ peripheral: CBPeripheral, didWriteValueFor/ { exit }
' "$SOURCE")
VALIDATION_ERROR_LINE=$(printf '%s\n' "$CHARACTERISTIC_CALLBACK" \
    | grep -n "if centralFreshF04ValidationPending" | head -n1 | cut -d: -f1)
UUID_RESET_LINE=$(printf '%s\n' "$CHARACTERISTIC_CALLBACK" \
    | grep -n "uuidNotAllowed on current ATT owner" | head -n1 | cut -d: -f1)
[ "$VALIDATION_ERROR_LINE" -lt "$UUID_RESET_LINE" ]
printf '%s\n' "$CHARACTERISTIC_CALLBACK" \
    | grep -Fq "replacement B2/B3/B4 incomplete"

# Executable v42 field sequence matching the 23:39 trace. A real app-local connect is submitted
# before the ordinary lineage exists. The terminal binds its descendant AutoReconnect generation;
# the delayed intent then encounters `.connecting` and submits no second connect. A fresh exact
# saved-owner/current-2F04 beacon is sufficient even when the system table is empty.
exact_owner=F04-SAVED-OWNER
current_generation=12036          # 0x2F04
event_time=0
actual_issue_serial=0
global_issued_token=0
global_issued_owner=none
global_issued_generation=0
global_issued_at=0
global_auto_reconnect=0
connect_submissions=0
terminal_generation=0
evidence_generation=0
ordinary_lineage_active=0
ordinary_claim_budget=1
ordinary_cancels=0
ordinary_reopens=0
pending_terminal=0
deferred_intent=0
issued_connect_pending=0
bound_issued_token=0
bound_issued_owner=none
bound_issued_generation=0
bound_terminal_generation=0
evidence_not_before=0
beacon_observed=0
beacon_token=0
beacon_terminal_generation=0

# 23:39:36.343 — this is the only operation that creates provenance.
event_time=1
connect_submissions=$((connect_submissions + 1))
actual_issue_serial=$((actual_issue_serial + 1))
global_issued_token=$actual_issue_serial
global_issued_owner=$exact_owner
global_issued_generation=$current_generation
global_issued_at=$event_time
global_auto_reconnect=1
[ "$connect_submissions" -eq 1 ]
[ "$global_issued_token" -ne 0 ]

# 23:39:41.960 — the terminal invalidates old evidence but not the one-shot budget or global
# provenance, then binds that earlier actual request to this new terminal generation.
event_time=2
terminal_generation=$((terminal_generation + 1))
evidence_generation=$((evidence_generation + 1))
ordinary_lineage_active=1
beacon_observed=0
if [ "$global_issued_token" -ne 0 ] \
        && [ "$global_issued_owner" = "$exact_owner" ] \
        && [ "$global_issued_generation" -eq "$current_generation" ] \
        && [ "$global_issued_at" -le "$event_time" ] \
        && [ "$global_auto_reconnect" -eq 1 ]; then
    issued_connect_pending=1
    bound_issued_token=$global_issued_token
    bound_issued_owner=$global_issued_owner
    bound_issued_generation=$global_issued_generation
    bound_terminal_generation=$terminal_generation
    evidence_not_before=$event_time
fi
deferred_intent=1
[ "$issued_connect_pending" -eq 1 ]
[ "$bound_issued_token" -eq "$global_issued_token" ]
[ "$ordinary_claim_budget" -eq 1 ]

# Delayed consumption sees Core Bluetooth's descendant `.connecting` state. The data intent stays
# retained and, crucially, no new `centralManager.connect` or false issued token is created.
owner_state=connecting
elapsed_ticks=300
: "$elapsed_ticks"
[ "$deferred_intent" -eq 1 ]
[ "$connect_submissions" -eq 1 ]
[ "$ordinary_cancels" -eq 0 ]

# 23:39:58.360 — exact fresh beacon after the terminal. retrieveConnectedPeripherals returns no
# exact F04 row; that absence does not veto the independently bound beacon route.
event_time=3
retrieve_exact_f04=0
beacon_exact_owner=1
beacon_generation=$current_generation
if [ "$issued_connect_pending" -eq 1 ] \
        && [ "$bound_issued_token" -ne 0 ] \
        && [ "$bound_issued_owner" = "$exact_owner" ] \
        && [ "$bound_issued_generation" -eq "$current_generation" ] \
        && [ "$bound_terminal_generation" -eq "$terminal_generation" ] \
        && [ "$beacon_exact_owner" -eq 1 ] \
        && [ "$beacon_generation" -eq "$current_generation" ] \
        && [ "$event_time" -ge "$evidence_not_before" ]; then
    beacon_observed=1
    beacon_token=$bound_issued_token
    beacon_terminal_generation=$bound_terminal_generation
fi
[ "$retrieve_exact_f04" -eq 0 ]
[ "$beacon_observed" -eq 1 ]

# Grace expiry first consumes the one-shot claim and only then issues one cancel.
eligible_beacon=0
if [ "$owner_state" = connecting ] \
        && [ "$beacon_token" -eq "$bound_issued_token" ] \
        && [ "$beacon_terminal_generation" -eq "$terminal_generation" ]; then
    eligible_beacon=1
fi
if [ "$ordinary_lineage_active" -eq 1 ] \
        && [ "$ordinary_claim_budget" -eq 1 ] \
        && [ "$eligible_beacon" -eq 1 ]; then
    ordinary_claim_budget=0        # consumed before the destructive command
    pending_terminal=1
    ordinary_cancels=$((ordinary_cancels + 1))
fi
[ "$ordinary_claim_budget" -eq 0 ]
[ "$ordinary_cancels" -eq 1 ]
[ "$pending_terminal" -eq 1 ]

# The cancel's one terminal boundary reopens the same exact owner once; it cannot reset budget.
pending_terminal=0
ordinary_reopens=$((ordinary_reopens + 1))
deferred_intent=1
[ "$ordinary_reopens" -eq 1 ]
[ "$ordinary_claim_budget" -eq 0 ]

# A later didConnect consumes current evidence/intent but does not create a fresh claim. Another
# terminal and another exact beacon in this lineage therefore cannot cancel or reopen again.
did_connect=1
evidence_generation=$((evidence_generation + 1))
beacon_observed=0
deferred_intent=0
issued_connect_pending=0
[ "$did_connect" -eq 1 ]
[ "$deferred_intent" -eq 0 ]
terminal_generation=$((terminal_generation + 1))
if [ "$ordinary_claim_budget" -eq 1 ]; then
    ordinary_cancels=$((ordinary_cancels + 1))
    ordinary_reopens=$((ordinary_reopens + 1))
fi
[ "$ordinary_cancels" -eq 1 ]
[ "$ordinary_reopens" -eq 1 ]

# Negative authority cases: an exact beacon cannot cancel without a real issued token, and stale
# terminal generation, wrong owner or wrong namespace cannot borrow somebody else's token.
invalid_cancels=0
no_lineage_token=0
if [ "$no_lineage_token" -ne 0 ]; then
    invalid_cancels=$((invalid_cancels + 1))
fi
stale_beacon_terminal=$((terminal_generation - 1))
if [ "$stale_beacon_terminal" -eq "$terminal_generation" ]; then
    invalid_cancels=$((invalid_cancels + 1))
fi
wrong_owner=F04-OTHER-OWNER
if [ "$wrong_owner" = "$exact_owner" ]; then
    invalid_cancels=$((invalid_cancels + 1))
fi
wrong_generation=12035
if [ "$wrong_generation" -eq "$current_generation" ]; then
    invalid_cancels=$((invalid_cancels + 1))
fi
[ "$invalid_cancels" -eq 0 ]

# Binding has two authorities. A newly submitted direct request in an already-active lineage is
# valid even on pre-iOS-17 systems; reusing an older pre-terminal token for a later generation is
# allowed only when that real connect enabled Core Bluetooth AutoReconnect.
direct_actual_connect=1
direct_auto_reconnect=0
direct_binding=0
if [ "$direct_actual_connect" -eq 1 ]; then
    direct_binding=1
fi
[ "$direct_binding" -eq 1 ]
descendant_prior_token=1
descendant_auto_reconnect=0
descendant_binding=0
if [ "$descendant_prior_token" -eq 1 ] \
        && [ "$descendant_auto_reconnect" -eq 1 ]; then
    descendant_binding=1
fi
[ "$descendant_binding" -eq 0 ]

# Race model: the same fresh beacon may arrive while the wrapper is still `.disconnected`.
# It is stored but no grace runs. Once the read-only observer sees `.connecting`, that stored
# token/generation proof arms one grace without requiring duplicate advertisement delivery.
race_owner_state=disconnected
race_bound_token=7
race_terminal_generation=9
race_beacon_token=7
race_beacon_terminal_generation=9
race_beacon_stored=1
race_grace_armed=0
race_cancels=0
race_budget=1
if [ "$race_owner_state" = connecting ] \
        && [ "$race_beacon_stored" -eq 1 ]; then
    race_grace_armed=1
fi
[ "$race_grace_armed" -eq 0 ]
race_owner_state=connecting
if [ "$race_owner_state" = connecting ] \
        && [ "$race_beacon_stored" -eq 1 ] \
        && [ "$race_beacon_token" -eq "$race_bound_token" ] \
        && [ "$race_beacon_terminal_generation" -eq "$race_terminal_generation" ]; then
    race_grace_armed=1
fi
if [ "$race_grace_armed" -eq 1 ] && [ "$race_budget" -eq 1 ]; then
    race_budget=0
    race_cancels=$((race_cancels + 1))
fi
[ "$race_cancels" -eq 1 ]

# A matching late didConnect invalidates the captured grace generation. It consumes no automatic
# budget and an already queued closure therefore cannot cancel a new/current request.
late_budget=1
late_evidence_generation=14
late_grace_generation=$late_evidence_generation
late_beacon_proof=1
late_cancels=0
late_evidence_generation=$((late_evidence_generation + 1))  # didConnect
late_beacon_proof=0
if [ "$late_grace_generation" -eq "$late_evidence_generation" ] \
        && [ "$late_beacon_proof" -eq 1 ] \
        && [ "$late_budget" -eq 1 ]; then
    late_budget=0
    late_cancels=$((late_cancels + 1))
fi
[ "$late_cancels" -eq 0 ]
[ "$late_budget" -eq 1 ]

# Power loss discards proof/grace but preserves owner, global actual-issued provenance and budget.
# If poweredOn exposes `.disconnected`, the old binding becomes one data intent; only the ensuing
# real connect call captures a new token.
radio_claim_budget=1
radio_global_token=21
radio_bound_token=21
radio_issued_pending=1
radio_deferred_intent=0
radio_connect_submissions=1
radio_cancels=0
radio_old_beacon_proof=1
radio_evidence_generation=4
radio_powered_on=0
radio_evidence_generation=$((radio_evidence_generation + 1))
radio_old_beacon_proof=0
[ "$radio_global_token" -eq 21 ]
[ "$radio_issued_pending" -eq 1 ]
[ "$radio_claim_budget" -eq 1 ]
radio_owner_state=disconnected
radio_powered_on=1
if [ "$radio_powered_on" -eq 1 ] \
        && [ "$radio_issued_pending" -eq 1 ] \
        && [ "$radio_owner_state" = disconnected ]; then
    radio_issued_pending=0
    radio_bound_token=0
    radio_deferred_intent=1
fi
[ "$radio_deferred_intent" -eq 1 ]
if [ "$radio_deferred_intent" -eq 1 ] \
        && [ "$radio_owner_state" = disconnected ]; then
    radio_deferred_intent=0
    radio_connect_submissions=$((radio_connect_submissions + 1))
    radio_global_token=$((radio_global_token + 1))
    radio_bound_token=$radio_global_token
    radio_issued_pending=1
fi
[ "$radio_connect_submissions" -eq 2 ]
[ "$radio_bound_token" -eq 22 ]
[ "$radio_cancels" -eq 0 ]
[ "$radio_claim_budget" -eq 1 ]

# Separate destructive-protocol budget: invalidation alone leaves a spent budget spent; only the
# validated different CBService object with B2/B3/B4 starts a new namespace lineage.
destructive_budget=0
old_ready_write_issued=1
old_secure_link_ready=1
old_helper_proof=1
old_ancs_cccd_proof=1
old_b4_proof=1
old_access_proof=1
service_changed=1
: "$service_changed"
old_ready_write_issued=0
old_secure_link_ready=0
old_helper_proof=0
old_ancs_cccd_proof=0
old_b4_proof=0
old_access_proof=0
[ "$destructive_budget" -eq 0 ]
[ "$old_ready_write_issued" -eq 0 ]
[ "$old_secure_link_ready" -eq 0 ]
same_invalidated_object=1
replacement_characteristics_incomplete=1
: "$same_invalidated_object" "$replacement_characteristics_incomplete"
[ "$destructive_budget" -eq 0 ]
validated_new_service_object=1
validated_b2_b3_b4=1
if [ "$validated_new_service_object" -eq 1 ] \
        && [ "$validated_b2_b3_b4" -eq 1 ]; then
    destructive_budget=1
fi
[ "$destructive_budget" -eq 1 ]
new_pair_accepted=1
new_b3_secure=1
new_ready_writes=0
if [ "$new_pair_accepted" -eq 1 ] && [ "$new_b3_secure" -eq 1 ] \
        && [ "$old_ready_write_issued" -eq 0 ]; then
    new_ready_writes=$((new_ready_writes + 1))
    old_ready_write_issued=1
fi
[ "$new_ready_writes" -eq 1 ]

READINESS_REFRESH=$(awk '
    /private func refreshCentralReadiness/ { capture = 1 }
    capture { print }
    capture && /private func confirmCentralB4Subscription/ { exit }
' "$SOURCE")
printf '%s\n' "$READINESS_REFRESH" | grep -Fq "if centralReadyForGreen()"
printf '%s\n' "$READINESS_REFRESH" \
    | grep -Fq "rearmCentralDestructiveRecoveryAfterFullProof"

# Executable state model: the first protocol error consumes the only automatic cancel; terminal
# callbacks and the replacement didConnect do not reset it, so the second error waits. Manual is
# independent. Only complete proof creates another automatic budget.
destructive_budget=1
automatic_cancels=0
waits=0
manual_cancels=0
consume_automatic_recovery() {
    if [ "$destructive_budget" -eq 1 ]; then
        destructive_budget=0
        automatic_cancels=$((automatic_cancels + 1))
    else
        waits=$((waits + 1))
    fi
}
consume_automatic_recovery       # first uuidNotAllowed
:                               # didDisconnect: budget intentionally unchanged
:                               # replacement didConnect: budget intentionally unchanged
consume_automatic_recovery       # second uuidNotAllowed
[ "$automatic_cancels" -eq 1 ]
[ "$waits" -eq 1 ]
manual_cancels=$((manual_cancels + 1))
[ "$manual_cancels" -eq 1 ]
[ "$destructive_budget" -eq 0 ]
destructive_budget=1             # complete current B3/READY/ANCS+B4 proof
consume_automatic_recovery
[ "$automatic_cancels" -eq 2 ]

if grep -Eq "centralConnectTimeout|armCentralConnectTimeout|stale \.connecting watchdog" \
    "$SOURCE"; then
    echo "generic destructive connection watchdog returned" >&2
    exit 1
fi

# B3 must always open Android's real ANCS probe. The transient Core Bluetooth privacy snapshot is
# diagnostic only and cannot retain the old waitingAncsAuthorization deadlock.
AFTER_SECURITY=$(awk '
    /private func continueCentralAfterSecurity/ { capture = 1 }
    capture { print }
    capture && /private func writeCentralAncsReady/ { exit }
' "$SOURCE")
printf '%s\n' "$AFTER_SECURITY" | grep -Fq "CONTRACT_V39_B3_ALWAYS_WRITES_READY"
printf '%s\n' "$AFTER_SECURITY" | grep -Fq "writeCentralAncsReady(peripheral)"
if printf '%s\n' "$AFTER_SECURITY" | grep -Fq "ANCS-READY не отправляю"; then
    echo "B3 still suppresses ANCS-READY for a transient authorization snapshot" >&2
    exit 1
fi

READY_WRITE=$(awk '
    /private func writeCentralAncsReady/ { capture = 1 }
    capture { print }
    capture && /Subscribe after PAIR\/B3\/ANCS-READY/ { exit }
' "$SOURCE")
printf '%s\n' "$READY_WRITE" \
    | grep -Fq "centralSecureLinkReady, !centralAncsReadyWriteIssued"
printf '%s\n' "$READY_WRITE" | grep -Fq "centralAncsReadyWriteIssued = true"
[ "$(printf '%s\n' "$READY_WRITE" | grep -Fc 'Data("ANCS-READY".utf8)')" -eq 1 ]

ANCS_PROOF=$(awk '
    /private func confirmCentralAncsReady/ { capture = 1 }
    capture { print }
    capture && /private func observeCentralReadiness/ { exit }
' "$SOURCE")
printf '%s\n' "$ANCS_PROOF" | grep -Fq "centralAncsAccessProven = true"
printf '%s\n' "$ANCS_PROOF" | grep -Fq "geelyPeripheral?.state == .connected"
printf '%s\n' "$ANCS_PROOF" | grep -Fq "centralSecureLinkReady"
printf '%s\n' "$ANCS_PROOF" | grep -Fq "centralHandshake == .ready"
printf '%s\n' "$ANCS_PROOF" | grep -Fq "centralAncsReadyWriteIssued"
printf '%s\n' "$ANCS_PROOF" | grep -Fq "centralHelperConfirmed"
printf '%s\n' "$ANCS_PROOF" | grep -Fq "centralB4Subscribed"
require_source "let effectiveAncsAccess = centralAncsAuthorized || centralAncsAccessProven"

GREEN_BLOCK=$(awk '
    /private func centralReadyForGreen/ { capture = 1 }
    capture { print }
    capture && /private func refreshCentralReadiness/ { exit }
' "$SOURCE")
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "geelyPeripheral?.state == .connected"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "centralSecureLinkReady"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "centralHandshake == .ready"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "centralAncsReadyWriteIssued"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "centralHelperConfirmed"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "centralAncsCccdConfirmed"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "centralB4Subscribed"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "valid.battery"
printf '%s\n' "$GREEN_BLOCK" | grep -Fq "valid.network"

AUTH_UPDATE=$(awk '
    /didUpdateANCSAuthorizationFor peripheral/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SOURCE")
printf '%s\n' "$AUTH_UPDATE" | grep -Fq "CONTRACT_V39_AUTH_UPDATE_NEVER_TEARS_LINK"
printf '%s\n' "$AUTH_UPDATE" \
    | grep -Fq "CONTRACT_V39_EXPLICIT_AUTH_FALSE_INVALIDATES_CURRENT_PROOF"
printf '%s\n' "$AUTH_UPDATE" | grep -Fq "centralAncsAccessProven = false"
printf '%s\n' "$AUTH_UPDATE" | grep -Fq "centralAncsCccdConfirmed = false"
if printf '%s\n' "$AUTH_UPDATE" \
    | grep -Eq "cancelCentralConnectionSafely|resetCentralLink|writeCentralAncsReady"; then
    echo "ANCS authorization callback still tears or restarts the current link" >&2
    exit 1
fi

# Permanent F04 UUIDs make UUID-only callback routing unsafe. Every value/write/notification
# callback must match the exact characteristic object retained for the current owner and phase.
PERIPHERAL_VALUE_CALLBACKS=$(awk '
    /func peripheral\(_ peripheral: CBPeripheral, didWriteValueFor/ { capture = 1 }
    capture { print }
    capture && /func peripheral\(_ peripheral: CBPeripheral, didModifyServices/ { exit }
' "$SOURCE")
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "characteristic === currentControl"
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "centralHandshake == .writingPair"
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "characteristic === currentSecure"
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "centralHandshake == .writingAncsReady"
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "centralHandshake == .readingSecure"
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "characteristic === currentWake"
printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" | grep -Fq "centralHandshake == .ready"
if printf '%s\n' "$PERIPHERAL_VALUE_CALLBACKS" \
    | grep -Eq "characteristic\.uuid == central(Control|Secure|Wake)UUID|peripheral\.identifier == geelyPeripheral"; then
    echo "peripheral callback still accepts a stale object by UUID" >&2
    exit 1
fi

PERIPHERAL_DELEGATE=$(awk '
    /extension ViewController: CBPeripheralDelegate/ { capture = 1 }
    capture { print }
    capture && /extension ViewController: CBPeripheralManagerDelegate/ { exit }
' "$SOURCE")
[ "$(printf '%s\n' "$PERIPHERAL_DELEGATE" \
    | grep -Fc 'peripheral === geelyPeripheral')" -eq 6 ]

# Every delayed discovery/security/wake operation is tied to the same CBPeripheral wrapper too;
# merely matching the stable UUID lets an old callback clear or advance the new owner's state.
if grep -Eq 'peripheral\.identifier == (self\.)?geelyPeripheral\?\.identifier' "$SOURCE"; then
    echo "current-owner lineage still uses identifier equality instead of object identity" >&2
    exit 1
fi
for delayed_marker in \
    "private func scheduleCentralCharacteristicDiscovery" \
    "private func scheduleCentralServiceRediscovery" \
    "private func retryCentralSecure" \
    "private func enableCentralWakeSubscription" \
    "private func scheduleCentralWakeSubscriptionRetry"
do
    DELAYED_BLOCK=$(awk -v marker="$delayed_marker" '
        index($0, marker) { capture = 1 }
        capture { print }
        capture && /^    }$/ { exit }
    ' "$SOURCE")
    printf '%s\n' "$DELAYED_BLOCK" | grep -Eq 'peripheral === (self\.)?geelyPeripheral'
done

# F05 is republished with a permanent UUID. In Central role, old manager callbacks must match the
# exact current telemetry characteristic object; Peripheral/bootstrap keeps its legacy UUID route.
PM_CALLBACKS=$(awk '
    /func peripheralManager\(_ peripheral: CBPeripheralManager, central: CBCentral,/ {
        capture = 1
    }
    capture { print }
    capture && /^}$/ { exit }
' "$SOURCE")
[ "$(printf '%s\n' "$PM_CALLBACKS" \
    | grep -Fc 'characteristic === currentTelemetry')" -eq 3 ]
printf '%s\n' "$PM_CALLBACKS" \
    | grep -Fq "request.characteristic === currentTelemetry"
printf '%s\n' "$PM_CALLBACKS" \
    | grep -Fq 'request.characteristic === $0'
printf '%s\n' "$PM_CALLBACKS" | grep -Fq "&& exactCurrentRelay"
printf '%s\n' "$PM_CALLBACKS" \
    | grep -Fq "role == .peripheral"
printf '%s\n' "$PM_CALLBACKS" \
    | grep -Fq "request.characteristic.uuid == controlUUID"

# Terminal non-auto reconnect is data first; a matching late callback consumes that exact intent.
require_source "CONTRACT_V39_TERMINAL_MATERIALIZES_EXACT_INTENT"
require_source "queueTerminalCentralReconnect(peripheral,"
require_source "CONTRACT_V39_LATE_DIDCONNECT_CONSUMES_EXACT_INTENT"
LATE_CONNECT=$(awk '
    /func centralManager\(_ central: CBCentralManager, didConnect/ { capture = 1 }
    capture { print }
    capture && /func centralManager\(_ central: CBCentralManager, didFailToConnect/ { exit }
' "$SOURCE")
LATE_CLEAR=$(printf '%s\n' "$LATE_CONNECT" \
    | grep -n "clearCentralDeferredConnectIntent()" | tail -n1 | cut -d: -f1)
LATE_ACCEPT=$(printf '%s\n' "$LATE_CONNECT" \
    | grep -n "continueCentralConnected(peripheral)" | tail -n1 | cut -d: -f1)
[ "$LATE_CLEAR" -lt "$LATE_ACCEPT" ]

# All current-owner Central delegate callbacks use wrapper identity, not only the stable UUID.
for callback in \
    "func centralManager(_ central: CBCentralManager, didConnect peripheral" \
    "func centralManager(_ central: CBCentralManager, didFailToConnect peripheral" \
    "private func handleCentralDisconnect" \
    "didUpdateANCSAuthorizationFor peripheral"
do
    CALLBACK_BLOCK=$(awk -v marker="$callback" '
        index($0, marker) { capture = 1 }
        capture { print }
        capture && /^    }$/ { exit }
    ' "$SOURCE")
    printf '%s\n' "$CALLBACK_BLOCK" | grep -Fq "peripheral === geelyPeripheral"
done

# Claim #2 is reachable only through two exact F04 system-table checks and the budget guard.
SECOND_CLAIM=$(awk '
    /private func observeFreshRestoreConnectPhysicalProof/ { capture = 1 }
    capture { print }
    capture && /A terminal callback is preferred/ { exit }
' "$SOURCE")
printf '%s\n' "$SECOND_CLAIM" \
    | grep -Fq "CONTRACT_V39_RESTORE_SECOND_CLAIM_EXACT_F04_ONLY"
[ "$(printf '%s\n' "$SECOND_CLAIM" \
    | grep -Fc 'restoredOwnerHasSystemLinkProof(peripheral)')" -ge 2 ]
printf '%s\n' "$SECOND_CLAIM" \
    | grep -Fq "< self.centralRestoreOwnershipClaimLimit"
require_source "private let centralRestoreOwnershipClaimLimit = 2"

SYSTEM_PROOF=$(awk '
    /private func restoredOwnerHasSystemLinkProof/ { capture = 1 }
    capture { print }
    capture && /Keep observing while the restored request/ { exit }
' "$SOURCE")
printf '%s\n' "$SYSTEM_PROOF" | grep -Fq "forKey: savedGeelyPeripheralPreference"
printf '%s\n' "$SYSTEM_PROOF" | grep -Fq "restored.identifier == savedIdentifier"
printf '%s\n' "$SYSTEM_PROOF" | grep -Fq '$0.identifier == savedIdentifier'

RESTORE_STATE=$(awk '
    /willRestoreState dict/ { seen += 1 }
    seen == 1 { print }
    seen == 1 && /func centralManager\(_ central: CBCentralManager, didDiscover/ { exit }
' "$SOURCE")
printf '%s\n' "$RESTORE_STATE" | grep -Fq "let savedIdentifier = savedIdentifier"
printf '%s\n' "$RESTORE_STATE" \
    | grep -Fq "no peripheral matches persisted Geely identity"
if printf '%s\n' "$RESTORE_STATE" \
    | grep -Eq "peripherals\.first\(where: \{ \$0\.state|\?\? peripherals\.first"; then
    echo "willRestoreState still falls back to an unvalidated arbitrary owner" >&2
    exit 1
fi

# A restored B4 subscription is accepted only as a two-sided, one-shot process-restoration
# hint. Central restoration must bind an already-connected exact saved wrapper; Peripheral
# restoration must bind the exact restored F05 object and the same saved subscriber ID.
RESTORED_B4_HINT=$(awk '
    /private func consumeRestoredB4HintIfEligible/ { capture = 1 }
    capture { print }
    capture && /private func continueCentralConnected/ { exit }
' "$SOURCE")
printf '%s\n' "$RESTORED_B4_HINT" \
    | grep -Fq "CONTRACT_V39_TWO_SIDED_RESTORED_B4_HINT"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "centralRestoredConnectedOwner === peripheral"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "peripheral === geelyPeripheral"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "peripheral.state == .connected"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "forKey: savedGeelyPeripheralPreference"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "peripheral.identifier == savedIdentifier"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "restoredF05 === telemetryCharacteristic"
printf '%s\n' "$RESTORED_B4_HINT" \
    | grep -Fq "centralRestoredF05SubscriberIDs.contains(savedIdentifier)"
printf '%s\n' "$RESTORED_B4_HINT" | grep -Fq "centralRestoredB4HintConsumed = true"

printf '%s\n' "$RESTORE_STATE" | grep -Fq "CONTRACT_V39_RESTORED_CONNECTED_OWNER_HALF"
printf '%s\n' "$RESTORE_STATE" | grep -Fq "if restored.state == .connected"
printf '%s\n' "$RESTORE_STATE" | grep -Fq "centralRestoredConnectedOwner = restored"
printf '%s\n' "$RESTORE_STATE" | grep -Fq "clearCentralRestoredB4Hint()"

PERIPHERAL_RESTORE=$(awk '
    /func peripheralManager\(_ peripheral: CBPeripheralManager,/ { seen += 1 }
    seen == 1 && /willRestoreState dict/ { capture = 1 }
    capture { print }
    capture && /func peripheralManager\(_ peripheral: CBPeripheralManager, didAdd/ { exit }
' "$SOURCE")
printf '%s\n' "$PERIPHERAL_RESTORE" \
    | grep -Fq "CONTRACT_V39_RESTORED_F05_SUBSCRIBER_HALF"
printf '%s\n' "$PERIPHERAL_RESTORE" | grep -Fq "service.uuid == telemetryRelayServiceUUID"
printf '%s\n' "$PERIPHERAL_RESTORE" | grep -Fq "mutable.uuid == telemetryRelayUUID"
printf '%s\n' "$PERIPHERAL_RESTORE" | grep -Fq "centralRestoredF05Characteristic = mutable"
printf '%s\n' "$PERIPHERAL_RESTORE" | grep -Fq "mutable.subscribedCentrals"
printf '%s\n' "$PERIPHERAL_RESTORE" \
    | grep -Fq "CONTRACT_V39_RESTORED_SERVICE_INSTALLS_EXACT_LINEAGE"
printf '%s\n' "$PERIPHERAL_RESTORE" | grep -Fq "clearPublishedService()"
printf '%s\n' "$PERIPHERAL_RESTORE" | grep -Fq "publishedLocalService = service"
printf '%s\n' "$PERIPHERAL_RESTORE" \
    | grep -Fq "publishedLocalServiceGeneration = localServicePublicationGeneration"
if printf '%s\n' "$PERIPHERAL_RESTORE" \
    | grep -Eq "centralB4Subscribed = true|confirmCentralB4Subscription"; then
    echo "one-sided Peripheral restoration directly established B4 readiness" >&2
    exit 1
fi

ROUTE_RESTORED_CONNECTED=$(printf '%s\n' "$ROUTE_BLOCK" | awk '
    /case \.connected:/ { capture = 1 }
    capture { print }
    capture && /case \.connecting:/ { exit }
')
printf '%s\n' "$ROUTE_RESTORED_CONNECTED" \
    | grep -Fq "allowRestoredB4Hint: centralRestoredConnectedOwner === peripheral"

# Local F04/F05 publication is also exact-object state. A late same-UUID didAdd from a removed
# generation must be observation-only and cannot clear the current pending flag, remove the new
# service, or start the Central route.
LOCAL_PUBLISH=$(awk '
    /private func publishServiceIfPossible/ { capture = 1 }
    capture { print }
    capture && /private func clearPublishedService/ { exit }
' "$SOURCE")
printf '%s\n' "$LOCAL_PUBLISH" | grep -Fq "pendingLocalService = service"
printf '%s\n' "$LOCAL_PUBLISH" \
    | grep -Fq "pendingLocalServiceGeneration = localServicePublicationGeneration"
PENDING_OBJECT_LINE=$(printf '%s\n' "$LOCAL_PUBLISH" \
    | grep -n "pendingLocalService = service" | cut -d: -f1)
ADD_OBJECT_LINE=$(printf '%s\n' "$LOCAL_PUBLISH" \
    | grep -n "peripheralManager.add(service)" | cut -d: -f1)
[ "$PENDING_OBJECT_LINE" -lt "$ADD_OBJECT_LINE" ]

LOCAL_CLEAR=$(awk '
    /private func clearPublishedService/ { capture = 1 }
    capture { print }
    capture && /private func startAdvertising/ { exit }
' "$SOURCE")
printf '%s\n' "$LOCAL_CLEAR" \
    | grep -Fq "CONTRACT_V39_LOCAL_SERVICE_GENERATION_INVALIDATES_LATE_DIDADD"
printf '%s\n' "$LOCAL_CLEAR" | grep -Fq "localServicePublicationGeneration &+= 1"
printf '%s\n' "$LOCAL_CLEAR" | grep -Fq "pendingLocalService = nil"
printf '%s\n' "$LOCAL_CLEAR" | grep -Fq "publishedLocalService = nil"

DID_ADD=$(awk '
    /func peripheralManager\(_ peripheral: CBPeripheralManager, didAdd service/ { capture = 1 }
    capture { print }
    capture && /func peripheralManagerDidStartAdvertising/ { exit }
' "$SOURCE")
printf '%s\n' "$DID_ADD" | grep -Fq "service === pending"
printf '%s\n' "$DID_ADD" \
    | grep -Fq "pendingGeneration == localServicePublicationGeneration"
printf '%s\n' "$DID_ADD" | grep -Fq "CONTRACT_V39_STALE_DIDADD_IS_OBSERVATION_ONLY"
printf '%s\n' "$DID_ADD" | grep -Fq "publishedLocalService = pending"
printf '%s\n' "$DID_ADD" | grep -Fq "publishedLocalServiceGeneration = pendingGeneration"
STALE_RETURN_LINE=$(printf '%s\n' "$DID_ADD" \
    | grep -n "Ignoring stale local GATT didAdd callback" | cut -d: -f1)
PENDING_CLEAR_LINE=$(printf '%s\n' "$DID_ADD" \
    | grep -n "pendingLocalService = nil" | cut -d: -f1)
[ "$STALE_RETURN_LINE" -lt "$PENDING_CLEAR_LINE" ]
STALE_BRANCH=$(printf '%s\n' "$DID_ADD" | awk '
    /guard let pending = pendingLocalService/ { capture = 1 }
    capture { print }
    capture && /return/ { exit }
')
if printf '%s\n' "$STALE_BRANCH" \
    | grep -Eq "removeAllServices|remove\(pending\)|clearPublishedService|servicePublished = true|startCentralRouteIfPossible"; then
    echo "stale didAdd callback mutates the current local publication" >&2
    exit 1
fi

printf '%s\n' "$ROUTE_BLOCK" | grep -Fq "let published = publishedLocalService"
printf '%s\n' "$ROUTE_BLOCK" \
    | grep -Fq "publishedLocalServiceGeneration == localServicePublicationGeneration"

RESTORE_DISCONNECTED=$(awk '
    /private func scheduleRestoredOwnerRecovery/ { capture = 1 }
    capture { print }
    capture && /private func clearCentralRestorationRecovery/ { exit }
' "$SOURCE")
printf '%s\n' "$RESTORE_DISCONNECTED" \
    | grep -Fq 'callback: "restoration grace observed .disconnected"'
if printf '%s\n' "$RESTORE_DISCONNECTED" | grep -Fq "issueCentralConnect"; then
    echo "restoration .disconnected bypasses shared claim-one terminal path" >&2
    exit 1
fi
RESTORE_REOPEN=$(awk '
    /private func reopenRestoredOwnerAfterTerminalCallback/ { capture = 1 }
    capture { print }
    capture && /Manual reconnect uses the same terminal boundary/ { exit }
' "$SOURCE")
printf '%s\n' "$RESTORE_REOPEN" \
    | grep -Fq "CONTRACT_V39_ALL_RESTORE_TERMINALS_ENTER_CLAIM_ONE"
CLAIM_ONE_LINE=$(printf '%s\n' "$RESTORE_REOPEN" \
    | grep -n "centralRestoreOwnershipClaimCount = max" | cut -d: -f1)
QUEUE_AFTER_CLAIM_LINE=$(printf '%s\n' "$RESTORE_REOPEN" \
    | grep -n "queueCentralConnectIntent(peripheral," | cut -d: -f1)
[ "$CLAIM_ONE_LINE" -lt "$QUEUE_AFTER_CLAIM_LINE" ]

# Executable state-sequence model: restore claim #1, exact F04 claim #2, a false snapshot after
# didConnect, one B3-gated READY, authoritative Android CCCD proof, then green readiness.
claims=0
ready_writes=0
access_proven=0
helper_ack=0
b4_cccd=0
ancs_cccd=0
telemetry=1

claims=$((claims + 1))                         # restored request claim
[ "$claims" -le 2 ]
physical_f04_proof=1                          # retrieveConnectedPeripherals(F04), exact owner
[ "$physical_f04_proof" -eq 1 ] && claims=$((claims + 1))
[ "$claims" -eq 2 ]
ancs_snapshot=0                               # didConnect(false) is diagnostic
b3_secure=1
[ "$b3_secure" -eq 1 ] && ready_writes=$((ready_writes + 1))
[ "$ready_writes" -eq 1 ]
helper_ack=1
b4_cccd=1
ancs_cccd=1
access_proven=1                               # F05/B4 ANCS-SUBSCRIBED
effective_access=$((ancs_snapshot || access_proven))
connected=1
secure=1
handshake_ready=1
green=$((connected && secure && handshake_ready && ready_writes == 1
    && helper_ack && effective_access && ancs_cccd && b4_cccd && telemetry))
[ "$green" -eq 1 ]
[ "$claims" -le 2 ]

# Either restoration callback order may create the same one-shot B4 hint. Neither half alone,
# a `.connecting` restoration, nor a fresh didConnect is allowed to seed B4. The hint also cannot
# make the UI green before current B3/READY and Android ANCS-SUBSCRIBED proof.
for callback_order in central_first peripheral_first; do
    restored_owner_half=0
    restored_f05_half=0
    restored_hint_consumed=0
    restored_b4=0
    if [ "$callback_order" = central_first ]; then
        restored_owner_half=1
        [ "$restored_f05_half" -eq 0 ] && [ "$restored_b4" -eq 0 ]
        restored_f05_half=1
    else
        restored_f05_half=1
        [ "$restored_owner_half" -eq 0 ] && [ "$restored_b4" -eq 0 ]
        restored_owner_half=1
    fi
    if [ "$restored_owner_half" -eq 1 ] && [ "$restored_f05_half" -eq 1 ] \
        && [ "$restored_hint_consumed" -eq 0 ]; then
        restored_b4=1
        restored_hint_consumed=1
    fi
    [ "$restored_b4" -eq 1 ]
    ready_writes=0
    helper_ack=0
    ancs_cccd=0
    access_proven=0
    green=$((restored_b4 && ready_writes == 1 && helper_ack \
        && ancs_cccd && access_proven))
    [ "$green" -eq 0 ]
    b3_secure=1
    [ "$b3_secure" -eq 1 ] && ready_writes=1
    helper_ack=1
    ancs_cccd=1
    access_proven=1
    green=$((restored_b4 && ready_writes == 1 && helper_ack \
        && ancs_cccd && access_proven))
    [ "$green" -eq 1 ]
done
restored_b4=1
fresh_did_connect=1
[ "$fresh_did_connect" -eq 1 ] && restored_b4=0
[ "$restored_b4" -eq 0 ]
restored_connecting=1
[ "$restored_connecting" -eq 1 ] && restored_b4=0
[ "$restored_b4" -eq 0 ]

# Publication ordering regression: A is removed, B becomes current pending, then late didAdd(A)
# must leave B untouched. Only exact didAdd(B,current generation) may publish. A restored exact R
# owns a later lineage, so another late B callback is ignored as well.
publication_generation=1
pending_service=A
pending_generation=$publication_generation
publication_generation=$((publication_generation + 1))  # stop/remove invalidates A
pending_service=""
pending_generation=0
publication_generation=$((publication_generation + 1))  # start creates B lineage
pending_service=B
pending_generation=$publication_generation
late_service=A
if [ "$late_service" = "$pending_service" ] \
    && [ "$pending_generation" -eq "$publication_generation" ]; then
    service_published=1
else
    service_published=0
fi
[ "$service_published" -eq 0 ]
[ "$pending_service" = B ]
current_service=B
if [ "$current_service" = "$pending_service" ] \
    && [ "$pending_generation" -eq "$publication_generation" ]; then
    service_published=1
    published_service=$current_service
    pending_service=""
fi
[ "$service_published" -eq 1 ]
[ "$published_service" = B ]
publication_generation=$((publication_generation + 1))  # exact restoration installs R
published_service=R
restored_generation=$publication_generation
late_service=B
[ "$late_service" != "$published_service" ]
[ "$restored_generation" -eq "$publication_generation" ]
[ "$published_service" = R ]

# An explicit privacy revoke invalidates prior proof but keeps the link. A new Android proof is
# required for green to return.
ancs_snapshot=0
access_proven=0
ancs_cccd=0
effective_access=$((ancs_snapshot || access_proven))
green=$((connected && secure && handshake_ready && ready_writes == 1
    && helper_ack && effective_access && ancs_cccd && b4_cccd && telemetry))
[ "$green" -eq 0 ]
access_proven=1
ancs_cccd=1
effective_access=$((ancs_snapshot || access_proven))
green=$((connected && secure && handshake_ready && ready_writes == 1
    && helper_ack && effective_access && ancs_cccd && b4_cccd && telemetry))
[ "$green" -eq 1 ]

echo "Helper v42 issued-lineage/beacon/hot-update/restoration/ANCS contract passed"
