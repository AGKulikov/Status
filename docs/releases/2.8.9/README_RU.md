# Natro 2.8.9 и совместимый Navigator

Статус: оба APK собраны, подписаны прежним ключом и независимо проверены
13.09.2026. Последующий результат пользователя отрицательный: карты на HUD и приборке не работают.
Восстановление показа подготовлено в [2.9.0](../2.9.0/README_RU.md); устранение белого засвета остаётся открытым.

| Компонент | Пакет | Версия | versionCode |
|---|---|---|---|
| Natro | ru.natro.statuswidget | 2.8.9 | 208021322 |
| Navigator | ru.yandex.yandexnavi | 30.3.0 | 739564630 |

Общий сертификат SHA-256: `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
Natro versionCode увеличен на 1 относительно 2.8.8. Установка обоих APK поверх
текущих версий без удаления приложения и очистки данных; сначала Navigator.

## Изменение и основание

В установленном Natro 2.8.8 вызов TextureView.getBitmap шёл из
onSurfaceTextureUpdated во время TextureView.draw, что запрещено контрактом
Android. Проверка перенесена в отдельную задачу UI-очереди. Отмена устаревших
задач привязана к текущей поверхности. Условия готовности producer и трёх
содержательных кадров сохранены. Добавлены состояния чтения кадра и ACK
в Natro, а в Navigator — события MapLoadedListener и отправки ready ACK.
Исправленная регистрация MapLoadedListener из 2.8.8-mapfix сохранена.

[Разбор фактического архива 131720](../../map-startup-2026-09-13/MAP_CHECK_131720_RU.md).
Исходный fix: `f48692a6385c67af5baef985cdcea01dea01bcdc`, дерево
`11ee25131588960fb1895b78dbdd52bdaa07b177`; полный
[CI 34752272383](https://github.com/AGKulikov/Status/actions/runs/34752272383) успешен.
Номер исходного commit самой сборки записывается CI в release-report.json.

## Матрица проверки

| Требование | Исходники | Автоматическая проверка | KX11 |
|---|---|---|---|
| NAV-018 / GATE-082: запуск карты HUD и приборки | MapFirstFrameDetector, HudCompositeView, InstrumentPanelView | DeferredCheck: очередь, три кадра, подавление рекурсивного callback, отмена старой поверхности, producer ACK; NavigationHudV2ContractTest | Не подтверждено |
| NAV-018: различать этапы готовности | NavigationHudEndpointService, navigator-mod/HudMapRenderer | Компиляция и контракт MapLoadedListener | Новые события ещё не получены с автомобиля |
| REL-002…006: обновление без потери данных | Release workflow и sign_navigation_hud_v2_pair.sh | Стабильная подпись v2/v3, один подписант, пакет/версия/label, zipalign, сравнение предыдущих APK | Установка новой пары ещё не выполнена |
| REL-007…010: опубликованный источник | Release workflow | Точный commit/tree, SHA-256 APK и baseline | Не требует KX11 |
| NATRO-027 / GATE-086: штатная поездка | Без изменений в данном выпуске | Результаты архивов сохранены отдельно | Открыто |

Локальные 17 проверок и CI подтверждают код и контракт Android, а не работу GPU
магнитолы. Архив показывает успешный attach обеих поверхностей, но не содержит
прежних событий ACK/пиксельной проверки, поэтому полная причина отказа не доказана.
GATE-082 и прежние аппаратные критерии остаются открытыми до проверки пользователя.

## Фактические APK и результат проверки

Источник сборки: `61f896bebd08c92335877a5af94620beece2e95d`, дерево
`7cf79cf13277b942956c8c24d048138591460af0`. Полный
[release CI 34754670964](https://github.com/AGKulikov/Status/actions/runs/34754670964)
успешен: **1881 тест приложения в 369 наборах и 108 проверок инструментов**,
без ошибок и пропусков. Контрольные суммы обоих архивов CI и пяти файлов
манифеста подтверждены независимо.

| Файл | Байты | SHA-256 |
|---|---:|---|
| `Natro-2.8.9-signed.apk` | 28351854 | `4575b28ca91e971d71e998c648b05fdc6354e3cb5d57a97a85cc6bb7f8ead97e` |
| `Navigator-30.3.0-Natro-2.8.9-signed.apk` | 155149731 | `be2a81b5625dc5a2e4c3e0934ee9fdd3ef0b95ed46692956327b74ab3b7d6745` |

Проверены APK Signature Scheme v2/v3, один подписант, прежний общий сертификат,
CRC всех ZIP-записей, zipalign 16 KiB, package/label/versionName/versionCode
и сравнение с фактическими APK Natro 2.8.8 / Navigator 2.8.8-mapfix.
Natro имеет versionCode +1. Navigator сохраняет метаданные базового пакета;
изменился только classes19.dex, остальные **17226** ZIP-записей
побайтно совпадают с 2.8.8-mapfix. Все **17222** защищённые записи baseline
сохранены. Подробности: [artifact-verification.json](artifact-verification.json),
[проверка совместимости](LOCAL-KX11-COMPATIBILITY.txt),
[подписи](signature-reports.txt), [provenance](build-provenance.json).

В фактическом DEX Natro оба onSurfaceTextureUpdated вызывают DeferredCheck.onFrame,
а чтение/квалификация происходят в отдельной checkFirstMapFrame. В Navigator
подтверждены прежняя WeakReference-регистрация MapLoadedListener, новые события
готовности и capabilities 0x1bfe7. [Проверка DEX](dex-verification.json).
Исходное имя Navigator в архиве CI — YN_30.3.0_Natro-HUD-v2-signed.apk;
пользовательский файл переименован без изменения байтов.

Аппаратные GATE-082/GATE-086 остаются открытыми. Эта сборка содержит исправление
выявленного нарушения контракта Android, но устранение всего наблюдавшегося
отказа запуска карты ещё требует результата на автомобиле. Данные штатной
поездки этим выпуском не восстановлены.
