# Natro3.4.0 — файловый менеджер с Яндекс Диском

Версия208021373, поверх3.3.9/208021372. Ветка `feature/file-manager-yandex-20261106`.
[Возможности, протокол и ограничения](../../FILE_MANAGER_YANDEX_RU.md).

SOURCE реализован;26локальных тестов file manager PASS. Полный CI/APK/PUBLISH
ожидаются, FIELD OPEN. Новые данные менеджера включаются в прежний backup adapter;
[review](../../backup/FULL_BACKUP_CONTRACT_RU.md). Стабильная подпись обязательна.
Navigator/Helper не изменялись; кандидат обязан подтвердить прежний classes19
SHA1aa6f536…6490 из3.3.9. Для новой функции повторная установка Navigator не нужна.

| Требование | SOURCE/TEST | APK | FIELD |
|---|---|---|---|
| FILES-001: встроенные локальные операции и USB | SOURCE, filesystem/UI tests PASS | ожидается | OPEN |
| FILES-002: вход и личный/публичный Диск как в примере | SOURCE, protocol fixture tests PASS | ожидается | live login/transfer OPEN |
| FILES-003: безопасная передача/ZIP/секреты/backup | SOURCE, fault/ZIP tests PASS; defaults CI ожидается | ожидается | OPEN |
| REL-001…011/017 | обязательные metadata/signature/provenance gates | ожидается | install-over OPEN |
