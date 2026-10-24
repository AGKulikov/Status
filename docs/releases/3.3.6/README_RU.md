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
