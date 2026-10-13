УГЛУБЛЁННЫЙ РАЗБОР UPDATE.ZIP — 13 ОКТЯБРЯ 2026

Это продолжение, а не повтор предыдущего перечня. Все выводы ниже получены
офлайн. Содержимое APK, ELF и автомобильные команды не исполнялись.
Исходный update-decrypted.zip повторно сверён по SHA256 после восстановления.

1. Что теперь раскрыто

Из 378 ранее неразрешённых обращений к настройкам определены строковые
ключи ещё для 159 обращений: GMCSoft 88, XCSettings2 44, CarService 4,
GPSProvider 23. Новых уникальных пар app/store/key меньше, поскольку многие
обращения относятся к одному и тому же ключу.

Приложение    Фиксированные пары store/key    Ещё не фиксированные обращения
GMCSoft       165                             47
XCSettings2   48                              105
CarService    57                              47
GPSProvider   27                              20
Всего         297                             219

Предыдущие 272 пары и 378 неразрешённых обращений — исторический срез.
Новые числа НЕ означают 297 пользовательских переключателей: в них есть
служебные флаги, библиотеки и повторы между APK. А 219 — не 219 скрытых
настроек: значительная часть является общими методами, принимающими имя
ключа, MAC-адресами, именами компонентов и ключами объектов Preference.

Для раскрытия использован анализ графа управления DEX с объединением
состояний на переходах, разрешением ресурсов, getter-ов перечисления и
подтверждённых одноступенчатых wrapper-вызовов. При несовпадающих значениях
в ветках анализ сохраняет неизвестность. APK при этом не запускается.
Обработано 199 методов прежнего списка, ошибок парсинга не зарегистрировано.

Файлы:
  SETTINGS_RESOLVED_INDEX_RU.txt — актуальный индекс разрешённых ключей;
  APP/settings_catalogue_combined.json — старые и новые точки обращения;
  APP/settings_cfg_resolved.json — каждое из 159 уточнений с предыдущей записью;
  APP/settings_cfg_remaining.json — оставшиеся обращения;
  nonliteral_settings_families.json — причины динамичности и источники;
  APP/resolved_settings_and_lifecycle.bytecode.txt — байткод проверяемых путей.

2. Реальные настройки подогрева и вентиляции GMCSoft

Перечисление a.h2.n содержит восемь вариантов. Значения взяты из аргументов
конструкторов <clinit>, а не угаданы по надписям меню:

Водитель, подогрев:
  left_seat_heat / left_seat_heat_close_time
Водитель, вентиляция:
  left_seat_blow / left_seat_blow_close_time
Пассажир, подогрев:
  right_seat_heat / right_seat_heat_close_time
Пассажир, вентиляция:
  right_seat_blow / right_seat_blow_close_time
Руль:
  ac_steering_wheel_status / ac_steering_wheel_status_close_time
Форсунки:
  ac_front_washing_status / ac_front_washing_status_close_time
Заднее стекло / боковые зеркала (название в приложении):
  ac_back_win_status / ac_back_win_status_close_time
Лобовое стекло:
  ac_front_win_status / ac_front_status_close_time

Последнее имя таймера действительно отличается от имени переключателя:
ac_front_status_close_time, без win. Оно не исправлялось и не нормализовалось.
В a.h2.m NONE означает «Не выключать» и хранит -1; время других вариантов
получено через TimeUnit.MINUTES.toMillis. Не следует записывать туда просто
число минут. Допустимые наборы времени различаются между вариантами.

UI a.g3.g взаимно выключает автоматический подогрев и вентиляцию одного
сиденья при выборе ненулевого уровня. Это связанная логика двух настроек.
Обработчик руля a.s2.e также учитывает время последней остановки и параметр
on_stop_engine_last_time_option; он не включает подогрев безусловно при каждом
сообщении. Повторные попытки и таймер выключения раздельные.

Нижняя цепочка GMCSoft:
  a.s4.a: type, functionId, zone, Number value;
  a.t4.a -> a.t4.d: type 1 направляется к sensor, type 2 к car function;
  a.t4.b: Integer -> setFunctionValue, Float -> setCustomizeFunctionValue;
  zone=-1 выбирает перегрузку без zone, иначе передаётся отдельная зона.
Это SDK-вызов, а не формат CAN-пакета.

Доказательства: GMCSoft/heating_keys.json, heating_enum_constructor_evidence.json,
heating_enums.bytecode.txt; классы a.h2.n/m/g, a.g3.g, a.s2.*, a.t4.*.

3. Переход от UI к аргументам автомобильного API

Для CarFunctionProxy выполнены 2172 статические специализации четырёх методов:
get/setFunctionSwitched и get/setFunctionIndex. В них подставлялись UI ID и
значение аргумента; известные условные переходы выбирались по константам.
Охвачены 181 ID из объединения support-map и type=1 узлов меню.
Для switch использованы 0/1, для index — пробный диапазон 0..7.
Диапазон 0..7 НЕ объявляется допустимым диапазоном каждого элемента UI.
Данные показывают возможные вызовы внутри метода при заданном входе, но не
доказывают доступность такого вызова через экран или поддержку автомобилем.

Проверенные вручную примеры включения:
UI ID 17: две записи — (738263296,1) и (738263552,1).
  Это совместное включение approach-unlock и away-lock, а не один property.
UI ID 31: (537985280,1), основной HUD.
UI ID 33: (654442752,1), снеговой режим HUD.
UI ID 116: (654378240,1), HUD brightness/adjust.
UI ID 215: (654443008,1), HUD AR engine.
UI ID 85: (537068800,537068801), SPEED_LIMITATION_MODE_AVSL.
UI ID 90: (537069056,537069059), SPEED_CONTROL_MODE_GPILOT.
UI ID 295: (538904064,1), intelligent fuel save.
Выключение этих примеров передаёт 0; для ID 17 — обеим функциям.

Это описание прочитанного кода, не инструкция для изменения режима движения.
Особенно ID 85/90: выбор режима ограничителя/gPilot не равен доказанному
включению круиз-контроля и не даёт доказанной команды задания его скорости.
Без реализации SDK/ECU нельзя вывести все условия допуска и физический эффект.

Входные числовые значения, методы, DEX-смещения и неизвестные аргументы:
  XCSettings2/vehicle_branch_candidates.json;
  XCSettings2/CarFunctionProxy.bytecode.txt;
  XCSettings2/all_proxy_classes.json — индекс всех 75 файлов proxy, публичных
    методов, известных UI-привязок и точек Android/ECarX API.
Восемь примеров выше дополнительно проверены против Java-реконструкции и
констант SDK; verification.json не объявляет все 2172 сценария независимо
верифицированными. Reflection, сложный heap-flow и нижний сервис не исполнены.

4. Доказанная граница SDK — важнее списка названий

В XCSettings2 есть com.ecarx.xui.adaptapi.car.Car.create(Context).
Его тело DEX буквально состоит из const/4 v0,0; return-object v0.
Точно такое же тело у LightingShow.create(Context).
Всего в adaptapi найдены 27 create-методов с тривиальным телом и 33 тривиальных
метода в целом. Сам по себе короткий метод не всегда заглушка, но эти две
фабрики действительно возвращают null в поставляемом DEX.

Следствие: для работающего автомобильного API нужна иная реализация класса
или другой путь исполнения. Вероятен системный слой/порядок загрузки классов,
но механизм подстановки из этого обновления не доказан. В манифесте XCSettings2
не найдено явного uses-library для adaptapi; это не исключает boot classpath.
Нельзя выдавать интерфейсные константы за раскрытую реализацию шины.

GMCSoft использует другой явно видимый вход:
  a.j2.b -> com.ecarx.xui.adaptapi.car.CarImpl.create/connect;
  watcher -> getICarFunction/getSensorManager;
  отдельно ecarx.car.ECarXCar.createCar/connect;
  отдельно MediaCenterAPI.get/init/registerMusic.
Тела CarImpl, ECarXCar и MediaCenterAPI отсутствуют в анализируемом GMCSoft DEX.
Каталог external_sdk_references.json показывает конкретные внешние классы:
GMCSoft 20, XCSettings2 5, CarService 79, GPSProvider 4.
Это число ссылок на отсутствующие классы выбранных car/vendor-пространств,
а не полный перечень всех отсутствующих Android-классов.

5. Приборная панель: внешний renderer и два слоя проверки прав

ICarImpl.getCarService("car_navigation_service") проверяет
android.car.permission.CAR_NAVIGATION_MANAGER, затем запрашивает
InstrumentClusterService.getNavigationService().
Для "cluster_service" отдельно проверяется
android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL.

InstrumentClusterService.bindInstrumentClusterRendererService читает ресурс
instrumentClusterRendererService, формирует ComponentName, кладёт Binder
callback android.car.cluster.IInstrumentClusterCallback в extras и вызывает
bindService с флагами 65.
Ресурс в данном APK: android.car.cluster.sample/.SampleClusterServiceImpl.
Такого компонента в четырёх манифестах нет. На реальной машине значение может
меняться системным resource overlay; overlay-пакетов и текущего runtime
значения здесь нет. Поэтому это не установленный получатель ECarX HUD/DIM.

Необычный предикат InstrumentClusterService.enforcePermission подтверждён
исходными Dalvik-инструкциями: проверка permission выполняется только когда
Binder.getCallingUid()==Process.myUid(). При разных UID метод сразу проходит
к return. Это потенциальный недостаток локальной проверки этого класса.
Но выдача сервиса через ICarImpl защищена отдельной проверкой, а запускаемые
cluster activities фильтруются по CAR_DISPLAY_IN_CLUSTER. Общего обхода
CarService и достижимости без прав этим не доказано.
Полный дамп: CarService/cluster_permission.bytecode.txt.

6. Поведение при разрыве автомобильного соединения

GMCSoft a.j2.c.onConnected получает ссылки c=ICarFunction и d=ISensor.
onDisConnected только записывает лог; эти поля не очищает.

XCSettings2 CarProxy.onDisConnected обнуляет mCar и isConnected, но сохраняет
mIcarFunction. getICarFunction сначала возвращает сохранённое mIcarFunction.
BaseProxy отдельно кэширует mCarFunction и запрашивает его только при null;
в этом классе не найдена ветка сброса кэша на переподключении.

Следствие, требующее runtime-проверки: если после обрыва SDK создаёт новый
Binder/proxy, потребитель может продолжить использовать старую ссылку.
Если SDK возвращает самовосстанавливающийся объект, проблема может не
проявиться. Это конкретный кандидат для диагностики, НЕ установленная
причина зависаний Natro, белой полосы или кнопок на машине пользователя.
Доказательства: APP/resolved_settings_and_lifecycle.bytecode.txt и исходники
a.j2.b/c, ecarx/settings/proxy/branch/CarProxy, proxy/base/BaseProxy.

7. Binder: отделены реальные протоколы от совпадения пакетов

По полям DESCRIPTOR/TRANSACTION получены 18 наборов и 231 константа.
Дополнительный проход по attachInterface/enforceInterface/writeInterfaceToken/
transact восстановил wire-метаданные 360 методов, 306 transact-сайтов и
26 уникальных разрешённых дескрипторов, включая HIDL-интерфейсы.
Дескриптор не равен отдельной доступной снаружи службе. Есть callbacks,
библиотеки, одна и та же схема в разных APK и несколько вызовов в одном методе.

Например, a.j1.e и a.j1.f в GMCSoft — callbacks/сервис Room
MultiInstanceInvalidationService, а не скрытый интерфейс HUD. По соседству
в обфусцированном пакете a.j1 действительно есть HUD-код, но соседство ничего
не доказывает. a.j1.f передаёт регистрацию callback+имя, отмену регистрации,
и уведомления о списках таблиц; a.j1.e — String[] callback.

CarService содержит wire-следы IVehicle@2.0, IVehicleCallback, IAudioControl,
ICar/ICarTest/ICarServiceHelper. Это граница до HIDL HAL, а не реализация ECU.
XCSettings2 также содержит IUserService/IMemberCenterService и библиотечные
media/session/notification протоколы.

Файлы: binder_interfaces.json, APP/binder_wire_methods.json,
binder_wire_summary.json, permission_check_sites.json.
Parcel-операции сохранены в порядке смещений; не делается предположение,
что все ветки onTransact выполняются последовательно.

8. Native-код: назначение, точки входа и уточнённые вызовы

18 ELF в пакете распределяются так:
  8 Virbox-загрузчиков — 4 архитектуры GMCSoft и 4 GPSProvider;
  5 libjnidispatch — JNA в GMCSoft;
  4 libvosk — распознавание речи в GMCSoft;
  1 libBugly — обработка native-crash в XCSettings2.
CarService не содержит собственной .so в APK, но может пользоваться системной.

Извлечены все импорты и 359 вхождений JNI-экспортов по архитектурам;
для JNI_OnLoad/Java_* сохранены дизассемблированные фрагменты точек входа.
Это 359 вхождений, не 359 уникальных автомобильных операций.
Для x86_64 дополнительно разрешены прямые переходы к выбранным импортам:
- JNA dlopen находится в Java_com_sun_jna_Native_open.
- У Vosk два прямых popen: Kaldi PipeInputImpl::Open и PipeOutputImpl::Open.
  Это поддержка pipe-ввода/вывода, а не автоматически удалённая shell-служба.
- В Virbox найденные косвенные jmp на GOT mprotect/dlopen выглядят как
  импортные trampoline-переходы. Они не объявляются полноценными вызывающими
  функциями бизнес-логики. Вычисляемые адреса/распаковка требуют отдельного анализа.

Связь Vosk с приложением подтверждается org.vosk.LibVosk Native.register
("vosk") и загрузкой модели vosk-model-small-ru через a.r4.f.
Наличие модели и этой цепочки не доказывает отсутствие любых сетевых функций
во всём ассистенте. Код других сетевых клиентов рассматривается отдельно.

Файлы: native_imports_jni.json, native_x64_import_xrefs.json, APP/*.jni.txt.
Ранее восстановленные 34 VM-тела и декодер строк сохранены. Остальная логика
Virbox runtime, все вычисляемые native-вызовы и полная семантика библиотек
НЕ объявляются полностью раскрытыми по списку импортов.

9. Kanzi и оставшиеся динамические ключи

Из carsetting_datasource.xml извлечены 98 именованных data-source узлов,
из kx11_carsetting.kzb.txt — 679 строк перечня ресурсов. Среди них Point_HUD,
HUD_Visiable, HUD_On_Off, HUD_AR_On_Off, HUD_Snow_Off, HUD_Adjust_On_Off.
В перечне бинарных ресурсов также есть Public.HUD_Snow_On_Off — имена
не унифицированы задним числом. Это свойства 3D-интерфейса настроек. Наличие
слова HUD не превращает их в команды панели и не раскрывает анимацию крыльев.
Геометрия, материалы, состояние Kanzi внутри .kzb полностью не декодированы.

Оставшиеся 219 обращений классифицированы, но классификация не равна
полной верификации каждого пути:
- Общие accessors (SharedUtils, ResolverUtils, SPUtils и др.) получают ключ
  как аргумент. Фиксированные вызывающие места учтены отдельно.
- Bluetooth preferences используют MAC-адрес устройства как ключ: phonebook,
  message-permission и rejection-count. MAC нельзя восстановить без runtime.
- CarBluetoothService выбирает восемь фиксированных Secure-ключей:
  android.car.BLUETOOTH_AUTOCONNECT_{NETWORK,MUSIC,PHONE,MESSAGING}_DEVICE_PRIORITY_{0,1}.
  Profile 5 -> NETWORK, 11 -> MUSIC, 16/17 -> PHONE, 18 -> MESSAGING.
  Это описание выбора ключа; операции проверяют BLUETOOTH_ADMIN.
- SuggestionParser использует имя категории или component name с суффиксами
  _setup_time/_is_dismissed; Secure-маркер suggested.completed_category.<category>.
- CarVolumeGroup обращается к внешней CarAudioManager.getVolumeSettingsKeyForGroup.
  Тела этого генератора в пакете нет; строковый префикс не придуман.
- GarageModeService читает GARAGE_MODE_ENABLED и GARAGE_MODE_MAINTENANCE_WINDOW
  (с префиксом android.car.), defaults 0 и 600000 соответственно.
- GMCSoft a.ha.i формирует Global-маркер <secure-key>_first_secure_init.
  Два статических вызова a.bc.m.e передают enabled_notification_listeners и
  enabled_accessibility_services. Имена полученных маркеров выводятся из этих
  строк, а не из наличия похожих ключей Android. Рядом есть root-команда
  cmd notification allow_listener для NaviService — команда не исполнялась.
- SettingsMultiAppViewItem получает ключ из setSettingKey; найдены привязки
  music_widget_support_apps_key и auto_start_music_player_last_support_apps_key.
- Остальное относится к Preference callbacks, Firebase/analytics, Bugly/Tinker
  и MultiDex с namespace/index. Нельзя честно перечислить все будущие значения
  динамического key-параметра как конечный список строк APK.

10. Проверка и ограничения

verification.json подтверждает агрегаты, восемь вручную сверенных примеров
CarFunctionProxy, два null-return SDK тела и локальный UID-предикат cluster.
Для всех новых разрешённых методов сохранён исходный Dalvik-дамп. Автоматический
анализ намеренно оставляет неизвестными heap/reflection и разные значения
в сливающихся ветках. Он не моделирует весь Java/Android runtime.

Скрипты tools/deep_*.py воспроизводят работу после извлечения исходных APK
в recovered/extracted. Порядок: deep_binder_native, deep_vehicle_paths,
deep_settings_flow, deep_binder_dex, deep_dependencies_catalogue,
deep_native_xrefs, deep_consolidate, deep_capture_paths, deep_verify_evidence.
deep_vehicle_paths создаёт доказательства перечисления для следующего шага.
Нужны androguard, pyelftools, capstone и зависимости ранее сохранённых audit-скриптов.

Что по-прежнему не разрешено: точная runtime-реализация CarImpl/MediaCenter/
ECarXCar, реальный renderer и overlays, HAL/ECU/DIM/QNX, фактические настройки
и права конкретной машины, вычисляемые JNI-пути и полный Kanzi binary format.
Образ system/vendor/framework и компоненты панели отсутствуют физически;
их реализацию нельзя получить дальнейшим поиском строк в этих четырёх APK.
Для нижнего слоя нужен полный соответствующий образ или разрешённое read-only
извлечение этих компонентов. Ничего на автомобиле не менялось.
