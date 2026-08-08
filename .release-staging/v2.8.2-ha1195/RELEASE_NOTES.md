# Status Widget v2.8.2-ha1195 + KX11 ANCS Helper v32

Этот релиз исправляет конкретный сбой из журналов HA1194: после успешных `PAIR`,
`CURRENT LINK B3`, `ANCS-READY` и B4 telemetry Android создавал прямой
`connectGatt(autoConnect=false)` поверх уже существующего входящего LE-соединения iPhone.
ECARX/Android 9 отвечал `GATT status=133`, после чего создавались ещё два конкурирующих
`BluetoothGatt` owner.

## Что изменено в HA1195

- reverse-маршрут регистрирует фоновый GATT client owner через
  `connectGatt(autoConnect=true)` на точном `BluetoothDevice` входящего соединения;
- при `status=133` и обычном разрыве сохраняются тот же `BluetoothGatt`, его `clientIf`,
  GATT server, namespace и B4 CCCD;
- повторная активация выполняется через `BluetoothGatt.connect()` без создания второго
  radio-open;
- прямой `autoConnect=false` запрещён контрактным тестом для managed incoming route;
- новый owner разрешён только если первоначальная GATT-регистрация действительно не создала
  пригодный client owner.

## Helper v32

Swift-логика Helper не изменялась: представленные журналы подтверждают, что она уже выполнила
единственный Central-сеанс с `RequiresANCS=true`, защищённый B3, валидные battery/network,
`ANCS-READY` и B4 wake CCCD. В архиве обновлены только документы совместимости с HA1195.

## Основание реализации

- AOSP Android 9: background GATT open присоединяет регистрацию клиента к существующему
  соединению, а `BluetoothGatt.connect()` повторно использует зарегистрированный client owner;
- NXP ANCS Client: discovery, security и подписки ANCS CCCD выполняются одним GATT Client на
  одном GAP-соединении;
- ESP32 ANCS Notifications: ANCS client привязывается к адресу уже подключённого iPhone и
  выполняет discovery/CCCD без второго handoff.

Источники:

- https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r8/core/java/android/bluetooth/BluetoothGatt.java
- https://android.googlesource.com/platform/system/bt/+/9c268d0a54396f22e893586069fe576b751b013f/bta/gatt/bta_gattc_act.cc
- https://mcuxpresso.nxp.com/mcuxsdk/25.03.00-pvw2/html/middleware/wireless/bluetooth/doc/DAUG/topics/ancs_client.html
- https://github.com/Smartphone-Companions/ESP32-ANCS-Notifications/blob/master/src/esp32notifications.cpp

## Проверено

- `testGeelyDebugUnitTest` и unsigned release build — GitHub Actions run `31277801506`;
- Helper v32 compile + single-owner contract — GitHub Actions run `31277801501`;
- package `ru.natro.statuswidget`, version `v2.8.2-ha1195`, versionCode `208021195`;
- 16 KiB zip alignment, APK v3 signature и стабильный update certificate;
- SHA-256 каждого опубликованного asset повторно проверяется после скачивания из Release.
