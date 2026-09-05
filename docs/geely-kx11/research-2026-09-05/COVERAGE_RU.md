# Матрица покрытия систем Geely KX11 / ECARX

Дата: 5 сентября 2026. **94 области исследования:** 86 карточек нового тематического разбора и 8 явно сохранённых направлений прежней работы. Строки могут пересекаться и не равны физическим ECU, опциям или проверенным командам. Это расширяемая карта известного и неизвестного; гарантия исчерпывающего охвата всей машины пока невозможна.

[Инструкция](../../../GEELY_KX11_KNOWLEDGE_RU.md) · [Журнал новых результатов](RESEARCH_LOG_RU.md) · [Что ещё изучать и собирать](DATA_PLAN_RU.md) · [Очередь вопросов](../open_questions.json) · [Машиночитаемая матрица](systems.json)

## Как читать карточку

«Установлено» относится только к названному бинарнику, записи или прежнему опыту. Исходные SHA-256, DEX method/offset, JSON pointers и номера пакетов находятся в записи доказательств. Декларация SDK, программный маршрут, наблюдённый статус и физическое исполнение учитываются отдельно. В этом дополнении испытаний на автомобиле не было.

## Навигация по областям

| Область | Карточки | Детальный разбор |
|---|---:|---|
| Кузов и комфорт | 25 | [Доказательства и ограничения](comfort_systems/report.md) |
| Силовая установка, шасси и безопасность | 24 | [Доказательства и ограничения](powertrain_chassis/report.md) |
| Архитектура, ECU, транспорт и жизненный цикл | 25 | [Доказательства и ограничения](architecture_domains/report.md) |
| Сервисы головной системы | 12 | [Доказательства и ограничения](android_platform/report.md) |
| Камера, парковка, дисплеи и взаимодействие | 8 | [Доказательства и ограничения](../../../GEELY_KX11_KNOWLEDGE_RU.md) |

## Реестр

| ID | Система | Карточка доказательств |
|---|---|---|
| COMF-001 | Двери, центральный замок и капот | [/systems/0](comfort_systems/systems.json) |
| COMF-002 | Детские замки и детский сценарий | [/systems/1](comfort_systems/systems.json) |
| COMF-003 | Физический и цифровой ключ | [/systems/2](comfort_systems/systems.json) |
| COMF-004 | Профили пользователя и память настроек | [/systems/3](comfort_systems/systems.json) |
| COMF-005 | Багажник и высота открытия | [/systems/4](comfort_systems/systems.json) |
| COMF-006 | Боковые стёкла и защита от защемления | [/systems/5](comfort_systems/systems.json) |
| COMF-007 | Люк, наклон и шторка панорамы | [/systems/6](comfort_systems/systems.json) |
| COMF-008 | Дворники, омыватели и датчик дождя | [/systems/7](comfort_systems/systems.json) |
| COMF-009 | Зеркала: моторы, наклон, складывание и обогрев | [/systems/8](comfort_systems/systems.json) |
| COMF-010 | Наружный свет и световые сценарии | [/systems/9](comfort_systems/systems.json) |
| COMF-011 | Лампы чтения и салонный свет | [/systems/10](comfort_systems/systems.json) |
| COMF-012 | Атмосферная подсветка и палитра | [/systems/11](comfort_systems/systems.json) |
| COMF-013 | Климат: температура и единицы | [/systems/12](comfort_systems/systems.json) |
| COMF-014 | Климат: вентилятор, зоны и потоки | [/systems/13](comfort_systems/systems.json) |
| COMF-015 | Обогрев стёкол и осушение | [/systems/14](comfort_systems/systems.json) |
| COMF-016 | Климатические предложения, REQUEST и CONFIRM | [/systems/15](comfort_systems/systems.json) |
| COMF-017 | Предварительный и послепоездочный климат | [/systems/16](comfort_systems/systems.json) |
| COMF-018 | Качество воздуха, фильтр и ароматизация | [/systems/17](comfort_systems/systems.json) |
| COMF-019 | Позиция сидений, easy-entry и память | [/systems/18](comfort_systems/systems.json) |
| COMF-020 | Подогрев и вентиляция сидений, таймеры | [/systems/19](comfort_systems/systems.json) |
| COMF-021 | Массаж сидений и программа hot-stone | [/systems/20](comfort_systems/systems.json) |
| COMF-022 | Подогрев руля и его AUTO-режим | [/systems/21](comfort_systems/systems.json) |
| COMF-023 | Беспроводная зарядка телефона | [/systems/22](comfort_systems/systems.json) |
| COMF-024 | Сценарии салона: мойка, дети, отдых итеатр | [/systems/23](comfort_systems/systems.json) |
| COMF-025 | Датчики комфорта, присутствия и опциональное оборудование | [/systems/24](comfort_systems/systems.json) |
| PT-ENGINE | Двигатель: обороты, состояние, охлаждение и масло | [/systems/0](powertrain_chassis/systems.json) |
| PT-FUEL | Топливо, расход и сервисные счётчики | [/systems/1](powertrain_chassis/systems.json) |
| PT-TRANSMISSION | Коробка: селектор, фактическая и рекомендуемая передача | [/systems/2](powertrain_chassis/systems.json) |
| PT-STARTSTOP | Автоматический старт-стоп двигателя | [/systems/3](powertrain_chassis/systems.json) |
| PT-DRIVEMODE | Режимы движения и индивидуальные профили | [/systems/4](powertrain_chassis/systems.json) |
| CH-AWD | Полный привод, муфты и распределение момента | [/systems/5](powertrain_chassis/systems.json) |
| CH-BRAKES | Рабочие тормоза, педаль и качество измерений | [/systems/6](powertrain_chassis/systems.json) |
| CH-ABS-ESC | ABS/ESC и спортивная настройка стабилизации | [/systems/7](powertrain_chassis/systems.json) |
| CH-EPB | Электрический стояночный тормоз | [/systems/8](powertrain_chassis/systems.json) |
| CH-AUTOHOLD-HDC | AutoHold и помощь на спуске/подъёме | [/systems/9](powertrain_chassis/systems.json) |
| CH-STEERING | Руль: угол, скорость и усилие | [/systems/10](powertrain_chassis/systems.json) |
| CH-SUSPENSION | Подвеска и регулировка высоты/демпфирования | [/systems/11](powertrain_chassis/systems.json) |
| CH-TPMS | Шины: давление, температура, свежесть и предупреждения | [/systems/12](powertrain_chassis/systems.json) |
| ADAS-CRUISE | Круиз: индикация, настройки и команды | [/systems/13](powertrain_chassis/systems.json) |
| ADAS-LIMITER | Лимитер, ограничение предупреждения и скорость круиза | [/systems/14](powertrain_chassis/systems.json) |
| ADAS-LANE | Полоса: LKA/ELKA/EMA/HWA и режимы предупреждений | [/systems/15](powertrain_chassis/systems.json) |
| ADAS-COLLISION | Предупреждение столкновения и AEB/CMS | [/systems/16](powertrain_chassis/systems.json) |
| ADAS-SIDE | Боковые/задние ассистенты: LCA/RCW/RCTA/DOW | [/systems/17](powertrain_chassis/systems.json) |
| ADAS-TSR | TSR, превышение скорости и настройка смещения | [/systems/18](powertrain_chassis/systems.json) |
| ADAS-DRIVER | Контроль водителя/усталости | [/systems/19](powertrain_chassis/systems.json) |
| ADAS-PERCEPTION | Геометрия полос, объекты и качество восприятия | [/systems/20](powertrain_chassis/systems.json) |
| CH-PEB | Экстренное торможение при парковке | [/systems/21](powertrain_chassis/systems.json) |
| SRS-BELTS | Ремни: замки, оснащённость и комфортное электронное подтягивание | [/systems/22](powertrain_chassis/systems.json) |
| SRS-IMPACT | SRS: аварийное состояние, записи удара и пиротехника | [/systems/23](powertrain_chassis/systems.json) |
| ARCH-001 | Android / AP и штатные сервисы IHU | [/systems/0](architecture_domains/systems.json) |
| ARCH-002 | QNX host и гипервизор | [/systems/1](architecture_domains/systems.json) |
| ARCH-003 | LA / LV: обычная работа и Failsafe guest | [/systems/2](architecture_domains/systems.json) |
| ARCH-004 | VP peer 198.18.34.1:50500 | [/systems/3](architecture_domains/systems.json) |
| ARCH-005 | Сетевой мост QNX и виртуальный Ethernet | [/systems/4](architecture_domains/systems.json) |
| ARCH-006 | Маршрут ASDM и автомобильные VLAN | [/systems/5](architecture_domains/systems.json) |
| ARCH-007 | TCAM и маршрут внешней связи | [/systems/6](architecture_domains/systems.json) |
| ARCH-008 | MCU / VIP firmware на диске | [/systems/7](architecture_domains/systems.json) |
| ARCH-009 | QNX CAN resource manager через SPI9 | [/systems/8](architecture_domains/systems.json) |
| ARCH-010 | Android SocketCAN / FlexRay доступность | [/systems/9](architecture_domains/systems.json) |
| ARCH-011 | IPCL SPI4 обмен IHU | [/systems/10](architecture_domains/systems.json) |
| ARCH-012 | Общий диагностический ECU inventory | [/systems/11](architecture_domains/systems.json) |
| ARCH-013 | QNX DoIP / UDS gateways | [/systems/12](architecture_domains/systems.json) |
| ARCH-014 | Информация о деталях и сборках IHU | [/systems/13](architecture_domains/systems.json) |
| ARCH-015 | DTC / diagnostic monitor | [/systems/14](architecture_domains/systems.json) |
| ARCH-016 | Идентичность версий AP/VP/QNX/MCU | [/systems/15](architecture_domains/systems.json) |
| ARCH-017 | Питание IHU / режимы AP, IM, EM | [/systems/16](architecture_domains/systems.json) |
| ARCH-018 | Сон, пробуждение и сохранение контекста | [/systems/17](architecture_domains/systems.json) |
| ARCH-019 | Перезапуски, watchdog и восстановление сервисов | [/systems/18](architecture_domains/systems.json) |
| ARCH-020 | Обновления / разделы / rollback | [/systems/19](architecture_domains/systems.json) |
| ARCH-021 | Android permissions / Binder / VHAL access | [/systems/20](architecture_domains/systems.json) |
| ARCH-022 | QNX policy / виртуальная изоляция / диагностический доступ | [/systems/21](architecture_domains/systems.json) |
| ARCH-023 | MCU relay: происхождение, семантика и время | [/systems/22](architecture_domains/systems.json) |
| ARCH-024 | Network management CAN / FlexRay / LIN | [/systems/23](architecture_domains/systems.json) |
| ARCH-025 | Наблюдаемость и полнота корпуса | [/systems/24](architecture_domains/systems.json) |
| PLAT_POWER | Питание, сон, пробуждение и режимы головной системы | [/systems/0](android_platform/systems.json) |
| PLAT_DISPLAY | Дисплеи, питание PSD, яркость и день/ночь | [/systems/1](android_platform/systems.json) |
| PLAT_AUDIO | Аудиотракт, источники, микширование и автомобильные предупреждения | [/systems/2](android_platform/systems.json) |
| PLAT_RADIO | Радиоприёмник AM/FM/DAB и дорожные объявления | [/systems/3](android_platform/systems.json) |
| PLAT_BLUETOOTH | Bluetooth: профильные соединения, BLE и сосуществование стеков | [/systems/4](android_platform/systems.json) |
| PLAT_PHONE | Обычная телефония, контакты, кнопки звонка и DIM | [/systems/5](android_platform/systems.json) |
| PLAT_CARPLAY | CarPlay, проекция и взаимодействие с Bluetooth/Wi-Fi/звуком | [/systems/6](android_platform/systems.json) |
| PLAT_LOCATION | GNSS, положение, NMEA и автомобильные данные навигации | [/systems/7](android_platform/systems.json) |
| PLAT_TCAM | TCAM: eCall/bCall/iCall, микрофон, удалённые функции и диагностика | [/systems/8](android_platform/systems.json) |
| PLAT_NETWORK | Связность: Wi-Fi, Ethernet, модем и внутренние сервисы | [/systems/9](android_platform/systems.json) |
| PLAT_UI | Штатный UI, жесты, шторка, launcher и оконные владельцы | [/systems/10](android_platform/systems.json) |
| PLAT_UPDATE | Обновления, диагностика и права системных сервисов | [/systems/11](android_platform/systems.json) |
| BASE-CAMERA | Камера 360 / AVM | [/systems/0](continuation/systems.json) |
| BASE-PAS | Парковочные датчики, окно PAS и приоритет уведомлений | [/systems/1](continuation/systems.json) |
| BASE-HUD | HUD: профили, PEN, маски содержимого | [/systems/2](continuation/systems.json) |
| BASE-DIM | Приборка DIM: крылья, машинка и владельцы графики | [/systems/3](continuation/systems.json) |
| BASE-NAV | Навигация: маршрут, HUD/DIM и координаты | [/systems/4](continuation/systems.json) |
| BASE-MEDIAKEY | Кнопки руля, события ввода и управление медиа | [/systems/5](continuation/systems.json) |
| BASE-ANCS | ANCS/BLE уведомления и восстановление соединений | [/systems/6](continuation/systems.json) |
| BASE-VOICE | Голосовое управление, микрофон и голосовые предупреждения | [/systems/7](continuation/systems.json) |

## COMF-001 — Двери, центральный замок и капот

**Установлено в имеющихся источниках:**

- BCM_FUNC_DOOR 0x21020100 для четырёх дверей/капота читает 30382/30388/30385/30391/30417. BCM_FUNC_DOOR_LOCK0x21020200 читает 30611; обе регистрации помечены active, но setter замка отсутствует.

**Остаётся неизвестным:**

- Исполнитель немедленного lock/unlock из ГУ; anti-theft и deadlock; механика блокировки и допустимые режимы.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** read_only_in_public_registration

**Следующий разбор:** Найти references на 30611 и низкоуровневые LockgCenReq в штатном settings, сравнить command и status enum.

**Недостающая опора:** Штатный consumer блокировки и схема кузовного ECU из уже имеющихся APK/диагностики.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/0`.

## COMF-002 — Детские замки и детский сценарий

**Установлено в имеющихся источниках:**

- BCM_FUNC_CHILD_SAFETY_LOCK0x21020400: zone16→CB_ChdLockReLeft/property33243/PA33841;zone64→33244/33842. API1→raw2,API0→raw1; feedbackraw1→API1,raw2→API0. Командная и статусная кодировки различаются.
- 0x21020700 использует отдельные CB_*_ChdMod33245/33246 и PA33843/33844. Это не алиас обычных детских замков.

**Остаётся неизвестным:**

- Наличие электрического управления на конкретной машине; взаимодействие Child scene и ручной настройки; сохранение после засыпания.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed

**Следующий разбор:** Сопоставить LockgCenReq2 со статусным enum, затем потребителей сцен и уже записанные 33841–33844.

**Недостающая опора:** PA33841–33844 с availability/data и штатный экран детских замков.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/1`.

## COMF-003 — Физический и цифровой ключ

**Установлено в имеющихся источниках:**

- CarKey createDigitalKey/delDigitalKeyItem/delAllDigitalKey/registerDigitalKeyCallback/unregisterDigitalKeyCallback возвращают false без действий; getDigitalKeys всегда пустой массив; cancelDiscovery пустой. Это реализация-заглушка.
- startDiscovery/readRealKey/unbindCarKey вызывают CB_PSET_ConnectKey(property33254) со значениями 0/1/17; unbindCarKey(int) игнорирует переданный ID. readRealKey — запись запроса, не пассивное чтение.

**Остаётся неизвестным:**

- Реальный смысл 0/1/17 в ECU, обратная связь 33853 и привязка физического ключа; существует ли отдельный сервис цифровых ключей вне этого SDK.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** mixed_stub_and_command_route

**Следующий разбор:** Разобрать CarKey$1 и PA_PSET_ConnectKey; искать другой service реализации digital key, не вызывать unbind для исследования.

**Недостающая опора:** CarKey callback33853 и штатный менеджер ключей; подтверждение комплектации.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/2`.

## COMF-004 — Профили пользователя и память настроек

**Установлено в имеющихся источниках:**

- Действия профиля разрешаются только при PA33855.data==1. getCurrentId читает 33845; getUserProfileData/applyUserProfileData работают только для текущего профиля.
- addUserProfile и addUserProfileCopyFrom отправляют CB_PSET_NewProfile33249, но всегда возвращают-1; результат приходит асинхронно через PA callbacks. Возврат-1 не доказывает, что профиль не создан.
- switchUserProfile(from,to) игнорирует from и выбирает to через CB_PSET_RequestActiveProfile 33248. applyUserProfileData возвращает true после вызова void-команды CB_PSET_ProfileCloudData, не дожидаясь асинхронного подтверждения результата.

**Остаётся неизвестным:**

- Полный список восстанавливаемых полей, воздействие на сиденья/зеркала, конфликт облачных и локальных профилей, атомарность/откат.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_async_result_required

**Следующий разбор:** Составить карту каждого ProfileFunction→protobuf field→устройства, разобрать результирующие callback33845–33849/33874; не считать snapshot другого профиля доступным.

**Недостающая опора:** Аннотации ProfileFunction, Profileclouddata, штатный profile consumer, существующий 33855/33873/33875.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/3`.

## COMF-005 — Багажник и высота открытия

**Установлено в имеющихся источниках:**

- Дверь багажника 0x21020100/zone0x20000000 выбирает две CB ветви по PA33675/33676; управление высотой 0x2c010800 отдельное. Общий успешный return не равен фактическому открытию.

**Остаётся неизвестным:**

- Ограничения по передаче/скорости, прерывание препятствием, калибровка/память высоты и оба PA одновременно active.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_conditional

**Следующий разбор:** Восстановить точную таблицу обеих ветвей и переходы target/current; исключить повторение импульса при неполном ответе.

**Недостающая опора:** Штатный consumer багажника и сигналы препятствия/допуска из текущих архивов.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/4`.

## COMF-006 — Боковые стёкла и защита от защемления

**Установлено в имеющихся источниках:**

- Позиции переводятся в raw1..26 с шагом 4%; групповые зоны выполняют последовательные CB, не атомарную операцию. non-EX11 mapper боковых окон не объясняет все pause enum, объявленные в supported list.

**Остаётся неизвестным:**

- Чем останавливать боковое стекло в KX11, anti-pinch/learned position и поведение при потере соединения; настоящая обратная связь от мотора.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_stop_semantics_incomplete

**Следующий разбор:** Связать PA33833–33840 с CurrentPos/MovingState; проследить несовпадение pause списка и non-EX11 callback.

**Недостающая опора:** Штатный window-control consumer и записанные moving/anti-pinch/availability состояния.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/5`.

## COMF-007 — Люк, наклон и шторка панорамы

**Установлено в имеющихся источниках:**

- Люк и шторка используют разные зоны 4/8 и отдельные пары press/release CB33193–33196, позиции 33197/33198 и наклон 33199. Регистрация наклона отправляет CB_SunRoofTiltReq; дополнительных CB закрытия люка/шторки в этой регистрации нет. Совместный физический эффект не проверен.

**Остаётся неизвестным:**

- Последовательность координации крыши/шторки; пределы, rain-close, защемление, калибровка и автозакрытие после выключения.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed

**Следующий разбор:** Разобрать pressed/released/tilt и текущую позицию 30479/30476, движение 30477/30475, связанные sensor fault enums.

**Недостающая опора:** Штатная roof UI логика и PA/CarSignal roof status/obstacle/config.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/6`.

## COMF-008 — Дворники, омыватели и датчик дождя

**Установлено в имеющихся источниках:**

- Сервисное положение 0x200c0100: zoneDEFAULT/1→CB_CL_WipFrntSrvMod32837/PA33346;zone2→задний 32838/33347. Настройка auto rear wiping0x200c0200→32836/33345. Управление принадлежит ClimateManager, несмотря на кузовное назначение.

**Остаётся неизвестным:**

- Мгновенный wipe/wash, скоростные ступени, дождевой сенсор, жидкость/обогрев форсунок, аппаратные допуски service position.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** settings_routes_confirmed_actuation_incomplete

**Следующий разбор:** Развести live actuation, retained setting, service operation и hardware config; нельзя трактовать rear-auto как немедленный взмах.

**Недостающая опора:** Доступные Wip/Wash/Rain raw сигналы и штатный потребитель сервисного режима.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/7`.

## COMF-009 — Зеркала: моторы, наклон, складывание и обогрев

**Установлено в имеющихся источниках:**

- Настройки auto-fold0x20090200 и reverse-dipping0x20090300 имеют CB33035/33036 и PA33644/33645. Отдельный MConfig моторный fold0x21060100 не подтверждает, что этот маршрут реализован текущим системным Bcm.

**Остаётся неизвестным:**

- Немедленное fold/unfold в runtime SDK, моторное движение стекла, позиционная память/обратная связь, привязка обогрева к заднему стеклу.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** settings_confirmed_motor_route_unresolved

**Следующий разбор:** Найти регистрацию 0x21060100 по всем системным/APK DEX, проверить fork версии и обратный motor-status, не подменять автоскладыванием.

**Недостающая опора:** Системный consumer зеркал и фактический classloader MConfig; существующие mirror motor properties.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/8`.

## COMF-010 — Наружный свет и световые сценарии

**Установлено в имеющихся источниках:**

- BCM_FUNC_LIGHT_DIPPED_BEAM фактически использует CB_AFSLight_Setting33027/PA33636, общий с AFS-setting. Поворотники 0x21051100/0x21051200 — только reading30421.
- Лево-/правостороннее движение 0x2b020100 кодируется LEFT→raw1,RIGHT→raw0 через CB_LeftRightSetting; welcome mode1..6→raw0..5. Это сохранённые настройки, не прямое зажигание лампы.

**Остаётся неизвестным:**

- Прямые low/high/fog/hazard actuators, уровень корректора, ограничения AUTO/AFS, физическая конфигурация фар.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** mixed_read_only_and_settings_routes

**Следующий разбор:** Разобрать consumer0x20040e00, отличить requested state от monitor/fault и настройки схемы света.

**Недостающая опора:** Штатный lighting UI, lamp ECU/конфигурация и actual light-status.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/9`.

## COMF-011 — Лампы чтения и салонный свет

**Установлено в имеющихся источниках:**

- Индивидуальные CB_ReadLight* используют 1=on,2=off; all switch1=on,0=off. Courtesy light имеет отдельную настройку через PA33825.

**Остаётся неизвестным:**

- Установленные зоны/плафоны, реакции на двери/блокировку, master-vs-local приоритет и восстановление.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_routes_static_confirmed

**Следующий разбор:** Сопоставить автоматический courtesy с ручными CB и проверить признаки third-row отдельно от реального KX11.

**Недостающая опора:** Availability локальных PA и штатная логика courtesy/door.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/10`.

## COMF-012 — Атмосферная подсветка и палитра

**Установлено в имеющихся источниках:**

- Текущий AmbienceLight цвет 0x200a0900 для KX11 DEFAULT-zone принимает таблицу 72 RGB integer entries; EX11 имеет другую 64-entry палитру и зонирование. Это другой contract, чем 13 именованных color enum клиента.
- KING scene определяется PA33926==11 и переводит 24 active status-проверки подсветки в notactive. Сбой доступности настройки может быть следствием сценария, а не неисправностью ламп.
- Поддерживаемый BREATHE mode0x200a0206 добавляется по PA33799, но setAmbLiMod принимает 0/0x200a0202/03/04/08; для 06 сначала вызывает CB_AmbLiAll(1), потом остаётся FAILED. Возврат FAILED не гарантирует отсутствие побочного эффекта.

**Остаётся неизвестным:**

- Активный runtime SDK/palette/compatibility для Natro, устранение несогласованного BREATHE пути, фактический RGB формат и число цветов оборудования.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_routes_static_confirmed_contract_mismatches

**Следующий разбор:** Сверить яркость/цвет/эффект Natro с 0x200a0900, palette и lambda; не считать mode06 безвредной probe.

**Недостающая опора:** Consumer штатной подсветки, PA33793–33829 и SceneMod actual state.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/11`.

## COMF-013 — Климат: температура и единицы

**Установлено в имеющихся источниках:**

- PA33460 выбирает C/F. SDK min/max/step:15.5..28.5°C/0.5 или 59..85°F/1. convertTemper2Index truncates float, а checkTemper проверяет публичные min/max. Клиентский диапазон Natro 16..30 не совпадает с этим контрактом.

**Остаётся неизвестным:**

- Как LO/HI отображает штатная UI; совпадает ли реально загруженный Hvac с системным; conversion на неожиданных PA/NaN.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_client_range_mismatch

**Следующий разбор:** Проверить Natro capability min/max/step и локальные helper bounds; не принимать 29–30 как допустимые только потому, что ползунок их даёт.

**Недостающая опора:** Штатный климат consumer temperature conversion, существующий PA33460 и classloader origin.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/12`.

## COMF-014 — Климат: вентилятор, зоны и потоки

**Установлено в имеющихся источниках:**

- PA fan имеет status0=manual/status1=AUTO; неправильный getter возвращает 255. Наличие raw fan-level без mode недостаточно. Manual KX11 list0..9,EX11 list0..5; front/rear routes различны.
- Climate-zone getClimateZone:config5→TRIPLE,129→FOUR,все прочие→DUAL. Возврат DUAL по умолчанию не является независимым доказательством физического числа зон.

**Остаётся неизвестным:**

- Какие задние actuators/панель установлены; freshness после сна; поддерживаемые режимы и ошибочные raw.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_routes_static_confirmed

**Следующий разбор:** Сопоставить архивные config и PA33317/33335, фильтрацию manual/AUTO, не отображать 255 как скорость.

**Недостающая опора:** Статус fan и данные вместе, cfg HVAC-zone и штатный UI.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/13`.

## COMF-015 — Обогрев стёкол и осушение

**Установлено в имеющихся источниках:**

- Электрообогрев лобового и max defrost разные функции; статус front/rear при popup33338/33339 availability1,data1 принудительно error. Это дополнительный отказный источник помимо основного PA.

**Остаётся неизвестным:**

- Смысл popup-причин, условия температуры/энергии/времени, совместимость rain/humidity sensors и алгоритмы повторного включения.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_routes_static_confirmed_multi_signal_status

**Следующий разбор:** Связать popup с actual heater state и diagnostics; не сбрасывать ошибку одной повторной записью.

**Недостающая опора:** Schemas PA_CL_FrntDefrostPopup/RearDefrostPopup и штатное уведомление.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/14`.

## COMF-016 — Климатические предложения, REQUEST и CONFIRM

**Установлено в имеющихся источниках:**

- AUTO_ION_REQUEST0x100c0200,DEHUMIDIFICATION_REQUEST0x100d0100 и CLOSE_WINDOW_REMIND_REQUEST0x100f0200 — входящие события без setter. Соответствующие CONFIRM —write-only ответы; слово REQUEST не означает запуск функции.
- Ответы CONFIRM имеют разные mapper: ионизация public0/1/2→raw2/1/0 через 32862; осушение 0/1/2→0/1/1 через 32821; окна 0/1/2→0/1/0 через 32839. Общий обработчик dialog-result сломает смысл.

**Остаётся неизвестным:**

- UI-смысл третьего ответа, жизненный цикл предложения, реакция при повторе/устаревшем dialog и физическая инициирующая логика ECU.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** event_response_routes_static_confirmed

**Следующий разбор:** Разобрать каждый callback request и UI кнопки, привязать ответ к актуальному событию; не выполнять universal toggle.

**Недостающая опора:** Штатные popup consumers и результативные feedback после выбора.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/15`.

## COMF-017 — Предварительный и послепоездочный климат

**Установлено в имеющихся источниках:**

- PRE0x100a0100→CB_CL_Pre32797/PA33329;POST0x100a0200→CB_CL_Post32796/PA33330,0/1. Эти paths не описывают отдельно запуск двигателя, wake-up, расписание или удалённый доступ.

**Остаётся неизвестным:**

- Реальная поддержка ICE KX11; зависимость от двигателя/АКБ/режима питания, ограничение длительности и выход из стоянки.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_hardware_unknown

**Следующий разбор:** Сравнить с гибридными общими SDK ветками; выяснить смысл PA permission прежде чем показывать как remote-start.

**Недостающая опора:** PA33329/33330 support, HVAC/engine topology и штатный consumer.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/16`.

## COMF-018 — Качество воздуха, фильтр и ароматизация

**Установлено в имеющихся источниках:**

- Fragrance main on0x100b0100 вызывает тот же CB_Fragra_LvlReq32869 что level, но on→raw2. Включение выбирает среднюю интенсивность, не обязательно последнюю.
- AIR_FRAGRANCE_TYPE0x100b0200 объявляет ароматы, однако эта регистрация имеет только status — ни getter, ни setter. Реальный slot0x100b0400 выбирает один из A/B/C через соответствующий TypRatReq(100); type-id read0x100b0500 использует slot как zone.
- Уровень становится notactive при PA33406.data==3; refreshing popup0x100b1200 read-only, active фиксирован и события фильтруются.

**Остаётся неизвестным:**

- Наличие cartridges/ionizer/PM/CO2/filter-life аппаратуры, реальный смысл slot/type-ID, единицы измерений и lifespan.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** mixed_static_control_and_placeholder

**Следующий разбор:** Не переносить имена шести ароматов в UI как установленное оборудование; проверить sensors и доступные consumers в APK.

**Недостающая опора:** Runtime availability PA33396/33406/33418–33420 и штатный fragrance/air-quality consumer.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/17`.

## COMF-019 — Позиция сидений, easy-entry и память

**Установлено в имеющихся источниках:**

- Существующие пути 32982–32989 — direction1/2 и stop0, не абсолютные миллиметры. Easy-entry0x20170100 отдельная настройка водителя; профиль может вызывать иной слой восстановления.

**Остаётся неизвестным:**

- Настоящие координаты, calibration/anti-pinch, необходимость periodic hold, остановка при потерянном touch/Binder, конфликт memory и ручной регулировки.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_stop_timing_unknown

**Следующий разбор:** Разобрать удержание и остановку штатной UI, связать PA33565+ с motoractuals; не считать callback долготу движения.

**Недостающая опора:** Штатный seat consumer и profile-field mappings.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/18`.

## COMF-020 — Подогрев и вентиляция сидений, таймеры

**Установлено в имеющихся источниках:**

- AUTO_*_TIME mapper реализует только public TIME1/2/3→5/15/30; TIME4 и OFF0 есть среди интерфейсных констант, но не в registered supported list этих функций. Вентиляционный таймер имеет зоны 1/4, отопительный 1/4/16/64.

**Остаётся неизвестным:**

- Что значит таймер: сохранённая длительность/оставшееся время; установленность rear/auto функций; thermal/fault-conditions.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_routes_static_confirmed_narrower_than_enum_catalog

**Следующий разбор:** Выяснить остановку через основной level/switch, не через несуществующее TIME_OFF; анализировать availability и данные раздельно.

**Недостающая опора:** Штатный timer consumer и PA33367–33381.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/19`.

## COMF-021 — Массаж сидений и программа hot-stone

**Установлено в имеющихся источниках:**

- Массаж разделён на intensity 0x10050700, switch 0x10050a00 и program 0x10050b00. В mapper интенсивности public OFF 0 и LEVEL1 оба преобразуются в raw 0. Это вывод о преобразовании значения в SDK, а не проверенный физический результат. Выключение следует исследовать через отдельный switch.
- Программы 1..8→raw0..7; первая исключается из supported list, если PA33621/33622 notavailable. Программы 9/A объявлены, но этим mapper не реализованы.

**Остаётся неизвестным:**

- Наличие массажных элементов; связь hot-stone с подогревом; длительность и физические программы.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_route_static_confirmed_equipment_unverified

**Следующий разбор:** Разобрать callbacks intensity/switch, физические команды и поддержку комплектации прежде чем показывать новые опции.

**Недостающая опора:** PA33599/33603 и 33621/33622, штатный seat massage consumer.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/20`.

## COMF-022 — Подогрев руля и его AUTO-режим

**Установлено в имеющихся источниках:**

- AUTO_STEERING_WHEEL_HEAT0x10090200 read-only: signal30472,0→off,любое иное→LEVEL1; advertised levels2/3 не восстанавливаются этим getter. AUTO_SWITCH0x10090400 — отдельный writable CB_SWH_AutoReq32859/PA33383.
- Manual heat status учитывает PA33385.data==5 как error вдобавок к PA33384 availability.

**Остаётся неизвестным:**

- Код 30472 и фактические thermalfaults, положение AUTO на KX11, смысл TIME декларации без регистрации.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** mixed_read_only_and_write_switch

**Следующий разбор:** Развести AUTO-state / AUTO-switch / manual-level; не пытаться управлять чтением AUTO-level.

**Недостающая опора:** SchemaSteerWhlHeatg/state diagnostics, PA33383–33385 и штатная UI.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/21`.

## COMF-023 — Беспроводная зарядка телефона

**Установлено в имеющихся источниках:**

- WPC_WORK_MODE0x26010100 принимает OFF0 и AUTO0x26010101→CB_WPC_Setting; charge-states0x26020100 только readPA33626. raw0→OVERHEAT,1→STANDBY,2→CHARGING,3→FOD,4→OVERVOLTAGE,5→OVERPOWER,6→ERROR,7/9→OFF,8→FULL; иное→UNKNOWN255.

**Остаётся неизвестным:**

- Физическая катушка / мощность / температурные пороги, реальные PEPS interruption/no-device состояния; DEFAULTOFF mapping7/9.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_setting_and_read_status_static_confirmed

**Следующий разбор:** Не считать raw0 выключением; проследить штатный индикатор ошибок WPC и его consumer.

**Недостающая опора:** WPC manager/fault schema и реальный PA33625/33626 support.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/22`.

## COMF-024 — Сценарии салона: мойка, дети, отдых итеатр

**Установлено в имеющихся источниках:**

- Семь bool функций сходятся к одному CB_SCEMOD_SceneModSeld 33272 и PA 33926: wash 9, children 2, awakening 1, romantic 4, king 11, queen 14, nap 3; их OFF всегда raw 0. Это один селектор режима, не семь независимых флагов.
- Theater 0x2f010100 работает в zone 4 через отдельный CB_SCEMOD_PassSceneModSeld 33271 / PA 33928, raw 6. Нельзя приравнивать к global scene.

**Остаётся неизвестным:**

- Какие функции физически составляют сценарий; условия входа, выхода и отката; пользовательский приоритет и реальное оснащение.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** write_routes_static_confirmed_composite_effects_unknown

**Следующий разбор:** Разобрать состав режимов. Общая OFF может выключить текущую другую сцену, поэтому нельзя делать независимые асинхронные переключатели.

**Недостающая опора:** Scenemod PA 33911–33928, штатный consumer сценариев и трассировки составных действий.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/23`.

## COMF-025 — Датчики комфорта, присутствия и опциональное оборудование

**Установлено в имеющихся источниках:**

- Сигналы и enum присутствуют в общем SDK, но fixed-active для climate zones, ambient и popup и непустой список значений не подтверждают датчик. Для оборудования нужно раздельно проверять cfg, availability PA и осмысленные живые измерения.

**Остаётся неизвестным:**

- Полная карта occupancy / rain / sunload / humidity / air quality / window obstacle, калибровка, свежесть и точная комплектация.

**Наличие оборудования:** Не установлено этой статической проверкой; декларации общего SDK не подтверждают комплектацию KX11.

**Путь управления:** read_and_config_catalog_only

**Следующий разбор:** Распределить датчики по системам и для каждого связать конфигурацию, измерение, потребителя и обозначение недостоверного значения.

**Недостающая опора:** Перечень ECU и конфигурация; Sensor mappings из уже восстановленного системного SDK.

[Источники и точные указатели](comfort_systems/systems.json) → `/systems/24`.

## PT-ENGINE — Двигатель: обороты, состояние, охлаждение и масло

**Установлено в имеющихся источниках:**

- В raw SDK есть разные каналы EngSpdDispd31381 и EngNSafeEngN31459. В сохранённых экспортах первый содержит меняющиеся числа 1500…1928, второй только-1. Нельзя объявлять обороты недоступными по одному getEngNSafeEngN.
- CarSignal getEngSpdDispd возвращает getIntProperty(31381,1) без масштабирования; физическая единица/множитель этим методом не заданы.
- Повторный разбор оригинального Sensor установил: EngSpdDispd31381 умножается на 0.5; raw1500 даёт Sensor750. Публичный ID1050880 — SENSOR_TYPE_RPM.

**Остаётся неизвестным:**

- Качество и свежесть RPM, температура/качество остальных датчиков, карта ECU двигателя, рабочие состояния, момент, ограничения и версии калибровок.

**Наличие оборудования:** Живые числовые каналы оборотов/расхода наблюдались; идентификатор ECU и полная аппаратная конфигурация не получены.

**Путь управления:** Телеметрия READ подтверждена на уровне SDK/VHAL; прямое управление двигателем не установлено.

**Следующий разбор:** Формула RPM уже проверена. Продолжить с quality/свежестью данных, температурой/состоянием двигателя и связью кэша Sensor с обновлениями ECU.

**Недостающая опора:** Сначала извлечь штатный Sensor implementation; если его недостаточно — диагностический списокECU и синхронный журнал штатной приборки.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/0`.

## PT-FUEL — Топливо, расход и сервисные счётчики

**Установлено в имеющихся источниках:**

- EngFuCns и EngFuCnsFild — отдельные READ сигналы; в экспортах первый-1, второй меняется. Фильтрованное значение нельзя автоматически считать литрами/100 км.
- Сервисные счётчики не эквивалентны текущим показаниям датчиков.
- Оригинальный Sensor пересчитывает FuLvlIndcdFuLvlValFromFuTbl30932 как raw×0.2×1000; процентный канал 30895 отдельный. DayToSrv30871 умножается на 24.

**Остаётся неизвестным:**

- Единицы, счётчик против скорости расхода, сброс/переполнение, источник остатка топлива, дистанция доТО.

**Наличие оборудования:** Часть числовых каналов наблюдалась; полной ECU-идентификации нет.

**Путь управления:** Телеметрия и отдельные сервисные API объявлены; исполнительный контроль подачи топлива не найден.

**Следующий разбор:** Разделить текущий расход, накопленный расход, уровень, запасхода и сервисные счётчики с точными формулами.

**Недостающая опора:** Штатный Sensor/Trip implementation, затем при необходимости только сопоставимый журнал показаний.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/1`.

## PT-TRANSMISSION — Коробка: селектор, фактическая и рекомендуемая передача

**Установлено в имеющихся источниках:**

- GearLvrIndcn31385 и PtGearAct31414 — разные READ каналы, оба содержат меняющиеся значения; GearIndcnRec31475…31477 — ещё одно семейство, в экспортах только-1.
- В SDK объявлены разные enum-схемы GearLvrIndcn2 и PtGearAct1; одинаковое число 1 в них не имеет общего смысла. Связь каждого getter с enum требует проверки употребления, а не сходстваимени.
- Точный Sensor.getGera привязывает GearLvrIndcn0→P,1→R,2→N,3→D; manual4 и остальные значения обрабатывает через GearIndcnRecGearIndcn. PtGearAct в этом публичном маршруте не используется.

**Остаётся неизвестным:**

- Тип и версия TCU, ограничения переключения, управление P/D/R/M, фактические передаточные числа; независимая верификация PtGearAct. Привязка GearLvrIndcn в Sensor уже установлена.

**Наличие оборудования:** Данные селектора и фактической передачи наблюдались; число передач не выводится из широкого enum SDK.

**Путь управления:** READ статусы подтверждены; исполняемый маршрут выбора передачи из ГУ не установлен.

**Следующий разбор:** Публичная привязка GearLvrIndcn установлена. Проверить getManMod, независимую семантику PtGearAct и качество/время каждого канала.

**Недостающая опора:** Штатный Sensor implementation; диагностическая идентификацияTCU при отсутствии вдампах.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/2`.

## PT-STARTSTOP — Автоматический старт-стоп двигателя

**Установлено в имеющихся источниках:**

- SETTING_FUNC_ENGINE_STOP_START0x20020100 в Vehicle.buildFunctions идёт вCB_SS_Activation, с отдельнымPA33634. Это настройкаStartStop, не доказанная команда зажигания/запуска двигателя.
- setEngStrtStopSetg использует OnOff2, аCB_SS_Activation — иноймаршрут. Нельзя переносить 0/1 междуAPI.
- OnOff2 полностью проверен: On=0,Off=1,isValid принимает 0/1. On был пропущен старымextractor из-за default-zero static field; это ошибка покрытия каталога, а не отсутствие командыOn.

**Остаётся неизвестным:**

- Условия запрета start-stop, текущее engine-run-state, сохранение профиля и runtime-доступность. Полярность OnOff2 уже установлена.

**Наличие оборудования:** Комплектация и runtime-поддержка отдельно не подтверждены.

**Путь управления:** Настроечный CB/PA маршрут подтверждён статически; прямой запуск/остановка двигателя не подтверждены.

**Следующий разбор:** Полярность OnOff2 уже установлена. Проверить PA33634: доступность, статус и данные; разделить состояние работающего двигателя и настройку автоматического start-stop.

**Недостающая опора:** Sensor/Vehicle реализация иPАstate имеющегося корпуса; после неё только целевой журнал штатной настройки.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/3`.

## PT-DRIVEMODE — Режимы движения и индивидуальные профили

**Установлено в имеющихся источниках:**

- ИмеютсяCB_DM_DriveMode32877 и отдельныеPA режима/доступности/времени/подтверждения; CB команда не равна PAобратнойсвязи.
- DrvModReqType2 содержитECO,Comfort,Sport,Individual,EV,Hybrid,Snow,Mud,Sand,Rock иошибку 15; это словарь общегoSDK, ане переченьрежимовKX11.

**Остаётся неизвестным:**

- Какие режимы аппаратно доступны вэтоймашине, условия переключения и подтверждения, сохранение и отмена, взаимодействие сTCU/AWD/EPS.

**Наличие оборудования:** Живые профили каждого режима не подтверждены; EV/Hybrid не признаются установленным оборудованием.

**Путь управления:** Статические настроечные CB/PA маршруты найдены; вся аппаратная реализация режима не раскрыта.

**Следующий разбор:** Разобратьтаймаутподтверждения/доступность/PEN и branchvehicle-specific в DriveMode implementation.

**Недостающая опора:** PA_DriveMode*из имеющихся логов и точная runtimeконфигурация; при отсутствии — штатный экранрежимов с синхроннойтрассировкой.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/4`.

## CH-AWD — Полный привод, муфты и распределение момента

**Установлено в имеющихся источниках:**

- Найден PA_DriveModeAWD33434 (BYTES/READ). НаличиеPA режимаAWD не описывает протокол управления муфтой или блокировками.

**Остаётся неизвестным:**

- Топология привода, ECU/актуаторы, распределение момента, ограничениятемпературы, режимыотказа.

**Наличие оборудования:** По этому корпусу не подтверждена конфигурация AWD-блока.

**Путь управления:** Найдены только фрагменты настроек/отображения; маршрут исполнительного контроля не установлен.

**Следующий разбор:** Поиск признаковAWD/DEM/Differential в имеющихся конфигурациях/протосхемах, отделение тематическогоPA от актуатора.

**Недостающая опора:** ДиагностическаяидентификацияECU привода и штатнаясхемасоединений конкретнойкомплектации, если не найдутся вполныхархивах.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/5`.

## CH-BRAKES — Рабочие тормоза, педаль и качество измерений

**Установлено в имеющихся источниках:**

- Педаль, отношенияпедали, моменттрения поколёсам, ABS/Brake warning представлены самостоятельнымиREADканалами; многие вэкспортахтолько-1.
- Для рядасемейств существуют Qf,Chks,Cntr. Числоосновногоканала безкачества ивремени нельзя считать провереннымизмерением.
- CB_DM_Brake_Settings иCB_DM_BrakingPedalFeeling_Settings — настройкипрофиля, некоманды тормозногомомента.

**Остаётся неизвестным:**

- Непосредственноеуправлениедавлением/моментом, назначениеECU, единицы, контрольцелостности, реальныеqualityenum.

**Наличие оборудования:** Значительная часть каналов в корпусе -1; это не доказывает отсутствие тормозного блока.

**Путь управления:** READ телеметрия и настройки профиля различены; исполнительный путь торможения не установлен.

**Следующий разбор:** Сопоставитькачество/счётчики сданными, проследитьмасштаб иvalidity безактивныхкоманд.

**Недостающая опора:** ДекодирующийпотребительSENSOR_TYPE_BRAKE_DEPTH иECU-идентификация.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/6`.

## CH-ABS-ESC — ABS/ESC и спортивная настройка стабилизации

**Установлено в имеющихся источниках:**

- SETTING_FUNC_ESC_SPORT_MODE0x20020300→CB_PBC_ESCSportActiv33026→PA33635; этонастройка, азначения EscSt/EscWarnIndcnReq — отдельныеREADсигналы.
- EscSptModReqdByDrvr31318 имееттипBYTES сProtoполямизначения иPEN; нельзя отправлятьвместонего одиночныйint.

**Остаётся неизвестным:**

- КакиемеханизмыESCменяетSport, границыскорости/состояния, диагностикаABS/ESC, обоснованныйPEN.

**Наличие оборудования:** Runtime-поддержка настройки не установлена; READ семейства преимущественно -1 в экспортах.

**Путь управления:** Настроечный CB/PA статически есть; алгоритмы и исполнительный контроль неизвестны.

**Следующий разбор:** ПроверитьPEN ираскрытьполярность/status+availability; затем связатьточныестатусныеenum.

**Недостающая опора:** PA33635 иштатныйобработчикSport; диагностическаяидентификацияABS/ESC приотсутствиивдампах.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/7`.

## CH-EPB — Электрический стояночный тормоз

**Установлено в имеющихся источниках:**

- НизкоуровневыйEpbSoftSwtCtrlSt29325→VHAL0x2140728D,INT32/READ_WRITE; setterвалидируется EpbSoftSwtCtrlSt:NoRequest0,ApplyRequest1,ReleaseRequest2,Forbidden3,Unknown4.
- Прямойsoft-switch, автоматическоеприменениеCB_EPBAutoApply33041/PA33651 ипубличныйPBC_EPB_SWITCH — разныепути. Последнийфактическиведёт к парковочномуэкстренномуторможению (смCH-PEB).
- READ EpbApplyEna/EpbRelsEna/secondary/состояние доступнывSDK, нов имеющихсяэкспортах-1.

**Остаётся неизвестным:**

- Эффективностькомандынаконкретномавтомобиле,гейты,каналподтверждения,наличиепульса/удержания,доставкадоECU.

**Наличие оборудования:** Отдельные разрешающие статусы не имеют валидного наблюдения в этих логах.

**Путь управления:** Статический кандидат исполнительного маршрута; runtime-исполнение и разрешения НЕ подтверждены.

**Следующий разбор:** РазобратьветвьconvertVehicleValue2Ipcp для 0x2140728D иизвестныеусловияApplyEna/RelsEna; выявитьenumпроверку и реальныйtransport.

**Недостающая опора:** Нативныйконвертер точногосвойства и штатныйпотребительEPB; ECU/физическоеисполнениенеизучатьчерезслепыезаписи.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/8`.

## CH-AUTOHOLD-HDC — AutoHold и помощь на спуске/подъёме

**Установлено в имеющихся источниках:**

- AUTO_HOLD0x20060400→CB_PressAutoholdBtn/PA33652 отделён от AutHldSoftSwtEnaSts31355.
- HillDwnCtrlSt31303 — отдельныйwriteсигнал, SwtStsforHillDwnCtrl31418 — read; CB_HillDescentSetting33037/PA33646 задаёт ещё одинуровеньAPI.
- ИмяPressAutoholdBtn само по себе неустанавливаетимпульсный илиабсолютныйхарактеруправления.

**Остаётся неизвестным:**

- Гейты,подтверждение,сохранение,значениекнопки,разницанастройкиидействующегоудержания,порогискорости/уклона.

**Наличие оборудования:** HSA/HDC/AutoHold не считать подтверждёнными лишь по SDK.

**Путь управления:** Настроечные/soft-switch маршруты есть статически; физическое поведение не раскрыто.

**Следующий разбор:** Проследитьsetter→команду→PA инеинтерпретироватьнастройкуOn как активноеудержание.

**Недостающая опора:** Реализация штатныхкнопок и PA/statusсовместносостояниемтормоза.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/9`.

## CH-STEERING — Руль: угол, скорость и усилие

**Установлено в имеющихся источниках:**

- Raw SteerWhlSnsrAg31515 иAgSpd31516 вэкспортахсодержат и малые значения, и 65287…65535; getterвозвращаетintбезпересчёта. Этооснованиепроверитьsigned16/scale, анепоказыватьдесяткитысячградусов.
- Qf31519 наблюдался 1 и 3; точнаяпривязкаqualityenumещёнепроверена.
- CB_SteeringWheelAssistLevel_Settings иCB_STEER_SteerAsscLvl задают настройкуусилия; ониненаправляютколёса нацелевойугол.
- Точный Sensor применяет угол raw/1024 и угловую скорость raw/128; sign-extension в этом методе и rawgetter нет. Это оставляет открытым согласование loggedunsigned16 с ожидаемым знаком и контрактом единицы.

**Остаётся неизвестным:**

- Физическая единица и signed-представление угла/скорости, нулевая точка, калибровка, quality и актуаторные EPS команды. Формулы SDK уже извлечены.

**Наличие оборудования:** Изменяющиеся угловые каналы есть; runtime-оборудование усилителя через ECU не идентифицировано.

**Путь управления:** READ измерения и настройки усилия найдены; управление направлением автомобиля не установлено.

**Следующий разбор:** Подтвердитьsigned-преобразованиеиисключитьinvalidдоcast; сопоставитьQf,неугадыватьпомаксимумам.

**Недостающая опора:** Sensor→SteerWhlSnsr implementation и потребитель штатнойприборки/парковки.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/10`.

## CH-SUSPENSION — Подвеска и регулировка высоты/демпфирования

**Установлено в имеющихся источниках:**

- ВSDKширокообъявлены датчикивысоты/quality,ошибки,режимыдемпфирования иCB_DM_Suspension_Settings32886/PA33452.
- Большинство rawканалов вэкспортах-1,двавысотныхканала 0; ниэто,ниширокийсписокSDKнедоказываютналичиерегулируемойподвески.

**Остаётся неизвестным:**

- Естьливданнойкомплектацииуправляемыеактуаторы,типконтроллера,калибровки,единицыигейты.

**Наличие оборудования:** Наличие управляемой подвески не установлено.

**Путь управления:** Только статика SDK/PA; runtime-маршрут не подтверждён.

**Следующий разбор:** Сопоставитьvehicle-typeветки иconfig-поля,затемискатьфактическийECU; неопробоватьотсутствующееоборудование.

**Недостающая опора:** ECU/комплектацияконкретногомашиныиздоступныхconfigurationфайлов,позже диагностическийсписок.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/11`.

## CH-TPMS — Шины: давление, температура, свежесть и предупреждения

**Установлено в имеющихся источниках:**

- ДлякаждогоколесаестьP,T,MsgOldFlg,PTimeout,батарея,потерядаваления,предупреждения иTireFillgAssiPSts:40rawREADканаловдля 4 колёс.
- MsgOldFlgнаблюдался 0 и 1,приэтомчисленныеP/Tвкорпуседоступны. Поэтому наличиесохранённогодавлениянеозначаетсвежегоизмерения.
- CarSignalgetterP/Tнеприменяетмножительилиoffset. ЕдиницывысшегоSensorAPIдолжныисследоватьсяотдельно.
- Точные формулы Sensor уже установлены: давление raw×1.373, температура raw−50.
- Availability P/T зависит от config19>1,carMode0,usageMode{13,2,11}; после этих условий PTimeout1→error,иначеactive. MsgOldFlg непосредственно в этом методе не проверяется.

**Остаётся неизвестным:**

- Контракт физической единицы давления/температуры, полный invalid-policy, время свежести, привязка датчиков, пороги и точность предупреждений. Формулы и статический availability-gate уже установлены.

**Наличие оборудования:** Различающиеся измерения 4 колёс наблюдались; аппаратный инвентарь датчиков не раскрыт.

**Путь управления:** READ каналы подтверждены; команды обучения/конфигурации конкретных датчиков не установлены.

**Следующий разбор:** Проверить typed unit contract, обработку MsgOldFlg и invalid markers в штатном потребителе. Сопоставить Sensor availability с хранением последнего значения и обновлением в логах.

**Недостающая опора:** SensorimplementationP/T/MsgOldFlg; при необходимости штатныепоказанияс меткойвремени.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/12`.

## ADAS-CRUISE — Круиз: индикация, настройки и команды

**Установлено в имеющихся источниках:**

- Шестьприоритетныхкруизныхстатусовнеявляютсякомандами; прежнийdisassemblyдлянихотправляющегоserviceненашёл.
- ACCandTSR/HWAнастроечныеCBесть,ноонинеявляются MAIN/SET/RES/+/-.
- Сохранённыйcapabilityprobe публичныхSPEED_CONTROL APIвозвращалnotavailable/current255/supportednull; эторезультатконкретныхсессий.

**Остаётся неизвестным:**

- РеальныймаршруткнопокMAIN/SET/RES/+/-доADASконтроллера,условиягейтови подтверждение.

**Наличие оборудования:** Наблюдения индикации/PCAP подтверждены; полный ADAS аппаратный инвентарь неизвестен.

**Путь управления:** Индикация/настройки найдены; исполнительные команды круиза НЕ найдены.

**Следующий разбор:** Продолжитьнеразобранныенативныеветви/маршрутывпределахкорпуса,неповторятьужеотрицательныезаписи.

**Недостающая опора:** ОставшиесяVP/nativeвходы/кнопочныймодуль вимеющихсяархивах; точнаяECUтопологияпринехватке.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/13`.

## ADAS-LIMITER — Лимитер, ограничение предупреждения и скорость круиза

**Установлено в имеющихся источниках:**

- AdjSpdLimnSts31354 — READстатус. SDKобъявляет несколькоLIMITATION/RANGE функций, ноимянеустанавливаетограничениетягидвигателя.
- Четыреприоритетныхпубличныхcruise/limiterAPI влогахвозвращалиnotavailable; этоограничениеисследованныхточеквхода, анедоказательствоневозможностицелойсистемы.

**Остаётся неизвестным:**

- Установка/активациялимита, маршрутфизическихкнопок, overrideиусловияотмены, отделениеотпредупрежденияTSR.

**Наличие оборудования:** Штатная индикация из предыдущих исследований есть, ECU-топология неполная.

**Путь управления:** Исполнительный маршрут не установлен.

**Следующий разбор:** ПроследитьконкретныйобработчикпубличногоLIMITATION/RANGE и проверятьpresenceотдельно.

**Недостающая опора:** Недостающиймаршруткнопок/ADASвтекущихVP/QNXматериалах.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/14`.

## ADAS-LANE — Полоса: LKA/ELKA/EMA/HWA и режимы предупреждений

**Установлено в имеющихся источниках:**

- ЕстьотдельныеCBвключенияLKA,ELKA,EMA,HWA,режимаLKA итипаwarning. EnumIntvAndWarnModForLaneKeepAidSts различаетInactive0,InterventionAndWarning1,InterventionOnly2,WarningOnly3.
- СырыестатусыAsyLaneKeepAidSts иAsyEmgy* наблюдались; наблюдениефлага 0/1 само посебе не подтверждаеткомплектациюили фактическоевмешательство.

**Остаётся неизвестным:**

- ПривязканастроеккфактическиустановленнымADAS,гейты,геометрия,quality,режимыотказаиприоритетручногоуправления.

**Наличие оборудования:** Комплектация каждого ассистента не установлена по именам SDK.

**Путь управления:** Настроечные CB/PA + READ статусы; непосредственная команда угла/момента EPS не установлена.

**Следующий разбор:** ПроверитьвесьbuildFunctionsADAS и разделитьavailable/enabled/active/fault.

**Недостающая опора:** ADASimplementation,vehicleconfigsи реальныеPAavailability;

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/15`.

## ADAS-COLLISION — Предупреждение столкновения и AEB/CMS

**Установлено в имеющихся источниках:**

- РаздельныеCllsnMtgtnOnoffSts/FctSts/FaultSts/WarnSts/ForwardWarn показывают: включено,функционирует,ошибкаипредупреждениенеодинbool.
- CB_ASY_CMS32779 иCB_ASY_CMS_Warning32780 — отдельныекоманды; warning используетCllsnAidSnvtySeldSts,содержащийinactive0,off1,on2,medium3,high4 иreserved5…7.
- CllsnAidSnvtySeldSts28733 — BYTESproto сPEN,нескалярныйHALint.

**Остаётся неизвестным:**

- ФизическаяподдержкаAEB,порогиактивации,поведениенаотказе/засорении,совместимостьпрофилейPEN.

**Наличие оборудования:** Флаг в SDK не подтверждает оборудование/калибровку конкретной машины.

**Путь управления:** Настройки и индикация подтверждены статически; исполнительный маршрут тормозного момента не установлен.

**Следующий разбор:** Связатьвалидаторы ссостояниями; зарезервированныезначенияневыдаватьзапользовательскиережимы.

**Недостающая опора:** ADASimplementation+PAavailability+конфигурациянаборaрадар/камера.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/16`.

## ADAS-SIDE — Боковые/задние ассистенты: LCA/RCW/RCTA/DOW

**Установлено в имеющихся источниках:**

- LCA,предупреждениеLCA,RCW,RCTA,DOW,ELOW имеютраздельныеCBAPI; RCTAлевая/праваяиндикации — READ.
- ДляRCTAобъявленыотдельныеграфика/громкость/activation, следовательнопоказкартинки ифункцияассистентанесовпадают.

**Остаётся неизвестным:**

- Расположение/количестводатчиков,наличиекаждойфункции,гейтыдверей/передачи/скорости,фактическоеторможение.

**Наличие оборудования:** Декларации общего SDK, без полной проверки комплектации.

**Путь управления:** Настроечные CB и READ индикация; исполнительные маршруты не раскрыты.

**Следующий разбор:** Отделитьзаднее предупреждение/перекрёстныйтрафик/показграфики и сопоставитьсыройпротокол.

**Недостающая опора:** PAavailability иконфигурацияADAS/Passap; припробелахECU-список.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/17`.

## ADAS-TSR — TSR, превышение скорости и настройка смещения

**Установлено в имеющихся источниках:**

- CB_ASY_SpeedCompensation32773 используетOffsForSpdWarnSetgReq:Inactive0,Plus1,Minus2. Этошаговаязаявка, анеабсолютное+1/-2 км/ч.
- ДругойenumOffsForSpdWarnSetgSts описываетMinus10,Minus5,Zero,Plus5,Plus10; привязкастатусакконкретномуPAпокаотдельнаяпроверка.
- TSRwarningнастройкинеэквивалентнылимитеруилицелевойскоростикруиза.

**Остаётся неизвестным:**

- Полнаяцепочкаoffsetcommand→PA,единицы/проценты,границы,привязкакдорожнымзнакам,наличиефункцийвкомплектации.

**Наличие оборудования:** TSR/ACC включение по флагам SDK — ненадёжная идентификация оборудования.

**Путь управления:** Настроечные API найдены; использовать как команду скорости движения нельзя.

**Следующий разбор:** Извлечьstep/absolute/value/percentветки;недопускатьподменыпространствкоманд.

**Недостающая опора:** ADASbuildFunctions/SPEED_LIMIT_WARNING_OFFSET иживыеPAизимеющихсясессий.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/18`.

## ADAS-DRIVER — Контроль водителя/усталости

**Установлено в имеющихся источниках:**

- SDKсодержитDPSиDPS_Reminder какраздельныенастройки; publicSENSOR_TYPE_TIREDNESS_DRIVING_STATE —декларациянаблюдения,анедоказательствоDMS-камеры.

**Остаётся неизвестным:**

- Алгоритм/типдатчика,камераилианализруления,privacy/data path,гейты,точныесостоянияиоборудование.

**Наличие оборудования:** Наличие DMS-камеры в этой комплектации не подтверждено

**Путь управления:** Только декларации/настройки без полного runtime-подтверждения.

**Следующий разбор:** НайтиреальныезависимостиDPS иналичиеаппаратныхисточников, неравнятьназваниесDK ккамере.

**Недостающая опора:** ECU/config/штатныйпотребительDPS; ужесуществующиедампыпрежде новыхсъёмок.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/19`.

## ADAS-PERCEPTION — Геометрия полос, объекты и качество восприятия

**Установлено в имеющихся источниках:**

- Есть 32READкоэффициентаAsyLine*PrmA/B/C/D; во всехсохранённыхпримерахониповторяют 3000/1600/1000/1000. Такиецифрынельзяиспользоватькакготовуюгеометриюполосбезединиц/validity.
- Есть 9 полейAsyObjForBigData0:скорости,дистанции,confidence,TTC,type; вэкспортахтолько-1.
- ПриборочныйAsyObjType — отдельныйканал; этонедоказательство полноценногоobjectlist.

**Остаётся неизвестным:**

- Формулаполинома,координаты,scale/offset,validrange,обновление/качество,association объектов.

**Наличие оборудования:** Широкий SDK даёт поля; доступность полезных геометрических данных не подтверждена.

**Путь управления:** READ визуализационная/телеметрическая часть; интерфейсы восприятия ECU не декодированы.

**Следующий разбор:** Искатьобработкуinvalidи единицдокорреляциисцен; повторяющиесязначениянесчитатьреальнымдвижением.

**Недостающая опора:** Нативныйдекодерполос/объектови штатныйrendererприборки/QNX;

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/20`.

## CH-PEB — Экстренное торможение при парковке

**Установлено в имеющихся источниках:**

- ПубличноеSETTING_FUNC_PBC_EPB_SWITCH0x20061000 вVehicle.buildFunctions реальносвязано сCB_PEB_PrkgEmgBrkSysSwt32980 иPA33562. ЕгоимяEPBнеозначаеткомандустояночномутормозу.
- ДляrawPrkgEmgBrkSysSwt28695 естьINT32RW,а PrkgEmgyBrkSysSts28996 —READ; настройкасистемыипроисходящееторможениенеодинаковы.
- SDKenumInhbCdnOfPrkgEmgyBrkперечисляетусловиянедоступности(двери,ремень,ESC,некорректнаяскорость/передача,дождь,зеркало). Привязкаenumкконкретномуполюещёнепроверена.

**Остаётся неизвестным:**

- НаличиеPEB,маршрутinhibitionкодa,пороги,актуаторнаячасть,реакциянаневалидныевходы.

**Наличие оборудования:** PEB аппаратно/функционально не подтверждён наличием setting API.

**Путь управления:** API связан с настройкой PEB; доказательства прямого применения/отпускания EPB через него нет

**Следующий разбор:** Найтикодпричинзапрета иeffectivenessнаблюдение, сначалаoffline.

**Недостающая опора:** Passapimplementation/state machine иPAavailabilityизкорпуса.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/21`.

## SRS-BELTS — Ремни: замки, оснащённость и комфортное электронное подтягивание

**Установлено в имеющихся источниках:**

- НакаждоеместозамокпредставленнесколькимиполямиBltLockSt1/BltLockSts,длязаднихместотдельноBltLockEquid. Нельзяподменятьналичиедатчиказначениемзамка.
- SDKтакжеописываеттретийряд — этонедоказываеттретийрядвKX11.
- CB_ElecSeatbelt_Driver/Passenger относятсяккомфортнойнастройке; это недоказанные командыпиропреднатяжителей.

**Остаётся неизвестным:**

- Точноесопоставлениеquality/state,наличиеэлектроприводаремней,логикаwarning,разделениекомфортнойифатальнойзащиты.

**Наличие оборудования:** Комплектация сидений/датчиков не доказана полным словарём SDK.

**Путь управления:** READ ремни и настройки комфорта; пиротехнический исполнительный маршрут не установлен.

**Следующий разбор:** Сопоставитьequipped/status/valueипубличныйsensor;невыводитькомплектациюизнеиспользуемыхдеклараций.

**Недостающая опора:** SensorSAFE_BELT implementation иконфигурацияseating/ECU.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/22`.

## SRS-IMPACT — SRS: аварийное состояние, записи удара и пиротехника

**Установлено в имеющихся источниках:**

- CrashStsSafe содержитотдельныеChks,Cntr,Sts; RecOfImpct содержитнаправленияудара,Roll,DeltaV,orientation — READсигналы.
- НазванияCrashFront/Side/Ped неявляютсякомандамиактивацииподушек. Нативногоисполнительногомаршрутапиротехникиневыявлено.

**Остаётся неизвестным:**

- ПолныйSRSблок,событиясрабатывания,EDR/диагностика,качествоизапреты,оборудованиеикалибровки.

**Наличие оборудования:** Наличие базовой пассивной защиты пользовательского авто не равно идентификации каждой подушки/датчика по SDK.

**Путь управления:** Видимая часть — READ телеметрия; алгоритмы и исполнение SRS не раскрыты.

**Следующий разбор:** Разделитьсостояниевреальномвремени/историческуюзапись/checksum/counter;невыдаватьобщийSDKзаEDRдамп.

**Недостающая опора:** ECU-идентификацияSRS иописаниядиагностическихданных,еслиихнетвдампах.

[Источники и точные указатели](powertrain_chassis/systems.json) → `/systems/23`.

## ARCH-001 — Android / AP и штатные сервисы IHU

**Установлено в имеющихся источниках:**

- Наблюдён Android endpoint 198.18.34.15 и штатный route; normal LA config загружает Android guest.

**Остаётся неизвестным:**

- Полная цепочка текущих process→shared library→firmware version для всех сервисов.
- Отдельность Android как физического ECU не установлена.

**Наличие оборудования:** software_domain_confirmed

**Путь управления:** software_domain_and_network_observed

**Следующий разбор:** Сопоставить SHA загруженных библиотек, build props и exact SDK classloader с уже извлечённой картой; не подменять версию firmware версией Natro.

**Недостающая опора:** ['Имеющиеся HU-Route proc maps/native maps и identity-build-power.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/0`.

## ARCH-002 — QNX host и гипервизор

**Установлено в имеющихся источниках:**

- startup запускает VMM/lifecycle, создаёт guest peer; vsomeip unicast .34.2.

**Остаётся неизвестным:**

- Все границы виртуальных устройств, изоляции и рестарта доменов.
- Текущее исполнение каждой условной ветви startup.

**Наличие оборудования:** software_domain_confirmed

**Путь управления:** host_and_guest_configuration_confirmed

**Следующий разбор:** Соединить VMM events из существующих логов с выбранным config и состояниями QNX/Android MCU relay.

**Недостающая опора:** ['QNX startup/SLM, linux configs, старые DIM Slog и boot window уже доступны.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/1`.

## ARCH-003 — LA / LV: обычная работа и Failsafe guest

**Установлено в имеющихся источниках:**

- Оба config объявляют system la и один la_to_host; LV Failsafe, переключение GPIO154.

**Остаётся неизвестным:**

- Все условия перехода/восстановления LV и фактическая boot epoch каждого снимка.

**Наличие оборудования:** alternative_guest_configs_not_evidence_of_two_ecus

**Путь управления:** alternative_guest_configs_confirmed

**Следующий разбор:** Привязать маркеры upgrade mode и fs_update_client к logs; исключить ошибочный поиск production VP внутри LV только по имени.

**Недостающая опора:** ['Имеющиеся boot window / update archive.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/2`.

## ARCH-004 — VP peer 198.18.34.1:50500

**Установлено в имеющихся источниках:**

- Штатный route называет .1 VP; двусторонний UDP подтверждён старым PCAP; живой сосед наблюдался.

**Остаётся неизвестным:**

- Физический чип/плата, активный образ, процесс-получатель и firmware ownership.
- Полный набор доступных из AP штатных команд.

**Наличие оборудования:** peer_observed_identity_unknown

**Путь управления:** network_peer_observed_receiver_identity_unknown

**Следующий разбор:** Проследить incoming PA_McuLog и PA_VP_Version до IPCP group/method и сравнить наблюдённую MCU версию с имеющимися образами.

**Недостающая опора:** ['Имеющиеся locator/update manifests, PA_McuLog путь и version interfaces.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/3`.

## ARCH-005 — Сетевой мост QNX и виртуальный Ethernet

**Установлено в имеющихся источниках:**

- vp0 связан с guest la; bridge добавляет vp0 и emac0.

**Остаётся неизвестным:**

- Все аппаратные порты/VLAN и конкретный switch peer за emac0.

**Наличие оборудования:** virtual_interfaces_confirmed

**Путь управления:** configured_bridge_plus_android_routes

**Следующий разбор:** Восстановить таблицу physical/virtual interfaces и VLAN из DTB/sysfs; не называть vp0 физическим VP.

**Недостающая опора:** ['Имеющиеся DTB, network dumps, HU-Route sysfs.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/4`.

## ARCH-006 — Маршрут ASDM и автомобильные VLAN

**Установлено в имеющихся источниках:**

- Route ASDM .36.1→eth0.5, vehicle /16→eth0.11; живые route совпадают.

**Остаётся неизвестным:**

- Физический ASDM identity и соответствие прямого адреса software group ASDM в VP потоке.

**Наличие оборудования:** configured_endpoint_not_ecu_identity

**Путь управления:** route_observed_no_control_proven

**Следующий разбор:** Сопоставить трафик каждого VLAN с source/destination и штатными config, сохраняя distinction между прямым ECU и пересылаемой группой.

**Недостающая опора:** ['Существующие Ethernet PCAP; source/switch mapping.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/5`.

## ARCH-007 — TCAM и маршрут внешней связи

**Установлено в имеющихся источниках:**

- Stock config называет .32.17 TCAM и задаёт запасной gateway.

**Остаётся неизвестным:**

- Версия, hardware identity, режимы связи/сна и command consumers TCAM.

**Наличие оборудования:** hardware_identity_unconfirmed

**Путь управления:** configured_route_only

**Следующий разбор:** Разобрать TCAM штатный клиент и различить route existence, modem state и фактическую сеть; не считать PERMANENT ARP живым ответом ECU.

**Недостающая опора:** ['Имеющиеся native TCAM service/SDK и network snapshots.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/6`.

## ARCH-008 — MCU / VIP firmware на диске

**Установлено в имеющихся источниках:**

- MPC5746C ELF: 86 DWARF units, FreeRTOS/CAN/SPI/board power, несколько reference board вариантов.

**Остаётся неизвестным:**

- Загружен ли именно этот image на автомобильный MCU.
- Совпадает ли он с production MCU_Version 20.24.10.23024.41141.

**Наличие оборудования:** firmware_file_only

**Путь управления:** binary_contents_proven_runtime_not_proven

**Следующий разбор:** Офлайн разобрать process_get_fw_version и цепочку выбора image в flasher/updater; сравнить с MCU relay, не запускать flasher.

**Недостающая опора:** ['Уже имеющиеся updater/manifest, загрузочные версии и version handler.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/7`.

## ARCH-009 — QNX CAN resource manager через SPI9

**Установлено в имеющихся источниках:**

- can_driver связывает SPI client с CAN device; canflasher содержит reference targets.

**Остаётся неизвестным:**

- Запуск can_driver в production и физическая шина за ним.

**Наличие оборудования:** device_file_not_runtime_hardware_proof

**Путь управления:** static_driver_only

**Следующий разбор:** По всем имеющимся процессным/загрузочным логам найти исполнение driver и аргументы; не выводить наличие канала по файлу.

**Недостающая опора:** ['Имеющиеся startup/SLM и QNX process/device inventory.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/8`.

## ARCH-010 — Android SocketCAN / FlexRay доступность

**Установлено в имеющихся источниках:**

- Generic init.qti.can conditional script существует; два Bus-Capture не содержат raw bus или action windows.

**Остаётся неизвестным:**

- Реальные физические контроллеры/доступ к Chassis CAN из AP.

**Наличие оборудования:** unsupported_by_these_capture_artifacts

**Путь управления:** no_raw_bus_route_proven

**Следующий разбор:** Сопоставить raw sysfs/kernel/netdev evidence и conditional script; не принимать вывод collector о распиновке как доказательство.

**Недостающая опора:** ['Bus-Capture devices-sysfs-devicetree/network-can-flexray уже доступны.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/9`.

## ARCH-011 — IPCL SPI4 обмен IHU

**Установлено в имеющихся источниках:**

- Ранее подтверждены payload125/89fields и ограниченный исходящий OA; startup запускает cluster_controller.

**Остаётся неизвестным:**

- Аппаратный peer SPI4 и связь с SPI9/MCU image.
- Все transitions/frame lifecycle вне разобранных сообщений.

**Наличие оборудования:** software_route_confirmed_peer_hardware_unknown

**Путь управления:** specific_status_and_limited_output_route

**Следующий разбор:** Проверить frame signatures и роли peer, не отождествлять два SPI по общей теме vehicle data.

**Недостающая опора:** ['Имеющиеся cluster ELF/DWARF и MCU image.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/10`.

## ARCH-012 — Общий диагностический ECU inventory

**Установлено в имеющихся источниках:**

- SDK ECU_TYPE и DoIP targets доступны как статические декларации.

**Остаётся неизвестным:**

- Полный список физических ECU с diagnostic address/HW/SW/version.

**Наличие оборудования:** physical_inventory_incomplete

**Путь управления:** no_vehicle_wide_inventory

**Следующий разбор:** По существующим архивам искать прямые идентификационные ответы; хранить отдельно от списка процессов и software groups.

**Недостающая опора:** ['Сначала результаты IPartInfos/diagnostic services в старых логах; ECU identity report только если отсутствует.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/11`.

## ARCH-013 — QNX DoIP / UDS gateways

**Установлено в имеющихся источниках:**

- DoIP config имеет physical0xE000+6functional; UDS core иной функциональный subset; SLM запускает UDS_SOMEIP_Gateway.

**Остаётся неизвестным:**

- Полная routing/allowlist, active protocol branch и доступ к другим ECU.

**Наличие оборудования:** software_components_confirmed

**Путь управления:** configured_gateway_not_verified_vehicle_control

**Следующий разбор:** Статически проследить config parser→target dispatcher и runtime init evidence; не считать functional address отдельным ECU.

**Недостающая опора:** ['Реальные gateway ELF, ini, vsomeip и старые logs уже имеются.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/12`.

## ARCH-014 — Информация о деталях и сборках IHU

**Установлено в имеющихся источниках:**

- IPartInfos объявляет core/delivery, AP/VP module/local config, post build numbers.

**Остаётся неизвестным:**

- Реализация источника каждого поля и непустые текущие ответы.

**Наличие оборудования:** not_a_physical_inventory

**Путь управления:** sdk_interface_only

**Следующий разбор:** Разобрать PartInfos.getPartInfoString до caller/provider; искать уже полученные значения перед новым запросом.

**Недостающая опора:** ['Exact adaptapi implementation и старые service dumps.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/13`.

## ARCH-015 — DTC / diagnostic monitor

**Установлено в имеющихся источниках:**

- SDK IDtcManager/monitor, QNX DiagServices и диагностические OA существуют.

**Остаётся неизвестным:**

- Какие физические домены покрывает каждый список DTC, code mapping/status/time и очистка.

**Наличие оборудования:** diagnostic_software_present

**Путь управления:** several_partial_diagnostic_paths

**Следующий разбор:** Составить владельцев DTC и read/clear semantics; не объединять разные списки как весь автомобиль.

**Недостающая опора:** ['Имеющиеся DtcManager implementation, QNX ELF и MCU DIAG relay.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/14`.

## ARCH-016 — Идентичность версий AP/VP/QNX/MCU

**Установлено в имеющихся источниках:**

- QNX version text есть; AP_Version.properties пуст; dump VpVersion использует PA_ErrorReport; MCU relay version повторена дважды.

**Остаётся неизвестным:**

- Полная схема version fields и их физические owners.

**Наличие оборудования:** multiple_software_versions_observed

**Путь управления:** mixed_sources_need_normalization

**Следующий разбор:** Нормализовать отдельные поля и provenance; не выбирать случайный VpVersion label как версию ECU.

**Недостающая опора:** ['Все перечисленные источники уже в архиве.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/15`.

## ARCH-017 — Питание IHU / режимы AP, IM, EM

**Установлено в имеющихся источниках:**

- Power SDK задаёт раздельные states; MCU relay показывает AP0→1→2, IM, EM.

**Остаётся неизвестным:**

- Все причины/guard transitions, отключение по энергии и связь с automotive use mode.

**Наличие оборудования:** live_logical_states_observed

**Путь управления:** observed_startup_states_static_command_wrappers

**Следующий разбор:** Восстановить state machines по handler/enum; сопоставить два boot histories и их embedded counters.

**Недостающая опора:** ['Реальные PowerSomeIPService/proxy implementation и уже сохранённые startup logs.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/16`.

## ARCH-018 — Сон, пробуждение и сохранение контекста

**Установлено в имеющихся источниках:**

- QNX/Android readiness раздельно логируется; SDK содержит partial/welcome/modem sleep статусы.

**Остаётся неизвестным:**

- Полный сон→wake цикл, причины wake, отложенная выгрузка логов и сохранность подписок/данных.

**Наличие оборудования:** sleep_capability_not_fully_observed

**Путь управления:** startup_subset_only

**Следующий разбор:** Классифицировать уже имеющиеся epochs как cold boot/wake/reconnect по реальным markers; не делать вывод по одному ignition.

**Недостающая опора:** ['Сначала boot/live window и DLT архивы; новый цикл только если нужный переход не записан.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/17`.

## ARCH-019 — Перезапуски, watchdog и восстановление сервисов

**Установлено в имеющихся источниках:**

- VMM lifecycle, Binder/VHAL reconnect и QNX health components существуют.

**Остаётся неизвестным:**

- Полная причина→граница reset→восстановление подписок/экранов/сети.

**Наличие оборудования:** software_lifecycle_confirmed

**Путь управления:** software_recovery_hooks_found

**Следующий разбор:** Связать watchdog/reconnect события в пределах единой эпохи; различать рестарт APK, Android guest, QNX и MCU.

**Недостающая опора:** ['Существующие crash/boot/MCU logs.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/18`.

## ARCH-020 — Обновления / разделы / rollback

**Установлено в имеющихся источниках:**

- 23 image manifest записи; LA/LV alternative; update script не проверяет swdl exit перед echo success.

**Остаётся неизвестным:**

- Production updater chain, фактические active banks, подпись/rollback/recovery outcome.

**Наличие оборудования:** partition_artifacts_not_ecus

**Путь управления:** update_mechanism_static_partial

**Следующий разбор:** Сравнить consumer manifest и result status/active slot; не использовать generic script как инструкцию обновления.

**Недостающая опора:** ['Имеющиеся update package evidence, client binaries, manifest, postboot versions.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/19`.

## ARCH-021 — Android permissions / Binder / VHAL access

**Установлено в имеющихся источниках:**

- setCarServiceHelper проверяет UID1000; VHAL access и static write route отдельны.

**Остаётся неизвестным:**

- Разрешения каждого конечного API и доступ Natro в конкретной runtime configuration.

**Наличие оборудования:** software_policy_layer

**Путь управления:** one_check_proven_full_chain_unknown

**Следующий разбор:** Составить permission цепочку по конкретному API, не объявлять все методы открытыми или закрытыми по одному check.

**Недостающая опора:** ['Exact manifests, service bytecode, package dumps уже имеются.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/20`.

## ARCH-022 — QNX policy / виртуальная изоляция / диагностический доступ

**Установлено в имеющихся источниках:**

- Startup и VM config имеют security/ASLR/virtual device branches; gateway config задаёт собственные параметры.

**Остаётся неизвестным:**

- Какая policy активна на каждой epoch и какие проверки реально применяет конечный handler.

**Наличие оборудования:** software_policy_layer

**Путь управления:** configuration_only_not_access_bypass

**Следующий разбор:** Связать branch conditions с live state и service permissions; не выполнять изменение policy или security routines.

**Недостающая опора:** ['Имеющиеся platform variables, QNX policy manifests и runtime logs.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/21`.

## ARCH-023 — MCU relay: происхождение, семантика и время

**Установлено в имеющихся источниках:**

- 1307 строк PA_McuLog, одна версия в двух boot histories, 23 software log groups.

**Остаётся неизвестным:**

- Источник до peer .1 и его hardware owner; связь конкретных 1307 строк двух boot history с восстановленным UDP→Java трактом; единицы embedded counter; фильтры/потеря сообщений.

**Наличие оборудования:** logical_mcu_source_observed

**Путь управления:** live_relay_observed_origin_mapping_pending

**Следующий разбор:** Проследить property→wire→peer; разделить embedded timestamp/counter и Android receipt; не считать McuLog_Panic всеобщим crash signal.

**Недостающая опора:** ['VHAL incoming converter и PA_McuLog service implementation уже доступны.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/22`.

## ARCH-024 — Network management CAN / FlexRay / LIN

**Установлено в имеющихся источниках:**

- MCU DIAG relay показывает ComM/NM и FrMod/CanMod/LinMod состояния.

**Остаётся неизвестным:**

- Физическая топология, каждый controller/channel, state enum meanings и доступ из AP.

**Наличие оборудования:** physical_bus_assignment_unknown

**Путь управления:** network_management_status_only

**Следующий разбор:** Построить наблюдённые значения/переходы и связать с callback источником; не выводить CAN IDs, распиновку или активные каналы из имён.

**Недостающая опора:** ['Имеющиеся MCU relays и конфиги; по каждому bus затем production firmware mapping.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/23`.

## ARCH-025 — Наблюдаемость и полнота корпуса

**Установлено в имеющихся источниках:**

- Дополнительные HU-Route/Bus-Capture несут version/boot data, хотя raw bus нет; прежние 64 источника не исчерпывают все материалы.

**Остаётся неизвестным:**

- Окончательный полный inventory всех Library/прошлых исследовательских архивов.

**Наличие оборудования:** not_applicable

**Путь управления:** evidence_inventory_expanded

**Следующий разбор:** Не просить повторный замер до учёта содержимого уже сохранённых вложений; хранить глубину разбора по каждому архиву.

**Недостающая опора:** ['Уже материализованные дополнительные архивы и дальнейшая точная инвентаризация.']

[Источники и точные указатели](architecture_domains/systems.json) → `/systems/24`.

## PLAT_POWER — Питание, сон, пробуждение и режимы головной системы

**Установлено в имеющихся источниках:**

- Живой powersomeip Binder имеет IECarXCarPower, Android PowerSomeIPService работает под UID1000; одновременно живёт native powersomeip HAL.
- apStatusChange управляет mute, сохраняет lastAPStatus и выключает/восстанавливает Wi-Fi/Bluetooth по wifi_pre_state/bt_pre_state. Это отдельный штатный владелец жизненного цикла связности, помимо Natro/Helper.
- PowerSomeIPHALCallback.ScPwIVIMngrReqState(B) отбрасывает повтор requestType, обращается к IIplm.ReleaseResourceGroup, затем PowerManager.onQNXRequest(B). Это доказанная software-связь запроса QNX с Android power path, не команда двигателя.

**Остаётся неизвестным:**

- Полная таблица AP/IM/EM/IPLM и допустимых переходов; путь в изменённом Android PowerManager; тайминги сна/пробуждения и их связь с конкретными сбоями ANCS/музыки; когда питание снимается физически.

**Наличие оборудования:** Android service, native process and Binder observed; controller/ECU power ownership beyond this software boundary unresolved.

**Путь управления:** STATIC_IMPLEMENTATION + RUNTIME_SERVICE_PRESENT; no new power action performed; engine control not established.

**Следующий разбор:** Раскрыть access$-переходы PowerSomeIPServiceImpl, таблицы callback IDs и сопоставить с PowerManager.onQNXRequest и PA_Power_Res; затем связать с уже сохранёнными boot-логами.

**Недостающая опора:** ['Сначала существующие Android services/framework power implementations и boot/suspend логи; при недостатке — синхронная запись штатного сна/пробуждения без принудительного сброса Bluetooth.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/0`.

## PLAT_DISPLAY — Дисплеи, питание PSD, яркость и день/ночь

**Установлено в имеющихся источниках:**

- Runtime подтверждает четыре логических display: 0=1920×720/60Hz; 1=1920×720/60Hz; 2=1920×1080/30Hz; 3=1920×720/60Hz. Имена HDMI не устанавливают физическую роль каждого выхода.
- EcarxMultidisplayService.onBind возвращает null; onCreate публикует Binder ecarx_multidisplay. Путь через bindService сам по себе не даёт этого интерфейса.
- setPsdDisplayOnAndOff допускает 2=on и 1=off, направляет их в CB_Power_PSDStatus. Getter manager при отсутствии сервиса/RemoteException возвращает 0 INVALID. Это отдельный PSD power path; не обобщать на HUD/DIM.
- IECarXCarPower отдельно содержит CSD/PSD brightness, day/night/theme/screensaver параметры; общий метод display on/off AdaptAPI не описывает весь этот набор.

**Остаётся неизвестным:**

- PSD⇄физический дисплей в каждой конфигурации; runtime зоны яркости и шкалы; ограничения сна/передачи; pixel-owner штатных крыльев; независимый эффект power и screensaver.

**Наличие оборудования:** Four logical display devices observed; physical mapping of every output and optional PSD variants incomplete.

**Путь управления:** PSD setter statically traced to CB; display enumeration observed; physical execution and full backlight mapping OPEN.

**Следующий разбор:** Связать onPsdScreenOnOff/onPA_Power_Res с native PSD HAL и carconfig; изучить brightness setters и отдельные PA feedback.

**Недостающая опора:** ['Сначала существующие multidisplay callbacks/config и display snapshots; только затем видео с синхронным состоянием конкретного физического экрана.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/1`.

## PLAT_AUDIO — Аудиотракт, источники, микширование и автомобильные предупреждения

**Установлено в имеющихся источниках:**

- Живой ecarx_audio_service и EcarxAudioService сосуществуют с AudioFlinger/audio policy и native audiocontrol HAL. Приложение плеера не является единственным владельцем звука.
- EcarxAudioManager разделяет ENT/NAVI/BEEP каналы 0/1/2; отдельные mixSource NAVI, HFT, VR, eCall, duckMasterAudioVolume/Siri, PDCWarning и DIMSoundWarningLevel. Числа — SDK namespace, не CAN.
- В общем API есть EQ, balance/fader, speed-volume, Bose/Harman/ClariFi и isInternalAmplifier. Наличие таких символов не подтверждает каждую аудиокомплектацию.

**Остаётся неизвестным:**

- Физические каналы усилителя/динамиков, codec routing, permission enforcement и mix priority; кто удерживает mute после boot/call/PDC; фактическая поддержка DSP опций.

**Наличие оборудования:** Audio service stack observed; exact amplifier/DSP equipment and channel topology not established.

**Путь управления:** SDK setters and Binder observed; full service/native/audio hardware chain not yet traced.

**Следующий разбор:** Найти реализацию EcarxAudioService и источник audio_policy; связать API channels/mix with focus и native audiocontrol, не отключая предупреждения.

**Недостающая опора:** ['Сначала имеющиеся audio libraries/service APK; при пробеле — audio_policy конфигурация и снимки audio focus/routing во время штатных переходов.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/2`.

## PLAT_RADIO — Радиоприёмник AM/FM/DAB и дорожные объявления

**Установлено в имеющихся источниках:**

- ts_radiomanager Binder IRadioServiceBase и broadcastradio существуют одновременно; живут vendor.ecarx.ts.radioservice@1.0 и vendor.ecarx.xma.broadcastradio@2.1.
- IRadioServiceBase имеет tuneToAmFm/tuneToDAB, seek/scan, source switch и отдельные TA/news/alarm/link параметры. MediaSession next/previous не описывает эту систему полностью.

**Остаётся неизвестным:**

- Какая ветвь является production backend; единицы частоты/диапазоны/регион; наличие DAB hardware; приоритеты и возврат после TA/eCall; доступ к native от обычного приложения.

**Наличие оборудования:** Radio software and native processes observed; DAB and tuner hardware options unverified.

**Путь управления:** SDK interface and runtime services present; production path and physical tuning unverified.

**Следующий разбор:** Связать radio service wrapper с двумя HAL и callback RadioInfo; разобрать единицы tuning и enum источника.

**Недостающая опора:** ['Сначала имеющиеся ecarx_radio_service.jar и radio/native dependencies; конфигурация региона/тюнера при её отсутствии.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/3`.

## PLAT_BLUETOOTH — Bluetooth: профильные соединения, BLE и сосуществование стеков

**Установлено в имеющихся источниках:**

- В одном runtime видны BluetoothManager/GATT, A2DPsink, AVRCPcontroller, HFPclient, PBAPclient и MAPclient плюс отдельный ECARX extension. Наличие общего Bluetooth ON не подтверждает готовность каждого профиля.
- ECARX extension содержит auto/passive connect, HFP auto-reject и PBAP sync API; ecarx.jar также содержит отдельный PSDBluetoothManager. Это разные интерфейсы, не автоматически один общий канал ANCS.
- В новом архиве есть ts-dm-foundation-lib с DeviceBluetoothProfileManager/BluetoothConnectManager и nFore config: MaxAclNum4; MaxDeviceNum2; MaxHfpNum/MaxAvpNum/MaxPbapNum2; LeScanEnable=true. MaxAclNum4 не является доказательством четырёх одновременно работающих ANCS/BLE каналов.

**Остаётся неизвестным:**

- Активный production owner nFore/Android/TS-DM и profile arbitration; реальное число BLE соединений; взаимоисключение CarPlay; взаимодействие с OEM power lifecycle и повторным сопряжением.

**Наличие оборудования:** Android Bluetooth HAL/profile services observed; software config is not measurement of simultaneous hardware capacity.

**Путь управления:** Several concrete software interfaces identified; complete connection state machine and ANCS ownership OPEN.

**Следующий разбор:** Проследить BluetoothConnectManager/DeviceBluetoothProfileManager до сервиса и профилей; отдельно сопоставить PowerSomeIP enable/disable с эпохами ANCS.

**Недостающая опора:** ['Сначала ts-dm-foundation/ts-platform/Bluetooth APK из уже полученного нового архива и прежние Natro logs; новое измерение только для оставшейся границы.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/4`.

## PLAT_PHONE — Обычная телефония, контакты, кнопки звонка и DIM

**Установлено в имеющихся источниках:**

- У XCBTPhone3 нет sharedUserId system; runtime работает под u0_a19. InCallServiceImpl exported=true защищён BIND_INCALL_SERVICE; BluetoothService работает в :remote. Приложение телефонии не тождественно системному Bluetooth service.
- Manifest содержит NoViewActivity с CALL и отдельные DIM receiver actions для outcall/answer/decline/answer-and-hold/answer-and-end/ringtone-mute; отдельный key receiver CALL/RCALL/MUTE. Это конкретные входы штатного UI, но ещё не готовый проверенный API Natro.
- EcarxDimMenuReceiver.onReceive вызывает UiCallManager: answerCall/answerAndHoldCall/answerAndEndCall, rejectCall/disconnectCall, mute и safePlaceCallInternal. InCallServiceImpl получает Android Telecom onCallAdded/onCallRemoved. Статически подтверждены выбранные участки цепи, полный lifecycle и доступ внешнего UID ещё не установлены.
- Оба архива launcher-phone содержат побайтно одинаковые выбранные phone APK и component reports; второе имя не означает новую версию phone firmware.

**Остаётся неизвестным:**

- Полный call/hold/swap/ringtone/audio route lifecycle; ограничения вызывающего UID и payload DIM broadcast; активный телефон при двух подключениях; приоритет вызова на HUD/приборке.

**Наличие оборудования:** Phone app and HFPclient/Telecom runtime services present; no call attempt or contact data inspected.

**Путь управления:** Manifest entry points plus selected receiver/service method references; physical call control not verified.

**Следующий разбор:** Продолжить EcarxDimMenuReceiver/ControlKeyBroadcastReceiver→call manager→Android Telecom/HFP и извлечь только контракт extras, permission checks и state conditions.

**Недостающая опора:** ['Сначала уже полученный XCBTPhone3 APK и существующие call-service state snapshots; звонки/контакты в новые материалы не нужны для первичного control-flow разбора.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/5`.

## PLAT_CARPLAY — CarPlay, проекция и взаимодействие с Bluetooth/Wi-Fi/звуком

**Установлено в имеющихся источниках:**

- Runtime имеет оба пакета com.ts.carplay и com.ts.carplay.app, CarPlayService и CarPlayRemoteService; ts-carplay-adapter.jar содержит отдельные adapters audio/video/Bluetooth/Wi-Fi/location.
- Audio adapter выделяет request/abandon focus и duck; Wi-Fi adapter start/stop SoftAP; Bluetooth adapter coexistence/auto-connect и classic profiles; location adapter получает gear, speed, GPGGA/GPRMC. Это интерфейсный состав, не подтверждение режима беспроводного подключения.

**Остаётся неизвестным:**

- USB/беспроводной режим конкретной комплектации; владелец focus/projection display; влияние на ANCS/HFP/Wi-Fi; разрешения car.permission.CARPLAY_APP и отказ после sleep.

**Наличие оборудования:** CarPlay processes/services observed; actual paired session and wireless hardware capability not established.

**Путь управления:** Adapter APIs and running service stack identified; full session graph OPEN.

**Следующий разбор:** Связать StateManager session state с focus owners, Wi-Fi AP и Bluetooth coexistence; проверить очистку после disconnect/suspend.

**Недостающая опора:** ['Сначала имеющиеся ts-platform-library и adapter/service code; только затем обезличенный session-state log если останется пробел.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/6`.

## PLAT_LOCATION — GNSS, положение, NMEA и автомобильные данные навигации

**Установлено в имеющихся источниках:**

- Runtime подтверждает location Binder и native vendor.ecarx.xma.gnss@1.0 process; init rc запускает GNSS HAL с группами system/gps/radio/inet.
- CarPlay location interface отдельно предоставляет GPGGA/GPRMC, скорость и передачу; координатная система выбирается ILocationAdapter.setMapCoordinateSystem. GPS и vehicle-position paths нельзя считать одним измерением.

**Остаётся неизвестным:**

- Первичный приёмник/антенна; native decoder; источники fused location/dead reckoning; шкалы и timestamp; WGS/GCJ преобразования и валидность quality flags.

**Наличие оборудования:** GNSS software process and Android location service observed; physical receiver and antenna linkage unresolved.

**Путь управления:** Observation interfaces identified; independent measurement quality and full raw-to-map route OPEN.

**Следующий разбор:** Сравнить источники ILocationAdapter с Android location и vendor GNSS callbacks; отделить ECU velocity, NMEA time и callback-arrival time.

**Недостающая опора:** ['Сначала имеющиеся GNSS native binary и config, adapter location implementation; при пробеле — обезличенные качество/timestamp/скорость без координат поездки.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/7`.

## PLAT_TCAM — TCAM: eCall/bCall/iCall, микрофон, удалённые функции и диагностика

**Установлено в имеющихся источниках:**

- Живой tcam Binder ITcamService, Android TcamService и native tcam HAL подтверждены одним runtime snapshot.
- ITcamService/TcamManager разделяют call state/type/support/callback mode, IHU microphone use, CarLocator support, RVDC/remote diagnostics, vehicle IP table и reset support. Это собственная subsystem chain, не обычный Bluetooth вызов.
- Общий интерфейс содержит также charging/light-show поля; они не доказывают наличие зарядки или такого режима на бензиновом KX11.

**Остаётся неизвестным:**

- Физический TCAM/SIM и региональный backend; какой вызов реально поддерживается; приоритет/микрофон/аудиовыход при eCall; lifecycle RVDC authorization; допустимый reset protocol.

**Наличие оборудования:** TCAM software service stack observed; exact physical module/region/backend and optional features unverified.

**Путь управления:** Signatures and runtime service presence; no emergency call, remote command or reset invoked.

**Следующий разбор:** Сопоставить get/isSupported API с параметрами carconfig и callback enum; определить границы обычной телефонии, audio mix и удалённого backend.

**Недостающая опора:** ['Сначала имеющиеся TCAM native interfaces и service APK, конфигурация региона/support; не нужны пробные экстренные звонки.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/8`.

## PLAT_NETWORK — Связность: Wi-Fi, Ethernet, модем и внутренние сервисы

**Установлено в имеющихся источниках:**

- Runtime одновременно регистрирует connectivity/ethernet/wifi и vendor connectivity process. Статический vsomeip.json задаёт Android .15, discovery peer .2, порт 30490 и приложения PowerSomeIP/PAS/AVM/USB-update/Diag/QnxSlog.
- IECarXCarPower имеет отдельный getModemStatus; TCAM интерфейс предоставляет external/master/slave IP и vehicle IP table. Названия адресов не устанавливают физическую топологию ECU.

**Остаётся неизвестным:**

- Полная L2/L3 topology/gateway ownership, modem/TCAM role, routes/firewall and resource arbitration; связь .1 VP с этими отдельными SOME/IP services.

**Наличие оборудования:** Several live OS/HAL networking services observed; SIM/modem physical ownership and complete routing unresolved.

**Путь управления:** Runtime services and local config confirmed; complete interdomain control route OPEN.

**Следующий разбор:** Сопоставить registrations/apps/ports с native client constructors и QNX configs; не объединять VP UDP50335/50500 с discovery30490.

**Недостающая опора:** ['Сначала уже собранные network/VM/QNX configs и native connectivity; новые network snapshots только по выявленному отсутствию конкретной привязки.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/9`.

## PLAT_UI — Штатный UI, жесты, шторка, launcher и оконные владельцы

**Установлено в имеющихся источниках:**

- Новый runtime точно фиксирует ecarx.notificationcenterui/.ControlBoardService, action ecarx.notificationcenterui.action.CONTROLLER_BOARD, UID1000; рядом есть GestureService и InputService. Это продвигает поиск от имени пакета к конкретным работающим компонентам.
- Также живут ECARX SystemUI plugin AppWatcher/UserData services, PartialService и MediaWindowStateService; механизм UI priority нельзя описать одним SystemUI пакетом.
- Runtime видит ecarx_daemon_OverlayDisplay#0…4. Имена слоёв и ServiceRecord не устанавливают владельца каждого пикселя крыльев/машинки и не доказывают безопасный disable-компонент.

**Остаётся неизвестным:**

- Кто принимает edge gesture; IPC от жеста к ControlBoard; другие обязанности ControlBoardService; parent/layerStack pixel attribution; точный обратимый suppress API.

**Наличие оборудования:** Named software components and layers observed; physical pixel ownership and complete gesture chain unresolved.

**Путь управления:** Runtime component anchor strengthened; no component disabled, window injected or priority changed.

**Следующий разбор:** Найти consumers action CONTROLLER_BOARD и bind/start цепь; проверить manifest/component permissions и дополнительные функции до выбора точки подавления.

**Недостающая опора:** ['Сначала поиск XCNotificationCenterUI/gesture/input APK в уже собранном корпусе и существующие window snapshots; если их нет — запросить только конкретные APK/config.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/10`.

## PLAT_UPDATE — Обновления, диагностика и права системных сервисов

**Установлено в имеющихся источниках:**

- Runtime имеет ota, dtcnl, usbupdate и QnxSlog Binder services; init запускает отдельные OTA/DTC/PKI native HAL. Это самостоятельные жизненные циклы, а не обычная установка APK.
- IOtaService имеет assignment/download/install consent/scheduled installation/resource group API и callbacks; IDtcnlService различает getDtcCodeStatus и setDtcCode. Название get/set не отменяет побочных эффектов диагностических/обновляющих действий.
- Manifest PowerSomeIPService и MultidisplayService имеют sharedUserId system и explicit exported=true; SettingsProvider экспортирует authority EcarxSettings. Это не доказательство допустимости вызова от Natro: runtime UID, Binder checks и SELinux остаются отдельными границами.

**Остаётся неизвестным:**

- Полный boot/update recovery graph; доверенные подписи и региональные constraints; диагностические DTC владельцы/значения; Binder permission checks/SELinux для каждого API; policy persistence через reboot/update.

**Наличие оборудования:** Update/diagnostics software services present; does not identify all physical ECU or supported firmware targets.

**Путь управления:** Interface/config/runtime presence established; update/routine/reset/write not executed or qualified.

**Следующий разбор:** Разделить software update targets, consent/status callbacks и необратимые операции; установить service-side enforcement без обхода прав.

**Недостающая опора:** ['Сначала имеющиеся OTA/DTC/PKI APK/native/config и metadata; приватные сертификаты/ключи не запрашивать и не публиковать.']

[Источники и точные указатели](android_platform/systems.json) → `/systems/11`.

## BASE-CAMERA — Камера 360 / AVM

**Установлено в имеющихся источниках:**

- Статическая внешняя ветвь и PAC callback разобраны; в прежнем корпусе config154=3, активный raw29021=1…5; значение 3 — только частный наблюдавшийся случай.

**Остаётся неизвестным:**

- Физический смысл каждого режима, поддержка комплектации, окончание переходов и независимая видимость.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Сопоставить имеющиеся PAC watchers, 29021/29043 и оконные события с временными метками.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/0`.

## BASE-PAS — Парковочные датчики, окно PAS и приоритет уведомлений

**Установлено в имеющихся источниках:**

- Текущая политика Natro 2.7.7: окно com.ecarx.parking на основном дисплее, два свежих снимка закрытия, без TTL; камера независима. GATE-046 открыт.

**Остаётся неизвестным:**

- Соответствие свежих окон фактической графике; восстановление при Binder failure, зажигании и холодном старте.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Сохранить NOTIF-009; проверить имеющиеся снимки, затем только недостающий синхронный опыт по GATE-046.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/1`.

## BASE-HUD — HUD: профили, PEN, маски содержимого

**Установлено в имеющихся источниках:**

- В прежних опытах CB accepted/echo сочетались с invalid feedback; отдельный HUD PA оставался IntellDrv.

**Остаётся неизвестным:**

- Активный профиль, смысл PEN, причина invalid и независимый физический эффект.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Разобрать текущий профиль и валидность; не подставлять 15 и не повторять перебор enum.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/2`.

## BASE-DIM — Приборка DIM: крылья, машинка и владельцы графики

**Установлено в имеющихся источниках:**

- Предыдущая отметка тестаI сделана после восстановления dimmenu; новый runtime подтверждает логические дисплеи и overlay, не владельца всех пикселей.

**Остаётся неизвестным:**

- Связь Android surface, QNX/LVDS и физических областей; независимый владелец каждого элемента.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Свести старые timeline и слой/процесс; следующий физический опыт требует метки до восстановления.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/3`.

## BASE-NAV — Навигация: маршрут, HUD/DIM и координаты

**Установлено в имеющихся источниках:**

- Есть отдельный GNSS/location тракт и NAVI display API; NAVI ID сам по себе не является выключателем штатной навигации.

**Остаётся неизвестным:**

- Владелец guidance, контракт манёвров/полос, источник координат и приоритет двух навигаций.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Продолжить с PLAT_LOCATION/PLAT_CARPLAY и штатными consumer навигации; различать измерение координат и маршрут.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/4`.

## BASE-MEDIAKEY — Кнопки руля, события ввода и управление медиа

**Установлено в имеющихся источниках:**

- Прежний журнал содержит три блокировки main thread дольше 8 секунд; timestamp внутри callback не измеряет полную задержку нажатия.

**Остаётся неизвестным:**

- Нажатие→callback→dispatch→player→audio, eventTime/downTime, повторы и long-press.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Сопоставить сохранённые input/media traces; отделить торможениеUI от транспорта автомобиля.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/5`.

## BASE-ANCS — ANCS/BLE уведомления и восстановление соединений

**Установлено в имеющихся источниках:**

- Новый разбор установил штатные Bluetooth профили и владельца power lifecycle; конфигурационные лимиты nFore не доказывают число одновременных ANCS соединений.

**Остаётся неизвестным:**

- Владелец BLE-сеанса, подписки и reconnect, арбитрация с телефонией/CarPlay, причина конкретных сбоев.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Сначала разобрать уже найденные Bluetooth логи с привязкой к PowerSomeIP; личные тексты уведомлений для этого не нужны.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/6`.

## BASE-VOICE — Голосовое управление, микрофон и голосовые предупреждения

**Установлено в имеющихся источниках:**

- Аудиоинтерфейсы содержат VR/eCall/HFT mix; наличие этих обозначений не раскрывает штатный voice assistant.

**Остаётся неизвестным:**

- Пакет и сервис распознавания, языки, push-to-talk/wake-word, маршрутизация микрофона и права клиента.

**Наличие оборудования:** Область исследования; присутствие каждого оборудования/опции и физическое исполнение этим дополнением не проверены.

**Путь управления:** См. точные ограничения исторического исследования и связанных карточек; полный исполнительный контракт не установлен.

**Следующий разбор:** Найти штатный manifest/consumer VR в существующем системном архиве и связь с audio focus; отметить отсутствующие материалы.

**Недостающая опора:** Сначала существующие источники по указанному следующему шагу; затем конкретное недостающее наблюдение.

[Источники и точные указатели](continuation/systems.json) → `/systems/7`.

## Когда добавлять новые строки

Новый ECU, сервис, сигнал, опция, штатный экран или переход состояния добавляет новую карточку либо расширяет существующую с источником. «Не найдено» требует указания проверенного набора. Неизвестное оборудование не помечается отсутствующим. Полнота прежнего каталога из 4440 записей не закрывает эту матрицу; аудит неявных нулей уже показал ограничение извлечения.
