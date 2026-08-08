# KX11 ANCS Helper v23 for iPhone

Helper v23 accompanies Status Widget HA1179. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## What v23 fixes

The reported log reaches a valid BLE connection, but v22 then starts a filtered B2/B3
characteristic discovery and retries another discovery from inside the error callback. Core
Bluetooth returns `CBError.uuidNotAllowed`, so the Helper destroys an otherwise healthy link.

v23 uses a serialized recovery sequence:

- service and characteristic discovery are unfiltered;
- each characteristic request is delayed until the previous delegate callback has returned;
- `uuidNotAllowed` retries are bounded and never overlap;
- after repeated failures, the Helper rediscovers the current service on the same ATT link before
  it considers a disconnect;
- `didModifyServices` discards invalidated `CBService` handles and rediscovers on the same link;
- a normal B4 unsubscribe no longer clears an already-proven ANCS handoff or starts a new Central
  bootstrap loop.

The Central and Peripheral restoration identifiers move to v23. Classic pairing and the system
LE bond are not removed.

## Central-mode sequence

1. Helper publishes the local generation-3 B4 relay without advertising it.
2. Helper scans for KX11 generation 2 and connects with
   `CBConnectPeripheralOptionRequiresANCS`.
3. It enumerates the current D2D9 service and B2/B3 characteristics without UUID filters.
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

The bootstrap service remains `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F02`. The iPhone-owned
telemetry relay remains generation 3 at service `...2F03`, characteristic `...B4...2F03`.

The bundle identifier remains `ru.natro.kx11ancshelper`, minimum iOS is 13.0, and both
`bluetooth-central` and `bluetooth-peripheral` background modes remain enabled. Build and install
with the same Apple Development Team as the existing Helper. Install v23 over the previous
Helper, open it once, select **Central**, and do not force-quit it.

Classic HFP/A2DP/PBAP, device names, pairing, the system LE bond and ANCS authorization are never
modified by Helper.
