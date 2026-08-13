#!/bin/bash

# KX11 Bluetooth evidence collector for macOS/Linux.
#
# The head unit is accessed read-only. This script never remounts a partition,
# edits/deletes a head-unit file, toggles Bluetooth, restarts a Bluetooth
# service, clears a cache, or changes a setting. With the explicit --root flag
# it runs `adb root` once; that only restarts adbd and is used to read protected
# Bluetooth evidence.

set -u
set -o pipefail
umask 077

SCRIPT_VERSION="1.0"
ROOT_MODE=0
ADB_SERIAL=""
OUTPUT_PARENT="${PWD}"

usage() {
  cat <<'EOF'
KX11 Bluetooth collector (read-only on the head unit)

Usage:
  KX11_Bluetooth_Collect.command [--root] [--serial SERIAL] [--output DIR]

Options:
  --root          Explicitly run `adb root` once (restarts adbd only), then
                  collect protected Bluetooth state such as pairing/link keys.
  --serial VALUE  Select an ADB device by serial. Required only when more than
                  one device is connected.
  --output DIR    Parent directory for the timestamped result. Default: current
                  directory.
  -h, --help      Show this help.

No command in this collector writes to the head unit. The result is private:
it can contain Bluetooth addresses, pairing material/link keys and logs.
EOF
}

fail() {
  printf '\nERROR: %s\n' "$1" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --root)
      ROOT_MODE=1
      shift
      ;;
    --serial)
      [ "$#" -ge 2 ] || fail "--serial requires a value"
      ADB_SERIAL="$2"
      shift 2
      ;;
    --output)
      [ "$#" -ge 2 ] || fail "--output requires a directory"
      OUTPUT_PARENT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1 (use --help)"
      ;;
  esac
done

command -v adb >/dev/null 2>&1 || fail \
  "adb was not found; install Android Platform Tools first"
command -v tar >/dev/null 2>&1 || fail "tar was not found"

mkdir -p "$OUTPUT_PARENT" || fail "cannot create output parent: $OUTPUT_PARENT"
[ -d "$OUTPUT_PARENT" ] || fail "output parent is not a directory: $OUTPUT_PARENT"

TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
RESULT_NAME="KX11_Bluetooth_Collect_${TIMESTAMP}"
RESULT_DIR="${OUTPUT_PARENT%/}/${RESULT_NAME}"
ARCHIVE_PATH="${RESULT_DIR}.tar.gz"
ARCHIVE_SHA_PATH="${ARCHIVE_PATH}.sha256"

[ ! -e "$RESULT_DIR" ] || fail "result already exists: $RESULT_DIR"
[ ! -e "$ARCHIVE_PATH" ] || fail "archive already exists: $ARCHIVE_PATH"

mkdir -p \
  "$RESULT_DIR/META" \
  "$RESULT_DIR/DIAGNOSTICS" \
  "$RESULT_DIR/DISCOVERY" \
  "$RESULT_DIR/MANIFESTS" \
  "$RESULT_DIR/FILES" \
  "$RESULT_DIR/LOGS" || fail "cannot create local result directories"
chmod 700 "$RESULT_DIR"

COLLECTOR_LOG="$RESULT_DIR/LOGS/collector.log"
ERROR_LOG="$RESULT_DIR/LOGS/nonfatal_errors.log"
PULL_LOG="$RESULT_DIR/LOGS/adb_pull.log"
: >"$COLLECTOR_LOG"
: >"$ERROR_LOG"
: >"$PULL_LOG"

say() {
  printf '%s\n' "$1"
  printf '%s\n' "$1" >>"$COLLECTOR_LOG"
}

note_error() {
  printf '%s\n' "$1" >>"$ERROR_LOG"
}

# Quote one string for the remote Android /system/bin/sh. All paths fed to
# this function are either fixed collector paths or validated absolute paths.
remote_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

path_key() {
  printf '%s' "$1" | sed 's#^/##; s#[^A-Za-z0-9._-]#_#g'
}

is_allowed_remote_path() {
  case "$1" in
    /system/*|/vendor/*|/odm/*|/product/*|/system_ext/*|/data/misc/bluedroid/*|\
    /data/misc/bluetooth/*|/data/vendor/bluetooth/*|\
    /data/user/0/com.ts.dm.service/databases/*|\
    /data/data/com.ts.dm.service/databases/*|/sdcard/*|/storage/emulated/0/*)
      ;;
    /system|/vendor|/odm|/product|/system_ext|/data/misc/bluedroid|\
    /data/misc/bluetooth|/data/vendor/bluetooth|\
    /data/user/0/com.ts.dm.service/databases|\
    /data/data/com.ts.dm.service/databases|/sdcard|/storage/emulated/0)
      ;;
    *)
      return 1
      ;;
  esac
  case "/$1/" in
    */../*) return 1 ;;
  esac
  return 0
}

adb start-server >/dev/null 2>&1 || fail "could not start the local ADB server"

ADB_DEVICES_RAW="$(adb devices 2>/dev/null)" || fail "could not query ADB devices"
if [ -n "$ADB_SERIAL" ]; then
  DEVICE_STATE="$(printf '%s\n' "$ADB_DEVICES_RAW" | awk -v serial="$ADB_SERIAL" \
    '$1 == serial { print $2; exit }')"
  [ "$DEVICE_STATE" = "device" ] || fail \
    "ADB device '$ADB_SERIAL' is not ready (state: ${DEVICE_STATE:-not found})"
else
  READY_COUNT="$(printf '%s\n' "$ADB_DEVICES_RAW" | \
    awk '$2 == "device" { n++ } END { print n + 0 }')"
  if [ "$READY_COUNT" -eq 0 ]; then
    printf '%s\n' "$ADB_DEVICES_RAW" >&2
    fail "no authorized ADB device is ready"
  fi
  if [ "$READY_COUNT" -gt 1 ]; then
    printf '%s\n' "$ADB_DEVICES_RAW" >&2
    fail "more than one ADB device is ready; use --serial"
  fi
  ADB_SERIAL="$(printf '%s\n' "$ADB_DEVICES_RAW" | \
    awk '$2 == "device" { print $1; exit }')"
fi

ADB=(adb -s "$ADB_SERIAL")
"${ADB[@]}" wait-for-device >/dev/null 2>&1 || fail "ADB device did not become ready"

if [ "$ROOT_MODE" -eq 1 ]; then
  say "--root selected: running adb root once; this restarts adbd only."
  say "No Bluetooth service, adapter, cache, setting or head-unit file is changed."
  if ! "${ADB[@]}" root >"$RESULT_DIR/LOGS/adb_root.log" 2>&1; then
    fail "adb root failed; see LOGS/adb_root.log"
  fi
  "${ADB[@]}" wait-for-device >/dev/null 2>&1 || fail "device did not return after adb root"
  REMOTE_UID="$("${ADB[@]}" shell 'id -u' 2>/dev/null | tr -d '\r\n')"
  [ "$REMOTE_UID" = "0" ] || fail \
    "--root was requested but adbd UID is ${REMOTE_UID:-unknown}; protected data was not read"
else
  say "Standard read-only mode. adb root will not be called."
fi

say "Collecting private Bluetooth evidence from one ADB device."
say "Raw values are written only to the protected local result, not printed here."

printf '%s\n' \
  "KX11 Bluetooth evidence collection" \
  "Collector version: $SCRIPT_VERSION" \
  "Collected at local time: $(date '+%Y-%m-%d %H:%M:%S %z')" \
  "Explicit root mode: $ROOT_MODE" \
  "" \
  "PRIVACY WARNING" \
  "This directory and its archive may contain Bluetooth device addresses," \
  "pairing records/link keys, notification-related logs and proprietary files." \
  "Keep it private. Do not post the archive publicly without redaction." \
  "" \
  "HEAD-UNIT SAFETY" \
  "The collector only executed read/query/pull commands on the head unit." \
  "It did not remount, edit, delete, push, install, toggle Bluetooth, restart" \
  "Bluetooth services, clear caches, or change Android settings." \
  "When explicitly selected, adb root restarted adbd solely for read access." \
  >"$RESULT_DIR/README_PRIVATE.txt"

run_remote_capture() {
  local output_file="$1"
  local remote_command="$2"
  mkdir -p "$(dirname "$output_file")"
  if ! "${ADB[@]}" shell "$remote_command" >"$output_file" 2>&1; then
    note_error "remote capture failed: $(basename "$output_file")"
  fi
  chmod 600 "$output_file" 2>/dev/null || true
}

remote_exists() {
  local quoted
  quoted="$(remote_quote "$1")"
  "${ADB[@]}" shell "[ -e $quoted ]" >/dev/null 2>&1
}

pull_remote() {
  local remote_path="$1"
  local local_path
  if ! is_allowed_remote_path "$remote_path"; then
    note_error "skipped unsafe/unexpected remote path: $remote_path"
    return 0
  fi
  if ! remote_exists "$remote_path"; then
    note_error "remote path missing or unreadable: $remote_path"
    return 0
  fi
  local_path="$RESULT_DIR/FILES$remote_path"
  if [ -e "$local_path" ]; then
    return 0
  fi
  mkdir -p "$(dirname "$local_path")"
  if ! "${ADB[@]}" pull "$remote_path" "$local_path" >>"$PULL_LOG" 2>&1; then
    note_error "adb pull failed: $remote_path"
    return 0
  fi
  chmod -R go-rwx "$local_path" 2>/dev/null || true
}

capture_path_manifests() {
  local remote_path="$1"
  local key quoted
  key="$(path_key "$remote_path")"
  quoted="$(remote_quote "$remote_path")"
  if ! remote_exists "$remote_path"; then
    note_error "manifest path missing or unreadable: $remote_path"
    return 0
  fi
  run_remote_capture "$RESULT_DIR/MANIFESTS/${key}.ls-laZ.txt" \
    "ls -laZ $quoted"
  run_remote_capture "$RESULT_DIR/MANIFESTS/${key}.recursive-ls-laZ.txt" \
    "if command -v find >/dev/null 2>&1; then find $quoted -exec ls -ldZ '{}' ';' 2>/dev/null || find $quoted -exec ls -ld '{}' ';' 2>/dev/null; else ls -laZ $quoted; fi"
  run_remote_capture "$RESULT_DIR/MANIFESTS/${key}.remote-sha256.txt" \
    "if command -v sha256sum >/dev/null 2>&1; then find $quoted -type f -exec sha256sum '{}' ';' 2>/dev/null; elif command -v toybox >/dev/null 2>&1; then find $quoted -type f -exec toybox sha256sum '{}' ';' 2>/dev/null; else echo 'sha256sum unavailable on head unit'; fi"
}

say "[1/7] Build, properties and service inventory"
run_remote_capture "$RESULT_DIR/META/getprop.txt" "getprop"
run_remote_capture "$RESULT_DIR/META/build_fingerprint.txt" "getprop ro.build.fingerprint"
run_remote_capture "$RESULT_DIR/META/device_identity.txt" \
  "printf 'model='; getprop ro.product.model; printf 'android='; getprop ro.build.version.release; printf 'sdk='; getprop ro.build.version.sdk; uname -a; id; getenforce 2>/dev/null"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/service_list.txt" "service list"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/dumpsys_services.txt" "dumpsys -l"

say "[2/7] Bluetooth and package diagnostics"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/dumpsys_bluetooth_manager.txt" \
  "dumpsys bluetooth_manager"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/dumpsys_bluetooth.txt" "dumpsys bluetooth"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/dumpsys_package_com.android.bluetooth.txt" \
  "dumpsys package com.android.bluetooth"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/dumpsys_package_com.ts.dm.service.txt" \
  "dumpsys package com.ts.dm.service"
run_remote_capture "$RESULT_DIR/DIAGNOSTICS/overlay_list.txt" \
  "cmd overlay list 2>/dev/null || true"
run_remote_capture "$RESULT_DIR/DISCOVERY/packages_full_paths.txt" "pm list packages -f"
run_remote_capture "$RESULT_DIR/DISCOVERY/com.android.bluetooth_paths.txt" \
  "pm path com.android.bluetooth"

SELECTED_PACKAGES="$RESULT_DIR/DISCOVERY/selected_bluetooth_vendor_packages.txt"
: >"$SELECTED_PACKAGES"
printf '%s\n' "com.android.bluetooth" >>"$SELECTED_PACKAGES"
while IFS= read -r package_line; do
  package_line="$(printf '%s' "$package_line" | tr -d '\r')"
  package_name="${package_line##*=}"
  package_lower="$(printf '%s' "$package_name" | tr '[:upper:]' '[:lower:]')"
  case "$package_lower" in
    *bluetooth*|*nfore*|*btphone*|*btservice*|*dialer*|*contacts*|\
    com.ts.dm.service|*dm.service*|*devicemanager*|\
    com.ecarx.*phone*|com.ecarx.*bt*|com.ecarx.*dim*|com.ecarx.*dms*|\
    com.geely.*phone*|com.geely.*bt*|com.geely.*dim*|com.geely.*dms*)
      case "$package_name" in
        *[!A-Za-z0-9._-]*|'') ;;
        *) printf '%s\n' "$package_name" >>"$SELECTED_PACKAGES" ;;
      esac
      ;;
  esac
done <"$RESULT_DIR/DISCOVERY/packages_full_paths.txt"
LC_ALL=C sort -u "$SELECTED_PACKAGES" >"${SELECTED_PACKAGES}.sorted"
mv "${SELECTED_PACKAGES}.sorted" "$SELECTED_PACKAGES"

SELECTED_PACKAGE_PATHS="$RESULT_DIR/DISCOVERY/selected_package_paths.txt"
: >"$SELECTED_PACKAGE_PATHS"
while IFS= read -r package_name; do
  [ -n "$package_name" ] || continue
  package_quoted="$(remote_quote "$package_name")"
  if ! "${ADB[@]}" shell "pm path $package_quoted" \
    >>"$SELECTED_PACKAGE_PATHS" 2>>"$ERROR_LOG"; then
    note_error "pm path failed: $package_name"
  fi
done <"$SELECTED_PACKAGES"

say "[3/7] Bluetooth configuration directories and nFore backups"
CONFIG_DIRS="
/system/etc/bluetooth
/vendor/etc/bluetooth
/odm/etc/bluetooth
/product/etc/bluetooth
/system_ext/etc/bluetooth
"
printf '%s\n' "$CONFIG_DIRS" | while IFS= read -r remote_path; do
  [ -n "$remote_path" ] || continue
  capture_path_manifests "$remote_path"
  pull_remote "$remote_path"
done

NFORE_LIST="$RESULT_DIR/DISCOVERY/nfore_files.txt"
run_remote_capture "$NFORE_LIST" \
  "find /system/etc/bluetooth /vendor/etc/bluetooth /odm/etc/bluetooth /product/etc/bluetooth /system_ext/etc/bluetooth -type f 2>/dev/null | grep -E '/nForeBluetooth[^/]*$' || true"

say "[4/7] Relevant Bluetooth/ECARX/DM APK, JAR and native libraries"
APK_LIST="$RESULT_DIR/DISCOVERY/discovered_bluetooth_vendor_apks.txt"
JAR_LIST="$RESULT_DIR/DISCOVERY/discovered_bluetooth_vendor_jars.txt"
LIB_LIST="$RESULT_DIR/DISCOVERY/discovered_bluetooth_vendor_native_libs.txt"
run_remote_capture "$APK_LIST" \
  "find /system/app /system/priv-app /vendor/app /vendor/priv-app /odm/app /odm/priv-app /product/app /product/priv-app /system_ext/app /system_ext/priv-app -type f 2>/dev/null | grep -Ei '/[^/]*(bluetooth|nfore|btphone|btservice|dialer|contacts|phone|dim|dms|dmservice|dm[-_.]?service|devicemanager)[^/]*[.]apk$' || true"
run_remote_capture "$JAR_LIST" \
  "find /system/framework /vendor/framework /odm/framework /product/framework /system_ext/framework -type f 2>/dev/null | grep -Ei '/[^/]*(bluetooth|nfore|ecarx|geely|adaptapi|psd|ts[-_.]?dm|dm[-_.]?(foundation|service)|dim|dms)[^/]*[.]jar$' || true"
run_remote_capture "$LIB_LIST" \
  "find /system/lib /system/lib64 /vendor/lib /vendor/lib64 /odm/lib /odm/lib64 /product/lib /product/lib64 /system_ext/lib /system_ext/lib64 -type f 2>/dev/null | grep -Ei '/(lib[^/]*(bluetooth|nfore)|libbt[-_a-z0-9]*|lib[^/]*(ecarx|geely|dim|dms)[^/]*(bt|bluetooth)|lib[^/]*(bt|bluetooth)[^/]*(ecarx|geely|dim|dms))[^/]*[.]so$' || true"

for path_list in "$SELECTED_PACKAGE_PATHS" "$APK_LIST" "$JAR_LIST" "$LIB_LIST" "$NFORE_LIST"; do
  while IFS= read -r remote_path; do
    remote_path="$(printf '%s' "$remote_path" | tr -d '\r')"
    case "$remote_path" in package:*) remote_path="${remote_path#package:}" ;; esac
    [ -n "$remote_path" ] || continue
    pull_remote "$remote_path"
  done <"$path_list"
done

# Known KX11 vendor seams are listed explicitly because their filenames need not contain
# "bluetooth". Missing paths remain non-fatal and are recorded in the collector log.
for known_vendor_file in \
  /system/framework/ecarx.adaptapi.jar \
  /system/framework/ts-dm-foundation-lib.jar \
  /system/framework/ts-platform-library.jar; do
  pull_remote "$known_vendor_file"
done

say "[5/7] Optional protected pairing state and btsnoop"
PUBLIC_BTSNOOP_LIST="$RESULT_DIR/DISCOVERY/public_btsnoop_files.txt"
: >"$PUBLIC_BTSNOOP_LIST"
for public_snoop in \
  /sdcard/btsnoop_hci.log \
  /sdcard/btsnoop_hci.log.last \
  /storage/emulated/0/btsnoop_hci.log \
  /storage/emulated/0/btsnoop_hci.log.last; do
  if remote_exists "$public_snoop"; then
    printf '%s\n' "$public_snoop" >>"$PUBLIC_BTSNOOP_LIST"
    pull_remote "$public_snoop"
  fi
done

if [ "$ROOT_MODE" -eq 1 ]; then
  say "Protected mode: pairing/link-key material is being stored locally with mode 600."
  PRIVATE_DIRS="
/data/misc/bluedroid
/data/misc/bluetooth
/data/vendor/bluetooth
"
  printf '%s\n' "$PRIVATE_DIRS" | while IFS= read -r remote_path; do
    [ -n "$remote_path" ] || continue
    capture_path_manifests "$remote_path"
    pull_remote "$remote_path"
  done
  PRIVATE_BTSNOOP_LIST="$RESULT_DIR/DISCOVERY/private_btsnoop_files.txt"
  run_remote_capture "$PRIVATE_BTSNOOP_LIST" \
    "find /data/misc/bluedroid /data/misc/bluetooth /data/vendor/bluetooth -type f 2>/dev/null | grep -Ei '(btsnoop|hci[^/]*snoop)' || true"
  while IFS= read -r remote_path; do
    remote_path="$(printf '%s' "$remote_path" | tr -d '\r')"
    [ -n "$remote_path" ] || continue
    pull_remote "$remote_path"
  done <"$PRIVATE_BTSNOOP_LIST"

  # ECARX's device-manager cache is a likely source of duplicate UI phone rows. Pull only its
  # database directory (including WAL/SHM), never the rest of the app's private data.
  for dm_database_dir in \
    /data/user/0/com.ts.dm.service/databases \
    /data/data/com.ts.dm.service/databases; do
    capture_path_manifests "$dm_database_dir"
    pull_remote "$dm_database_dir"
  done
else
  printf '%s\n' \
    "Not collected: protected pairing state and private btsnoop files." \
    "Re-run with the explicit --root flag if those bytes are required." \
    >"$RESULT_DIR/DISCOVERY/protected_data_not_collected.txt"
fi

say "[6/7] Bluetooth-focused logcat slice"
LOGCAT_SLICE="$RESULT_DIR/DIAGNOSTICS/logcat_bluetooth_slice.txt"
LOGCAT_TMP="$RESULT_DIR/LOGS/.logcat-current.tmp"
if "${ADB[@]}" logcat -d -v threadtime >"$LOGCAT_TMP" 2>>"$ERROR_LOG"; then
  grep -Eai \
    'bluetooth|bt_stack|btif|btm_|bta_|gatt|nfore|ancs|a2dp|avrcp|headset|hfp|pbap|bondstate|btsnoop|ecarx.*(bt|phone)|(^|[^a-z])ble([^a-z]|$)' \
    "$LOGCAT_TMP" >"$LOGCAT_SLICE" || :
else
  : >"$LOGCAT_SLICE"
  note_error "adb logcat capture failed"
fi
rm -f "$LOGCAT_TMP"
chmod 600 "$LOGCAT_SLICE"

say "[7/7] Local integrity manifest and private archive"
chmod -R go-rwx "$RESULT_DIR" 2>/dev/null || true
LOCAL_MANIFEST="$RESULT_DIR/MANIFESTS/local_files.sha256"
: >"$LOCAL_MANIFEST"
if command -v sha256sum >/dev/null 2>&1; then
  (
    cd "$RESULT_DIR" || exit 1
    find . -type f ! -path './MANIFESTS/local_files.sha256' \
      -exec sha256sum '{}' ';' | LC_ALL=C sort
  ) >"$LOCAL_MANIFEST" || fail "could not create local SHA-256 manifest"
elif command -v shasum >/dev/null 2>&1; then
  (
    cd "$RESULT_DIR" || exit 1
    find . -type f ! -path './MANIFESTS/local_files.sha256' \
      -exec shasum -a 256 '{}' ';' | LC_ALL=C sort
  ) >"$LOCAL_MANIFEST" || fail "could not create local SHA-256 manifest"
else
  fail "neither sha256sum nor shasum is available locally"
fi

chmod -R go-rwx "$RESULT_DIR" 2>/dev/null || true
tar -czf "$ARCHIVE_PATH" -C "$OUTPUT_PARENT" "$RESULT_NAME" || \
  fail "could not create archive: $ARCHIVE_PATH"
chmod 600 "$ARCHIVE_PATH"

if command -v sha256sum >/dev/null 2>&1; then
  ARCHIVE_HASH="$(sha256sum "$ARCHIVE_PATH" | awk '{print $1}')"
else
  ARCHIVE_HASH="$(shasum -a 256 "$ARCHIVE_PATH" | awk '{print $1}')"
fi
printf '%s  %s\n' "$ARCHIVE_HASH" "$(basename "$ARCHIVE_PATH")" \
  >"$ARCHIVE_SHA_PATH"
chmod 600 "$ARCHIVE_SHA_PATH"

say "Collection complete."
printf '\nPrivate directory: %s\n' "$RESULT_DIR"
printf 'Private archive:   %s\n' "$ARCHIVE_PATH"
printf 'Archive SHA-256:   %s\n' "$ARCHIVE_HASH"
printf '\nKeep the archive private: it may contain addresses and Bluetooth link keys.\n'
