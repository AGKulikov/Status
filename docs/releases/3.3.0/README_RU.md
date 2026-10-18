# Natro 3.3.0 / 208021363 — адресная диагностика крыльев

SOURCE: отдельная кнопка «Диагностика крыльев» в диагностике Natro; существующий
локальный ADB, отмена, приватный ZIP и сохранение в Download/Natro для iPhone.
Пара состояний: обычная приборка / крылья видны. Режимы не переключаются.
Сбор включает NaviMode до/после, display/layer/window/composition, PID/UID,
список пакетов, activity, fingerprint/boot_id/uptime, границы времени команд.
На API28 идентификаторы берутся только из mBuiltInDisplayId в полном display dump
при поддерживаемой справке screencap. До4снимков; иначе явно unmapped default.
Это не равенство с logical displayId2 и не доказательство полного HWC/DIM output.
Вендорское соответствие и crop требуют сопоставления с физическим фото.

Каждая команда ограничена15секундами/1МиБ; общий бюджет170секунд (подключение ADB
отдельно), PNG до8МиБ, передача32КиБ chunks, SHA до/после и PNG framing/CRC.
Кадры не декодируются в Bitmap внутри Natro. Отказ, обрыв, отмена и усечение
дают partial manifest; после обрыва нет reconnect/retry. Временный PNG создаётся
в уникальном приватном /data/local/tmp/natro-wings-* и удаляется при рабочем
соединении. Если связь оборвалась, cleanup=unconfirmed с точным путём в ZIP;
не заявляется гарантированное удаление на отключённом устройстве.
Локально сохраняются2последних wings ZIP; общие Download файлы не удаляются.
Новые настройки не создаются. Defaults335…362→363 рассмотрены отдельно.

TEST: локальный production replay проверяет реальные PNG, чанки/SHA/CRC,
namespace IDs, truncation, отказ снимка, транспорт, отмену, retention/cleanup.
Полная Android CI и проверка подписанного APK до выпуска обязательны.
APK: кандидат; до отдельной записи проверки подписанный выпуск не заявлен.
FIELD: KX11 не подключён. Сбор, фото/снимки, install-over требуют проверки.
Renderer и независимое скрытие крыльев остаются OPEN. Helper/Navigator не меняются.

## Как собрать

1. На стоянке откройте Natro → Диагностика → «Диагностика крыльев».
2. В обычной компоновке выберите «Обычная приборка», дождитесь результата,
   сделайте фото во время сбора, сохраните ZIP через «В Загрузки/Natro».
3. Включите привычным способом навигацию на приборке, дождитесь видимых крыльев.
   Повторите сбор с выбором «Крылья видны», также сделайте фото.
4. Передайте оба ZIP и соответствующие фото лично. Держите состояние неизменным
   во время каждого сбора. Отрицательный screenshot не исключает overlay/crop.

Проектная ветка: fix/media-touch-329. После полного CI здесь фиксируются точные
source/tree/run/SHA подписи. Новый полный QNX дамп не нужен.

## Проверка APK

SOURCE: `3a45d7587c099756d0ea3673fe6fe7156ec10180`, tree `83167c81ae0be17ac2fcf274fb13e2c5f32f1238`.
CI: https://github.com/AGKulikov/Status/actions/runs/39099592113
Android tests: {'tests': 2184, 'failures': 0, 'errors': 0, 'skipped': 0}.
APK SHA256: `3813fb212362ef054cea9410508c701b427ec174cf2c8720eae7ac6cd7e84aba`, 29780576 bytes.
Stable certificate/v2/v3, exact API28, zipalign16KiB, identity/release,
неизменный CI payload и versionCode+1 проверены.
Navigator patch совпал с3.2.9; вложенный WeChat payload без изменений.
[Отчёт](release-report.json), [подпись](signature-verification.txt),
[исходный signing report](original-signing-report.json).
Предыдущая запись «кандидат» относится к состоянию до CI и этой проверки.
Публикация stable фиксируется отдельно после readback всех7assets. FIELD OPEN.
