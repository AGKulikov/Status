# Natro 3.3.1 / 208021364 — переключение приборки и адресная диагностика

Опубликована в stable/latest 18.10.2026 в 16:30:30 МСК.
[Скачать APK](https://github.com/AGKulikov/Status/releases/download/natro-v3.3.1/Natro-3.3.1-signed.apk).
Publisher [39131800780](https://github.com/AGKulikov/Status/actions/runs/39131800780), job117688327590: SUCCESS; все7assets скачаны и побайтно сверены, SHA PASS.
[Протокол публикации](publication-verification.json).
Исходный коммит `1ec7f42fbb1f877a177e16fb6432f053f3360d1a`,
дерево `167e1e4a4215f94268926210a22be2b8cbf19bbc`.
[Android CI 39108683065](https://github.com/AGKulikov/Status/actions/runs/39108683065), job 117619936398: SUCCESS.
Рабочая ветка: `fix/media-touch-329`; ветка выпуска: `release/natro-3.3.1-verification`.

Обновление поверх Natro 3.3.0 с прежней подписью. Navigator и iPhone Helper переустанавливать не требуется.

В выпуск включены:
- отмена запоздавших действий выключения приборки после повторного включения;
- завершение только собственной панели без остановки Natro, HUD, ANCS и медиасервисов;
- возврат штатного DIM после проверки, что экран свободен: mode1 → readback1 → DIM[0]; другое приложение сохраняет режим;
- проверка поколения разрешения на запуск и ограниченная диагностика неподтверждённого открытия/закрытия;
- исправленный разбор штатного screencap help с кодом 1 для адресного сбора изображений.

Проверки: 2185 Android-тестов и 266 проверок инструментов без ошибок и пропусков. Подтверждены происхождение APK, прежний сертификат, v2/v3, Android 9/API 28, выравнивание, версия/пакет и метаданные установки поверх 3.3.0.

Источник: `1ec7f42fbb1f877a177e16fb6432f053f3360d1a`.
Сборка: https://github.com/AGKulikov/Status/actions/runs/39108683065
SHA-256 APK: `b12e7c21c5360b7c75fae6f0967e2f36da49ba8f55b98f804e18c37932fc05ba`.

На KX11 ещё не подтверждены исчезновение окна «Открыть это приложение на текущем экране?», десять циклов включения/выключения и физическая установка поверх. Полное соответствие переносу произвольного активного приложения в mNavi не доказано. Независимое скрытие крыльев остаётся открытой задачей.



## Матрица проверки

| Требование | Источник | Автоматическая проверка | KX11 |
|---|---|---|---|
| CLUSTER-023/024/026, GATE-059/066 | InstrumentPanelCloseCoordinator, InstrumentDisplayLauncher, InstrumentDisplayOwner, InstrumentPanelActivity | production InstrumentCloseReplay / InstrumentOwnerReplay; Android CI SUCCESS | OPEN: OEM-диалог, 10 циклов, сохранение PID/HUD/ANCS/media |
| Адресные PNG в wings ZIP | WingsEvidenceArchive | WingsEvidenceReplay и test_system_file_dump, полный CI | OPEN: новый снимок на машине |
| REL-001…006/010 | точный APK из CI, подпись и manifest | verify_offline_release_handoff: package/label/version/API28/v2/v3/один signer/zipalign16KiB/exact payload PASS | install-over metadata PASS; физическая установка OPEN |
| REL-007/009 | исходный commit/tree и архив | исходники опубликованы; stable/latest и readback всех7assets PASS | не применяется |
| REL-017 | classes19.dex | SHA совпадает с 3.3.0: 710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96 | прежний Navigator сохраняется |
| Миграция defaults | BackupDefaultsMigration | полный CI; storage inventory 90 owners, settings 48 editors / 253 methods / 3012 labels PASS | полный runtime backup/restore не сертифицирован |

APK: 29 780 576 байт. Все 1517 распакованных payload entries совпадают с unsigned CI-кандидатом.
Публичный `signing-handoff.json` содержит только подпись/служебные байты APK, без ключей или паролей.
Контейнер WeChat compat пересобран; все четыре распакованные записи совпадают с 3.3.0.
Helper не менялся. [Машинный отчёт](release-report.json), [подпись](signature-verification.txt),
[изменения и ограничения](../../geely-kx11/research-2026-10-18-wings/SWITCH_CLOSE_FIX_RU.md).

Историческое «CI и подпись ожидаются» из предыдущего описания заменено результатами проверки выше.
Публикация завершена; аппаратная приёмка остаётся отдельным этапом и не закрыта готовым APK.
