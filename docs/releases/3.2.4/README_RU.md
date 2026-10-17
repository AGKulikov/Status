# Выпуск 3.2.4 — технический протокол

APK собран, подписан прежним ключом и сохранён для выдачи. Публикация stable
GitHub Release на момент этой записи ещё OPEN; наличие workflow не равно успеху.
[Изменения и ограничения](release-notes.md), [машинный протокол](release-report.json).

## Происхождение и подпись

- SOURCE `4db858f7337d13e917b90f2faf97787631f3a79e`,
  tree `6bcd0323a53d69293d75f50c92269bd733cef18d`.
- Полный CI39026892021 SUCCESS:2147 Android tests,260 tool/replay tests,
  108 вложенных Java policy/source cases; ошибок/пропусков нет.
- Оба скачанных CI-архива сверены по SHA-256, размеру и ZIP CRC; точные IDs в JSON.
- APK `Natro-3.2.4-signed.apk`,29645336байт, SHA-256
  `295d2f09ce2d50fb3fa6eb5a956fe132eb6b2f1bb229ec90d44875d26429618c`.
- REL-015: ранее сохранённый приватный комплект восстановлен самостоятельно;
  новый ключ не создан. Alias status-widget-ha, стабильный сертификат совпал.
  Временные копии ключа/пароля/архива удалены, исходный резерв сохранён.
- v3 для точного API28, v2 отдельной проверкой minSDK24, один подписант,
  zipalign16KiB, labelNatro, min/target28, non-debuggable подтверждены.
- Все1516 payload entries остались неизменны. Code208021357=3.2.3+1,
  прежние package/сертификат; это проверка метаданных, не установка на KX11.
- Navigator patch710af1b6… совпал с3.2.3; новый Navigator не требуется.
  Generic pairRequired из CI сохранён в исходном отчёте и не является результатом
  сравнения. Helper не изменён.

## Требование → реализация → проверка → FIELD

| Требование | Исходники | Автоматическая проверка | KX11 |
|---|---|---|---|
| Постоянное сопряжение/сервер/разрешение ADB | LanTrustStore, LanPairing, LanBootReceiver, LanTransferService, LanTransferActivity, client.js | LanCoreReplay, test_lan_trust_store; backup review88owners | OPEN |
| Те же TXT/ZIP с телефона | LanTransferService → DiagnosticJournal/DiagnosticBundle | wiring/export checks, полная Java-сборка | OPEN |
| Системный дамп без перезагрузки | LanTransferService → SystemFileDump | существующие collector replays, cancellation/global running gate; APK payload | OPEN |
| Кнопки обновления HU/LAN | NatroUpdater, UpdateActivity, SettingsDestinationCatalog, client.js | manifest tests, navigation contracts, подпись фактического APK | OPEN |
| QNX uint64 stat/частичный ADB stdout | QnxFileReader, AdbConsoleSession, AdbShellResult | production replays, полевой stat-response fixture | OPEN |
| Белая полоса/низкий заряд iPhone | InstrumentDisplayOwner, InstrumentOemController, WidgetService | диагностические evidence/replay; не доказательство устранения | OPEN |

Backup: новые CE LAN hashes и flags явно разобраны, чужая identity из архива
не активируется. Defaults335…356→357 с тестом сохранения неизвестных ключей.
Static inventory88owners PASS не доказывает полный runtime restore.

## Публикация без передачи приватного ключа

`signing-handoff.json` содержит только294665байт общедоступных ZIP headers/
подписи и3033операции копирования/добавления. Проверенный CI APK остаётся
источником payload. Восстановление даёт побайтно тот же подписанный APK по SHA;
затем повторяются apksigner, zipalign, payload, version/manifest и CI XML gates.
Три synthetic handoff tests PASS; это не замена криптографической проверке APK.
Ни ключа, ни пароля, ни токена в handoff нет. Существующий build workflow с
contents:read не ослаблен. Отдельный publish job получает только необходимое
contents:write, отказывается перезаписывать выпуск, проверяет скачанные assets
до перевода draft в stable. Если разрешения GitHub откажут, публикация остаётся
OPEN и обхода разрешений нет.

Исходный отчёт подписи сохранён в signing-report-original.json. Release manifest
создан по фактическому APK, не по номеру в исходниках. Первый переход с3.2.3
ручной; проверку более нового выпуска на HU можно принять только отдельно.
