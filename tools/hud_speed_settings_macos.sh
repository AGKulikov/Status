#!/bin/bash
set -euo pipefail

# Root-ADB migration helper for HUD Speed 76.0-L13 on macOS.
# The raw backup is kept locally, but restore imports only reviewed user settings.

PACKAGE='air.StrelkaHUDFREE'
REMOTE_APP_DIR="/data/user/0/$PACKAGE"
REMOTE_SETTINGS="$REMOTE_APP_DIR/shared_prefs/app_settings.xml"
REMOTE_DEVICES="$REMOTE_APP_DIR/files/devices.json"
REMOTE_STAGE='/data/local/tmp/natro_hud_speed_settings.xml'
REMOTE_DEVICES_STAGE='/data/local/tmp/natro_hud_speed_devices.json'

ADB_BIN=''
PYTHON_BIN=''
ROOT_MODE=''
CLEANUP_DIR=''

fail() {
  printf 'ОШИБКА: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [ -n "$CLEANUP_DIR" ] && [ -d "$CLEANUP_DIR" ]; then
    rm -rf -- "$CLEANUP_DIR"
  fi
}

trap cleanup EXIT

usage() {
  cat <<'EOF'
Перенос настроек HUD Speed через root-ADB на macOS

Использование:
  hud_speed_settings_macos.sh backup [ПАПКА]
  hud_speed_settings_macos.sh restore ПАПКА [--with-devices]
  hud_speed_settings_macos.sh verify ПАПКА
  hud_speed_settings_macos.sh self-test

backup
  Сохраняет исходный app_settings.xml, безопасную копию настроек и devices.json.
  Если папка не указана, создаётся HUD-Speed-backup-ДАТА в текущем каталоге.

restore
  Объединяет безопасные пользовательские настройки со свежими настройками
  новой версии HUD Speed. Аккаунт, лицензия и покупки не переносятся.
  --with-devices дополнительно восстанавливает devices.json.

Важно: выполните backup ДО удаления старого HUD Speed.
EOF
}

find_python() {
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="$(command -v python3)"
  else
    fail 'Не найден python3. Установите: brew install python'
  fi
}

find_adb() {
  local candidate
  if [ -n "${ADB:-}" ] && [ -x "${ADB}" ]; then
    ADB_BIN="$ADB"
    return
  fi
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
    return
  fi
  for candidate in \
      "${ANDROID_HOME:-}/platform-tools/adb" \
      "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
      "$HOME/Library/Android/sdk/platform-tools/adb" \
      '/opt/homebrew/bin/adb' \
      '/usr/local/bin/adb'; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      ADB_BIN="$candidate"
      return
    fi
  done
  fail 'Не найден adb. Установите: brew install android-platform-tools'
}

prepare_device() {
  local state direct_id su_id root_output
  state="$("$ADB_BIN" get-state 2>/dev/null || true)"
  [ "$state" = 'device' ] || fail \
    'Магнитола не подключена. Проверьте adb devices и разрешение отладки.'

  direct_id="$("$ADB_BIN" shell id 2>/dev/null | tr -d '\r' || true)"
  if grep -q 'uid=0(root)' <<<"$direct_id"; then
    ROOT_MODE='direct'
    return
  fi

  su_id="$("$ADB_BIN" shell 'su -c id' 2>/dev/null | tr -d '\r' || true)"
  if grep -q 'uid=0(root)' <<<"$su_id"; then
    ROOT_MODE='su'
    return
  fi

  root_output="$("$ADB_BIN" root 2>&1 || true)"
  "$ADB_BIN" wait-for-device
  direct_id="$("$ADB_BIN" shell id 2>/dev/null | tr -d '\r' || true)"
  if grep -q 'uid=0(root)' <<<"$direct_id"; then
    ROOT_MODE='direct'
    return
  fi

  fail "ADB подключён, но root недоступен. Ответ adb root: $root_output"
}

root_shell() {
  local command="$1"
  if [ "$ROOT_MODE" = 'direct' ]; then
    "$ADB_BIN" shell "$command"
  else
    "$ADB_BIN" shell "su -c '$command'"
  fi
}

root_exec_out() {
  local command="$1"
  if [ "$ROOT_MODE" = 'direct' ]; then
    "$ADB_BIN" exec-out "$command"
  else
    "$ADB_BIN" exec-out "su -c '$command'"
  fi
}

remote_file_exists() {
  root_shell "test -f $1" >/dev/null 2>&1
}

sanitize_settings() {
  local source="$1"
  local target="$2"
  "$PYTHON_BIN" - "$source" "$target" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

source = Path(sys.argv[1])
target = Path(sys.argv[2])

allowed_exact = {
    "alertAlways",
    "alertAverageSpeed",
    "alertDistance",
    "alertFakeCams",
    "alertGpsStatus",
    "alertNearestTruckCam",
    "alertNoCams",
    "alertSpeedingThreshold",
    "alertTruckCams",
    "ambushesAutoUpdate",
    "ambushesHeatAlert",
    "autoBackgroundStart",
    "autoStart",
    "autoStartBluetooth",
    "autoStartHomeScreen",
    "autoTurnOffTimeout",
    "backgroundModeAutoStart",
    "backgroundWindowScale",
    "dataBaseCountry",
    "databaseAutoUpdate",
    "developerMode",
    "drawRays",
    "gpsShowInfoAlways",
    "gpsUseGpsProvider",
    "gpsUseNetworkProvider",
    "hudColor",
    "hudModeMaxBrightness",
    "menuAutoOpen",
    "navigatorLaunch",
    "navigatorSplitMode",
    "onBoardingCompleted",
    "onboardComputerFirstSlot",
    "onboardComputerFourthSlot",
    "onboardComputerSecondSlot",
    "onboardComputerThirdSlot",
    "onlyCriticalBumps",
    "onlyHighRank",
    "onlyNonNewbie",
    "relativeVolume",
    "requestAudioFocus",
    "showBumps_Beta",
    "showBumpsLarge",
    "showCarAutoNotifications",
    "showPopup",
    "showPopupButton",
    "soundChannel",
    "speakDistance",
    "speakOut",
    "speedCalibration",
    "uiModeMap",
    "useBetaServer",
    "vehicleMode",
}
allowed_prefixes = (
    "NotificationModeForType_",
    "alertAlways_Type_",
)

try:
    source_root = ET.parse(source).getroot()
except (ET.ParseError, OSError) as error:
    raise SystemExit(f"Некорректный app_settings.xml: {error}")
if source_root.tag != "map":
    raise SystemExit("Некорректный app_settings.xml: корневой элемент не map")

safe_root = ET.Element("map")
kept = 0
for item in source_root:
    name = item.attrib.get("name", "")
    if name in allowed_exact or name.startswith(allowed_prefixes):
        safe_root.append(item)
        kept += 1

if kept == 0:
    raise SystemExit("В исходном файле не найдено ни одной известной настройки")
ET.ElementTree(safe_root).write(target, encoding="utf-8", xml_declaration=True)
print(kept)
PY
}

merge_settings() {
  local current="$1"
  local safe="$2"
  local target="$3"
  "$PYTHON_BIN" - "$current" "$safe" "$target" <<'PY'
from copy import deepcopy
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

current_path, safe_path, target_path = map(Path, sys.argv[1:4])

if current_path.exists() and current_path.stat().st_size:
    try:
        current_root = ET.parse(current_path).getroot()
    except ET.ParseError as error:
        raise SystemExit(f"Новые настройки HUD Speed повреждены: {error}")
else:
    current_root = ET.Element("map")
if current_root.tag != "map":
    raise SystemExit("Новые настройки HUD Speed имеют неизвестный формат")

try:
    safe_root = ET.parse(safe_path).getroot()
except ET.ParseError as error:
    raise SystemExit(f"Безопасная резервная копия повреждена: {error}")

safe_names = {item.attrib.get("name") for item in safe_root}
for existing in list(current_root):
    if existing.attrib.get("name") in safe_names:
        current_root.remove(existing)
for item in safe_root:
    current_root.append(deepcopy(item))

ET.ElementTree(current_root).write(target_path, encoding="utf-8", xml_declaration=True)
print(len(safe_names))
PY
}

verify_imported_settings() {
  local safe="$1"
  local actual="$2"
  "$PYTHON_BIN" - "$safe" "$actual" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

def normalized(item):
    return (
        item.tag,
        tuple(sorted(item.attrib.items())),
        item.text or "",
        tuple(normalized(child) for child in item),
    )

safe_root = ET.parse(Path(sys.argv[1])).getroot()
actual_root = ET.parse(Path(sys.argv[2])).getroot()
actual = {item.attrib.get("name"): normalized(item) for item in actual_root}
missing = []
for item in safe_root:
    name = item.attrib.get("name")
    if actual.get(name) != normalized(item):
        missing.append(name)
if missing:
    raise SystemExit("Не совпали восстановленные настройки: " + ", ".join(missing))
print(len(list(safe_root)))
PY
}

write_checksums() {
  local directory="$1"
  (
    cd "$directory"
    : > SHA256SUMS.txt
    for file in app_settings.raw.xml app_settings.safe.xml devices.json metadata.txt; do
      if [ -f "$file" ]; then
        shasum -a 256 "$file" >> SHA256SUMS.txt
      fi
    done
  )
}

verify_backup() {
  local directory="$1"
  [ -d "$directory" ] || fail "Папка резервной копии не найдена: $directory"
  [ -f "$directory/app_settings.raw.xml" ] || fail 'Нет app_settings.raw.xml'
  [ -f "$directory/app_settings.safe.xml" ] || fail 'Нет app_settings.safe.xml'
  [ -f "$directory/SHA256SUMS.txt" ] || fail 'Нет SHA256SUMS.txt'
  (
    cd "$directory"
    shasum -a 256 -c SHA256SUMS.txt >/dev/null
  ) || fail 'Контрольные суммы резервной копии не совпали'
  sanitize_settings "$directory/app_settings.raw.xml" \
    "$directory/.safe-verification.xml" >/dev/null
  cmp -s "$directory/app_settings.safe.xml" "$directory/.safe-verification.xml" || {
    rm -f "$directory/.safe-verification.xml"
    fail 'Безопасная копия не соответствует исходному файлу'
  }
  rm -f "$directory/.safe-verification.xml"
  printf 'Резервная копия исправна: %s\n' "$directory"
}

backup_settings() {
  local directory="${1:-$PWD/HUD-Speed-backup-$(date +%Y%m%d-%H%M%S)}"
  local kept package_path model version

  [ ! -e "$directory" ] || fail "Папка уже существует: $directory"
  mkdir -p "$directory"
  chmod 700 "$directory"

  package_path="$("$ADB_BIN" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')"
  [ -n "$package_path" ] || fail 'HUD Speed не установлен'
  remote_file_exists "$REMOTE_SETTINGS" || fail \
    "Не найден файл настроек: $REMOTE_SETTINGS"

  printf 'Останавливаю HUD Speed и создаю согласованную копию...\n'
  "$ADB_BIN" shell am force-stop "$PACKAGE" >/dev/null
  root_exec_out "cat $REMOTE_SETTINGS" > "$directory/app_settings.raw.xml"
  chmod 600 "$directory/app_settings.raw.xml"
  [ -s "$directory/app_settings.raw.xml" ] || fail 'Скопирован пустой app_settings.xml'

  kept="$(sanitize_settings "$directory/app_settings.raw.xml" \
    "$directory/app_settings.safe.xml")"
  chmod 600 "$directory/app_settings.safe.xml"

  if remote_file_exists "$REMOTE_DEVICES"; then
    root_exec_out "cat $REMOTE_DEVICES" > "$directory/devices.json"
    chmod 600 "$directory/devices.json"
  fi

  model="$("$ADB_BIN" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  version="$("$ADB_BIN" shell dumpsys package "$PACKAGE" 2>/dev/null \
    | sed -n -e 's/^[[:space:]]*versionName=/versionName=/p' \
      -e 's/^[[:space:]]*versionCode=/versionCode=/p' \
    | sed -n '1,2p' | tr -d '\r')"
  {
    printf 'created=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'package=%s\n' "$PACKAGE"
    printf 'device_model=%s\n' "$model"
    printf '%s\n' "$version"
    printf 'safe_settings=%s\n' "$kept"
  } > "$directory/metadata.txt"
  chmod 600 "$directory/metadata.txt"
  write_checksums "$directory"
  chmod 600 "$directory/SHA256SUMS.txt"
  verify_backup "$directory"

  printf '\nГотово. Сохранено безопасных настроек: %s\n' "$kept"
  printf 'Папка: %s\n' "$directory"
  printf '%s\n' \
    'app_settings.raw.xml содержит личные данные. Не отправляйте его и не кладите в Git.' \
    'Теперь можно удалить старый HUD Speed и установить новую подписанную версию.'
}

restore_settings() {
  local directory="$1"
  local with_devices="${2:-}"
  local temp_dir current merged actual uid imported package_dump

  [ "$with_devices" = '' ] || [ "$with_devices" = '--with-devices' ] || \
    fail 'Допустим только параметр --with-devices'
  verify_backup "$directory"

  package_dump="$("$ADB_BIN" shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r')"
  [ -n "$package_dump" ] || fail 'Новая версия HUD Speed не установлена'
  grep -q 'HudSpeedCameraBridgeService' <<<"$package_dump" || fail \
    'Установлен старый HUD Speed без моста Natro. Сначала установите новую версию.'

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/natro-hud-speed-restore.XXXXXX")"
  CLEANUP_DIR="$temp_dir"
  current="$temp_dir/current.xml"
  merged="$temp_dir/merged.xml"
  actual="$temp_dir/actual.xml"

  "$ADB_BIN" shell am force-stop "$PACKAGE" >/dev/null
  if remote_file_exists "$REMOTE_SETTINGS"; then
    root_exec_out "cat $REMOTE_SETTINGS" > "$current"
  else
    printf '<map />\n' > "$current"
  fi
  imported="$(merge_settings "$current" "$directory/app_settings.safe.xml" "$merged")"

  "$ADB_BIN" push "$merged" "$REMOTE_STAGE" >/dev/null
  uid="$(root_shell "stat -c %u $REMOTE_APP_DIR" 2>/dev/null | tr -d '\r')"
  grep -Eq '^[0-9]+$' <<<"$uid" || fail 'Не удалось определить UID HUD Speed'
  root_shell "mkdir -p $REMOTE_APP_DIR/shared_prefs && cp $REMOTE_STAGE $REMOTE_SETTINGS && chown $uid:$uid $REMOTE_SETTINGS && chmod 600 $REMOTE_SETTINGS && restorecon $REMOTE_SETTINGS >/dev/null 2>&1; rm -f $REMOTE_STAGE" >/dev/null

  if [ "$with_devices" = '--with-devices' ]; then
    [ -f "$directory/devices.json" ] || fail 'В резервной копии нет devices.json'
    "$ADB_BIN" push "$directory/devices.json" "$REMOTE_DEVICES_STAGE" >/dev/null
    root_shell "mkdir -p $REMOTE_APP_DIR/files && cp $REMOTE_DEVICES_STAGE $REMOTE_DEVICES && chown $uid:$uid $REMOTE_DEVICES && chmod 600 $REMOTE_DEVICES && restorecon $REMOTE_DEVICES >/dev/null 2>&1; rm -f $REMOTE_DEVICES_STAGE" >/dev/null
  fi

  root_exec_out "cat $REMOTE_SETTINGS" > "$actual"
  verify_imported_settings "$directory/app_settings.safe.xml" "$actual" >/dev/null

  printf '\nГотово. Восстановлено настроек: %s\n' "$imported"
  if [ "$with_devices" = '--with-devices' ]; then
    printf 'Список устройств devices.json также восстановлен.\n'
  fi
  printf 'Запустите HUD Speed и проверьте настройки.\n'
}

self_test() {
  local temp_dir raw safe current merged
  find_python
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/natro-hud-speed-test.XXXXXX")"
  CLEANUP_DIR="$temp_dir"
  raw="$temp_dir/raw.xml"
  safe="$temp_dir/safe.xml"
  current="$temp_dir/current.xml"
  merged="$temp_dir/merged.xml"
  cat > "$raw" <<'XML'
<map>
  <boolean name="speakOut" value="true" />
  <int name="NotificationModeForType_3" value="2" />
  <string name="userToken">secret-old-token</string>
  <string name="inAppPurchaseToken">secret-purchase</string>
</map>
XML
  cat > "$current" <<'XML'
<map>
  <boolean name="speakOut" value="false" />
  <string name="userToken">fresh-new-token</string>
</map>
XML
  sanitize_settings "$raw" "$safe" >/dev/null
  merge_settings "$current" "$safe" "$merged" >/dev/null
  "$PYTHON_BIN" - "$safe" "$merged" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

safe = ET.parse(Path(sys.argv[1])).getroot()
merged = ET.parse(Path(sys.argv[2])).getroot()
safe_names = {item.attrib.get("name") for item in safe}
values = {item.attrib.get("name"): (item.text or item.attrib.get("value")) for item in merged}
assert safe_names == {"speakOut", "NotificationModeForType_3"}
assert values["speakOut"] == "true"
assert values["NotificationModeForType_3"] == "2"
assert values["userToken"] == "fresh-new-token"
assert "inAppPurchaseToken" not in values
PY
  printf 'Self-test: OK\n'
}

main() {
  local action="${1:-}"
  umask 077
  case "$action" in
    self-test)
      self_test
      ;;
    backup)
      find_python
      find_adb
      prepare_device
      backup_settings "${2:-}"
      ;;
    restore)
      [ "$#" -ge 2 ] || fail 'Для restore укажите папку резервной копии'
      find_python
      find_adb
      prepare_device
      restore_settings "$2" "${3:-}"
      ;;
    verify)
      [ "$#" -eq 2 ] || fail 'Для verify укажите папку резервной копии'
      find_python
      verify_backup "$2"
      ;;
    -h|--help|help|'')
      usage
      ;;
    *)
      usage >&2
      fail "Неизвестное действие: $action"
      ;;
  esac
}

main "$@"
