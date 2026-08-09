#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE="$ROOT/KX11ANCSHelper/ViewController.swift"
PROJECT="$ROOT/KX11ANCSHelper.xcodeproj/project.pbxproj"

require_source() {
    grep -Fq "$1" "$SOURCE" || {
        echo "missing v38 contract marker: $1" >&2
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
[ "$(printf '%s\n' "$ISSUE_BLOCK" | grep -Fc 'centralManager.connect(peripheral, options: options)')" -eq 1 ]
ISSUE_GATE_LINE=$(grep -n "CONTRACT_V38_ISSUE_CONNECT_POWER_GATE" "$SOURCE" | cut -d: -f1)
ISSUE_COMMAND_LINE=$(grep -n "centralManager.connect(peripheral, options: options)" "$SOURCE" \
    | cut -d: -f1)
[ "$ISSUE_GATE_LINE" -lt "$ISSUE_COMMAND_LINE" ]

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

MANUAL_TERMINALS=$(grep -Fc \
    "reopenManualOwnerAfterTerminalCallback(peripheral," "$SOURCE")
[ "$MANUAL_TERMINALS" -eq 2 ] || {
    echo "manual reconnect must be consumed by exactly two terminal callbacks" >&2
    exit 1
}

[ "$(grep -Fc 'CURRENT_PROJECT_VERSION = 38;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'MARKETING_VERSION = 38.0;' "$PROJECT")" -eq 2 ]
[ "$(grep -Fc 'PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;' "$PROJECT")" -eq 2 ]

if grep -Eq "centralConnectTimeout|armCentralConnectTimeout|stale \.connecting watchdog" \
    "$SOURCE"; then
    echo "generic destructive connection watchdog returned" >&2
    exit 1
fi

echo "Helper v38 restoration contract passed"
