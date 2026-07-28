# KX11 ANCS Helper v5 for iPhone

Helper v5 implements both sides of the dedicated application BLE transport:

- the head unit advertises the stable logical service `Geely_ANCS`;
- the iPhone scans for that service as a Core Bluetooth central and connects first;
- the encrypted `PAIR` → `SECURE` handshake lets Status Widget attach its ANCS
  GATT client to the exact verified iPhone peer;
- `iPhone_ANCS` advertising remains enabled as a compatibility fallback for the
  previous Android-central route;
- Classic Bluetooth names, HFP/A2DP and stock pairing are not renamed or replaced.

## Обновление с v4

Обновление обязательно для проверки нового основного маршрута HA1132. Helper v4
оставляет iPhone только BLE-периферией, поэтому магнитола по-прежнему вынуждена
искать его и инициировать каждое переподключение.

Bundle ID не изменён: `ru.natro.kx11ancshelper`. При выборе той же Team в Xcode
v5 устанавливается поверх прежней версии. После обновления откройте Helper вручную
один раз и разрешите Bluetooth.

## Установка с Mac

1. Откройте `KX11ANCSHelper.xcodeproj` в Xcode.
2. Подключите iPhone и выберите его как Run Destination.
3. В `Signing & Capabilities` выберите свою Team.
4. Нажмите Run и разрешите приложению Bluetooth.
5. Один раз откройте Helper и оставьте режим постоянного соединения включённым.

Проект использует фоновые режимы `bluetooth-central` и `bluetooth-peripheral`,
а также Core Bluetooth state restoration. Если пользователь принудительно выгрузит
Helper из переключателя приложений, iOS не обязана перезапускать его в фоне до
следующего ручного открытия.

## Тест нового маршрута

1. Установите Status Widget HA1132 на магнитолу и включите коннектор телефона.
2. Откройте Helper v5. По умолчанию соединение запускается автоматически.
3. В журнале iPhone должны последовательно появиться:
   - `SCAN candidate=...`;
   - `CONNECTED ...`;
   - `INFO ... = Geely_ANCS/1`;
   - `WRITE PAIR`;
   - `SECURE value=SECURE ATT OK`;
   - `Основной защищённый канал готов`.
4. На магнитоле ожидаются состояния:
   - `iPhone_ANCS · INCOMING LINK`;
   - `PAIR принят`;
   - `SECURE ATT OK`;
   - `SAME-PEER ATTACH`;
   - после публикации Apple-сервиса — `ANCS READY`.

Проверка является техническим прототипом: Core Bluetooth не гарантирует, что
системный ANCS будет опубликован на входящем приложенческом BLE-соединении на
конкретной версии iOS/ECARX. Если после `SECURE ATT OK` магнитола не видит сервис
ANCS `7905F431-B5CE-4E99-A40F-4B1E122D00D0`, пришлите журналы обеих сторон.
