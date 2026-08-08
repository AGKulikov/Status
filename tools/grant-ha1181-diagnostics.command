#!/bin/sh
set -eu

PACKAGE="ru.natro.statuswidget"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb не найден. Установите Android Platform Tools и повторите." >&2
  exit 1
fi

adb get-state >/dev/null
adb shell pm grant "$PACKAGE" android.permission.READ_LOGS
adb shell pm grant "$PACKAGE" android.permission.DUMP
adb shell appops set "$PACKAGE" GET_USAGE_STATS allow

echo ""
echo "Готово: READ_LOGS, DUMP и Usage Access выданы $PACKAGE."
echo "Права сохраняются после перезагрузки и обновления APK; удаление приложения их сбросит."
