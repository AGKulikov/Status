# Natro 3.3.7 — готовые действия лайка и Алисы

В «Действия Natro» добавлены «Лайк / убрать лайк» и «Алиса в Навигаторе».
Назначаются на физические кнопки/жесты, в меню водителя и HOME.
Лайк использует MediaSession/notification action выбранного плеера; поддержка
зависит от плеера. Алиса вызывается через yandexnavi://ask_alice адресно
в ru.yandex.yandexnavi без принудительных window extras.

SOURCE cfcabac4dd2627943e7653076a0c1974a2f71a26,
tree a4995218a6f4237ae5b09a4134874da0d10727c6.
[CI40081126066](https://github.com/AGKulikov/Status/actions/runs/40081126066) SUCCESS:
2197 Android и279 tool/replay tests; failures/errors/skipped0.
Settings audit48/253/3017, backup inventory92 PASS.

APK3.3.7/code208021370 подписан прежним ключом: один signer, v2/v3,
точный API28, zipalign16KiB, package/label/non-debug и versionCode+1
против фактической3.3.6 PASS. Все1517 CI payload entries неизменны.
SHA256 49bd6878eac273300194440f79aec7fba164cf77b6f3fa2c8afd16bdf871554a.
[Отчёт](release-report.json), [подпись](signature-verification.txt).
Navigator patch прежний; дополнительная установка Navigator/Helper не нужна.

SOURCE/TEST/APK PASS в указанном объёме. PUBLISH OPEN до readback всех7assets.
FIELD OPEN: установка поверх с сохранением настроек, лайк/снятие лайка
реального плеера, появление голосового интерфейса Алисы, назначения кнопок
и регрессии MEDIA-GESTURES-336/GATE-079/095. Автотесты не подтверждают
физическое действие на ECARX.


## 24.10.2026 12:24 МСК — публикация3.3.7 завершена

Stable/latest natro-v3.3.7, release422633894. Publisher40081769491 SUCCESS:
все7assets скачаны и побайтно сверены перед публикацией. SOURCE/TEST/APK/PUBLISH
PASS в указанном объёме; FIELD лайк/Алиса/установка/медиакнопки OPEN.
Временные приватные копии ключа удалены.
