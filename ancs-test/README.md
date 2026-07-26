# KX11 ANCS Test v2

Независимое диагностическое приложение для Geely Monjaro KX11 / ECARX Android 9.
Оно не использует код, настройки, package name или данные основного приложения Status.

- Package: `ru.natro.ancstest`
- Минимальный Android: 8.0
- Целевое устройство: Android 9 / API 28

## Что проверяет приложение

1. Наличие BLE scanner, advertiser и GATT server в ECARX/nFore.
2. Список bonded-устройств, их Bluetooth type и реальный адрес.
3. Нефильтрованное BLE-сканирование и сырые advertising packets.
4. Наличие ANCS Service Solicitation с AD type `0x15`.
5. Поддержку `addServiceSolicitationUuid()` или её OEM-backport в прошивке ECARX.
6. Входящее BLE-подключение к GATT server без автоматического признания peer как iPhone.
7. Явное подтверждение нужного peer командой `PAIR` из LightBlue.
8. LE bonding и реальное шифрование ATT через encrypted-характеристику.
9. Один контролируемый `connectGatt(TRANSPORT_LE)` только к verified peer.
10. Наличие ANCS, последовательные CCCD-подписки, Notification Source,
    Data Source, Control Point, AppIdentifier и DisplayName.

## Диагностические характеристики v2

| Назначение | UUID | Действие в LightBlue |
| --- | --- | --- |
| INFO | `d2d9e4b1-47f1-4e44-a8bb-a932fd5a2f01` | Прочитать название теста |
| CONTROL | `d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f01` | Записать ASCII `PAIR` |
| SECURE | `d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f01` | После pairing записать ASCII `ANCS` или прочитать |

CONTROL открыт только для запуска теста. SECURE требует encrypted read/write. Поэтому
`SECURE ATT OK` означает, что проверен именно зашифрованный BLE-link, а не только
наличие старого Classic bond.

## Как тестировать

1. Установите APK на магнитолу.
2. Разрешите геолокацию — Android 9 требует её для BLE-сканирования.
3. Нажмите **Ждать iPhone**.
4. Если приложение сообщает `ANCS SOLICITATION REQUESTED`, разблокируйте iPhone и
   подтвердите появившийся запрос сопряжения/уведомлений.
   Это означает, что приложение запросило ANCS-рекламу через OEM API; наличие
   настоящего AD type `0x15` нужно подтвердить вторым BLE-сканером.
5. Если приложение сообщает `DIAGNOSTIC ADV ACTIVE`, откройте на iPhone
   **KX11 ANCS Helper** из каталога `ios-ancs-helper`. Он подключается с системной
   опцией `CBConnectPeripheralOptionRequiresANCS: true`, автоматически отправляет
   `PAIR` и читает SECURE. Это предпочтительный тест для системного разрешения ANCS.
6. LightBlue можно использовать как запасной транспортный тест: найдите устройство
   с сервисом `d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f01`, нажмите **Connect**,
   откройте CONTROL `d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f01`
   и запишите ASCII `PAIR`. Только этот callback фиксирует verified peer; другие
   входящие BLE-подключения игнорируются.
7. Подтвердите системный запрос LE pairing на iPhone. После `BOND_BONDED` откройте
   SECURE `d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f01` и запишите ASCII `ANCS`
   либо выполните Read. Успех отображается как `SECURE ATT OK`.
8. После состояния `ANCS READY` отправьте на iPhone новое уведомление.
9. **BLE scan** — отдельная проверка сканера. iPhone там обычно не отображается:
   ANCS solicitation рекламирует аксессуар, то есть магнитола.
10. Если тест не проходит, нажмите **Сохранить лог**. Файл создаётся в каталоге
   приложения и его можно забрать через ADB.

## Основные результаты

| Состояние | Значение |
| --- | --- |
| `SCAN_FAILED_2/3/4/5` | Проблема регистрации BLE scanner или ограничение ECARX |
| `ADVERTISER_UNAVAILABLE` | Встроенный стек не отдаёт Peripheral/advertiser mode |
| `DIAGNOSTIC ADV ACTIVE` | Обычная реклама работает, но публичного ANCS solicitation API нет |
| `ANCS SOLICITATION REQUESTED` | В прошивке найден API/backport; наличие `0x15` в эфире ещё нужно проверить |
| `INCOMING LINK` | Есть BLE-link, но peer ещё не подтверждён |
| `VERIFIED PEER` | iPhone helper/LightBlue прислал `PAIR`; только этот peer участвует в тесте |
| `LE BOND BONDED` | Завершено BLE-сопряжение именно verified peer |
| `SECURE ATT OK` | Encrypted read/write реально прошёл |
| `GATT CONNECTED` | Получен реальный BLE peer и открыт GATT client |
| `CONNECTED · ANCS НЕ НАЙДЕН` | На этом link iPhone не опубликовал ANCS |
| `CCCD_FAILED_5/8/12/15` | Нужны LE bonding, шифрование или разрешение уведомлений |
| `ANCS READY` | Транспорт, discovery и обе ANCS-подписки работают |

## Важное ограничение Android 9

Публичный метод Android `AdvertiseData.Builder.addServiceSolicitationUuid()` появился
только в API 31. На API 28 приложение пытается обнаружить OEM-backport через reflection.
Обычный `addServiceUuid()` не является заменой, поскольку создаёт другой тип BLE-рекламы.
