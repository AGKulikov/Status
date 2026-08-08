# KX11 ANCS Helper v27 for iPhone

Helper v27 accompanies Status Widget HA1189. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## Why the connection is now split in two

The device log proves that HA1186 opens the F04 GATT server and receives the iPhone link, but
Helper v25 stops before `PAIR`: Core Bluetooth rejects F04 characteristic discovery with
`CBErrorDomain:8` / `uuidNotAllowed`. A fresh F04 namespace produced the same result as F02, so
this is not merely an old service-cache entry.

In v25 the custom bootstrap and Apple's ANCS requirement were requested on the same connection.
That creates a circular gate: Helper needs B2/B3 to authenticate the Android peer, while Android
does not attach its ANCS client until B2/B3 succeeds. Helper v27 removes that cycle.

## Central-mode sequence

1. Helper publishes the non-advertised iPhone-owned generation-5 B4 telemetry relay.
2. It scans for Android's generation-4 F04 service and opens a **plain BLE bootstrap** with
   `RequiresANCS=false`.
3. Only on that first link it discovers F04, writes `PAIR` to B2 and completes the encrypted B3
   current-link challenge.
4. Helper writes `ANCS-HANDOFF` to encrypted B3. HA1189 preserves the verified bonded identity
   and keeps the GATT server/advertiser alive while the bootstrap link closes.
5. Helper reconnects to the same `CBPeripheral` with `RequiresANCS=true`. It deliberately performs
   no custom F04 service or characteristic discovery on this second link.
6. HA1189 accepts either the resolved bonded callback or Android 9's fresh anonymous callback
   inside the encrypted, physically released handoff window. It keeps the bonded identity for
   `connectGatt`, then discovers/subscribes to ANCS plus the iPhone-owned F05/B4 relay.
7. `RequiresANCS` `didConnect` keeps ownership active. A B4 read/subscription enriches telemetry,
   but its absence never tears down or restarts a working ANCS link.

The Android side permits only one pre-PAIR `createBond()` owner per session. Rotating anonymous
iOS RPA callbacks cannot start parallel SMP/bond attempts.

## Telemetry retained

The eight-byte frame is `A5 01 LL FF NN SS SS CC`:

- `LL`: public `UIDevice.batteryLevel` percentage;
- `FF`: power flags plus bit 5 while the iPhone is locked;
- `NN`: normalized active CoreTelephony type;
- `SS SS`: little-endian sequence;
- `CC`: CRC-8/ATM.

The Android bootstrap service is `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04`. The iPhone-owned
telemetry relay is generation 5 at service `...2F05`, characteristic `...B4...2F05`.

## Installation

The bundle identifier remains `ru.natro.kx11ancshelper`, minimum iOS is 13.0, and both
`bluetooth-central` and `bluetooth-peripheral` background modes remain enabled. Build and install
with the same Apple Development Team as the existing Helper. Install HA1189 and Helper v27 as a
matched pair, open Helper once, select **Central**, and do not force-quit it.

Do not delete the Classic Bluetooth pairing during the first test. Classic HFP/A2DP/PBAP, device
names, the system LE bond and ANCS authorization are not modified by Helper.
