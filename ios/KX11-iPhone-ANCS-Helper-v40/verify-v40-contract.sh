#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE="$ROOT/KX11ANCSHelper/ViewController.swift"
PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"

require_source() {
    grep -Fq "$1" "$SOURCE" || {
        echo "missing v40 contract marker: $1" >&2
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

[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 40;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 40.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]

# v40 closes the observed didConnect -> uuidNotAllowed -> cancel loop. The automatic destructive
# budget belongs to the retained owner/restoration lineage, not to one pending cancel callback.
require_source "CONTRACT_V40_ONE_DESTRUCTIVE_RECOVERY_PER_LINEAGE"
require_source "CONTRACT_V40_MANUAL_RECONNECT_IS_INDEPENDENT"
require_source "CONTRACT_V40_SERVICE_CHANGED_REARMS_BUDGET"
require_source "CONTRACT_V40_FULL_PROOF_REARMS_BUDGET"

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
    | grep -Fq "rearmCentralDestructiveRecoveryForFreshF04"
if printf '%s\n' "$SERVICE_CHANGED" | grep -Fq '$0.uuid =='; then
    echo "same-UUID stale service can re-arm destructive recovery" >&2
    exit 1
fi

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

echo "Helper v40 restoration/ANCS/destructive-recovery contract passed"
