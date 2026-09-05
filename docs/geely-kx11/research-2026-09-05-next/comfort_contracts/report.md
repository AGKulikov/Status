# Продолжение контрактов комфорта KX11: consumer, manager, callback

Дата: 5 сентября 2026. Результат — углубление 25 карточек COMF, а не аппаратная приёмка и не декларация полной расшифровки автомобиля. Изучались сохранённые APK/JAR, исходный референс MConfig и существующие журналы. Бинарники автомобиля не исполнялись; команды, операции с ключами/профилями, испытания моторов и сборка APK не выполнялись. Общий репозиторий не изменён.

Практически важные новые результаты: штатный климат уже раскрывает **LO — 16…28 °C — HI**, а не числовой диапазон 16…30; валидатор SDK допускает дробь вне сетки и NaN; штатная кнопка движения сиденья обрабатывает UP, но пропускает CANCEL; profile apply может вернуть true даже после обработанного Binder-исключения; сохранённый production Settings трактует RGB-функцию подсветки как boolean вопреки регистрации в его SDK. Эти выводы следуют из конкретных тел методов и runtime строк, а не из названий интерфейсов.

## Охват и происхождение

В `source_inventory.json` отражены **102 финальных ZIP/TAR/APK контейнера, 11673 member-записи, 122 уникальных APK/JAR**. Отфильтрованный DEX-проход дал 116 DEX-файлов результатов и 50650 методов-кандидатов. Это индекс отбора: количество найденных методов не равно количеству вручную полностью расшифрованных функций. Разобраны относящиеся к задаче уникальные реализации и сквозные ветви; Android Window/temperature классы, попавшие по ключевому слову, не выдаются за новые автомобильные органы управления. Промежуточные raw DEX из MConfig build не считаются независимой реализацией вместо включённых итоговых APK/JAR; их сопоставление дополняется отдельным исследованием полноты SDK.

Runtime-проход охватил 123 текстовых источника/архива и 5374 текстовых member-файла. Извлечены 1701 сгруппированная запись, 123 различных function ID с `Data{status,value,...}` и 5 журнальных инициализаций списка температур LO/HI. Повторы одной комбинации внутри файла сохраняются как occurrences/first/last, а не считаются отдельными физическими испытаниями. Исходные полные bytecode/text находятся только в `private/`. Публичные JSON содержат маршруты, имена методов, хэши, смещения и ограниченные автомобильные runtime-фрагменты.

Канонические числовые CB/PA ниже относятся к системным JAR из `ecarx-hud-system-20260727-010640.zip`, SHA256 `9bc62950e55910fc6eb59c99bf80439f7e7ac41f853b4c6c486977e14d062edd`. `ecarx.adaptapi.jar` имеет SHA256 `87a6fcf44f2a3a2dab7575d6e7a50396b980794074fd5fb5843162fbbde62ef0`; Settings APK — `4fe63822f44d6f6dfb77564d1b0f9b4aec9fb725c6e1a590862efc1c0ac570e5`. Копии SDK в Settings/DimMenu/Natro/MConfig и других APK не объединяются по одному имени поля. Существование системного файла не доказывает runtime class origin.

`evidence.json` хранит точные DEX entry/SHA, class, method, descriptor, code_offset. Code offset — адрес заголовка `code_item`; относительные смещения инструкций из androguard отсчитываются от начала `insns`: абсолютная позиция инструкции в обычном DEX равна `code_offset+16+instruction_offset`. Номер строки декомпилятора не подменяет DEX-адрес.

## Общий контракт для обычных функций COMF

Восстановлен путь `consumer → ICarFunction → AbsCarFunction → registered VehicleFunction/model/zone → ZoneTask.callSetFunction → mapper/CB manager → property/Binder`, а обратный — `PA/signal → ZoneTask.getData → Data change flags → отдельные typed callbacks`. У ключей и профилей отдельная модель, описанная ниже.

- `AbsCarFunction.setFunctionValue`/`setCustomizeFunctionValue` ищет зарегистрированную функцию и zone. `ZoneTask.callSetFunction` вызывает setter, если он есть; при его отсутствии возвращает INVALID. **Универсальной проверки availability и вхождения в supported list перед вызовом нет.** Декларации supported values не являются автоматическим запретом записи. Адреса: Adapt `0xda524`, `0xda3d0`, `0xe7174` (`CC-CORE-GATE`).
- Public boolean true означает ровно `ApiResult.SUCCEED` на этом уровне. False объединяет разные отказы. Нельзя выводить атомарность, отсутствие побочных эффектов или физическое изменение только из boolean.
- `notifyIntCallback`/`notifyCustomCallback` отдельно публикуют изменения статуса, допустимых значений и значения; `Data.setValue` подавляет неизменное значение и уведомление при notavailable. При notactive/error та же универсальная блокировка value-callback отсутствует. Адреса `0xdb644`, `0xdb4ac`, `0xe6610` (`CC-CORE-FEEDBACK`). Поэтому повтор уже достигнутой команды может не породить новый value callback; обратный callback не содержит идентификатора команды.
- Getter также не заменяет поддержку/свежесть. В архивном runtime встречается `TEMP notavailable:15.0` и `FAN_SPEED active:255`: это не действительная заданная температура 15°C и не 255-я скорость. Причина видна в преобразовании raw/default и различии manual/AUTO.
- После disconnect нельзя считать сохранённый snapshot новым измерением. В изученном общем слое не установлен универсальный watchdog, отмена очереди старых commands или безопасный STOP всех моторов.

Эти правила дополняют каждую карточку в `contracts.json`; они не отменяют специализированные проверки конкретного mapper/manager.

## Температура: четыре разных уровня теперь разделены

В `KX11-Bus-Capture-20260810-005510-27602-1234567.zip` / `android/logcat-before.txt` строка 13916 связывает PID 3526 с `ecarx.hvac.app`. Строки 21601/21609 показывают FunctionManagerPolicy/FunctionManagerCarFunction с `Hvac VersionName:20240314_2350`, VersionCode 20240314. Затем виден реальный consumer-путь: Adapt support/read → VehicleHelper → HvacManager → TempManager.

| Уровень | Установленное значение | Доказательство |
|---|---|---|
| Runtime capability | min 15.5, max 28.5, step 0.5, active | Строки 21618–21708 указанного logcat |
| Stock UI | 27 элементов: HI, 28.0, 27.5,…16.0, LO | TempManager `unitCList`, строка 21709 |
| Системный C validator/mapper | 15.5≤T≤28.5; `trunc((T−15.5)/0.5+1)` | Hvac checkTemper/convertTemper2Index |
| Системный F validator/mapper | 59≤T≤85; `trunc(T−59+1)` при PA33460.data==1 | Те же методы, независимая ветвь F |
| Natro source | Локальные 16…30, шаг 0.5, четыре зоны | GeelyCarIntegration.java:1014–1029, 4479–4484 |
| Физический результат | Не установлен этим исследованием | Нет нового опыта и измерения температуры |

LO/HI список повторён в пяти сохранённых источниках: Bus005510:21709, Bus065954:22281, Cruise20260809-001402/logcat-static-full:18448, Cruise20260809-110616/qnx-dim-history-full:22908 и Raw-Ethernet20260815-135547/logcat-all:10656. Несмотря на имя `qnx-dim-history`, строка содержит Android TempManager; имя файла не меняет владельца процесса.

Совместные runtime capability и список устанавливают, что **числовая середина штатного C UI —16…28**, крайние UI-пункты —LO/HI. При каноническом SDK индексы 1/27 соответствуют 15.5/28.5. Прямая реакция на выбор именно LO/HI, содержимое click-handler и фактически отправленный индекс в этих строках не записаны: последний участок восстанавливается из недостающего XCHvac.apk. Не следует называть 15.5/28.5 физической температурой воздуха или отдельным доказанным режимом компрессора.

У Natro диапазон остаётся локальным и после capability probe. Более того, `isValidControlValue` на чтении тоже сравнивает с локальными 16…30 (4634–4637), что исключает SDK endpoint15.5. Простого исправления верхнего числа недостаточно: нужно раздельно представить LO/HI, числовую сетку, единицу, zone и доступность. Это обоснование следующего изменения, а не выполненный патч.

Валидатор SDK проверяет только границы. Пример 16.2°C проходит, преобразуется в index 2 и при обратном декодировании даёт 16.0°C. Особый случай NaN проходит `cmpl-float` min и `cmpg-float` max, затем `float-to-int` даёт 0. Предикатная обёртка `IVehicleFunction$IValueTaskBuild.lambda$onSetFunctionValue$1` (`0xe5644`) при false возвращает PARAM_ERROR, а при true выполняет mapper и CB; дополнительного finite/grid guard там нет. CB_LeftTemp/RightTemp/SecLeftTemp/SecRightTemp прямо вызывают setIntProperty с ID 32806/32807/32814/32815, без локального range validator. Ни NaN, ни значения вне сетки не отправлялись; `VALIDATION.json` содержит только символическую проверку уже прочитанной логики.

## Подсветка: найден реальный конфликт внутри штатного consumer

`KXAmbienceFrag` получает AtmosphereLampsProxy, который работает через ICarFunction и watcher/reconnect observer. Однако `isBreathOn` (`Settings classes2.dex 0x3f8850`) читает 0x2a010300 и сравнивает его с 1. `setBreathValue` (`0x3f9908`) записывает в тот же ID boolean 0/1, игнорирует return и сразу публикует в Kanzi/RxBus AM_BREATHING/AM_CLOSED.

И системный AmbienceLight (`buildFunctions 0x106f90`), и встроенный в этот же Settings (`0x45c358`) регистрируют 0x2a010300 как **SETTING_FUNC_BREATHE_MODE_COLOR**, supported 72 RGB, прямой `CB_Zone1ColorSettings33213`, feedbackPA33808. Runtime Bus005510:60458–60462 действительно даёт этому ID active/value65326 (`0xff2e`) и RGB-палитру. Значит имя `setBreathValue` не подтверждает корректное управление режимом; это проверяемое несоответствие consumer/SDK.

Поскольку общий setter не проверяет supported list, нельзя обещать, что 0/1 будет безопасно отвергнут до property-запроса. Эффект нижнего обработчика не установлен. Равным образом прежняя mode-функция 0x200a0206 не становится рабочей: её разобранный setter сначала включает CB_AmbLiAll(1), затем возвращает FAILED без установки режима. Новый consumer не закрывает этот дефект.

Обычный color UI имеет дополнительную задержку 500ms перед записью в соответствующем режиме (`setAmbientColor 0x3f97e8`); это delay клиентского Runnable, а не гарантированный автомобильный timeout. King modePA33926==11 по-прежнему переводит ряд настроек в notactive. RGB палитра, main-color enum, effect enum и mode — разные контракты.

## Сиденья, крыша, ключи и профили

Полное изложение и точные таблицы — `lifecycle/report.md`; здесь существенные последствия для всех COMF-карточек.

**Сиденья.** Production `KXAdjustSeatFrag.onTouch` (`Settings D1 0x4ff650`) реализует DOWN и UP. UP проходит через `handleClickEvent` → CarFunctionProxy.seat*Adjust(..., down=false) → setFunctionValue(..., 0). ACTION_CANCEL=3 уходит непосредственно в return true, до setter и UI-события. Дочерний и наследуемый cleanup теперь прочитан: `BaseCommFragment → BaseFragment → AndroidX Fragment` снимает watcher/ссылки, а `CarSettingActivity → Settings → BaseActivity` снимает Rx-подписки, surface и VR capability; seat STOP в этих Java-телах нет. Callback потери доступности публикует `SeatAdjustEvent(false)`: типизированный RxBus consumer ведёт в Kanzi protobuf **cmd3014/isPressDown=false**, затем queueEvent и JNI, без Java motor setter. Прежние вопросы к непрочитанным base UI/RxBus заменены этой точной цепочкой. Платформенная доставка focus/cancel, native/серверный cleanup и ECU watchdog не установлены; отсутствие физической остановки во всём автомобиле не утверждается.

**Люк/шторка.** WindowFrag setting key121 выбирает zone4, иначе 8. Float position0x21030300 и direction0x21030100 различаются. Ползунок отправляет ближайшую разрешённую позицию на onStopTrackingTouch; его onProgressChanged пуст. SDK direction отдельно имеет press1/release0 для OPEN/CLOSE. Readback delay1500ms и Kanzi sunroofControlDuration10000 описывают UI/визуальный протокол, не доказанный motor watchdog. Эта ветвь не доказывает STOP боковых стёкол.

**Ключи.** Digital create/delete/register методы остаются заглушками в четырёх проверенных копиях CarKey. Рабочая статическая physical-discovery ветвь — CB33254 с 0/1/17; 33853 является PA результата, не CB. `CarKey$1.onPA_PSET_Key_Result` (`0xfabcc`) даёт data 1→timeout, 2→multipleKeyFound(true), 3→onKeyReadResult(0), 4→onKeyReadResult(1), остальные игнорирует. Availability в этом теле не проверяется. `unbindCarKey(int)` игнорирует аргумент. Альтернативный GKUI getCarKeyStatus читает sensor0x200100 и не выдаёт цифровые ключи. Конкретный отдельный installed issuance-service в корпусе не установлен; guessed APK и ключевые БД не запрашиваются.

**Профили.** Найден consumer create/copy/apply в AdaptApiTestApp/ProfileFragment; production Settings имеет отдельный reset-path. add/addCopy всегда возвращает−1 после CB33249, а PA33847 обрабатывается по end-markerPA33888. `onUserProfileAdded` вызывается и для FAILED, после него приходит action-status; даже тестовый текст«success» не является подтверждением результата. apply проверяет currentId/target/profile и PA33855.data==1, отправляет void CB_ProfileCloudData33264 и возвращает true. Void setbytesProperty отбрасывает ApiResult; его setProperty может поймать Binder Exception и вернуть CARSIG_SERVICE_NOT_RUNNING. Поэтому true не доказывает даже принятую Binder-передачу. PA33874 результата использует текущий profileID в момент callback, не сохранённый target запроса. Автоповтор по−1/true/onAdded недопустимо выводить из этого API.

## REQUEST/CONFIRM, ароматизация и сценарии

Общий callback анализ усиливает, но не заменяет специализированные мапперы:

| Входящее предложение | Ответ public0/1/2 → raw | Команда | Ограничение |
|---|---|---|---|
| AUTO_ION_REQUEST0x100c0200 | 2/1/0 | CB_PM25_HmiPopUpResp32862 | REQUEST имеет callback, не setter |
| DEHUMIDIFICATION_REQUEST0x100d0100 | 0/1/1 | CB_CL_HumPop32821 | Третий ответ совпадает со вторым на этом уровне |
| CLOSE_WINDOW_REMIND_REQUEST0x100f0200 | 0/1/0 | CB_CL_ClmCloseWinPop32839 | Третий ответ совпадает с первым |

В runtime HumPop встречается NotAvailable, ClmCloseWinPop — Active/Off, PM25 popup — invalid/Off; это не записанный выбор кнопки. Для текста кнопок, привязки ответа к актуальному предложению, закрытия/таймаута/повтора нужен production XCHvac. Ни имя REQUEST, ни public2 не определяет автоматически«запустить»/«отменить».

Fragrance on пишет тот же уровень, что level control: on→2, off→0. Type-description имеет status без getter/setter, slotA/B/C вызывает свой TypRatReq(100), type-ID использует slot как zone. Уровень блокируется при PA33406.data 3. Теперь есть ограниченные реальные отрицательные наблюдения: fragrance/type/level/slot/type-ID/auto-refresh notavailable в сохранённых сеансах; supported list при этом остаётся непустым. Это не отсутствие оборудования во всех KX11.

Семь bool scene-функций пишут общий CB33272 и читают PA33926; OFF любой из них пишет 0, поэтому отложенное выключение старой сцены может отменить новый общий режим. Theater zone 4 использует другой CB33271/PA33928. В доступной Java-регистрации нет перечня исполнительных действий каждого сценария; native/ECU оркестрация, приоритеты и восстановление не устанавливаются по имени King/Queen/Children. В извлечённых COMF Data-строках scene-записей нет; физический состав остаётся OPEN.

## Матрица 25 карточек

Все исходные факты, маршруты и открытые вопросы сохранены в `contracts.json`, дополнены consumer-chain, общим контрактом и ограниченными runtime-наблюдениями. Списки PA/zone из старого автоматического каталога не попарно склеиваются: точное соответствие берётся только из тела регистрации или отдельной таблицы.

| Карточка | Продвинутый контракт и ограничение |
|---|---|
| COMF-001 двери/замок/капот | Чтение door/lock имеет active runtime; немедленный central-lock setter в исследованном Bcm не найден. Active read не является write support. |
| COMF-002 детские замки | Разные обычные/scene CB и инверсия public/raw сохранены; runtime notavailable по наблюдавшимся зонам. |
| COMF-003 ключи | Заглушки цифровой ветви отделены от CB33254→PA33853 callbacks; alternative sensor API только читает. |
| COMF-004 профили | Test consumer, end-queue, onAdded при FAILED и swallowed Binder failure восстановлены; ID-корреляция открыта. |
| COMF-005 багажник | Production key111→zone0x20000000; две ветви PA/CB и height-setting сохраняются раздельно; physical stop/obstacle не установлены. |
| COMF-006 боковые стёкла | SDK position/direction и групповые последовательные CB сохранены; roof consumer не переносится на side-window CANCEL. |
| COMF-007 люк/шторка | Production slider/send-on-release и direction press/release различены; 1500ms/10000 не признаны safety timeout. |
| COMF-008 дворники | Штатные setting proxy и ClimateManager service-position/auto-rear-wipe; front active/rear notavailable встречаются раздельно. Immediate wash не восстановлен. |
| COMF-009 зеркала | Auto-fold active, reverse-dipping notavailable в записанных сеансах; MConfig immediate fold не доказывает актуальный системный route. |
| COMF-010 внешний свет | Settings light consumers→mapped functions; AFS/dipped-beam alias и только чтение поворотников сохранены. Configuration не считается лампой actuator. |
| COMF-011 салонный свет | Разные per-zone on/off и all-switch; runtime зоны active/notavailable. Master/local физический приоритет неизвестен. |
| COMF-012 ambience | Production boolean/RGB mismatch подтверждён байткодом и RGB runtime; King блокировка и палитры не смешиваются. |
| COMF-013 температура | Runtime LO/HI и capability, SDK range/grid/NaN и Natro descriptor разделены; click-handler XCHvac отсутствует. |
| COMF-014 fan/zones/flow | Runtime manual255 при AUTO, front/rear support и mode callbacks; default DUAL не доказывает число физических контуров. |
| COMF-015 defrost/осушение | Actual heater PA и popup-error PA — разные входы; runtime off не объясняет условия отказа. |
| COMF-016 предложения | Входящие REQUEST и write-onlyCONFIRM с тремя разными mapper; production buttons/lifecycle отсутствуют. |
| COMF-017 PRE/POST | Существующие CB подтверждены, runtime notavailable; remote-start/engine-start из них не выводится. |
| COMF-018 воздух/fragrance | Слоты, type placeholders, level reset и notavailable runtime; no equipment inference from enum. |
| COMF-019 сиденья/память | Production DOWN/UP→CB0/1/2; inherited cleanup и typed RxBus→cmd3014→JNI прочитаны без Java STOP. Native/ECU и физическая остановка остаются открыты. |
| COMF-020 heat/vent/timers | Runtime уровни/таймеры observed; 5/15/30 — mapped setting, не доказанное remaining-time. MConfig теряет write-result. |
| COMF-021 массаж | Switch/intensity/program различены, runtime notavailable; OFF intensity не приравнен motor-off. |
| COMF-022 руль | Manual levels active; AUTO read/switch notactive в сеансах. ErrorPA33385 и read-only auto getter сохранены. |
| COMF-023 WPC | Work-mode отдельно от typed charge-state. Runtime public0 не переименован в raw 0 OVERHEAT. |
| COMF-024 сцены | Общий selector и отдельный theater route; состав исполнительных действий/откат не раскрыты. |
| COMF-025 датчики/опции | Availability/status/value разнесены, default/fixed-active не выдаются за sensor presence; частоты и физические units требуют точного decoder. |

## Доказанный адресный пробел

Отсутствует **`/system/priv-app/XCHvac/XCHvac.apk`**, package `ecarx.hvac.app`. Путь подтверждён `KX11-HU-Route-20260810-070911-29994-1234567.zip/android/all-packages.txt:70` и повторными package-path inventories. Его нет среди APK/JAR members 102 просмотренных контейнеров; runtime подтверждает настоящий stock process/MainActivity. Этот единственный production consumer позволит проверить LO/HI click conversion, popup buttons и climate/seat control lifecycle. `collection_targets.json` задаёт exact package/path и доказательства для macOS-досбора.

Settings, system Adapt/Car, CarService, GKUI и test consumer уже получены. Для runtime-origin достаточно сначала package metadata/current hash/maps; при неизменном hash копировать бинарник повторно не требуется. Отдельный digital-key-service не установлен даже как exact missing path, поэтому не добавлены догадки о ключевых БД или выдаче ключей. Сбор должен оставаться чтением адресных файлов/метаданных; существование этой очереди не запускает операции автомобиля.

`VALIDATION.json` фиксирует 25 уникальных COMF, разрешённые evidence references, хэши результатов и отсутствие нового физического теста. Незакрытые звенья отмечены по точной области; общий GATE-048 и hardware acceptance остаются открытыми.
