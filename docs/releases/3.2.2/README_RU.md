# Natro 3.2.2 / 208021355

Доработка системного сбора после полевого результата3.2.1.

- Добавлены4 точных пути из screen log и PanelLibrary XML: /lib64/graphics.conf
  и3 драйвера MAX96755_QC_0_UART, MAX96755_QC_1_UART, DP_COMMON_QC_UART.
  Прежние /lib копии сохраняются отдельно; совпадение SHA не доказывает symlink.
- При несовпадении overlap mappings сохраняется исходный полученный prefix,
  а expected tail и спорный reply помещаются отдельно в proc-read-trace.tsv.
- После content-invalid/overlap mismatch выполняется одна отдельная попытка
  wide_sequential: запрос до256KiB+1 sentinel, монотонные offsets, без rewind.
  Оба snapshots сохраняются раздельно; структурный валидатор обязателен.
  Отказ/некорректный ответ не обходится, stream прекращается с partial evidence.
- Прежние stage60с, максимум8 процессов,256KiB на попытку и64MiB ZIP остаются.
  Сводный status остаётся partial при неуспешной первой попытке, даже если
  повторная валидна: успешный retry искать в отдельном блоке attempt=wide_sequential.

Это кандидат исправления чтения, не подтверждённое восстановление mappings
на реальном QNX и не отключение крыльев. Большие запросы ещё требуют FIELD.
На стоящем автомобиле обновить поверх3.2.1 без удаления данных, включить
навигацию и выполнить системный сбор, отметив видимость крыльев.

Build source:3af18fca8b5dc8be2c81fc86eec04c2f9fbd20ab;
tree:5ca6f5f36e1dbe2dbc02126b42baa83eb43551b7.
CI: https://github.com/AGKulikov/Status/actions/runs/38926871638
CI SUCCESS:2145 Android-тестов/422 suites,252 tool tests и108 Java policy/source
cases, без ошибок/пропусков. XML totals проверены независимо. ZIP digest
db1ec489ba4c82d7817c0ce487371cce38337b5a80b726cc1b7b4f9d7388e3de совпал с GitHub.

Подписанный APK29592088байт, SHA256
`0a679034e829368ae6d4bb131859b8b0618460bd7ae28228235bc8e4fa5ca4f8`.
Прежний cert, один signer,v2/v3,API28,package/label/versionCode+1,
zipalign16KiB/install-over metadata3.2.1 проверены.1516 payload entries
неизменны при подписи. В DEX подтверждены wide_sequential, expected_tail_hex,
reply_hex и4 новых lib64 paths. Реальная установка/новый сбор FIELD OPEN.

Navigator classes19.dex побайтно совпал с3.2.1 (SHA710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96),
переустановка не нужна; общий pairRequired в сыром staging report сохранён.
Helper не менялся. Signing SDK tools36.0.0 повторно использованы из предыдущего
проверенного комплекта, их hashes сверены и записаны в [release-report.json](release-report.json).
