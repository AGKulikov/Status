# KX11 ANCS Helper v19 for iPhone

Helper v19 accompanies Status Widget HA1176. Select the same role in Helper and in
**Status Widget → Phone → iPhone BLE role**.

## What v19 fixes

Helper v18 correctly measured battery level, charging state and LTE/5G, but Central mode sent
those values through the short-lived iPhone-central → KX11-server bootstrap connection. Android
then became the ANCS GATT client, the bootstrap connection was released, and the status remained
local to the iPhone (`KX11 WRITE: ещё не было`).

Helper v19 keeps generation 2 only for the protected PAIR/B3 bootstrap and publishes a separate,
non-advertised generation-3 B4 relay on the iPhone-owned GATT database:

- bootstrap service: `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F02`;
- telemetry relay service: `D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F03`;
- telemetry relay characteristic: `D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F03`.

After KX11 attaches its ANCS client to the verified iPhone, that same Android-owned GATT client
reads and subscribes to the generation-3 relay. No second BLE connection is opened for telemetry.
The old generation-2 B4 write/wake loop is not used after the secure handshake.

## Central-mode sequence

1. Helper publishes the local generation-3 B4 relay without advertising it.
2. Helper scans for KX11 generation 2 and connects with
   `CBConnectPeripheralOptionRequiresANCS`.
3. It writes `PAIR` to B2 and reads B3. The expected first ATT status 5 restores current-link
   security; the successful retry proves the live encrypted peer.
4. KX11 attaches its ANCS GATT client to that exact peer and discovers both ANCS and the local
   generation-3 relay.
5. KX11 reads B4 before ANCS setup, then subscribes after ANCS is ready. A one-second B4 read is
   the deterministic background refresh; notifications are the immediate change path.
6. When the bootstrap connection is released, Helper does not reconnect it. The UI becomes
   **ANCS + ТЕЛЕМЕТРИЯ АКТИВНЫ** after a relay read or subscription proves the working owner.

If no relay proof arrives within 30 seconds, Helper returns to one fresh, filtered bootstrap
attempt. A relay unsubscribe has a five-second grace period. Neither recovery deletes the Classic
pairing or the system LE bond.

## Telemetry

The fixed eight-byte frame remains `A5 01 LL FF NN SS SS CC`:

- `LL`: exact public `UIDevice.batteryLevel` percentage;
- `FF`: valid/external-power/charging/full flags from `UIDevice.batteryState`;
- `NN`: normalized active CoreTelephony type (5G, LTE, 4G, 3G, E, G or 1X);
- `SS SS`: little-endian sequence;
- `CC`: CRC-8/ATM.

The iPhone publishes on battery state/level and radio changes, on a foreground safety sample and
on periodic reads from KX11. Status Widget does not estimate charging or network type.

## Roles and installation

- **Peripheral** remains the original Helper v11-compatible `iPhone_ANCS` route.
- **Central** is the reverse `Geely_ANCS` bootstrap plus the generation-3 relay described above.

The bundle identifier remains `ru.natro.kx11ancshelper`, minimum iOS is 13.0, and the project keeps
both `bluetooth-central` and `bluetooth-peripheral` background modes. Build and install with the
same Apple Development Team as the existing Helper. Install Helper v19 and HA1176 together, open
Helper once, select **Central** on both devices, and do not force-quit it.

Classic HFP/A2DP/PBAP, device names, pairing and ANCS authorization are never modified by Helper.
