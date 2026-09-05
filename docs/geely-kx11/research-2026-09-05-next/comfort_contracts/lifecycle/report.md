# Lifecycle: сиденье, люк/шторка, CarKey и UserProfile

Дата анализа: 2026-09-05. Только офлайн-чтение исходных ZIP, DEX и ранее собранных текстовых материалов. Бинарники автомобиля не исполнялись, команды устройству не отправлялись, APK не собирался. Общий репозиторий не изменён.

Вывод: обычный STOP по отпусканию кнопки сиденья реализован в найденном штатном UI, но тот же обработчик пропускает ACTION_CANCEL. STOP при потере фокуса, уничтожении UI или Binder-соединения этой цепочкой не подтверждён. Для люка/шторки найден отдельный потребитель целевой позиции: его отпускание ползунка нельзя трактовать как отпускание удерживаемого направления. Digital-key API в четырёх конкретных копиях CarKey содержит заглушки; это не доказывает отсутствия иной реализации. Создание профиля действительно асинхронно и возвращает −1, а `applyUserProfileData=true` не подтверждает даже успешную передачу Binder.

Все выводы о командах и callbacks ниже имеют уровень **STATIC_IMPLEMENTATION**, не KX11 physical acceptance. Пути APK подтверждены старым runtime inventory; новых наблюдений движения или callbacks нет.

## Доказательственная база и воспроизведение

Основной оригинал — `ecarx-hud-system-20260727-010640.zip`, 181 member, SHA256 `9bc62950e55910fc6eb59c99bf80439f7e7ac41f853b4c6c486977e14d062edd`. Для каждого выбранного метода в `evidence.json` указаны архив, SHA256 архива, member, SHA256 APK/JAR, DEX entry и SHA256, полный дескриптор, `code_offset`, число инструкций и SHA256 их нормализованного представления. Opcode arrays и чужие тела методов исключены из публичных файлов по REL-008. `code_offset` — адрес заголовка DEX code_item; `+offset` — смещение инструкции от начала массива insns. Для стандартного Dalvik code_item адрес инструкции в DEX = `code_offset + 16 + offset`; это не номер строки декомпилятора. Member-хеши независимо пересчитаны из ZIP.

| Сокращение | Member оригинала | Назначение |
|---|---|---|
| Settings D1/D2 | `packages/ecarx.settings/base.apk`, classes.dex/classes2.dex | Штатный KX11 UI и proxy |
| Adapt | `framework/system_framework/ecarx.adaptapi.jar`, classes.dex | Конкретная системная реализация SDK |
| Car | `framework/system_framework/ecarx.car.jar`, classes.dex | Manager и Binder wrapper |
| Test | `packages/ecarx.adaptapi.platform/base.apk`, classes.dex | AdaptApiTestApp, диагностический consumer |
| GKUI | `framework/system_framework/ecarx.gkui.openapi.impl.jar` | Альтернативная ветвь чтения состояния ключа |

Пути классов и методы разрешены статически. Settings также содержит копии SDK; наличие системного JAR само по себе не доказывает, что runtime загрузил именно его. Разные class loader / bootclasspath остаются отдельным открытым вопросом; цепочка ниже не превращает их в доказанный единый runtime execution trace.

Прочитаны AGENTS.md, PROJECT_REQUIREMENTS_RU.md, GEELY_KX11_KNOWLEDGE_RU.md и предыдущий `audit-work/analysis/comfort_systems/report.md`. Физические проверки, GATE-048 и разрешение write не закрываются этим отчётом.

## Сиденье: есть отпускание, нет доказанного аварийного завершения

Найдена производственная точка навигации: `KXFunctionFragment.handleNavigate`, Settings D1 `0x4f7e64`, создаёт `KXAdjustSeatFrag`. Его `intiProxy()` (`0x4ffc54`) получает CarFunctionProxy и регистрирует watcher после connected, либо ожидает ICarState observer. Это конкретный штатный consumer, а не только объявленный SDK.

`KXAdjustSeatFrag.onTouch(View,MotionEvent)` — Settings D1, code `0x4ff650`:

- `+0x0` читает `getAction()`; `+0xa` пропускает DOWN=0 в рабочую ветвь.
- `+0xe` отправляет любое значение, кроме UP=1, к `+0x17c return true`. Таким образом, ACTION_CANCEL=3 в этом методе потребляется без вызова STOP. MOVE и прочие action также не проходят.
- DOWN вызывает `handleClickEvent(..., true)`; UP вызывает тот же путь с `false`. Затем `onSeatClick(view,false)` публикует RxBus событие.
- `handleClickEvent`, code `0x4ff8f4`, выбирает length/height/backrest/cushion. При null proxy логирует и возвращает.

Четыре метода `CarFunctionProxy.seat*Adjust`, Settings D2, на DOWN вызывают `ICarFunction.setFunctionValue(function, zone, function+1/2)`, на UP — тот же function/zone со значением **0**. Результат boolean игнорируется. `getSeatIndex` (`0x3d55e0`) учитывает `isDriverSideLeft()`: UI-сторона не должна безусловно заменяться на driver/passenger.

| Движение | Function | Proxy code | CB driver / passenger | PA доступности driver / passenger | PA состояния driver / passenger |
|---|---:|---:|---:|---:|---:|
| Продольное | 755106048 / 0x2d020100 | 0x3d629c | 32982 / 32983 | 33565 / 33567 | 33566 / 33568 |
| Высота | 755106304 / 0x2d020200 | 0x3d61ec | 32984 / 32985 | 33569 / 33571 | 33570 / 33572 |
| Наклон подушки | 755171584 / 0x2d030100 | 0x3d613c | 32986 / 32987 | 33573 / 33575 | 33574 / 33576 |
| Спинка | 755171840 / 0x2d030200 | 0x3d608c | 32988 / 32989 | 33577 / 33579 | 33578 / 33580 |

Маршруты закреплены в `Seat.buildFunctions` Adapt `0x111e2c`. Публичные `0 / function+1 / function+2` преобразуются в raw `0 / 1 / 2`; zone1 — driver, zone4 — passenger для этой регистрации. Manager вызывает `setIntProperty(CB,1,raw)` после валидатора: `SwtHozlSts1` для длины; `SwtVertSts1` для высоты/подушки; `SaFwdback` для спинки. Manager code: length `0xf0bc4/0xf0c00`, height `0xf0b4c/0xf0b88`, cushion `0xf0a5c/0xf0a98`, backrest `0xf0430/0xf046c`.

Обратный UI-путь подтверждён через `KXAdjustSeatFrag$1.onSupportedFunctionStatusChanged`, `0x4ff54c`. Zone1/4 задаёт выбранное сиденье и обновление UI. При статусе не active и сохранённом view вызывается `onSeatClick(view,false)`; **сам этот вызов не отправляет motor STOP**. `onSeatClick`, `0x4fffc0`, создаёт `SeatAdjustEvent` и публикует RxBus событие; там нет CarFunctionProxy/CB setter. Downstream теперь прослежен: `CarSettingActivity.registerListener` (`0x3f37f4`) подписывает типизированный consumer; lambda `$4` (`0x3f3414`) передаёт поля в `KanziManager.sendMessageAdjustment` (`0x26ddf0`). Формируется protobuf **cmd3014**, включая `isPressDown=false`, затем `sendMessageWrapper` (`0x26e26c`) → `ECarxKanziLib.Request` (`0x489250`) → `KanziView.queueEvent` (`0x261200`) → Runnable `$1.run` (`0x488ee4`) → `KanziCommunicationBase.SendToKanzi` → SWIG/JNI. В этой Java-цепочке нет вызова `ICarFunction`, `CarFunctionProxy` или моторного CB. Native-получатель не установлен как дополнительный STOP; наличие визуального события само по себе его не доказывает.

Точная граница: `KanziCommunicationBase.SendToKanzi` (Settings D1 `0x489684`) вызывает native-декларацию `Swig_Template_APPJNI.KanziCommunicationBase_SendToKanzi`; `KanziView.queueEvent` передаёт задачу в native `KanziNativeLibrary.submitTask`. У native-деклараций нет DEX-тела (`code_offset=0`). В закреплённом APK единственный member `lib/` — `lib/arm64-v8a/libBugly.so`; имя и runtime-путь обслуживающей Kanzi библиотеки этим APK не установлены. До получения current PID maps конкретный native library path не включается в запрос досбора.

`RxBus.post` (`0x3ebbe0`) передаёт событие в serialized subscriber, `toFlowable` (`0x3ebb80`) фильтрует по типу. Поиск прямых ссылок на seat/roof event и send-методы выполнен по всем **116258 конкретным телам трёх DEX Settings**; затем связанные ветви прочитаны полностью. Это граница поиска прямых ссылок, а не число расшифрованных функций или доказательство отсутствия reflection/native consumers. Когда Kanzi wrapper ещё не имеет библиотеки, он повторяет отправку через **1000 ms**; это отложенный клиентский запрос, не motor watchdog и не гарантированная отмена старых сообщений.

| Событие | Статический результат | Граница вывода |
|---|---|---|
| Обычный ACTION_UP | Setter со значением 0 существует | Принятие CB и физическая остановка не наблюдались |
| ACTION_CANCEL | В точном `onTouch` вызов STOP отсутствует | Нельзя переносить отрицание на весь framework/ECU |
| Потеря доступности | Указанный callback сбрасывает UI-событие на false | Это само по себе не подтверждает motor command |
| `onPause` | `0x4fff08`: super и VR-unregister; цепочка предков прочитана | Ни дочерний метод, ни прочитанные app/base методы не вызывают seat STOP |
| `onDestroyView` | `0x4ffed0`: super и watcher-unregister | Удаление watcher не равно STOP |
| Binder loss | Доказанного STOP в исследованной цепочке нет | Серверный cleanup, ECU timeout и остановка при смерти клиента не установлены |

Наследование установлено по самому DEX: `KXAdjustSeatFrag → BaseCommFragment → BaseFragment → androidx.fragment.app.Fragment`. `BaseCommFragment.onPause` (`0x4f03bc`) вызывает super и пишет журнал; `onDestroyView`/`onDestroy` (`0x4f039c/0x4f037c`) обнуляют rootView. `BaseFragment.onDestroyView`/`onDestroy` (`0x4f085c/0x4f0834`) снимают watcher через `VehicleCallbackHelper.unregisterFunctionValueWatcher` (`0x3c8060`). Базовые AndroidX Fragment callbacks выставляют `mCalled`; дополнительного motor setter в этих телах нет.

Прочитана и цепочка Activity: `CarSettingActivity → Settings → BaseActivity`, затем содержащиеся в APK AndroidX-предки до внешнего `android.app.Activity`. `CarSettingActivity.onDestroy` (`0x3f35cc`) вызывает `RxManager.unSubscribe` (`0x3ebc4c`, dispose подписок), снимает surface callback и ссылки Kanzi; `Settings.onPause` (`0x3f5490`) через `VrVisionManager.cancelVision` (`0xdbfe4`) отменяет объявление VR capability. `BaseActivity` lifecycle вызывает super и журнал; его `onTouchEvent` (`0x3f2514`) обслуживает клавиатуру. В перечисленных app-defined cleanup-телах нет seat setter. В содержащейся в APK цепочке предков нет override `onWindowFocusChanged`, `onFocusChange` или `dispatchTouchEvent`, добавляющего отдельный путь STOP. Это закрывает прежний вопрос о непрочитанных base UI; поведение платформенного Activity, удалённого VR-сервиса, JNI и ECU этим отрицательным результатом не устанавливается.

`CarImpl.onECarXCarServiceDeath` Adapt `0xdc438` пересылает уведомление подсистемам. `AbsCarSignal.onECarXCarServiceDeath`, `0xd5a2c`, — пустой return; это применимо к наследникам без override, в частности исследованным CarKey/UserProfile, но не является универсальным выводом обо всех CarFunction подклассах. `ECarXCarPropertyManagerBase.onCarDisconnected`, Car `0xab4ec`, очищает callback registry, не отправляя моторную команду. Срок удержания движения в этой цепочке не установлен.

## Люк и шторка: направление, целевая позиция, обновление UI

Несмотря на имя `WindowFrag`, исследованная штатная ветвь использует setting key121 → zone4 (люк), иначе zone8 (шторка). Навигация создаёт WindowFrag в `KXFunctionFragment.handleNavigate`. Доказательства не следует распространять на боковые стёкла.

| Путь Settings D2 | Code | Семантика |
|---|---:|---|
| `WindowFrag$3.onProgressChanged` | 0x405bb0 | Пустой return |
| `onStartTrackingTouch` | 0x405bc4 | Убирает runnable отложенного обновления позиции |
| `onStopTrackingTouch` | 0x405c1c | Progress → transform → `setFwValueShort` |
| `WindowFrag.setFwValueShort` | 0x4079ac | `setCustomizeFunctionValue(553845504,4/8,float target)`; boolean игнорируется |
| `CarFunctionProxy.openCloseSunBlindSkylight` | 0x3d5da0 | `setFunctionValue(inputFunction,4/8,inputValue)` |
| `...ShortClick` | 0x3d5e24 | `setCustomizeFunctionValue(inputFunction,4/8,float inputValue)` |
| `slideControlSunBlindSkylight` | 0x3d75a0 | p≤9 → 0; иначе ближайшая поддерживаемая позиция; customize setter |

**553845504 = 0x21030300 (`WINDOW_POS`)**, в отличие от **553844992 = 0x21030100** дискретного управления. Название ShortClick не превращает вызов float/customize в непрерывное направление. `getSupportSlideValue` (`0x3d5ba8`) берёт supported values для WINDOW_POS и сортирует; null заменяет массивом `[0]`. `getClosestKey` (`0x3d4a70`) выбирает минимальную абсолютную разницу. Реально возвращённый supported set на KX11 не наблюдался этим анализом.

В Adapt `Bcm.buildFunctions` (`0x1095d0`) дискретный путь и позиция зарегистрированы раздельно. `lambda$buildFunctions$10` (`0x108b14`) для люка задаёт OPEN=1 → `CB_OpenSunRoof_Btn(1)`, OPEN_PAUSE=553844995 → тот же CB(0), CLOSE=0 → `CB_CloseSunRoof_Btn(1)`, CLOSE_PAUSE=553844996 → тот же CB(0). `$11` (`0x108ba0`) реализует аналогичную схему SunCurtain. Это наличие явных pause-команд в SDK, **не доказательство вызова pause при cancel/focus loss**. Roof manager CB33193/33194 имеет code `0xf5c54/0xf5bdc`.

Для WINDOW_POS roof setter направляется к `CB_SunRoofOpenPosnReq`, Car `0xf5d08`; соответствующий адаптер `...eUOecOuBbKpb10ssOdkY0xdgK04.apply` имеет code `0x102fd0`. Регистрация связывает позицию люка с PA33787 и availability PA33789, шторки с PA33788/33790. Это статическое соответствие; пользовательский target и actual/readback остаются различными понятиями.

Обратный путь UI: `WindowFrag.init`, `0x407590`, устанавливает `WindowFrag$4` callback; `callback(III)`, `0x405e5c`, различает WINDOW_POS553845504, процент553846272 и состояние движения554762752. Процентная ветвь создаёт `WindowsChangeEvent`; при value255 использует 0 для визуализации, что не доказывает физически закрытое окно. В ветви движения value1 отменяет runnable, другое значение ставит отложенный readback через **1500 ms**. `WindowFrag$1/$2.run`, `0x405ad8/0x405b44`, читает position zone4/8 и обновляет UI. **1500 ms — задержка обновления UI, не установленный safety timeout.**

`WindowFrag.onPause` (`0x4078f4`) вызывает super и VR-unregister; `onDestroyView` (`0x4078bc`) снимает callback. В этих телах нет явной pause/STOP-команды. Точный класс `ecarx.settings.ui.vehicle.WindowFrag` наследует ту же прочитанную цепочку `BaseCommFragment → BaseFragment → AndroidX Fragment`: inherited cleanup также не добавляет roof setter. В Kanzi `sendMessageSunroofControl` (`0x26deb8`, `0x26df7c`) записывается `sunroofControlDuration=10000` в сообщение3002: источник — визуальный протокол Kanzi. Это поле также не доказывает ECU timeout.

## CarKey: точные заглушки и отдельно реализованная PA-ветвь

`CarImpl.getCarKeyManager`, Adapt `0xdc224`, создаёт конкретный CarKey. Реальные тела digital-key методов, а не один interface, проверены в четырёх DEX-копиях:

| Источник | `createDigitalKey` code | `cancelDiscovery` code | Реальное тело |
|---|---:|---:|---|
| System adaptapi | 0xfac84 | 0xfad9c | create возвращает 0; cancel пустой |
| Settings D1 | 0x4505f0 | 0x45070c | То же |
| DimMenu classes.dex | 0x3cb51c | 0x3cb638 | То же |
| ts-carplay-adapter.jar | 0xfe734 | 0xfe84c | То же |

В системной реализации `getDigitalKeys` возвращает пустой массив; delete/register/unregister digital-key callbacks возвращают false. Отсюда нельзя заключить «на автомобиле нет цифрового ключа»: это отрицательный результат только по данным копиям этого класса. В дополнительном индексе direct-invoke consumer для createDigitalKey/startDiscovery/readRealKey вне самих CarKey/интерфейсов не найден; динамические вызовы, native-код и отсутствующие компоненты не исключены. Settings `CarProxy.getCarKeyManager`, `0x3d7a68`, — только getter, не consumer операции.

Отдельно реализован путь обнаружения физического ключа:

| Вызов CarKey | Manager | CB | Raw |
|---|---|---:|---:|
| `startDiscovery` | `ECarXCarProfileManager.CB_PSET_ConnectKey` | 33254 | 0 |
| `readRealKey` | Тот же | 33254 | 1 |
| `unbindCarKey(argument)` | Тот же; argument не используется | 33254 | 17 |
| `cancelDiscovery` | Нет вызова | — | — |

Manager code `0xede40` вызывает `setIntProperty(33254,1,value)`. **33853 — PA результата, не CB команды.** `CarKey.initPAFilter` добавляет 33853; `onInitCarSignalManager` получает ProfileManager и регистрирует PA callback. `CarPAEventCallback.convertPAData` Car `0xd0b50` декодирует PAIntType в `PATypes.PA_PSET_Key_Result`; `convertData` `0xd7c9c` вызывает типизированный callback.

`CarKey$1.onPA_PSET_Key_Result`, Adapt `0xfabcc`, реализует:

| PA33853.data | Observer |
|---:|---|
| ≤0 | Ничего |
| 1 | `timeout()` |
| 2 | `multipleKeyFound(true)` |
| 3 | `onKeyReadResult(0)` |
| 4 | `onKeyReadResult(1)` |
| Иное положительное | Ничего |

Метод не проверяет availability/status перед этой таблицей. Аргументы 0/1 в onKeyReadResult нельзя называть криптографическим идентификатором ключа без дальнейшего контракта. В методе логируется `PA.toString()`; повторный сбор реальных ключевых данных для доказательства маршрута не требуется.

Альтернативный найденный API `VehicleAPIImpl.getCarKeyStatus`, GKUI `0x81148`, читает sensor2097408 (`0x200100`) и при отсутствии sensor возвращает Integer.MIN_VALUE. `registerCarKeyStatusListener`, `0x819b8`, регистрирует listener того же sensor. Это чтение состояния; issuance/discovery digital key через иной service этими методами не доказаны.

## UserProfile: асинхронное создание, потеря transport result, неоднозначный callback

Реальный найденный consumer создания/применения — **AdaptApiTestApp**, не доказанный production account UI. `ProfileFragment.onCreate` Test `0x2740c0` получает Car/getUserProfileManager. `onViewCreated` `0x2742e4` регистрирует IUserProfileObserver; `onDestroyView` `0x27410c` снимает его. Lambda создания `0x27381c`, копирования `0x273c1c`, применения `0x273750` вызывают API и игнорируют его return. Штатный Settings `DialogHelper.resetUserInfo` `0x3c4ba0` использует reset текущего профиля; это не доказательство consumer для create/apply.

`CarImpl.getUserProfileManager`, Adapt `0xdc2a0`, создаёт UserProfile.

| Стадия создания | Доказательство |
|---|---|
| PREPARE | add/addCopy уведомляет action ADD1, status PREPARE1 |
| Предусловие | `notAllow`, `0xfbf64`, разрешает лишь непустую PA33855 с data1; в этом helper нет проверки availability |
| Запрос | `addUserProfile`, `0xfc694`, / addCopy `0xfc6f0`: progress2, `CB_PSET_NewProfile(0/sourceID)` |
| Синхронный return | ApiResult CB игнорируется, возвращается −1 независимо от отправки |
| Manager | `CB_PSET_NewProfile`, Car `0xedf30`, property33249, area1 |
| Доставка результата | `buildFilterChains`, `0xfcc78`: изменённая PA33847 обрабатывается в end-queue по **PA33888** |
| Преобразование | `$4`, `0xfd0bc`: PA33847.status>0; status1 → SUCCEED3, иное положительное → FAILED4; id=PA.data |

End-marker не выведен из имени: `AbsChainCarSignal$1.onPAChanged`, `0xd5abc`, отмечает изменение и запускает очередь при нужном message-end; `EndQueueTask.lambda$observerChange$0`, `0xd5c70`, проверяет isChange и передаёт сохранённую PA; `run`, `0xd5cac`, исполняет задачи. Регистрация callback закреплена в `AbsChainCarSignal.onInitCarSignalManager`, `0xd61c8`.

**`onUserProfileAdded(id)` не означает успех.** `UserProfile.onUserProfileAddStatus`, `0xfd36c`, сначала безусловно вызывает onUserProfileAdded, затем onUserProfileActionStatus(ADD1,id,status3/4). При FAILED4 первое событие тоже приходит. Дополнительно тестовый `ProfileFragment.onUserProfileAdded`, `0x27428c`, выводит текст об успешном добавлении. Такой UI-текст не является самостоятельным доказательством успеха; необходимо учитывать action-status callback.

`applyUserProfileData`, Adapt `0xfb9f4`, проверяет currentId≠−1, target==current, nonnull IProfile и notAllow; сообщает PREPARE1/PROGRESS2, разбирает JSON, создаёт Profileclouddata и вызывает `CB_PSET_ProfileCloudData`, после чего возвращает true без ожидания PA.

Transport-потеря подтверждена глубже:

1. `ECarXCarProfileManager.CB_PSET_ProfileCloudData`, Car `0xeec94`, сериализует payload и вызывает **void** `setbytesProperty(33264,1,bytes)`.
2. `ECarXCarPropertyManagerBase.setbytesProperty`, `0xab654`, вызывает возвращающий ApiResult `setProperty`, но не читает результат.
3. `setProperty`, `0xab23c`, вызывает Binder `IECarXCarProperty.setProperty(...):void`; при пойманном Exception возвращает `CARSIG_SERVICE_NOT_RUNNING`.
4. Этот ApiResult теряется в void-обёртке. Поэтому apply может вернуть true даже при обработанном transport/Binder исключении. `true` нельзя описывать как «Binder принял запрос».

Завершение apply доставляется PA33874: `$8`, `0xfd24c`, при data>0 отправляет action APPLY6; data1 → SUCCEED3, иное положительное → FAILED4. ID берётся через **getCurrentId() в момент callback**, а не из сохранённого target запроса. Корреляция конкретной операции при смене активного профиля, reconnect или задержанном событии этим API не доказана. Таймаут/повтор запроса, основанный только на −1/true или onAdded, не следует из контракта.

## Что осталось неизвестным и что собирать

`collection_targets.json` содержит точные paths и строки оригинального inventory. Settings, AdaptApiTestApp, ECarXCarService, системные adaptapi/car и GKUI уже присутствуют в корпусе; повторно копировать их только ради этого статического анализа не требуется. Отдельный отсутствующий binary path digital-key/profile production consumer не доказан: список guessed APK/БД не составляется.

Недостаёт адресных runtime наблюдений, а не ещё одной копии тех же классов: фактический class origin; текущий support/status зон; доставка PA и порядок callbacks при штатном UI-сценарии; корреляция target/current profile; серверная и физическая реакция на cancel/focus/Binder loss. Этот отчёт не задаёт команды движения или процедуру разрушения Binder-соединения. Для движения нужен отдельный согласованный безопасный протокол и установленный независимый способ остановки; наличие value0 в коде не заменяет проверку.

Отрицательный охват: проверены перечисленные реализации, базовые lifecycle-методы и конкретный типизированный RxBus seat route до JNI; дополнительный `scan_scope` сохраняет прежний отфильтрованный индекс. Отдельный Settings reference scan охватывает три полных DEX и фиксирует собственную более узкую цель. Это не exhaustive whole-firmware absence proof. Не доказаны native service/ECU safety timeout, платформенная доставка focus/cancel вне APK, отдельный цифровой ключ через иной пакет и production account create/apply UI. Полные инструкции всех исходных 109 методов и нового разбора сохранены приватно; публичные доказательства содержат только источник, SHA-256, адреса, сигнатуры и авторское изложение результата.
