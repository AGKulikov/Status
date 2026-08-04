# KX11 ANCS Helper v13 for iPhone

Helper v13 accompanies Status Widget HA1164 and keeps the explicit iPhone BLE-role selector. The
selection must match **Status Widget → Phone → iPhone BLE role**.

## Roles

- **Peripheral** is the default and preserves Helper v11 unchanged: iPhone advertises
  `iPhone_ANCS`, KX11 connects as the BLE central/GATT client, and the Android-owned link carries
  ANCS plus Helper telemetry.
- **Central** is opt-in: KX11 advertises `Geely_ANCS`, Helper scans for its private D2D9 service
  and connects with `CBConnectPeripheralOptionRequiresANCS`. On iOS 17+ the connection also uses
  system auto-reconnect. After `PAIR` and the encrypted `SECURE` read, KX11 attaches its ANCS GATT
  client to that exact incoming peer on the same physical BLE link.

Only one route runs at a time. Switching the segmented control stops the previous scan,
advertisement and app-owned connection before starting the selected route. Neither mode renames,
re-pairs or controls Classic HFP/A2DP/PBAP.

## Central-mode handshake

1. Helper scans only for service `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01`.
2. It connects with `RequiresANCS` and restores the last verified Core Bluetooth peripheral UUID.
3. It writes ASCII `PAIR` to B2 and reads encrypted B3 until LE encryption is established.
4. The verified KX11 UUID is persisted only after `SECURE ATT OK`.
5. Eight-byte B4 telemetry frames are written to KX11 with response.

The B4 frame remains `A5 01 LL FF NN SS SS CC` (battery, power flags, active data-network type,
sequence and CRC-8/ATM). In Peripheral mode it is served by READ/NOTIFY exactly as in v11; in
Central mode the same frame is sent by encrypted WRITE.

Helper v13 also treats a removed D2D9 service, invalid ATT handle, or encryption loss after a
previously successful SECURE exchange as a broken BLE session. It disconnects, discards every
cached service/characteristic, and reconnects only after a fresh `Geely_ANCS` advertisement.
This replaces v12's one-second rediscovery loop against stale handles.

## Installation

The bundle identifier remains `ru.natro.kx11ancshelper`. Select the same Apple Development Team
used for the installed Helper and run the project on the iPhone from Xcode. Both **Uses Bluetooth
LE accessories** and **Acts as a Bluetooth LE accessory** background modes are included.

Open Helper once after installation and do not force-quit it. Start with **Peripheral** on both
devices to verify the unchanged route, then select **Central** on both devices for the alternate
test. If iOS asks for LE pairing or notification access, accept it and leave “Show Notifications”
enabled for the head unit.
