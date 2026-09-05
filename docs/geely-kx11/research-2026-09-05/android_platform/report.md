# Android/ECARX: расширение охвата за пределы прежних 14 вопросов

Дата: 5 сентября 2026. Выход: **12 карточек систем** в `systems.json`, у каждого известного факта есть исходный архив, SHA-256 member и строка либо DEX class/method pointer. Карточки перечисляют неизвестное, подтверждение оснащения, границу control route и следующий офлайн-шаг. Это дополнительная часть общей матрицы автомобиля, а не самостоятельное заявление о полном охвате всех ECU.

## Новые результаты, имеющие практическое значение

### 1. Штатное питание ГУ самостоятельно меняет Bluetooth, Wi-Fi и звук

Из уже полученного `ecarx.powersomeip.service/base.apk` восстановлена реализация `PowerSomeIPServiceImpl.apStatusChange(I)V`. В ней есть выключение/восстановление Wi-Fi и Bluetooth, запоминание `wifi_pre_state`, `bt_pre_state`, `lastAPStatus`, mute и передача AP status в автомобильный manager. В ветви восстановления Bluetooth/Wi-Fi учитываются промежуточные состояния включения/выключения, а не только boolean.

Это добавляет конкретного штатного владельца жизненного цикла, который надо учитывать при расследовании ANCS, музыки и запуска после сна. **Причина конкретного сбоя пользователя пока не доказана**: нужна временная связь с соответствующим переходом PowerSomeIP, а не только совпадение симптомов.

Дополнительно `PowerSomeIPServiceImpl$PowerSomeIPHALCallback.ScPwIVIMngrReqState(B)` (`code_offset 0x7880`) отбрасывает повторный request type, вызывает `IIplm.ReleaseResourceGroup` и затем vendor-метод Android `PowerManager.onQNXRequest(B)`. Поэтому QNX и Android power lifecycle связаны конкретной исполняемой цепью. Это не команда выключения двигателя и не замена ещё не разобранному протоколу ECU питания.

`onPowerSoftKeyEvent` также не является одним универсальным «выключить»: внутри различаются короткое нажатие, длинные ветви, проверки UsageMode/CarMode, screensaver, hardkey и CB_Power_Softkey. Их нельзя уплощать в один toggle.

### 2. Управление PSD не совпадает с обычным bindService и имеет собственный enum

`EcarxMultidisplayService.onBind` возвращает `null`. При создании сервис публикует `ecarx_multidisplay` через `ServiceManager.addService`. Этот Binder действительно есть в отдельном runtime-дампе.

У реализации `setPsdDisplayOnAndOff(int)` значения **2=ON, 1=OFF** и вызов `CB_Power_PSDStatus`. Значение 0 — INVALID; framework getter возвращает его при null proxy или `RemoteException`. Следовательно, ноль здесь нельзя отображать как выключенный экран.

Это отдельный PSD power route. Из названия API нельзя выводить управление HUD или всей приборкой. В `IECarXCarPower` имеются отдельные CSD/PSD brightness, дневная/ночная яркость, theme и screensaver параметры, которые прежняя общая формулировка «экран вкл/выкл» не охватывала.

### 3. Аудиотракт шире MediaSession и запуска Яндекс Музыки

Новый runtime подтверждает `ecarx_audio_service`, `EcarxAudioService`, native audiocontrol HAL, Android audio service/AudioFlinger. В `ecarx.car.audio.jar` обнаружена отдельная адресация **ENT/NAVI/BEEP**, mix источников NAVI/HFT/VR/eCall, duck/Siri, PDC warning и DIM sound warning. Есть собственные balance/fader, EQ, speed-volume и варианты amplifier/DSP.

Эти интерфейсы помогают поставить отдельные вопросы о mute после сна, возврате источника после звонка/предупреждения, микшировании и приоритетах. Наличие Bose/Harman/ClariFi в общем SDK не доказывает конкретную аудиокомплектацию. Полная аудиополитика и физические каналы ещё не восстановлены; менять предупреждения или отправлять настройки в этом исследовании не требовалось.

### 4. Радио имеет самостоятельные сервисы и state machine

Runtime содержит одновременно `ts_radiomanager`, `broadcastradio`, native `vendor.ecarx.ts.radioservice@1.0` и `vendor.ecarx.xma.broadcastradio@2.1`. `IRadioServiceBase` содержит tuning AM/FM/DAB, seek/scan, возврат источника и отдельные TA/news/alarm/link параметры.

Это не исчерпывается командой next/previous плеера. Не установлены production backend, единицы частоты, региональная сетка, поддержка DAB и приоритет объявления относительно других источников.

### 5. Телефония, Bluetooth media, BLE и TCAM — разные системы

Runtime подтверждает Android **HFPclient, A2DPsink, AVRCPcontroller, PBAPclient, MAPclient и GATT**, отдельно ECARX Bluetooth extension. Framework extension даёт отдельные auto/passive-connect, HFP reject и PBAP sync маршруты; в том же `ecarx.jar` существует ещё отдельный `PSDBluetoothManager`.

В новом Bluetooth-архиве дополнительно есть `ts-dm-foundation-lib.jar` с `BluetoothConnectManager` и `DeviceBluetoothProfileManager`. Конфигурация nFore содержит `MaxAclNum=4`, `MaxDeviceNum=2`, `MaxHfpNum=2`, `MaxAvpNum=2`, `MaxPbapNum=2`, `LeScanEnable=true`. **Это не доказательство четырёх одновременных BLE/ANCS соединений.** Необходимо ещё установить активного владельца и фактическую профильную арбитрацию.

Повторно извлечён штатный `XCBTPhone3.apk` SHA-256 `b25b256bd73ab328fe67da1a8062e402dc88c20fa38d2733780a5aeacc56a427`. В отличие от ряда автомобильных сервисов, у него нет sharedUserId system; в runtime приложение работает под `u0_a19`.

- `InCallServiceImpl` явно exported и защищён `android.permission.BIND_INCALL_SERVICE`.
- Отдельный `BluetoothService` работает в процессе `:remote`.
- `NoViewActivity` обрабатывает `android.intent.action.CALL`.
- `EcarxDimMenuReceiver` имеет отдельные outcall/answer/decline/answer-and-hold/answer-and-end/ringtone-mute actions.
- Выбранные тела receiver подтверждают переход в `UiCallManager` с `answerCall`, `answerAndHoldCall`, `answerAndEndCall`, `rejectCall`, `disconnectCall`, `setRingtoneMute`, `setMuted` и `safePlaceCallInternal`; `InCallServiceImpl` получает Android Telecom callbacks.

Это уже конкретная цепочка штатного управления телефонией. Контракты extras, права стороннего приложения, выбор телефона при двух подключениях и все state conditions пока открыты. Никаких звонков не выполнялось; контакты, история звонков и значения телефонных номеров не изучались.

**TCAM выделен отдельно.** Живой Binder `tcam`, Android TcamService и native HAL подтверждены runtime. `ITcamService/TcamManager` содержат eCall/bCall/iCall, callback mode, IHU microphone use, CarLocator support, RVDC/remote diagnostics, vehicle IP table и reset support. Общие charging/light-show методы не доказывают соответствующее оборудование на данном KX11. Следующий этап — связать support/config/state с реальной комплектацией; пробные экстренные звонки и reset не нужны.

### 6. У шторки появился точный runtime-компонент, но вопрос безопасной замены ещё открыт

`ecarx-hud-dump.tar/services.txt`, строки **728–739**, фиксирует:

- компонент `ecarx.notificationcenterui/.ControlBoardService`;
- action `ecarx.notificationcenterui.action.CONTROLLER_BOARD`;
- пакет из `/system/app/XCNotificationCenterUI/XCNotificationCenterUI.apk`, процесс UID1000.

В этом же снимке отдельно работают `ecarx.xsf.gestureservice/.GestureService`, `ecarx.xsf.inputservice/.InputService`, SystemUI plugin AppWatcher/UserData, PartialService и MediaWindowStateService. Это существенно более точная исходная точка, чем одно имя пакета шторки. Но ServiceRecord ещё не доказывает владельца edge gesture, все функции ControlBoard или допустимость его отключения. Нужен разбор IPC/consumer цепи и побочных обязанностей компонента.

Новый dump повторно подтверждает четыре логических дисплея и пять `ecarx_daemon_OverlayDisplay` слоёв. Из их имён не выведено владение всеми пикселями крыльев/машинки; видео и синхронное изолированное наблюдение по-прежнему нужны.

### 7. Добавлены отдельные области CarPlay, GNSS, сети и обновлений

| Область | Новая точная опора | Главный остающийся пробел |
|---|---|---|
| CarPlay | Живые CarPlayService/RemoteService и отдельные adapters audio/video/Bluetooth/Wi-Fi/location | Session/focus lifecycle, беспроводной режим и взаимодействие с ANCS |
| GNSS | Живой native GNSS HAL, location Binder, отдельные GPGGA/GPRMC/speed/gear callbacks | Источник измерения, quality/time, coordinate system и физический приёмник |
| Сеть | Android connectivity/ethernet/wifi, vendor connectivity, SOME/IP applications и discovery peer | Полная L2/L3 topology, modem/TCAM ownership и связь с VP .1 |
| OTA/DTC/USB update | Живые ota/dtcnl/usbupdate/QnxSlog; OTA assignment/consent/resource-group API | Targets, доверие, recovery и service-side permissions; ничего не запускалось |

## Точный объём этой проверки

- Системный ZIP июля: индекс всех **181 members**; локально выбрано **45 members** для framework/малых APK/init/config/report; разобраны manifest всех **12 выбранных штатных APK**. Полное тело всех APK не анализировалось.
- DEX-индекс по выбранным framework и трём малым APK: **7607 class entries / 20941 method declarations**, с дубликатами SDK в разных JAR. Это технический поисковый индекс, **не число функций/систем**. Для конечных выводов оставлены **25 выбранных class/interface записей** в `platform_evidence.json`.
- PowerSomeIP/multidisplay: целевой разбор реальных тел методов; audio/radio/CarPlay/service interfaces — сигнатуры, константы и выбранные wrapper-методы. Нативные GNSS/audio/connectivity реализации целиком здесь не дизассемблировались.
- Отдельный `ecarx-hud-dump.tar`: все **10 имён/размеров/SHA**, targeted Binder/service/process/display/layer evidence. Дата захвата не выведена из текущей даты анализа. Содержимое личных окон, контакты и другие пользовательские строки не копировались в результаты.
- Два launcher-phone ZIP: по **3 выбранных members**; phone APK и оба component reports побайтно совпадают между архивами. Из одного phone APK разобран manifest и method reference graph **56 методов в четырёх точных классах**. Всё приложение телефонии не объявляется разобранным.
- Из шести новых Bluetooth-архивов здесь целевым образом выбран только **20260815-134614_76337**: **6 named members**, включая nFore/bt_stack config, два framework JAR, Bluetooth APK и package metadata. Для выводов разобраны nFore config, class index двух JAR и Bluetooth manifest; выбор member не означает полного смыслового разбора всех его байтов. Остальные пять получили только инвентаризацию имён в рамках поиска; их runtime-логи/HCI не анализировались. HCI, ключи сопряжения, database содержимое и ANCS notification payload не читались. Два Monjaro HUD log не использовались в этих выводах.
- Версия Natro **2.7.7 / NOTIF-009** и её запрет TTL сохранены; исходники Natro, APK, автомобиль и настройки не менялись.

## Состав результата и дальнейшая работа

`systems.json` — 12 готовых карточек; `platform_evidence.json` — выбранные интерфейсы с source hashes; `component_manifest_index.json` и `supplemental_interfaces.json` — manifest/interface metadata; `phone_call_routes.json` — только выбранные вызовы и протокольные строки с DEX offsets; `selected_sources.json`, `runtime_sources.json`, `supplemental_sources.json` — provenance. `build_systems.py` и `extract_platform.py` — собственные офлайн-скрипты.

Каталог `private/` содержит исходные выбранные байты и рабочее дизассемблирование и **не предназначен для публичного Git**. Большой поисковый `platform_api_index.json` также не требуется публиковать: конечные доказательства выделены отдельно. Публичный отчёт не копирует APK, ключи или полные чужие методы.

Продолжать сначала по сохранённым APK/JAR и свежему расширенному корпусу. Особенно продуктивны: PowerSomeIP→Android/QNX lifecycle, phone receiver→UiCallManager→Telecom, runtime ControlBoard→gesture IPC, BT-DM→profile owner и audio mixing→native policy. Новое требование собрать всё заново сейчас не обосновано.
