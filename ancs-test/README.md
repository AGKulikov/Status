# KX11 ANCS Test v6

Независимое диагностическое приложение для Geely Monjaro KX11 / ECARX Android 9.
Оно не использует код, настройки, package name или данные основного приложения Status.

- Package: `ru.natro.ancstest`
- Минимальный Android: 8.0
- Целевое устройство: Android 9 / API 28

## Что изменено в v6

Версия v6 проверяет, может ли Android 9 зарегистрировать GATT client на уже
существующем входящем BLE/ATT-соединении iPhone:

1. iPhone подключается к GATT server магнитолы.
2. ASCII `PAIR` фиксирует точный `BluetoothDevice` из
   `BluetoothGattServerCallback` как `verified peer`.
3. Зашифрованный доступ к SECURE подтверждает `SECURE ATT OK`.
4. После первого успешного SECURE приложение выполняет одну попытку:
   `verifiedPeer.connectGatt(context, true, callback, TRANSPORT_LE)`.
5. Если за 5 секунд нет успешного callback или получен ошибочный status,
   через 500 мс выполняется ровно один fallback с `autoConnect=false`.
6. При подключении сразу вызывается `discoverServices()`, без `requestMtu()`.

Другие адреса, временная корреляция, `TRANSPORT_AUTO`, GPSTether и циклы
переподключения в v6 не используются. GATT server и BLE-реклама не закрываются
во время двух клиентских попыток.

## Диагностические характеристики

| Назначение | UUID | Действие на iPhone |
| --- | --- | --- |
| INFO | `d2d9e4b1-47f1-4e44-a8bb-a932fd5a2f01` | Прочитать `KX11 ANCS Test v6` |
| CONTROL | `d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f01` | Записать ASCII `PAIR` |
| SECURE | `d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f01` | После pairing записать ASCII `ANCS` или прочитать |

SECURE требует encrypted read/write. Поэтому `SECURE ATT OK` подтверждает
шифрование текущего BLE-link, а не только наличие старого Classic bond.

## Как тестировать

1. Установите APK на магнитолу и нажмите **Ждать iPhone**.
2. На iPhone откройте **KX11 ANCS Helper**. Он подключается с системной опцией
   `CBConnectPeripheralOptionRequiresANCS`, отправляет `PAIR` и проверяет SECURE.
3. Подтвердите системные запросы pairing/ANCS на iPhone.
4. Дождитесь одного из результатов:
   - `ANCS READY` — сервис найден и обе подписки включены;
   - `CONNECTED · ANCS НЕ НАЙДЕН` — GATT client присоединился, но iOS не
     опубликовала ANCS на этом link;
   - `V6 ATTEMPTS EXHAUSTED` — обе разрешённые регистрации GATT client завершились
     без успеха.
5. Только после `ANCS READY` создайте на iPhone новое уведомление.

LightBlue можно использовать как ручной транспортный тест: подключитесь к
service `d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f01`, запишите `PAIR` в CONTROL, затем
после bonding прочитайте SECURE или запишите в него `ANCS`.

## Логи

Журнал виден в интерфейсе и одновременно пишется в logcat с тегом `KX11ANCS`.

```sh
adb logcat -v threadtime KX11ANCS:I BluetoothGatt:V BluetoothGattServer:V '*:S'
```

Ключевая строка первой попытки:

```text
connectGatt(autoConnect=true, TRANSPORT_LE) ... EXACT SAME VERIFIED BluetoothDevice
```

Успешный путь:

```text
SAME-PEER GATT CONNECTED
discoverServices accepted=true
ANCS READY
```

Для доказательства, что новая регистрация использовала тот же физический
ACL/ATT-link, нужен Bluetooth HCI snoop: в нём не должно появляться нового
`LE Create Connection` между SECURE и `onConnectionStateChange`.

## Ограничение Android 9

Публичный `AdvertiseData.Builder.addServiceSolicitationUuid()` появился позже
API 28. Приложение проверяет OEM/backport через reflection; если его нет,
запускает обычную диагностическую connectable-рекламу. Такая реклама сама по
себе не заменяет ANCS Service Solicitation с AD type `0x15`.

Интерфейс хранит не более 500 строк журнала и 80 уведомлений и обновляет панели
не чаще четырёх раз в секунду, чтобы серия BLE callback не блокировала экран.
