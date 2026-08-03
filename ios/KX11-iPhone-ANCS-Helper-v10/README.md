# KX11 ANCS Helper v10 for iPhone

Helper v10 keeps the single deterministic connection introduced in v9 and makes telemetry
event-driven with a one-second recovery sampler:

- iPhone is only a BLE peripheral/GATT server (`iPhone_ANCS`);
- Status Widget on KX11 is the only central/GATT client and owns reconnect;
- Apple ANCS and Helper telemetry use that same Android-owned GATT link;
- there is no reverse scan/connect to `Geely_ANCS`, so two Core Bluetooth roles cannot race;
- Core Bluetooth peripheral state restoration and `bluetooth-peripheral` background mode remain
  enabled.
- every public `UIDevice.batteryLevel` change is sent as its own B4 notification;
- cable/charging changes come directly from `UIDevice.batteryState` and are sent immediately;
- radio changes and active-data-SIM changes come directly from CoreTelephony;
- a one-second B4 read on KX11 is the deterministic fallback if iOS coalesces an app callback.

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

Helper reacts to UIKit/CoreTelephony events immediately and then performs a 250 ms settling
read. While the app is executable it also samples the same public properties once per second and
only publishes a new frame when one of the three groups changed. Status Widget independently
reads B4 once per second on the already-owned GATT connection, so a coalesced foreground callback
cannot leave a stale value for the previous 15-second interval. Changed frames are queued in order
under Core Bluetooth backpressure instead of replacing an unsent one.

## Installation over v6/v7/v8/v9

The bundle identifier is still `ru.natro.kx11ancshelper`. Select the same Apple Development Team
that signed the installed Helper and run the project on the iPhone from Xcode. In Signing &
Capabilities the only required Background Mode is **Acts as a Bluetooth LE accessory**.

After updating, open Helper once and leave its switch enabled. Do not force-quit it from the iOS
app switcher: iOS does not relaunch a user-force-quit Bluetooth app until it is opened manually.

## Verification

1. Helper must show `ГОТОВ · ЖДУ KX11`.
2. Status Widget connects to the selected iPhone once.
3. Helper's `Android READ` time must advance every second; the journal writes a bounded
   `KX11 READ B4 LIVE · 8 bytes` proof line every 30 seconds. This proves percentage, cable state
   and network type are read independently of ANCS authorization without growing the UI log on
   every poll.
4. After ANCS subscribes, Helper shows `KX11 ПОДКЛЮЧЁН · ЕДИНЫЙ GATT`.
5. Walk out of BLE range and return without toggling Bluetooth. Status Widget keeps the same
   Android `autoConnect` owner and reattaches instead of replacing it after a timeout.
6. Change the battery by one percent, attach/detach power, and move between LTE/3G/EDGE. The
   matching B4 notification/read must appear no later than the next one-second control read.
