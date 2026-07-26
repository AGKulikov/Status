# KX11 ANCS Helper v4 for iPhone

Тестовый iPhone-клиент для схемы HWGPS/GPSTether. В этой версии iPhone работает
как BLE Peripheral: публикует собственный GATT-сервис и рекламирует его, а
магнитола сама находит iPhone и создаёт соединение.

## Установка с Mac

1. Откройте `KX11ANCSHelper.xcodeproj` в Xcode.
2. Подключите iPhone и выберите его как Run Destination.
3. В `Signing & Capabilities` выберите свою Team.
4. Нажмите Run и разрешите приложению Bluetooth.

## Тест

1. На iPhone нажмите **Рекламировать iPhone по BLE**.
2. На магнитоле в KX11 ANCS Test v7 нажмите **Подключить iPhone BLE**.
3. При запросе LE-сопряжения подтвердите его.
4. В журнале iPhone должны появиться:
   - `WRITE ... value=PAIR`;
   - `READ ... SECURE`;
   - `SECURE IPHONE OK`.
5. Итог поиска системного ANCS показывается на магнитоле.

Через системные Bluetooth-настройки это BLE-соединение создавать не требуется.
Classic Bluetooth для аудио и звонков может быть подключён одновременно.
