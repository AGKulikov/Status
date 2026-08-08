@echo off
setlocal
set "PACKAGE=ru.natro.statuswidget"

where adb >nul 2>nul
if errorlevel 1 (
  echo adb не найден. Установите Android Platform Tools и повторите.
  exit /b 1
)

adb get-state >nul || exit /b 1
adb shell pm grant %PACKAGE% android.permission.READ_LOGS || exit /b 1
adb shell pm grant %PACKAGE% android.permission.DUMP || exit /b 1
adb shell pm grant %PACKAGE% android.permission.PACKAGE_USAGE_STATS || exit /b 1
adb shell appops set %PACKAGE% GET_USAGE_STATS allow || exit /b 1

echo.
echo Готово: READ_LOGS, DUMP, PACKAGE_USAGE_STATS и Usage Access выданы %PACKAGE%.
echo Права сохраняются после перезагрузки и обновления APK; удаление приложения их сбросит.
endlocal
