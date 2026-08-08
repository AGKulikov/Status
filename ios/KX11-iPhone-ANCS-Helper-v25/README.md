# KX11 ANCS Helper v25 for iPhone

Helper v25 accompanies Status Widget HA1182. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## What v25 fixes

The reported v24 log reaches `Central connected` and the current D2D9 service, but every B2/B3
enumeration is rejected with `CBErrorDomain:8` / `uuidNotAllowed`. Retrying the same F02 UUID on
the same peer cannot repair a Core Bluetooth/Android GATT database that has already been cached
with incompatible handles.

v25 keeps the bounded v24 recovery flow and moves both private databases to fresh UUID namespaces:

- bootstrap B0/B1/B2/B3/B4: generation 4 (`...2F04`);
- post-handoff iPhone-owned telemetry B0/B4: generation 5 (`...2F05`);
- Central and Peripheral restoration owners are new v25/g4/g5 identifiers;
- service and characteristic discovery remains serialized and non-overlapping;
- Classic pairing, the system LE bond and ANCS authorization are not deleted.

Status Widget HA1182 and Helper v25 must be installed together because the private UUIDs are a
matched protocol version.

## Central-mode sequence

1. Helper publishes the local generation-5 B4 relay without advertising it.
2. Helper scans for KX11 generation 4 and connects with
   `CBConnectPeripheralOptionRequiresANCS`.
3. It requests the current D2D9 service by UUID, then enumerates B2/B3 without a characteristic
   UUID filter.
4. It writes `PAIR` to B2 and reads B3. The expected first ATT status 5 restores current-link
   security; the successful retry proves the live encrypted peer.
5. KX11 attaches its ANCS GATT client to that exact iPhone and reads/subscribes to the local B4
   relay.
6. A later B4 unsubscribe is reported, but does not tear down the protected ANCS owner.

## Telemetry retained

The eight-byte frame is `A5 01 LL FF NN SS SS CC`:

- `LL`: public `UIDevice.batteryLevel` percentage;
- `FF`: power flags plus bit 5 while the iPhone is locked;
- `NN`: normalized active CoreTelephony type;
- `SS SS`: little-endian sequence;
- `CC`: CRC-8/ATM.

The bootstrap service is `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04`. The iPhone-owned telemetry
relay is generation 5 at service `...2F05`, characteristic `...B4...2F05`.

The bundle identifier remains `ru.natro.kx11ancshelper`, minimum iOS is 13.0, and both
`bluetooth-central` and `bluetooth-peripheral` background modes remain enabled. Build and install
with the same Apple Development Team as the existing Helper. Install v25 over the previous
Helper, open it once, select **Central**, and do not force-quit it.

Classic HFP/A2DP/PBAP, device names, pairing, the system LE bond and ANCS authorization are never
modified by Helper.
