# Natro 3.4.5 — порядок подготовки и запуска Музыки

При холодном старте Music Natro 2 могла показать медиасессию раньше завершения
`onGetRoot`. Natro отправлял `PLAY` до ответа `prepare_v1`. В новом APK добавлен
барьер: незавершённая подготовка, включая истёкший запрос, не разрешает cold PLAY.
Успешный ответ с token снимает барьер; ручная отмена и ограниченные повторы сохранены.

| Проверка | Результат |
|---|---|
| SOURCE | `a2c6f974e2382684b8bdbd3822bf8d143fd19353`, tree `9fd54e44d760ec9df6145cac3a081732d1a6813c` |
| TEST | [CI 42361490968](https://github.com/AGKulikov/Status/actions/runs/42361490968): 2283 Android-теста / 445 XML suites, 285 tool/replay tests; 0 failures/errors/skipped |
| APK | 3.4.5 / 208021378, 29973088 bytes, SHA-256 `db92b0995856620cfa3d738248672e0a3ca61cac46399bc32487a67453864f18` |
| Подпись и обновление | Один прежний сертификат, v2/v3, exact API 28, zipalign 16 KiB, versionCode +1 против 3.4.4, 1517 payload entries неизменны после подписи |
| PUBLISH | Подготовлено; фактический результат будет записан после проверки stable/latest и скачанных файлов |
| FIELD | OPEN: повторные холодные старты ECARX, фактический PLAYING и отсутствие повторного ANR ещё не проверены |

## Совместимость

| Компонент | Что устанавливать |
|---|---|
| Natro | Обновить до 3.4.5 поверх 3.4.4, без удаления и очистки данных |
| Яндекс Музыка | Оставить Natro 2 / code24026523. Исходники `music-mod` побайтно неизменны относительно runtime b04f768; provider, prepare_v1, protocol1 и callback34201 прежние |
| Яндекс Навигатор | Оставить Natro 3.4.2. classes19 и все native patch tool hashes совпадают с проверенной парой предыдущего выпуска |
| Natro Helper | Без изменений |

`candidate.json` сохраняет общий флаг требуемой парной интеграции Navigator.
Он не означает необходимость нового APK при наличии совместимой 3.4.2;
решение о повторном использовании явно записано в `companionCompatibility`.

## Матрица требований

| Требование | Исходники / проверка | FIELD |
|---|---|---|
| MUSIC-BOOT-MOD-342 / prepare-PLAY race | `NatroMusicBootstrap.blocksColdPlay`, барьер в `MediaResumeCommand`; production replay pending/expired/manual/token и присутствие обоих markers в подписанном DEX | OPEN |
| BOOT-338 | Прежние boot/manual permits и ограничение повторов сохранены; полный CI PASS | OPEN |
| REL-001…011 / 015 / 017 | [Отчёт](release-report.json), [CI](ci-verification.json), [подпись](signature-verification.txt), [API 28](exact-api28.txt), [update manifest](natro-update.json) | Реальная установка не выполнялась |

[Источник диагностики и границы вывода](../../geely-kx11/MUSIC_PREPARE_PLAY_RACE_2026_11_07_RU.md).
Архив источников выпуска закрепляется на точном runtime commit; изменения документации
и публикации находятся в `release/natro-3.4.5`. Закрытый ключ в Git не передаётся.
