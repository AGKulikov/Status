#!/bin/bash

# Однократная выдача диагностических прав Status Widget HA1181.
# Не изменяет прошивку, ECARX/CAN, Bluetooth или системные разделы.

set -u
set -o pipefail

PACKAGE="ru.natro.statuswidget"

fail() {
  echo
  echo "ОШИБКА: $1" >&2
  exit 1
}

echo "Status Widget HA1181 — расширенный регистратор действий"
echo

command -v adb >/dev/null 2>&1 || fail \
  "adb не найден. Установите Android Platform Tools и повторите."

adb start-server >/dev/null 2>&1 || fail "Не удалось запустить ADB."

DEVICE_COUNT="$(adb devices | awk '$2 == "device" { count++ } END { print count + 0 }')"
UNAUTHORIZED_COUNT="$(adb devices | awk '$2 == "unauthorized" { count++ } END { print count + 0 }')"

if [ "$DEVICE_COUNT" -eq 0 ]; then
  adb devices
  if [ "$UNAUTHORIZED_COUNT" -gt 0 ]; then
    fail "Подтвердите разрешение USB-отладки на магнитоле и запустите скрипт снова."
  fi
  fail "Магнитола не найдена. Проверьте режим ADB и кабель."
fi

if [ "$DEVICE_COUNT" -gt 1 ]; then
  adb devices
  fail "Подключено несколько Android-устройств. Отключите остальные."
fi

echo "Подключена магнитола:"
echo "  Модель:  $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo "  Android: $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
echo "  SDK:     $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
echo

PACKAGE_PATH="$(adb shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')"
[ -n "$PACKAGE_PATH" ] || fail \
  "Пакет $PACKAGE не найден. Сначала установите Status Widget HA1181."

echo "[1/3] Выдаю READ_LOGS..."
adb shell pm grant "$PACKAGE" android.permission.READ_LOGS || fail \
  "Не удалось выдать READ_LOGS."

echo "[2/3] Выдаю DUMP..."
adb shell pm grant "$PACKAGE" android.permission.DUMP || fail \
  "Не удалось выдать DUMP."

echo "[3/3] Разрешаю Usage Access..."
adb shell appops set "$PACKAGE" GET_USAGE_STATS allow || fail \
  "Не удалось разрешить GET_USAGE_STATS."

PACKAGE_DUMP="$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r')"
APPOPS_DUMP="$(adb shell appops get "$PACKAGE" GET_USAGE_STATS 2>/dev/null | tr -d '\r')"

echo "$PACKAGE_DUMP" | grep -q \
  'android.permission.READ_LOGS: granted=true' || fail "READ_LOGS не подтвердился."
echo "$PACKAGE_DUMP" | grep -q \
  'android.permission.DUMP: granted=true' || fail "DUMP не подтвердился."
echo "$APPOPS_DUMP" | grep -Eiq \
  'GET_USAGE_STATS: (allow|foreground)|UsageStats.*(allow|foreground)' || fail \
  "Usage Access не подтвердился: $APPOPS_DUMP"

echo
echo "ГОТОВО:"
echo "  READ_LOGS: есть"
echo "  DUMP: есть"
echo "  Usage Access: есть"
echo
echo "Права сохраняются после перезагрузки и обновления APK."
echo "Служба специальных возможностей включается отдельно в настройках ГУ."
echo "Root EV_KEY этим скриптом не выдаётся."
