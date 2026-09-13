# Natro 2.9.3 — штатные медиакнопки, запуск карты и камеры

Пользователь 13.09.2026 разрешил сборку новой пары и публикацию всех исходников с требованиями.
Natro: **2.9.3 / 208021326**; Navigator: **30.3.0 / 739564630**, модификация пары **2.9.3**.
Оба APK собраны, подписаны прежним сертификатом и проверены после скачивания.
Исходный commit: [`bc9a069460493711d4c51b7f58a752972007ff34`](https://github.com/AGKulikov/Status/commit/bc9a069460493711d4c51b7f58a752972007ff34),
tree: `3b12cdf7a66658c8ea566f74940d50afca024208`.
Release CI: [34777315541 / run 2](https://github.com/AGKulikov/Status/actions/runs/34777315541), success.
Последующий коммит с этим протоколом не является исходником APK.

| Файл для установки | Размер, байт | SHA-256 |
|---|---:|---|
| Natro-2.9.3-signed.apk | 28360046 | `19133dbabe4e6347897468e7ade8a101d3998b1830f10c3195f717096c508e62` |
| Navigator-30.3.0-Natro-2.9.3-signed.apk | 155153827 | `48aea07ece1c3c6a73a1ce67563ab8741c504c25297f5f047fda13847aef0820` |

Установка: сначала Navigator, затем Natro поверх установленной совместимой пары, без удаления
приложений и очистки данных. Пользовательское имя Navigator отличается от имени
`YN_30.3.0_Natro-HUD-v2-signed.apk` внутри CI-архива; байты и SHA-256 совпадают.

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
отсутствующего org.json JAR. Hosted CI получил настоящую зависимость: **152 проверки инструментов
прошли без пропусков**. Полная Android-сборка успешна: **1883 unit-теста, 370 suites, 0 failures,
0 errors, 0 skipped**; итог пересчитан из XML скачанного артефакта. Все Java-исходники navigator-mod
скомпилированы и собраны в DEX для API 28. Текущий Helper v70 contract прошёл; сборка IPA не запрошена.

Первый CI-кандидат `27be9fe` (run `34776786633`) остановлен до подписи: из 1883 Android
unit-тестов 1882 прошли, один source-contract ещё искал имена картинок непосредственно в
CameraDirectionMapLayer после их переноса в RouteCameraPolicy. Контракт теперь проверяет
вызов настоящего stock-провайдера, делегирование политики и оба ресурса в её исходнике;
поведенческие проверки выбора speed/lane/crossroad остаются обязательными.

Архив подписанной пары: `navigation-hud-v2-signed-2.9.3-2`, artifact `10324092916`,
SHA-256 `dd0b35263e74940dceb39a06941a4fe13b535249fae20f1cfe25f50c33c10351`.
Unit-test artifact `10324332159`, SHA-256
`d591b0c6f4e34937a205cbc62de89f35e3b430593ef1a3d6b2b3ea2bb9ded00b`.
Проверены размер и SHA-256 обоих скачанных архивов, CRC архивов и APK, все строки CI SHA256SUMS.
В CI apksigner проверил v2/v3 и одного подписанта, zipalign и статическую совместимость KX11.
Локально повторно прочитаны package/version/minSdk и сертификаты обоих блоков v2/v3.

В фактическом DEX отсутствует SteeringMediaKeyRouter, присутствуют пассивный наблюдатель,
ограниченное чтение журнала, sentMapGeneration, новый выбор/владение камерой и ANCS stopOnMain.
Для ключевых скомпилированных методов сохранены хеши инструкций. В сравнении Navigator с 2.9.2
изменился только `classes19.dex`: остальные **17030** payload entries совпадают побайтно
(META-INF с подписями исключён). Сохранность исходного baseline отдельно проверена в CI.

Отдельный [source CI 34777315530](https://github.com/AGKulikov/Status/actions/runs/34777315530)
также прошёл. На первом push запускались старые workflows Helper v53–v64; например v64 отверг
уже ранее изменённую константу HELLO_COALESCE_MS, ожидая 1500 вместо 30000 мс. Эти архивные
контракты не ослаблялись и не выдаются за успешные; выпуск проверен актуальным контрактом v70.

Машиночитаемые доказательства: [release-report.json](release-report.json),
[unit-test-summary.json](unit-test-summary.json), [final-download-check.json](final-download-check.json).
Отчёты инструментов: [signature-reports.txt](signature-reports.txt),
[KX11-COMPATIBILITY.txt](KX11-COMPATIBILITY.txt), [SHA256SUMS.txt](SHA256SUMS.txt).

Стабильный общий сертификат SHA-256:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
Navigator baseline SHA-256:
`663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f`.
Пакеты и пользовательские данные сохраняются; секреты и чужие исходники в публичный Git не входят.

Достоверные штатные поля «Поездки 2» и первопричина GATT 133 остаются неустановленными.
Успешная сборка не является подтверждением устранения белого старта или задержки звука на машине.
