# KX11 ANCS Helper v15 for iPhone

Helper v15 accompanies Status Widget HA1167 and keeps the explicit iPhone BLE-role selector. The
selection must match **Status Widget → Phone → iPhone BLE role**.

## Roles

- **Peripheral** is still the default and preserves the Helper v11 route: iPhone advertises
  `iPhone_ANCS`, KX11 connects as the BLE central/GATT client, and that Android-owned link carries
  ANCS plus Helper telemetry.
- **Central** is opt-in: KX11 advertises `Geely_ANCS`; Helper scans for its private D2D9 service and
  connects with `CBConnectPeripheralOptionRequiresANCS`. On iOS 17+ the connection also requests
  system auto-reconnect. KX11 then attaches its ANCS GATT client to the same incoming BLE peer.

Only one route runs at a time. Switching the segmented control stops the previous scan,
advertisement and app-owned connection before starting the selected route. Neither mode renames,
removes, re-pairs or controls Classic HFP/A2DP/PBAP.

## Central-mode current-link handshake

1. Helper scans only for service `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01`.
2. It connects with `RequiresANCS` and restores the last verified Core Bluetooth peripheral UUID.
3. It writes ASCII `PAIR` to B2.
4. The first B3 read on a new physical link receives one ATT authentication challenge from HA1167;
   Helper retries it on the same connection so Core Bluetooth can restore/start LE security.
5. A successful second B3 operation proves that the callback still belongs to this live peer. The
   KX11 UUID is then persisted and B4 telemetry starts.

This avoids the Android 9 deadlock where the shared device reports Classic `BOND_BONDED` while the
private LE characteristic is rejected by the framework with ATT code 12 before the app receives a
callback. D2D9 B3/B4 contain only link control and status telemetry; access remains restricted to
the peer claimed by `PAIR`. ANCS itself keeps Apple's protected service and authorization rules.

The B4 frame remains `A5 01 LL FF NN SS SS CC` (battery, power flags, active data-network type,
sequence and CRC-8/ATM). Peripheral mode still serves it through READ/NOTIFY; Central mode writes
the same frame with response after current-link confirmation.

Helper v15 also treats a removed D2D9 service or invalid ATT handle as a broken BLE session. It
disconnects, drops all cached services/characteristics, and waits for a fresh advertisement.
Repeated security failures are bounded and use the existing 1/2/5/10/20/30-second reconnect
backoff instead of hammering the same stale session.

## Installation

The bundle identifier remains `ru.natro.kx11ancshelper`. Select the same Apple Development Team
used for the installed Helper and run the project on the iPhone from Xcode. Both **Uses Bluetooth
LE accessories** and **Acts as a Bluetooth LE accessory** background modes are included.

Open Helper once after installation and do not force-quit it. Start with **Peripheral** on both
devices to verify the unchanged route, then select **Central** on both devices for the alternate
test. If iOS asks for LE pairing or notification access, accept it and leave “Show Notifications”
enabled for the head unit.
