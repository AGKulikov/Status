# Natro 3.2.9 / 208021362

SOURCE, TEST и APK проверены. Публикация stable выполняется; аппаратная приёмка FIELD открыта.
Действующая ветка: `fix/media-touch-329`.

## Изменения

[Описание для пользователя](release-notes.md). [Разбор новых архивов, фото и видео](../../geely-kx11/research-2026-10-17-white-bar/FIELD_1911_1915_RU.md).

Кнопки медиапанели принимают касание по значку с запасом 8dp и целевой областью 48dp в пределах виджета, с учётом экранного масштаба. Пустые части рамок кнопок, текста, FIT-полей и таймлайна пропускают DOWN. Принятый жест закреплён до UP/CANCEL; уход из области отменяет click без передачи команды соседу. Редактор, размеры, JSON и назначения сохранены.

Общий display Snapshot очищает только начальные ⓘ/ℹ, variation selectors и Unicode-пробелы. Это покрывает HOME, медиапанель, шторку, статус/popup/водительскую панель, HUD и приборку. Raw identity, artist/album, внутренняя ⓘ, обычная i и ⚠ не меняются. Display-часть MUSIC-027 заменена MUSIC-INFO-329.

Сборщик сохраняет 977 прежних исключений и QNX-first. Итог HU/LAN/ZIP разделяет copied/excluded/changed/unavailable/Android failures. Kernel snapshot выполняется до logcat; logcat получает последние 6000 записей перед фильтрацией и сохраняет настоящий exit/разрешённый partial stdout. Старые подходящие строки за этим горизонтом могут отсутствовать. Ограничения 15s/1MiB и отсутствие автоматического повтора сохранены. Changed-record содержит content/metadata match и before/after size.

## Источник и проверки

- SOURCE: [`030cfecf377d1b0858a59eacffa2ef6aa0fa5b34`](https://github.com/AGKulikov/Status/commit/030cfecf377d1b0858a59eacffa2ef6aa0fa5b34).
- Tree: `68e6203b11c54d63ce91321fd348d913f2a9c913`.
- CI: [run 39050946770](https://github.com/AGKulikov/Status/actions/runs/39050946770), job 117449229222, SUCCESS.
- 2183 Android-теста, 265 tool tests и 108 Java cases; 0 failures/errors/skips. [Выдержки CI](ci-evidence.json).
- API28 MotionEvent через реальные frame/proxy: FIT/CROP/STRETCH × размеры 200×80, 100×40, 240×120; соседние кнопки, UP вне области, CANCEL, пустая высота текста, FIT-поле, прямая кнопка, track и режим редактора. Отдельно title/Snapshot и backup migration.
- Первый run 39049928596 отменён следующей ревизией; run 39050129259 выявил 3 ошибки fixture: исходные View не были прикреплены к окну, поэтому Android откладывал click. Финальный тест повторяет attached lifecycle HOME и сохраняет настоящие click assertions. Все 4 gesture-теста прошли в итоговом CI.
- Локальные 9 collector/parser тестов прошли. Полный CI включил failure replay, known exclusions, большие архивы, partial output, сводку, порядок kernel/log и конечный logcat query.
- Backup: 89 storage owners проверены после review 6 изменённых owners, новых settings/defaults нет; migration 335…361→362. Settings inventory 48 editors / 253 methods / 3012 labels прошёл.

## APK и обновление

`Natro-3.2.9-signed.apk`, 29768288 байт.
SHA256: `d4235965c6af6910014acf40071c44691932dd8606617a2abbca83a6f2d84f2f`.

Прежний единственный сертификат `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`, v2/v3, exact API28, zipalign 16KiB, release/non-debuggable, label Natro, package `ru.natro.statuswidget`, versionCode 208021362 = 3.2.8 + 1 проверены. Все 1517 payload entries совпадают с APK успешного CI. DEX содержит новые media/collector методы, ресурс 977 исключений равен исходнику и 3.2.8.

Navigator classes19 побайтно равен 3.2.8, SHA `710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96`; Helper source не менялся. Вложенный WeChat compatibility APK отличается упаковкой ZIP, содержимое всех entries совпало. Отдельная переустановка Navigator/Helper не требуется. Исходный автоматический signing-report сохранён отдельно: общий признак pairRequired уточнён только после сравнения патча.

[release-report.json](release-report.json), [original-signing-report.json](original-signing-report.json), [signature-verification.txt](signature-verification.txt), [natro-update.json](natro-update.json), [публичный signature handoff без ключа](signing-handoff.json).
Приватные файлы подписи после операции удалены. Метаданные install-over не заменяют реальную проверку сохранения настроек на HU.

## FIELD и оставшиеся вопросы

Архив 3.2.8 vehicle372877 подтвердил завершённый обход 1080 путей, 966 исключений, 26 разрешённых ссылок, 8 stable + 2 changed files, 3 недоступных пути, 13 slog2 и 10 mappings; 50 payload SHA проверены. QNX сохранён при timeout Android logcat. Это подтверждает изоляцию сборщика 3.2.8, но не полноту всех файлов и не поведение 3.2.9.

В system/diagnostics 3.2.7 повтор белой полосы совпал с ANR/restart MConfig, restart dimmenu и NaviMode1 до записи allow Natro. STATIC MConfigInitService может писать1 и перезапускать dimmenu; конкретный writer и пиксельный владелец пока не установлены. OEM-priority/owner/tab gates сохранены. Белая полоса, крылья, реальные касания и все экраны с очищенным title остаются FIELD OPEN.
