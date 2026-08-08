# KX11 ANCS Helper v21 for iPhone

Helper v21 accompanies Status Widget HA1178. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## What v21 adds

Helper now sends the current iPhone lock state in every existing B4 telemetry frame:

- iOS protected-data lock/unlock events publish an immediate changed frame;
- the one-second sampler and 30-second heartbeat retain the same recovery behavior;
- bit 5 of `FF` is `1` while the iPhone is locked and `0` while it is unlocked;
- no second validity bit or new characteristic is introduced;
- Status Widget can suppress notification cards while the iPhone is unlocked.

The Central and Peripheral restoration identifiers move to v21. Classic pairing and the system
LE bond are not removed.

## Transport retained from v20

Helper uses generation 2 only for the protected PAIR/B3 bootstrap and publishes a separate,
non-advertised generation-3 B4 relay on the iPhone-owned GATT database:

- bootstrap service: `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F02`;
- telemetry relay service: `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F03`;
- telemetry relay characteristic: `D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F03`.

After KX11 attaches its ANCS client to the verified iPhone, the same Android-owned GATT client
reads and subscribes to the generation-3 relay. No second BLE connection is opened for telemetry.

## Central-mode sequence

1. Helper publishes the local generation-3 B4 relay without advertising it.
2. Helper scans for KX11 generation 2 and connects with
   `CBConnectPeripheralOptionRequiresANCS`.
3. It writes `PAIR` to B2 and reads B3. The expected first ATT status 5 restores current-link
   security; the successful retry proves the live encrypted peer.
4. KX11 attaches its ANCS GATT client to that exact peer and discovers both ANCS and the local
   generation-3 relay.
5. KX11 reads B4 before ANCS setup and subscribes after ANCS is ready.
6. When the bootstrap connection is released, Helper waits for B4 read/subscription proof and
   shows **ANCS + ТЕЛЕМЕТРИЯ АКТИВНЫ**.

## Telemetry

The eight-byte frame is `A5 01 LL FF NN SS SS CC`:

- `LL`: exact public `UIDevice.batteryLevel` percentage;
- `FF`: valid/external-power/charging/full flags from `UIDevice.batteryState`, plus bit 5 for the
  iPhone lock state;
- `NN`: normalized active CoreTelephony type (5G, LTE, 4G, 3G, E, G or 1X);
- `SS SS`: little-endian sequence;
- `CC`: CRC-8/ATM.

The bundle identifier remains `ru.natro.kx11ancshelper`, minimum iOS is 13.0, and both
`bluetooth-central` and `bluetooth-peripheral` background modes remain enabled. Build and install
with the same Apple Development Team as the existing Helper. Install Helper v21 over v20, open it
once, select **Central**, and do not force-quit it.

Classic HFP/A2DP/PBAP, device names, pairing and ANCS authorization are never modified by Helper.
