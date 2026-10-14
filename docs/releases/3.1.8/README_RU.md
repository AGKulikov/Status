# Natro3.1.8 / 208021351

14.10.2026. Расширенный системный QNX dump; [изменения и ограничения](SOURCE_REVIEW_RU.md).

- Source: `18520800dd0a91e10c2daa2e2aaf9778e24948a9`.
- Tree: `88ad65bb156372654bc44793d2d81a6e018e1be5`.
- [CI38533352012](https://github.com/AGKulikov/Status/actions/runs/38533352012): SUCCESS.
- 2141 Android tests,422 XML suites;246 tool tests;108 Java policy/source cases.
  Ноль failures/errors/skips. XML totals сверены независимо.
- Первый CI38519976505 также успешен, но выпущен только более поздний source
  с учётом неполных process metadata в UI complete.

## APK и происхождение

`Natro-3.1.8-signed.apk`, SHA256
`d8936d3329b3bba15aac44a4c2feceb1370be4c48fd3d9d008ed20f43d2794e6`.
Unsigned SHA256 `9244203ea07213d4679ccb8aff2638136ae481067f04697cc2965d71722f5204`.
Один стабильный сертификат
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`,
v2/v3/API28 и zipalign16KiB проверены. Все1516 payload entries после подписи
неизменны. Пакет ru.natro.statuswidget, min/target28, не debug. VersionCode+1
от3.1.7 и прежний сертификат подтверждают upgrade metadata, не физическую установку.

GitHub artifact11879885589: SHA256
`b53860d851b42df7e1d108f98ca1d1454eab356246bd3cd0754a7139ad783de9`;
tools11878857738: SHA256
`9fddb53eddfa686dcc2d382d762f719c9e55b4c2eac8fc638babe401a6bc64af`.
Оба download SHA/CRC проверены перед распаковкой. APK DEX содержит новые QNX
graphics paths, metadata filenames и proc allowlist guard.

Navigator classes19.dex SHA256
`710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96`
совпадает с проверенным3.1.7. Поэтому существующий Navigator переустанавливать
не требуется, Helper неизменен. Generic candidate/signing manifest сохраняет
pairRequired=true; это default staging, а не обнаруженное отличие patch.
Решение о reuse основано на отдельном побайтном сравнении.

## Следующее действие пользователя

Установить поверх3.1.7 без удаления. На стоящей машине открыть «Диагностика» →
«Дамп системных файлов», оставить экран открытым до завершения (максимум10минут),
передать ZIP лично. «Частичный дамп» также полезен: причины в manifest.tsv и
qnx-diagnostics.txt. Новые процессы/библиотеки — только чтение. Не менять режимы
приборки ради сбора. Отказ QNX не обходить запуском служб или изменением прав.

FIELD OPEN: сбор11 файлов, process exefile/mappings, физическая установка и
отдельное отключение OEM-крыльев. Исчезновение белой полосы и отсутствие повторов
заряда из3.1.7 также не подменяются этим CI. В этом выпуске управление крыльями
не реализовано; собирается недостающий материал для определения receiver.
