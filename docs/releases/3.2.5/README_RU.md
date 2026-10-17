# Natro3.2.5 — протокол выпуска

SOURCE опубликован, полный CI PASS, APK подписан прежним ключом и проверен.
Публикация stable на момент подготовки этого протокола ещё OPEN; окончательный
результат фиксируется в PUBLICATION_RU.md после read-back GitHub Release.
[Изменения и приёмка](release-notes.md), [машинный отчёт](release-report.json).

- Source `d7daff1b3807b1ba830f60f2dfd2174cec388bbb`,
  tree `032dae00dff3f831fe1a9bec1afc26714fd59eaa`.
- CI39034294601/job117400902131:2157 Android tests,264 tool tests,
  108 вложенных Java policy/source cases; ошибок/пропусков нет.
- SHA/size/CRC трёх скачанных CI artifacts проверены, exact IDs в JSON.
- APK29678104байт; SHA256
  `d3fed86ca73c76d1ff10eb3a89a22a731abc50df33ab1782e0986aa164a2daf6`.
- Stable certificate6e9855ae… совпал, v2/v3 и точный Android API28 проверены,
  zipalign16KiB, min/target28, package ru.natro.statuswidget, non-debuggable.
- Все1516 payload entries совпали с проверенным CI APK; code208021358=3.2.4+1.
- Navigator patch710af1b6… побайтно совпал с3.2.4; Helper не изменён.
- Native Canvas screenshot уведомления осмотрен: компактная белая плашка,
  чёрный жирный «Комфорт», верхний центр. Pixel/clip/runtime/timer tests PASS.
  Фотографией физического HUD этот renderer test не является.
- REL-015: использован существующий резервный комплект ключа. Новый ключ
  не создавался; временные JKS/pass/archive удалены, исходный резерв сохранён.

| Требование | SOURCE | TEST | FIELD |
|---|---|---|---|
| Вид уведомления MConfig, событие встроенного селектора | HudDriveModeBanner/Notification, HudRuntimeData, HUD config/UI | реальные state/runtime/native Canvas, JSON round-trip | OPEN |
| Все обычные файлы выбранных QNX roots | QnxFileReader.collectTree, VehicleEvidenceArchive | wire/ZIP20МиБ under64МиБheap, SHA/stat/double read/partial/errors | OPEN |
| Другие каналы | фиксированные read-only gateway snapshots | shell/archive replay, без device payload/сканирования | OPEN |
| Большой download с телефона | native anchor, cookie grant, streaming/ranges | owner/revoke/expiry/malformed range и >2ГиБ arithmetic | OPEN |
| Белая полоса | regional InstrumentDisplayOwner + DIM files/MConfig events | реальный fixture и overlap/unknown guards | ROOT CAUSE OPEN |

Backup review:89storage owners, новый HUD boolean внутри прежнего JSONschema6,
explicit false сохраняется, transient banner не восстанавливается. Full QNX
archive не является managed backup/firmware restore. Статический inventory
не доказывает полного runtime restore. Findings MConfig48/46.1 и ограничения
массового QNX сбора сохранены в research-2026-10-17-white-bar.

Public signing-handoff содержит только подпись/ZIP headers и диапазоны точного
CI APK. Publisher сверяет build/source, восстанавливает тот же SHA, повторяет
signature/payload/identity/tests/manifest gates, затем загружает7assets draft,
скачивает и сравнивает каждый, и лишь после этого делает stable/latest.
