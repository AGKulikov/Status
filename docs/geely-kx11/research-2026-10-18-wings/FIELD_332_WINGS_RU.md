# FIELD 3.3.2: одна загрузка, переключение и проверка Sys2038

18.10.2026. Пользователь: «Переключение приборки вроде работает». Это предварительный положительный FIELD-результат, не автоматическое закрытие десяти циклов и всех GATE-059/066.

## Источники

| Архив | Состояние / начало МСК | SHA256 |
|---|---|---|
| Natro-wings-cb09738c-1399-48c0-b184-268414747846.zip | visible / 18:14:49.866 | df30baaaf3b430f38e1aec8c67c1e1e0835534313e5433d44276a1e66c34434e |
| Natro-wings-6c1ed8c5-22c5-43dc-94e1-cfc99b20b8aa.zip | normal / 18:16:05.800 | f1949d43ff651202be59552b9cbfed9841061467cd538a976a52d54f5953f27b |

Оба ZIP CRC PASS, 40/40 manifest bytes/SHA PASS, 8 PNG декодируются. Natro3.3.2. Firmware qti/kx11_high/kx11_high:9/20.24.10.23024.41151/539:userdebug/test-keys. Одинаковый boot714ae222-ca2b-4374-a58d-bfb947fdd16a, start delta75.934с. Сырые логи/карты/изображения пользователя в публичный Git не включены. [Машинные факты](FIELD_332_EVIDENCE.json).

## Что подтверждает переключение

| Признак | visible | normal |
|---|---|---|
| NaviMode до/после |3/3|1/1|
| PID Natro |372|372|
| Задача настроек Natro |16664|16664|
| InstrumentPanelActivity |task16668, stack14|отсутствует|
| HUD и DIM menu Natro |присутствуют|присутствуют|
| HWC display2 до/после |7 Client /7 Client|7 Device /7 Device|

Сохранение PID и задачи в одной загрузке согласуется с закрытием только собственной панели. Это два снимка, не непрерывная запись всех десяти циклов, ANCS/media или отсутствия промежуточного диалога. Происхождение установленного APK криптографически из этих логов не определяется; версия в session3.3.2 и сообщение пользователя согласованы.

## Крылья и граница кадра

builtin2 обоих архивов1920×1080; builtin0/1/3 —1920×720. builtin1 полностью чёрный. На visible builtin2 есть карта верхней панели и нижние HUD/меню; декоративных крыльев визуально нет. На normal верх чёрный, есть горизонтальная серая полоса и нижние HUD/меню. Полоса не атрибутирована по одному изображению. Нет синхронного физического фото и проверенного конечного QDI1 кадра. Обычный screencap не доказывает полный физический выход даже при Client composition. Смена Client/Device — корреляция с составом сцены, не установленная причина крыльев.

## Проверка конкретного подозрения: Sys2038

Первичное наблюдение «вместо панели появляется полноэкранный слой dimmenu» уточнено по windows и SurfaceFlinger:

| Признак Sys2038:com.ecarx.dimmenu/MainActivity | visible | normal |
|---|---|---|
| Window/parent identity |fda349b, тот же|fda349b, тот же|
| isReadyForDisplay / isOnScreen / isVisible |false/false/false|true/true/true|
| Surface shown |false|true|
| SF flags / VisibleRegion |0x1 / пусто|0 /1920×1080|
| HWC display2 |нет|есть|

Это существующее окно, а не новый renderer, созданный при OFF. В помеченном visible снимке оно скрыто. Тем самым именно этот слой не участвует в зафиксированной Android-композиции с крыльями, о которых сообщает user_state. Пакет dimmenu целиком и его возможные команды другим компонентам этим не исключены; нет синхронного физического фото.

Natro InstrumentOemController.applyWhiteBarNow уже управляет SYSTEM_ALERT_WINDOW пакета dimmenu по навигационному режиму; это согласуется с изменением видимости. В этих архивах нет appops readback/временной трассы вызова, поэтому конкретная причина hide не доказана только снимками. Два безымянных слоя #0/#3 в обоих состояниях принадлежат plus.monjaro/uid10073:1920×1080 и728×190. Суффикс не означает нового владельца; наличие слоя не доказывает пиксели крыльев.

## STATIC: создание и содержимое окна

Из ранее предоставленного KX11-Cruise-Trace-20260809-001402.zip (SHA bbb4b7bb8c2f04864ef41777c6679e2881d2db3084563c9d10518a20cb61bc05) извлечён DimMenu.apk,7864621байт, SHA214193adbfd1f6ee9033c455b4f8f373fdfee95d89a12b3522f445a4ce647820. Свежий установленный APK в новых архивах отсутствует; тождество байтов не заявляется.

- MainViewModel.initPresentation: +0x50 создаёт FullMapPresentation, +0x64 выбирает displays[2], +0xa4 загружает2038, +0xa8 setType, +0xae show; high-line width1920/height720, background android transparent.
- FullMapPresentation.onCreate: +0x16 layout0x7f0c0042 (full_map_layout), +0x40 setContentView; FullMapFragment размещён внутри presentation.
- full_map_fragment содержит LanesDisPlayView, FullContainerView, FullVolumePopView, NavigationPopView. FullContainerView — известные media/call/WeChat popups, а не обнаруженная геометрия двух крыльев.
- FullMapFragment.onNavModeChanged: +0x14 сравнение с3; +0x1a VISIBLE0, иначе +0x1e GONE8; +0x22 setVisibility у viewFullMapContainer. Видимость контейнера и видимость всего Android Window — разные уровни.
- LanesDisPlayView.showLanesView строит элементы из List<LaneModel>, выбирает day/night background; пустой список скрывается. По именам/этой ветви нельзя приписать ему декоративные крылья.

Просмотрены4220 методов namespace com.ecarx.dimmenu двух DEX для адресного поиска window/type/presentation. Это не исчерпывающая интерпретация всех методов и native ресурсов. В исходном августовском archive нет файлов hwcomposer/SurfaceFlinger/libdisplay/SDM/gralloc по поиску путей; это ограничение этого источника, не всех ранее присланных данных.

## Вывод и следующий участок

Отдельная гипотеза Sys2038 стала слабее: окно уже скрыто при visible. Переход Client/Device не использовать как переключатель крыльев. Продолжение — полная проверенная граница Android/HWC→QDI1 и фактический принимающий nav dispatcher, с уже полученными QNX бинарниками/конфигом. Не повторять полный QNX сбор и уже разобранные3KZB/843изображения без новых SHA. Локальный OpenWFD query требует lock; наличие guest HAB stubs не даёт рабочего пассивного захвата. Renderer/независимый hide остаются OPEN, runtime-код в этом исследовании не менялся.
