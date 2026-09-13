# Natro 2.9.3 — штатные медиакнопки, запуск карты и камеры

Пользователь 13.09.2026 разрешил сборку новой пары и публикацию всех исходников с требованиями.
Natro: **2.9.3 / 208021326**; Navigator: **30.3.0 / 739564630**, модификация пары **2.9.3**.
На этапе подготовки этого документа подписанные APK ещё не получены. После CI протокол
дополняется точными commit/tree, SHA-256 и результатами проверки скачанных артефактов.

| Требование | Изменение и источник | Автоматическая проверка | KX11 |
|---|---|---|---|
| BASE-MEDIAKEY / MUSIC-015 | Убран SteeringMediaKeyRouter и Accessibility key-filter; SteeringKeyDiagnostics только наблюдает IEDIA/MEDIA и доступные MediaSession | test_passive_media_diagnostics, PassiveMediaKeyContractTest; отсутствие пересылки, независимые потоки, границы наблюдаемости и снятие подписок | Нужна длительная проверка руля с разными плеерами; заполнение внешнего канала не доказано |
| Диагностика зависаний | ActionRecorder читает ограниченный хвост вне LOCK; DiagnosticsActivity читает/экспортирует в фоне; дамп резервирует queued-work | test_action_recorder_async, test_passive_media_diagnostics; UTF-8, sparse 8 GiB, потоковый JSON, session fence | Требуется новый журнал при повторении задержки |
| Холодный запуск приборки | InstrumentPanelView восстанавливает геометрию без Settings и сохраняет Surface при позднем callback | test_cluster_cold_lease; late/stale callbacks, resize, off, повторная попытка | Обновление обоих APK в двух порядках без входа в Settings |
| NAV-019 / белый старт | Локальный update до успешной отправки текущей surface lease не раскрывает карту; полного tile/pixel gate нет | test_map_visibility_recovery, test_map_prepare_before_surface | Цвет первого native/GPU буфера после IPC ещё не проверен |
| Дубли камер / IMG_8390 | Обычный control/POLICE pin той же записи не создаётся под единым; обычный слой снимается до его появления | test_route_event_map_layer, test_camera_ownership_handoff, test_navigation_event_visibility | Проверить до приближения, во время и после проезда |
| Тип камер | Дополнительная табличка определяется выбранным stock-ресурсом Navigator, не наличием lane-тега | selectedCameraImageId + RouteCameraPolicy, test_route_event_map_layer; camera-stock-choice в журнале | Сравнить на одном маршруте с основным Навигатором |
| Дорожные события | Самостоятельные POLICE/ACCIDENT/ALWAYS на соседних дорогах не удаляются с дублирующей камерой | 22 категории, 80 записей; смешанные теги и независимые профили | Наличие конкретных двух ДТП требует сравнения актуальных источников |
| ANCS Android | Пустой GATT owner завершает stop через прежнее доказательство отсутствия owner/scan и свободного process drain | test_ancs_empty_owner_stop и существующие Java-контракты | Переподключение после обновления без перезагрузки ещё не подтверждено |
| ANCS iPhone | Snapshot.peerReady отделён от local active; C5 HELLO означает запрос, не доставку | verify-v70-personal-contract.sh | Исходники Helper опубликованы; эти APK не обновляют Helper на iPhone |

Локальная подготовка: 146 успешных Python/Java fixture-проверок из 147, одна пропущена из-за
отсутствующего org.json JAR. Hosted CI должен получить настоящую зависимость и выполнить полный
Android build/test и компиляцию navigator-mod. Проверки Helper v70 проходят, сборка IPA не запрошена.

Стабильный общий сертификат SHA-256:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
Navigator baseline SHA-256:
`663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f`.
Пакеты и пользовательские данные сохраняются; секреты и чужие исходники в публичный Git не входят.

Достоверные штатные поля «Поездки 2» и первопричина GATT 133 остаются неустановленными.
Успешная сборка не является подтверждением устранения белого старта или задержки звука на машине.
