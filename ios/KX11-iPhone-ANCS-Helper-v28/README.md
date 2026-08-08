# KX11 ANCS Helper v28 for iPhone

Helper v28 is the companion for Status Widget HA1190. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## Why v28 changes service discovery

The latest vehicle trace reaches Android's GATT server but fails before `PAIR`. Core Bluetooth
returns `CBErrorDomain:8` (`uuidNotAllowed`) while discovering the fixed F04 characteristics.
The same v27/F04 pair worked earlier and then failed after Android republished its server, which
identifies a stale Core Bluetooth GATT database rather than a pairing or Classic Bluetooth fault.

HA1190 therefore advertises a small stable discovery beacon and publishes the real bootstrap
service under a new 16-bit generation on every Android GATT-server start. The advertisement
carries that generation; Helper v28 derives the matching B0/B2/B3 UUIDs before connecting. A
failed unverified bootstrap causes Android to rotate the namespace again.

## Central-mode sequence

1. Helper scans only for the stable beacon `D2D9E4BF-47F1-4E44-A8BB-A932FD5AFFFF`.
2. It reads protocol/generation bytes from manufacturer or service data and derives the current
   Android service, CONTROL B2 and SECURE B3 UUIDs.
3. It opens a plain BLE bootstrap (`RequiresANCS=false`), discovers only that fresh namespace,
   writes `PAIR`, and completes the encrypted B3 current-link challenge.
4. It writes `ANCS-HANDOFF`, releases the bootstrap link, and reconnects to the same peripheral
   with `RequiresANCS=true` without custom-service discovery.
5. HA1190 maps the protected second link to the verified bonded iPhone and subscribes to ANCS.
   The iPhone-owned F05/B4 telemetry relay remains optional.

The fixed F04 UUIDs are retained only for the legacy iPhone-peripheral route. Classic HFP/A2DP,
device names, pairing records, the system LE bond, and ANCS authorization are never changed.

## Installation

The bundle identifier remains `ru.natro.kx11ancshelper`; minimum iOS is 13.0 and both Bluetooth
background modes remain enabled. Build/install with the same Apple Development Team as before.
Install HA1190 and Helper v28 as a matched pair, open Helper once, select **Central**, and leave
it running in the background. Do not delete the car's Classic Bluetooth pairing for this test.
