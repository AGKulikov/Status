#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

usage() {
  echo "Usage: $0 audit|disable|restore [ecarx.notificationcenterui/.ExactComponent]" >&2
  exit 2
}

ACTION="${1:-}"
COMPONENT="${2:-}"
ADB_BIN="${ADB:-adb}"

if [[ "$ACTION" == "audit" ]]; then
  exec "$(dirname "$0")/kx11-shade-audit.sh"
fi

[[ "$ACTION" == "disable" || "$ACTION" == "restore" ]] || usage
[[ "$COMPONENT" == ecarx.notificationcenterui/* ]] || {
  echo "Refused: an exact ecarx.notificationcenterui/component is required." >&2
  exit 3
}
[[ "$COMPONENT" != "ecarx.notificationcenterui/" ]] || usage

if [[ "$ACTION" == "disable" ]]; then
  "$ADB_BIN" shell dumpsys package ecarx.notificationcenterui | grep -F -- "${COMPONENT#*/}" >/dev/null || {
    echo "Refused: component was not found in the installed package dump." >&2
    exit 4
  }
  "$ADB_BIN" shell pm disable-user --user 0 "$COMPONENT"
  echo "Disabled only $COMPONENT"
  echo "Recovery: $0 restore '$COMPONENT'"
else
  "$ADB_BIN" shell pm enable --user 0 "$COMPONENT"
  echo "Restored $COMPONENT"
fi
