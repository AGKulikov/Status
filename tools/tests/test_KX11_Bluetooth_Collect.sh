#!/bin/bash

set -eu
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COLLECTOR="$SCRIPT_DIR/KX11_Bluetooth_Collect.command"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kx11-bt-collect-test.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT HUP INT TERM

FAKE_BIN="$TMP_ROOT/bin"
ADB_LOG="$TMP_ROOT/adb.log"
ADB_STATE="$TMP_ROOT/adb-state"
mkdir -p "$FAKE_BIN" "$ADB_STATE" "$TMP_ROOT/out-normal" "$TMP_ROOT/out-root"

cat >"$FAKE_BIN/adb" <<'FAKE_ADB'
#!/bin/bash
set -u
printf '%s\n' "$*" >>"$FAKE_ADB_LOG"

if [ "${1:-}" = "-s" ]; then
  shift 2
fi

case "${1:-}" in
  start-server|wait-for-device)
    exit 0
    ;;
  devices)
    printf 'List of devices attached\nFAKE-KX11\tdevice\n'
    exit 0
    ;;
  root)
    : >"$FAKE_ADB_STATE/root"
    printf 'restarting adbd as root\n'
    exit 0
    ;;
  pull)
    remote="${2:-}"
    destination="${3:-}"
    case "$remote" in
      *.apk|*.jar|*.so|*.properties|*.bak|*.log|*.conf)
        mkdir -p "$(dirname "$destination")"
        printf 'fake bytes for %s\n' "$remote" >"$destination"
        ;;
      *)
        mkdir -p "$destination"
        printf 'fake directory bytes\n' >"$destination/fake.bin"
        ;;
    esac
    exit 0
    ;;
  logcat)
    printf '08-13 12:00:00.000 I/BluetoothGatt: fake GATT event\n'
    exit 0
    ;;
  shell)
    shift
    command_text="$*"
    printf 'SHELL %s\n' "$command_text" >>"$FAKE_ADB_LOG"
    case "$command_text" in
      "[ -e '/odm/etc/bluetooth' ]")
        exit 1
        ;;
      'id -u')
        if [ -f "$FAKE_ADB_STATE/root" ]; then printf '0\n'; else printf '2000\n'; fi
        ;;
      *'pm list packages -f'*)
        printf 'package:/system/priv-app/Bluetooth/Bluetooth.apk=com.android.bluetooth\n'
        printf 'package:/system/app/EcarxBtPhone/EcarxBtPhone.apk=com.ecarx.btphone\n'
        ;;
      *'pm path com.android.bluetooth'*)
        printf 'package:/system/priv-app/Bluetooth/Bluetooth.apk\n'
        ;;
      *'pm path com.ecarx.btphone'*)
        printf 'package:/system/app/EcarxBtPhone/EcarxBtPhone.apk\n'
        ;;
      *"grep -E '/nForeBluetooth"*)
        printf '/system/etc/bluetooth/nForeBluetooth.properties\n'
        printf '/system/etc/bluetooth/nForeBluetooth.properties.before_ble_1.bak\n'
        ;;
      *"grep -Ei '/[^/]*(bluetooth|nfore|btphone"*)
        printf '/system/priv-app/Bluetooth/Bluetooth.apk\n'
        ;;
      *"grep -Ei '/[^/]*(bluetooth|nfore|ecarx|geely|adaptapi|psd|ts"*)
        printf '/system/framework/com.ecarx.bluetooth.jar\n'
        printf '/system/framework/ECARX Bluetooth API.jar\n'
        ;;
      *"grep -Ei '/(lib"*)
        printf '/vendor/lib64/libbt-vendor.so\n'
        ;;
      *"grep -Ei '(btsnoop"*)
        printf '/data/misc/bluetooth/logs/btsnoop_hci.log\n'
        ;;
      *)
        printf 'fake read-only output\n'
        ;;
    esac
    exit 0
    ;;
esac

exit 0
FAKE_ADB
chmod +x "$FAKE_BIN/adb"

run_collector() {
  PATH="$FAKE_BIN:$PATH" \
  FAKE_ADB_LOG="$ADB_LOG" \
  FAKE_ADB_STATE="$ADB_STATE" \
    "$COLLECTOR" "$@" >/dev/null
}

: >"$ADB_LOG"
run_collector --output "$TMP_ROOT/out-normal"

if grep -qE '(^| )root($| )' "$ADB_LOG"; then
  printf 'normal mode unexpectedly called adb root\n' >&2
  exit 1
fi
if grep -Eq '/data/misc/blu|/data/(user/0|data)/com[.]ts[.]dm[.]service' "$ADB_LOG"; then
  printf 'normal mode unexpectedly accessed protected Bluetooth data\n' >&2
  exit 1
fi

: >"$ADB_LOG"
rm -f "$ADB_STATE/root"
run_collector --root --output "$TMP_ROOT/out-root"

grep -qE '(^| )root$' "$ADB_LOG" || {
  printf 'root mode did not call adb root\n' >&2
  exit 1
}
[ "$(grep -Ec '(^| )root$' "$ADB_LOG")" -eq 1 ] || {
  printf 'root mode called adb root more than once\n' >&2
  exit 1
}
grep -q '/data/misc/bluetooth' "$ADB_LOG" || {
  printf 'root mode did not inspect protected Bluetooth data\n' >&2
  exit 1
}
grep -q '/data/user/0/com.ts.dm.service/databases' "$ADB_LOG" || {
  printf 'root mode did not inspect the scoped ECARX device database\n' >&2
  exit 1
}

if grep '^SHELL ' "$ADB_LOG" | grep -Eiq \
  'remount|disable-verity|(^|[ ;])(mount|umount|sed|rm|mv|cp|setprop|stop|start)([ ;]|$)|svc[[:space:]]+bluetooth|settings[[:space:]]+put|service[[:space:]]+call|force-stop|clear[[:space:]]+cache'; then
  printf 'a forbidden mutating head-unit command was detected\n' >&2
  exit 1
fi

find "$TMP_ROOT/out-normal" -name '*.tar.gz' -type f | grep -q .
find "$TMP_ROOT/out-normal" -name '*.tar.gz.sha256' -type f | grep -q .
find "$TMP_ROOT/out-root" -name '*.tar.gz' -type f | grep -q .
find "$TMP_ROOT/out-root" -name '*.tar.gz.sha256' -type f | grep -q .

NORMAL_RESULT=""
for candidate in "$TMP_ROOT"/out-normal/KX11_Bluetooth_Collect_*; do
  if [ -d "$candidate" ]; then
    NORMAL_RESULT="$candidate"
    break
  fi
done
[ -n "$NORMAL_RESULT" ] || {
  printf 'normal-mode result directory was not created\n' >&2
  exit 1
}
[ -f "$NORMAL_RESULT/FILES/system/framework/ECARX Bluetooth API.jar" ] || {
  printf 'a discovered remote filename containing spaces was not preserved\n' >&2
  exit 1
}
grep -q 'manifest path missing or unreadable: /odm/etc/bluetooth' \
  "$NORMAL_RESULT/LOGS/nonfatal_errors.log" || {
  printf 'a missing optional path was not handled as a nonfatal condition\n' >&2
  exit 1
}

for checksum_file in \
  "$TMP_ROOT"/out-normal/*.tar.gz.sha256 \
  "$TMP_ROOT"/out-root/*.tar.gz.sha256; do
  (
    cd "$(dirname "$checksum_file")"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum -c "$(basename "$checksum_file")" >/dev/null
    else
      expected="$(awk '{print $1}' "$checksum_file")"
      archive="$(awk '{print $2}' "$checksum_file")"
      actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
      [ "$expected" = "$actual" ]
    fi
  )
done

printf 'KX11 Bluetooth collector self-test: PASS\n'
