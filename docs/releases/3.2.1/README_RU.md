# Natro 3.2.1 / 208021354

Изменён QNX-сборщик по дампу16октября:

- Для разрешённых slog2 имён принимаются regular/name-special, сохраняется
  исходный stat и причина отказа. Отказ типа/open не расходует лимит12 файлов;
  process-specific журналы, включая wfd_be, имеют приоритет перед display.
- Полные2048-byte mappings блоки продолжаются от границы строки с проверкой
  повторного хвоста. Изменение/обрыв/невалидные данные не объявляются успехом.
- Прежние allowlist, лимиты и read-only ограничения сохранены. Настройки и
  defaults не менялись; совместимость backup schemas335…353→354 рассмотрена.

Это кандидат исправления сбора, а не устранение крыльев. Новый полевой дамп
должен подтвердить режимы файлов, доступность slog2 bytes и валидность mappings.
На стоящем автомобиле обновить Natro поверх3.2.0 без удаления данных, включить
навигацию, собрать системный ZIP и отметить, видны ли крылья во время сбора.

## Проверки

CI [38911412256](https://github.com/AGKulikov/Status/actions/runs/38911412256)
SUCCESS.2144 Android-теста/422 suites;252 tool tests и108 Java policy/source
cases успешны, без ошибок/пропусков. XML totals сверены независимо.
Candidate ZIP SHA256 e4f0616be41f580ddfd8587f297faafa597c425076de1f7e9f65852436854dd7
совпал с GitHub artifact digest; CRC корректен.

Build source:0208e154dd4892c0ead471b7339bf058b13a3278;
tree:78b37cc8effec2c045b522f4a49c7d0711393d8b.
APK29592088байт; SHA256:
`c55257b3611f32d3e0a9236ae9195f4f0679f9c99f8ce6130985689f7bf105a7`.

Один прежний подписант, v2/v3, API28, label/package/versionCode+1,
zipalign16KiB и install-over metadata поверх3.2.0 проверены.1516 ZIP payload
entries неизменны после подписи. В DEX присутствуют QnxFileReader,
unsupported_file_type, mode=0x, changed_during_read и read_content_invalid.
Физическая установка/сбор на KX11 остаются OPEN.

Использованы сохранённые Android SDK36.0.0 signing tools от3.2.0;
их SHA256 сверены с прежним release report до использования и записаны в
новом отчёте. Приватные ключи, firmware и пользовательские дампы не публикуются.

Navigator patch SHA256
710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96
совпадает с3.2.0: переустановка Navigator не требуется. Общий staging-флаг
pairRequired сохранён в сыром отчёте; отдельное сравнение разрешает прежний
совместимый мод. Helper не менялся.

[Отчёт APK](release-report.json),
[доказательства и ограничения](../../geely-kx11/research-2026-10-16-qnx/README_RU.md).
