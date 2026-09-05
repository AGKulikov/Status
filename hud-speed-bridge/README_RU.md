# Исходники моста HUD Speed 76.0-L13 для Natro

В этом каталоге хранится весь написанный для проекта исходный Java-код read-only моста камер
HUD Speed. Исходный APK HUD Speed, его декомпилированный код, база камер, настройки, лицензия и
закрытые ключи в Git не добавляются.

Мост добавляет в пакет `air.StrelkaHUDFREE` один явный Messenger-сервис. Он отдаёт Natro только
ограниченный снимок уже загруженных в память камер, проверяет Binder UID, точный пакет
`ru.natro.statuswidget` и стабильный сертификат Natro. Сервис не открывает Activity HUD Speed и
не читает его базу с диска.

## Воспроизводимая сборка

1. Собрать наш исходник в изолированный `classes3.dex`:

   ```bash
   ANDROID_SDK_ROOT=/path/to/android-sdk \
     tools/build_hud_speed_bridge_dex.sh build/hud-speed-bridge
   ```

2. Добавить dex и точечную запись сервиса в проверенный baseline HUD Speed 76.0-L13:

   ```bash
   APKTOOL_JAR=/protected/tools/apktool_3.0.3.jar \
   ZIPALIGN=/path/to/android-sdk/build-tools/36.0.0/zipalign \
     tools/build_hud_speed_bridge_apk.sh \
       /protected/HUD-Speed-76.0-L13.apk \
       build/hud-speed-bridge/classes3.dex \
       build/hud-speed-bridge/HUD-Speed-NatroBridge-unsigned.apk
   ```

3. Для финальной сборки тем же стабильным сертификатом Natro использовать
   `tools/sign_hud_speed_bridge_apk.sh`. Скрипт проверяет baseline SHA-256, пакет, версию,
   схемы подписи v2/v3 и сертификат
   `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

Все патчеры manifest, сценарии сборки и проверки находятся в `tools/`; подписанный APK и
защищённый baseline являются артефактами сборки и намеренно не входят в исходный репозиторий.
