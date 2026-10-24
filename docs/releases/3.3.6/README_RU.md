# Natro3.3.6 — жесты медиакнопок

В «Кнопки → MEDIA» добавлены1×/2×/3×/длительное для previous/play-pause/next.
Используются общие распознаватель, каталог действий, Apply/Cancel иполная копия.
MEDIA и«Отключить действие по умолчанию» включаются явно. Ненастроенные кнопки
сохраняют прежний маршрут. Серия600мс; удержание от1000мс, действие ждёт
подтверждения media-route прошивкой. Короткое после длительного подавляется.

## 24.10.2026 — 3.3.6: SOURCE/TEST/APK проверены, FIELD OPEN

SOURCE `dd1a2b78fd86e6dadfffdfc1db2343ba42a987cf`, tree `22a7d0e908fc84af69698926066c15d7c65db623` опубликованы в
feature/media-gestures-20261024; удалённое дерево совпадает с локальным.
CI [40076243146](https://github.com/AGKulikov/Status/actions/runs/40076243146)
SUCCESS:2194 Android-теста /429 XML suites и279 tool/replay tests,
failures/errors/skipped0. Settings audit48/253/3017 иbackup inventory92 PASS.

APK3.3.6/code208021369,29821536bytes, SHA256 `8fb5419e039ba90b0377344e7c684d0178cec9db3ad4ff7352c45fffe0dd8372`.
Один прежний signer, v2/v3/точныйAPI28, package/label/non-debug/zipalign16KiB,
versionCode+1 против фактической3.3.5 и1517 неизменных CI payload entries PASS.
Navigator classes19.dex имеет прежний SHA710af1b6…; Helper не менялся.
Дополнительная установка Navigator/Helper для этого обновления не требуется.
Публичный signature-only handoff не содержит ключа/пароля. Публикация
stable/latest выполняется через draft/readback всех7assets; до её результата
PUBLISH OPEN. Физическая установка/сохранение данных иMEDIA-GESTURES-336,
GATE-079/095 остаются FIELD OPEN: ни повторяемость на руле, ни фактический
отклик под нагрузкой без аппаратной трассы не объявлены подтверждёнными.

| Уровень | Доказательство | Результат |
|---|---|---|
| SOURCE | Публичные commit/tree выше; изменение от всей3.3.5 | PASS |
| TEST |2194Android/279tool; новый replay, draft/Apply/Cancel, migration335…368→369| PASS |
| APK |[release-report.json](release-report.json), [подпись](signature-verification.txt)| PASS |
| PUBLISH |[канал](https://github.com/AGKulikov/Status/releases/tag/natro-v3.3.6), readback всехassets| OPEN до успешного publisher |
| FIELD |Все три кнопки/жесты; OFF/default/SRC; звонок/DIM/gallery; reboot/update/backup;3×30мин| OPEN |

[Исследование и ограничения](../../geely-kx11/MEDIA_GESTURES_2026_10_24_RU.md).

История подготовки до CI:

# Natro 3.3.6 — назначения медиакнопок

Кандидат versionCode208021369 поверх3.3.5, branch feature/media-gestures-20261024.
Previous/play-pause/next:1×/2×/3×/длинное в общем редакторе. Серия600мс,
распознавание удержания от1000мс с обязательным native-media подтверждением.
Ненастроенные кнопки работают прежним способом. Новые назначения сохраняются
общей полной копией. Исправления media delivery/traffic23.10 включены.

SOURCE: см. историю этой ветки.17 portable replays PASS. Android CI, подписанный
APK, проверка exact payload/install-over/publication пока OPEN. FIELD OPEN:
три кнопки/жесты, исходныйрежим/OFF, звонок/DIM/gallery, restart/update/backup,
отсутствие дублей и3×30мин. Helper/Navigator runtime не меняются.
[Подробности](../../geely-kx11/MEDIA_GESTURES_2026_10_24_RU.md).
