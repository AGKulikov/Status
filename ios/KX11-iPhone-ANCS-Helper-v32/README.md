# KX11 ANCS Helper v32 for iPhone

Helper v32 is the matched companion for Status Widget `v2.8.2-ha1194`. Select **Central** in
Helper and the iPhone-Central/ANCS route in Status Widget.

## Connection contract

- The first and only `CBCentralManager.connect` call uses
  `CBConnectPeripheralOptionRequiresANCS = true`.
- `PAIR`, the protected B3 current-link challenge, and `ANCS-READY` run on that same
  `CBPeripheral`; there is no bootstrap-to-ANCS disconnect.
- On iOS 17 and later Core Bluetooth auto-reconnect is enabled. On earlier iOS releases the same
  retained peripheral is reconnected with bounded backoff.
- System AutoReconnect and the older-iOS manual backoff are mutually exclusive; Helper never
  issues a second `connect` while Core Bluetooth owns recovery.
- A pending `.connecting` owner is observed indefinitely and is never cancelled by a timer.
- Stable Central and Peripheral restoration identifiers, the selected role, the Android identity,
  and the dynamic D2D9 namespace survive app relaunches.
- An ordinary radio loss preserves the iPhone F05/B4 peripheral service. Only an explicit hard
  reset after a stale/invalid GATT database requests a fresh Android namespace.
- Helper subscribes to Android's dynamic B4 wake characteristic. Its one-byte notifications wake
  `bluetooth-central` in the background, after which Helper samples fresh public battery/network
  state and publishes it through the iPhone-owned F05/B4 relay.

## Honest readiness

Green status is shown only after all five proofs are present on the current runtime:

1. Helper completed B3 and received `ANCS-READY` on its original RequiresANCS owner.
2. Android enabled both ANCS Notification Source and Data Source CCCDs.
3. Android subscribed to the iPhone-owned F05/B4 CCCD.
4. B4 contains a valid battery value.
5. B4 contains a known network type.

A B4 read alone is diagnostic and is not treated as a subscription. An unsubscribe clears only
the B4 proof and does not tear down the protected ANCS owner.

## Build

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone, keep the existing bundle identifier
`ru.natro.kx11ancshelper`, and install over the previous Helper so the Core Bluetooth restoration
identity and iOS ANCS authorization remain associated with the app.
