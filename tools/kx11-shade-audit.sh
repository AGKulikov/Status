#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ADB_BIN="${ADB:-adb}"
OUTPUT="${1:-kx11-shade-audit-$(date +%Y%m%d-%H%M%S).txt}"

{
  echo "Natro KX11 system-shade audit"
  date -u '+UTC %Y-%m-%dT%H:%M:%SZ'
  "$ADB_BIN" shell getprop ro.build.fingerprint
  echo
  echo "== window owner =="
  "$ADB_BIN" shell dumpsys window windows | sed -n '/ecarx.notificationcenterui/,+18p'
  echo
  echo "== package components =="
  "$ADB_BIN" shell dumpsys package ecarx.notificationcenterui
  echo
  echo "== resolved activities and services =="
  "$ADB_BIN" shell cmd package query-activities -a android.intent.action.MAIN \
    -p ecarx.notificationcenterui 2>&1 || true
  "$ADB_BIN" shell cmd package query-services -p ecarx.notificationcenterui 2>&1 || true
  echo
  echo "== enabled/disabled state =="
  "$ADB_BIN" shell pm list packages -d | grep -F ecarx.notificationcenterui || true
  "$ADB_BIN" shell dumpsys package ecarx.notificationcenterui | \
    sed -n '/User 0:/,/User [1-9][0-9]*:/p'
} > "$OUTPUT"

echo "Audit saved: $OUTPUT"
echo "No component was changed. Attach this file before selecting the exact component."
