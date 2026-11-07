# Natro 3.4.1 — единая голосовая сессия Алисы

07.11.2026: SOURCE / TEST / APK PASS; публикация stable запрошена пользователем
«Ну так доделывай и публикуй». PUBLISH OPEN до успешного readback;
FIELD остаётся OPEN. Предыдущая stable — 3.4.0.

Обязательна новая пара Natro 3.4.1 + Navigator 30.3.0 / Natro 3.4.1.
Установка поверх прежних приложений, без удаления данных. Helper прежний.

## Реализация и границы

Отслеживается состояние движка AliceKit/Quark независимо от способа активации.
Pre-focus hook запоминает игравший плеер, native idle начинает отсчёт 2000 мс.
Повторное слушание отменяет прежний таймер. Перед PLAY проверяются исходный
media token, текущая пауза, ручное управление, другой активный плеер и heartbeat.
Ожидание снимка до аудиофокуса ограничено 300 мс. При таймауте автоматический
PLAY не разрешается; обычная работа Алисы продолжается. Загруженный модуль
или окончание одной фразы TTS сами по себе не означают конец разговора.

| Требование | SOURCE | TEST / APK | FIELD |
|---|---|---|---|
| ALICE-VOICE-END-340/341: конец разговора до закрытия UI | NativeAliceBridge, NativeAliceCoordinator | native idle stream, 38 Alice tests, точные CI DEX PASS | На KX11 OPEN |
| Любой источник запуска внутри нашего Навигатора | pre-focus hooks AliceKit/Quark | детерминированный повтор патчера; штатная кнопка/голос/intent не перечисляются как отдельные разрешения | Проверка трёх способов OPEN |
| MUSIC-ALICE-338: исходная музыка, +2s, отмена при новой реплике/ручном управлении | AliceMusicResume, AliceMediaPlayback | runtime timer/Binder/manual guards PASS | Звук/тайминги OPEN |
| ALICE-338-R2/339: фон/окно, совместимый IPC | framework ResultReceiver, штатный BUTTON | foreign-loader/lifecycle tests PASS | Фон/окно OPEN |
| REL-002…010/017: подпись, версия, точная пара | source/tree/CI закреплены | подписи v2/v3/exact API28, zipalign, package, +1, exact payload PASS | Физическая установка OPEN |

## Происхождение и проверки

Source `deb16c7682b3030b52da5878fd6bf2ca3fba9001`, tree `b34889d9e5fb341290043206eb71e85621e10bf4`.
[CI 42280645665](https://github.com/AGKulikov/Status/actions/runs/42280645665),
job 128108584147 SUCCESS. 2269 Android tests / 38 Alice tests, 283 tool tests;
failures/errors/skipped = 0. Отчёты XML взяты из того же CI artifact.

Natro versionCode 208021374 = 3.4.0 + 1; 29956704 bytes,
SHA-256 `d23e5fe64f5984289de031c80693efcc4ca87ab0059605c62bd42338e32e2eb2`.
Стабильный единственный сертификат `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`;
v2/v3 и отдельный диапазон API28, zipalign16KiB, label/min/target28 PASS.
Все 1517 payload entries совпадают с unsigned APK CI.
Signature handoff воспроизводит подписанный APK побайтно, ключ не передаётся.

Navigator: 156546666 bytes, SHA-256 `014ceef56df04c4a71a0ef02a8a60b35893cbfa591f7519dff86bf816485a6f4`.
Pinned baseline3.3.9 SHA `52736b41714bba696cd54442804816741e2834b57a0d880702560667106a89dc`.
Изменены только classes11.dex/classes15.dex/classes19.dex; остальные
17224 entries, manifest, resources и native libraries неизменны.
classes19 совпал с CI, classes11/15 заново воспроизведены точным CI-патчером.
Identity/badging/arm64-v8a/sharedUserId/certificate/v2/v3/API28/zipalign PASS.
Чужой APK/декомпиляция и приватные ключи не публикуются.

[Отчёт Natro](release-report.json), [Navigator](navigator-release-report.json),
[подпись Natro](signature-verification.txt), [подпись Navigator](navigator-signature-verification.txt),
[манифест обновлений](natro-update.json), [исследование](../../geely-kx11/ALICE_2026_11_06_RU.md).
Проверка воспроизводится tools/verify_existing_alice_candidate.py без ключей.

## Открытая аппаратная приёмка

Штатная кнопка/голос/руль; короткий ответ, уточнение, новая реплика в течение2s;
initial paused, ручная пауза/смена плеера; фон/окно; нет речи/ошибка; смерть
процесса перед PLAY; время реального звука. Quark error classification ограничена
native idle и ошибкой подписки, IO errors обрабатываются отдельно. Прежние
незакрытые автомобильные дефекты не объявляются устранёнными этим выпуском.
