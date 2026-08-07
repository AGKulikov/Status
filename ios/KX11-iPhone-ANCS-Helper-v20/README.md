# KX11 ANCS Helper v20 for iPhone

Helper v20 accompanies Status Widget HA1177. Select **Central** both in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## What v20 fixes

The v19 logs showed that Core Bluetooth could deliver the 15-second connect timeout only after
the Helper returned from background. The timeout ran first, started a full BLE reset, and the
already-successful `didConnect` callback arrived a few milliseconds later. The reset then
disconnected that valid link before `PAIR`/B3 discovery could finish.

v20 makes connection recovery generation-safe:

- every connect timeout has a monotonically increasing token, so a cancelled timeout cannot tear
  down a newer connection;
- `.connected` at timeout continues service discovery instead of cancelling the link;
- `.connecting` receives one three-second callback grace period, which lets a queued `didConnect`
  run after foreground resume;
- a truly stuck connection is reset only after that grace period;
- state restoration prefers the saved, connected or connecting peripheral and reuses it instead
  of opening a parallel connection;
- a late `didConnect` is rejected only when a real reset has already begun;
- delayed reconnect work is tokenized as well, so a cancelled reconnect cannot restart a newer
  session.

The Central and Peripheral restoration identifiers move to v20, preventing v19 manager state
from being restored into the new connection state machine. Classic pairing and the system LE
bond are not removed.

## Transport retained from v19

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
- `FF`: valid/external-power/charging/full flags from `UIDevice.batteryState`;
- `NN`: normalized active CoreTelephony type (5G, LTE, 4G, 3G, E, G or 1X);
- `SS SS`: little-endian sequence;
- `CC`: CRC-8/ATM.

The bundle identifier remains `ru.natro.kx11ancshelper`, minimum iOS is 13.0, and both
`bluetooth-central` and `bluetooth-peripheral` background modes remain enabled. Build and install
with the same Apple Development Team as the existing Helper. Install Helper v20 over v19, open it
once, select **Central**, and do not force-quit it.

Classic HFP/A2DP/PBAP, device names, pairing and ANCS authorization are never modified by Helper.
