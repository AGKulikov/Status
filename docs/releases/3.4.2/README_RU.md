# Natro 3.4.2 — повторные вызовы Алисы и запуск Музыки

SOURCE опубликован: `b2434a5804071cf21390c312bb0cf20e5861cec0`, дерево
`9dc90daff513cc9c8c591e623e4cf0bef4aceed8`. Актуальная ветка выпуска:
[release/natro-3.4.2](https://github.com/AGKulikov/Status/tree/release/natro-3.4.2).

TEST: [полный CI42351315865](https://github.com/AGKulikov/Status/actions/runs/42351315865)
SUCCESS: 2275 Android и 283 tool/replay tests, без ошибок/пропусков.
[Целевой CI42351315843](https://github.com/AGKulikov/Status/actions/runs/42351315843):23tests PASS.
APK: все три APK подписаны стабильным сертификатом Natro, v2/v3, exact API28 и
zipalign16KiB проверены. FIELD: OPEN — на магнитоле этот комплект пока не проверен.
Публикация Natro в stable выполняется отдельным workflow с побайтной проверкой assets.

Исправления исходников: новый pre-focus handshake при каждом вызове Алисы,
освобождение зависшей сессии при потере heartbeat, экспорт событий в журнал.
Мод Музыки подготавливает native MediaBrowser внутри собственного UID, выдаёт
токен только подписанному клиенту Natro; PLAY остаётся адресным и отменяемым.
Временный отказ подключения повторяется до первоначального deadline, без повторного
PLAY после выдачи token. Не меняются аккаунт, подписка и native очередь.

## Установка

1. Обновить Natro до3.4.2 и парный Navigator до Natro3.4.2. Для Алисы нужны оба APK.
2. Мод Музыки имеет другой сертификат, чем исходный присланный APK: поверх него
   не устанавливается. До замены сохранить нужные данные; потребуются вход в аккаунт
   и повторная загрузка офлайн-треков. Автоматического удаления нет.
3. После установки Музыки войти в аккаунт и запустить трек, чтобы появилась очередь.
4. Проверить повторные вызовы Алисы разными способами, возврат через2s после
   окончания разговора, ручную паузу и холодный запуск магнитолы.

Для Natro проверены прежний пакет/сертификат, code208021375=3.4.1+1 и1517неизменных
CI payload entries. В Navigator относительно3.4.1 изменён только classes19.dex;
manifest/resources/native libraries сохранены. У Музыки сохранены4725штатных entries,
изменён только manifest (версия/provider) и добавлен classes7.dex. Ключи и
проприетарные APK/декомпиляция не публикуются в Git. Helper не изменён.

## Артефакты

- Natro-3.4.2-signed.apk: `ba618418d5be284b98cbb18c2a05d4976f941ddef93394632e748123a656bae9`.
- Navigator-30.3.0-Natro-3.4.2-signed.apk: `1e8e3ecaefcc3f205e06eff95d99bfca9a023bf7e9d41bbd7b60df472409f5bb`.
- Yandex-Music-2026.10.2-Natro-1-signed.apk: `0ff7d3fceb0a7abb12f98917dcf9f649996368b6c2c23c8fa9b6725fba5ab371`.

[Изменения и ограничения](release-notes.md) · [CI](ci-verification.json) · [пара](pair-verification.json).
