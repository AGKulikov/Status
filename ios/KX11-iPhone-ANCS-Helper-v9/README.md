# KX11 ANCS Helper v9 for iPhone

Helper v9 replaces the former two-route BLE bridge with one deterministic connection:

- iPhone is only a BLE peripheral/GATT server (`iPhone_ANCS`);
- Status Widget on KX11 is the only central/GATT client and owns reconnect;
- Apple ANCS and Helper telemetry use that same Android-owned GATT link;
- there is no reverse scan/connect to `Geely_ANCS`, so two Core Bluetooth roles cannot race;
- Core Bluetooth peripheral state restoration and `bluetooth-peripheral` background mode remain
  enabled.

## Exact telemetry protocol

Characteristic `D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F01` supports `READ + NOTIFY` and is readable
before an ANCS authorization operation. It returns one fixed eight-byte snapshot:

```
A5 01 LL FF NN SS SS CC
```

- `LL`: exact `UIDevice.batteryLevel` percentage (`FF` means unavailable);
- `FF`: validity, external-power, charging and full flags from `UIDevice.batteryState`;
- `NN`: current public CoreTelephony radio generation (`5G`, `LTE`, `4G`, `3G`, `E`, `G`,
  `1X`, `SOS`, `SAT` or unavailable);
- `SS SS`: little-endian sequence number;
- `CC`: CRC-8/ATM over the first seven bytes.

The frame fits every standard ATT MTU. Status Widget reads it before starting encrypted ANCS
subscriptions and rejects wrong-version, truncated or CRC-damaged frames. Android no longer
infers charging or radio generation from percentage changes.

## Installation over v6/v7/v8

The bundle identifier is still `ru.natro.kx11ancshelper`. Select the same Apple Development Team
that signed the installed Helper and run the project on the iPhone from Xcode. In Signing &
Capabilities the only required Background Mode is **Acts as a Bluetooth LE accessory**.

After updating, open Helper once and leave its switch enabled. Do not force-quit it from the iOS
app switcher: iOS does not relaunch a user-force-quit Bluetooth app until it is opened manually.

## Verification

1. Helper must show `ГОТОВ · ЖДУ KX11`.
2. Status Widget connects to the selected iPhone once.
3. Helper must log `KX11 READ B4 OK · 8 bytes`; this proves percentage, cable state and network
   type were read independently of ANCS authorization.
4. After ANCS subscribes, Helper shows `KX11 ПОДКЛЮЧЁН · ЕДИНЫЙ GATT`.
5. Walk out of BLE range and return without toggling Bluetooth. Status Widget keeps the same
   Android `autoConnect` owner and reattaches instead of replacing it after a timeout.
