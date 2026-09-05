# Bluetooth runtime KX11 — шесть архивов, 05.09.2026

**Найдены два разных класса разрывов, которые нельзя смешивать:** повторяемый native/HCI `reason=40 (0x28)` принадлежит GATT-клиенту `org.astpepper.hwgps`, а Natro в отдельной эпохе сам выполняет `clientDisconnect → close → unregisterApp`. При части этих Natro-разрывов штатный телефон продолжает сообщать `isHfpConnected=true`. Причины внутренних решений Natro в сохранённых Bluetooth-срезах отсутствуют.

Проанализированы 248366 строк шести `DIAGNOSTICS/logcat_bluetooth_slice.txt`, выбранные разделы всех шести `dumpsys_bluetooth_manager.txt` и три HCI `.last`: 144934 records. HCI разобран только по framing и явно разрешённым scalar fields событий подключения/разрыва; ACL, SCO, ключи, ATT/ANCS notification payload, контакты и история звонков не декодировались. Отчёт и `timeline.json` содержат только типизированную очищенную проекцию. Полные первичные байты находятся в `private/` и не предназначены для публичного Git.

Номера BT01…BT15 разрешаются в `evidence.json`: каждый вывод имеет namespace, уровень наблюдения/вывода, SHA архива/member и исходные строки либо HCI record/byte offset. Статические DEX-доказательства дополнительно находятся в `static/evidence.json`. Никаких запусков оригиналов, APK build, переключений Bluetooth, пар, звонков или команд автомобилю не выполнялось. Natro 2.7.7/NOTIF-009 без TTL сохранены; это исследование августовских эпох, не аппаратная приёмка текущей версии.

## Точные окна и состояние

Время logcat сохранено буквально: оно не объявляется UTC по имени архива.

| Архив | Logcat-окно | Строк | Снимок Natro GATT |
|---|---|---:|---|
| 20260814-210122_61118 | 14.08 21:01:24.971–21:01:36.673 | 3605 | Registered, ID6, 0 connections |
| 20260815-090005_64290 | 15.08 08:52:12.298–09:01:16.346 | 81687 | Запись статистики, нет Registered/активного ID |
| 20260815-104207_69107 | 15.08 10:39:13.047–10:42:36.682 | 51913 | Registered, ID6, 1 connection |
| 20260815-114556_71773 | 15.08 11:45:31.530–11:46:55.264 | 16820 | Registered, ID6, 1 connection |
| 20260815-132904_74834 | 15.08 13:24:07.632–13:29:36.723 | 83990 | Запись статистики, нет Registered/активного ID |
| 20260815-134614_76337 | 15.08 13:46:20.264–13:46:40.331 | 10351 | Запись статистики, нет Registered/активного ID |

Все шесть snapshots показывают Bluetooth `ON`, `enabled=true`, `crashed 0 times`. Это ограниченный счётчик своей эпохи, а не доказательство отсутствия Binder, profile или reconnect ошибок. `RESTARTED` в enable history не равен crash. Fingerprint, текущие `nForeBluetooth.properties` и `bt_stack.conf` побайтно совпадают между шестью архивами; версия установленного Natro этими файлами не устанавливается. Рядом с текущим nFore имеются backup-имена предыдущих изменений: текущую конфигурацию нельзя называть нетронутым заводским baseline. [BT01, BT03, BT15; `source_inventory.json`]

## Питание и запуск: связь стала runtime-фактом

В logcat есть реальные `BluetoothManagerService.enable(ecarx.powersomeip.service)`:

- 10:39:13.203 — `mBluetooth=null`, binding=true, state=OFF.
- 11:45:32.947 — state=BLE_ON.
- 13:24:09.342 — state=BLE_TURNING_ON.

При последнем запуске в13:24:10.222 зарегистрированы `waitForOnOff time out`, `MESSAGE_RESTART_BLUETOOTH_SERVICE`, затем BLE_TURNING_ON→BLE_ON и BLE_ON→TURNING_ON. Поздние снимки подтверждают ON. Это конкретный timeout ожидания старта, который прежняя инвентаризация не обнаруживала. [BT01, BT02]

Существующий статический PowerSomeIP-разбор показывает `apStatusChange → сохранение bt_pre_state/wifi_pre_state → off/restore` и QNX callback через `PowerManager.onQNXRequest`. Новые enable-записи согласуются с участием этого штатного владельца. Однако **ни одного `apStatusChange`, `ScPwIVIMngrReqState`, `lastAPStatus`, `bt_pre_state` или `wifi_pre_state` в шести Bluetooth slices нет**. Поэтому причинную цепь «сон вызвал конкретный ANCS-разрыв» здесь установить нельзя. Следующий сбор должен дать очищенные power epoch/request callbacks, а не повторные те же APK и произвольный reboot. [BT13; прежний `android_platform/report.md`]

## Разрыв GPS: два воспроизведения и HCI

В10:42 snapshot ID5 однозначно принадлежит `org.astpepper.hwgps`, ID6 — Natro. В13:29 GPS вновь зарегистрирован сID5, а scanner ID6 принадлежит Navigator. Идентификаторы нельзя переносить между независимыми эпохами по одному числу. [BT03]

| Этап GPS client5 | Утреннее окно | Дневное окно |
|---|---|---|
| Native GATT/BTM reason0x28/40 | 10:39:42.257 | 13:25:01.600 |
| Callback приложению status40 | 10:39:42.258 | 13:25:01.620 |
| Приложение close/unregister | 10:39:42.260 | 13:25:01.621–.622 |
| Новая попытка connect | 10:39:43.065 | 13:25:01.903 |
| clientDisconnect(connId=null), затем native reason0x100 | 10:39:47.963–.964 | 13:25:06.801–.802 |
| Следующее подключение status0 | 10:39:49.764 | 13:25:09.409 |
| Services discovery status0 | 10:39:50.439 | 13:25:10.085 |
| До успешного discovery от native-разрыва | 8.182с | 8.485с |

Первое повторное подключение отменяет клиент, когда connId ещё null; `0x100` здесь нельзя объявлять вторым физическим HCI disconnect. В доступной Java-реализации GattService native status проходит без преобразования. Названная расшифровка vendor-кодов0x28/0x100 требует отсутствующих native библиотек; в отчёте сохранены исходные числа и пространства. [BT04; BT-ST-009]

Из `.last` архива13:46 получено независимое подтверждение дневного разрыва: **HCI event0x05, record4516, byte offset1026715, 10:25:01.599616UTC, handle3, status0, reason40**. Logcat из другого архива13:29 содержит handle3/reason40 в13:25:01.600. Сдвиг+3часа согласует их до0.384мс; поздний callback приложения отделён от controller event. Это связь двух первичных источников, не предположение по имени архива. [BT11]

## Natro: локальное закрытие и доступное восстановление

В эпохе10:39 Natro имеет ID6 и собственный PID. После успешного services discovery он вызывает `clientDisconnect → close → unregisterApp` в10:40:59.494–.500 и10:41:26.347–.348. Через примерно1с native отправляет disconnect reason0x13, а completion имеет reason0x16/22. Число в отправленной команде и число завершившего события — разные поля разных направлений; их нельзя объявлять противоречием или двумя разрывами. Штатный InCallService в10:41:00.516 и10:41:27.400 фиксирует `isHfpConnected=true`. [BT05, BT06]

Natro восстанавливает client6:10:41:29.547 connection status0,10:41:29.678 MTU185/status0,10:41:29.769 services status0. В11:45 наблюдается ещё один успешный цикл: connect11:45:51.969 → connected11:45:52.727 → MTU18511:45:52.904 → services11:45:52.930. Затем видны локальные `registerForNotification`, а dumpsys подтверждает1 connection. Это не доказательство CCCD write acknowledgment, ANCS authorization или доставки сообщения. [BT05, BT07]

В13:29/13:46 Natro уже не Registered и не имеет активного client ID/connection; при этом GPS GATT и автомобильные HFP/A2DP продолжают существовать. Что именно завершило Natro-сессию в этой эпохе, Bluetooth slice не показывает: более ранняя часть журнала потеряна/не вошла в выборку, а HCI handle не устанавливает package без синхронного GATT mapping. [BT03]

## Дополнительные факты и пределы

- Navigator имеет длительные ongoing BLE scans, включая две записи одновременно в13:46. Это scanner history, не два GATT-соединения и не доказательство радиоконфликта с Natro. Ранний Natro scan history содержит38.486/60.021/49.847с и поздние2.204/2.242с; полного внутреннего backoff/owner reason нет. [BT08]
- В13:29:12.234 GattService регистрирует `DeadObjectException`, но нет stack/recipient. Его нельзя приписать Natro по близости; ближайший scanner ID6 принадлежит Navigator. [BT09]
- Ошибка bind `android.bluetooth.IBluetoothHeadset` относится к обычной роли Headset. Машина использует HeadsetClientService; совпадение слова Headset не доказывает отказ автомобильного HFP. [BT10]
- Missing GATT cache files встречаются перед успешным service discovery. Само `can't open GATT cache file` не устанавливает повреждённую пару и не разрешает удаление bond/keys. Ключи и содержимое cache не изучались. [BT14]
- Нулевые поиски `insufficient key`, `invalid handle`, выбранных ANCS phase tags и descriptor-write callbacks относятся строго к248366 сохранённым строкам. Они не отменяют пользовательский дефект из другого журнала. ANCS внутри личной device metadata CarPlay не считается ANCS lifecycle event. [BT13]

Важная хронология `.last`: архив14.08 21:01 содержит HCI14.08 13:36–13:47UTC, архив15.08 09:00 — HCI14.08 18:10–18:15UTC, архив15.08 13:46 — HCI15.08 10:24–10:45UTC. Старые `.last` нельзя синхронизировать с текущим logcat только по нахождению в одном tar. В HCI имеются также reason8 и неуспешные LE enhanced connection complete status2; без package mapping они не получают атрибуцию Natro. Vendor command return byte отдельно оставлен неинтерпретированным. [BT12]

## Реальный штатный стек и адресный досбор

Параллельный целевой разбор тел подтвердил: `ts-dm-foundation-lib.jar` — wrapper Android Adapter/профилей11(A2DP sink) и16(HFP client), а не второй ANCS-владелец. В Bluetooth.apk реальные `A2dpSinkService`/`HeadsetClientService` читают `MaxAvpNum=2`/`MaxHfpNum=2` и применяют лимиты к state machines с ненулевым state. `AdapterApp` загружает `bluetooth_jni`; GattService работает через Binder/ClientMap/JNI. Сохранённый ELF32 `libbluetooth-binder.so` является socket glue и не заменяет arm64 backend. Полный отчёт: `static/report.md` и13 фактов/DEX offsets в `static/evidence.json`.

Полная повторная проверка присутствия охватила **158 зарегистрированных оригиналов с совпавшими SHA-256**, 102 внешних архива / 15306 members и обнаруженную вложенность ZIP/TAR. Прежний вывод об отсутствии `ts-dm-service.apk` исправлен: файл найден в двух Cruise-архивах 09.08.2026, 641997 байт, SHA-256 `b42926770416f0e05778a04aaa69b0b69c5dd9f1572babe88813a253df7aa8c1`. Это пакет `com.ts.dm.service`, версия `4.5.9.0`, versionCode `28`; те же version fields есть во всех шести Bluetooth dumpsys. Версия без свежего SHA ещё не доказывает совпадение установленных байтов. [DM10; `full_corpus_target_coverage.json`]

Разбор найденного **реального сервера DM** добавил 13 фактов в `dm_service_evidence.json`:

- Capability 5/6 проходят через BtManager → foundation HFP/A2DP → Android profile 16/11. Возврат DM `0` означает принятие запроса, а локальные callback могут синтезироваться самим сервером. Повторные pending connect/disconnect дедуплицируются; через 10000 мс оба таймера перечитывают состояние foundation и убирают pending token. В этих таймерах нет повторного connect, adapter reset или удаления пары. [DM01–07]
- При обновлении connected projection capability 1/2 сервер вызывает HFP disconnect capability 5 для других устройств. При отдельном profile callback и наличии tracked projection новое HFP другого устройства также отключается. В соседней ветке A2DP имеется только текст о disconnect: самого вызова disconnect там нет. [DM08–09]
- Profile callback передаётся слушателям, затем DeviceManager ставит обработку на Handler; при найденной модели обновление модели предшествует рассылке слушателям и арбитрации. Синтетические статусы и timeout reconciliation проходят ту же точку, поэтому callback сам по себе не становится независимым аппаратным feedback. [DM13]
- Пять точных arbitration/timeout-сообщений сервера не найдены в 248366 строках шести отфильтрованных Bluetooth slices. Это ограничение выборки, а не доказательство неисполнения политики или причины Natro teardown. В просмотренных `com/ts/dm` DEX-инструкциях нет прямых `BluetoothGatt`/`connectGatt`/ANCS references. [DM11–12]

`collection_targets.json` теперь содержит **8 отсутствующих файлов/ABI**, а известный DM APK переведён в metadata/SHA-only проверку с переносом только при изменившемся SHA. Первыми нужны arm64 `libbluetooth_jni.so`, `libbluetooth.so` и `EcarxBluetoothServiceExtension.apk`. Точный установленный пакет extension подтверждён: `ecarx.bluetooth.service.extension`; package identity для плана находится в `package_identity_evidence.json`. Полная проверка имён не доказывает отсутствие произвольно переименованного файла внутри raw firmware partition.

Дополнительно нужны проекции installed package version/SHA, process-library paths, GATT registry package/id/count, power/request/state и Natro owner/phase/reason без notification payload. Полный `dumpsys bluetooth_manager` содержит личные поля и не должен попадать в обычный итоговый пакет сборщика.

Остались конкретные блокеры: native status/backend тела и extension, точная версия Natro августа, внутренние причины client6 teardown и синхронный AP/QNX sleep epoch. Политика сервера DM теперь восстановлена статически, но её исполнение в конкретном разрыве не доказано. Повторный перенос известных одинаковых APK/JAR/config и удаление пары этих пробелов не закрывают.
