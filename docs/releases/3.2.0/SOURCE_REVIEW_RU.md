# Матрица выпуска 3.2.0

| Требование | Реализация | Проверка | KX11 |
|---|---|---|---|
| Полная композиция Android | GraphicsEvidence, AdbShellResult, AdbConsoleSession, DiagnosticsActivity | GraphicsEvidenceReplay:218124байт без старого усечения, предел1МиБ и exit marker | OPEN |
| Существующие графические журналы QNX | QnxFileReader.collectLogs, AdbTransport, SystemFileDump | QnxFileReaderReplay: allowlist, denial, changed, truncated, disconnect, malformed count | OPEN |
| Диагностика повреждённых mappings | QnxFileReader.procText/traceRead/validMappings | Сохранение prefix, block SHA/reply/offset, read_content_invalid | Причина OPEN |
| Версия и обновление | REL-004, workflow3.2.0, BackupDefaultsMigration | Version validator;335…352→353, отказ334/354; stable cert и metadata проверяются в APK | Установка OPEN |
| Исходники и исследования | build/qnx-graphics-320 | Опубликованное дерево db1a4ad89a7d846ccecebccbed1d11985d387b7c равно локальному source tree | Не применимо |

Локальный повтор:12 тестов collector/slog2 и1 проверка правила версии успешны.
Полный CI38635938599 запускается из6597996c9ff0342f023eb0667b7642df589a9c42.
Итог CI и подписанного APK записывается отдельно после получения артефактов.

Нет новых автомобильных setter/devctl/driver lock, запуска slogger или
qnxslogadapter. Нет новых настроек, секретов или managed backup roots.
Данные ограничены, неатомарны и могут быть неполными. Неисправность крыльев
не объявляется устранённой. Navigator/Helper не меняются; compiled patch
проверяется на побайтовое совпадение с ранее выданным.
