# KX11 ANCS Helper v35 for iPhone

Helper v35 is the matched companion for Status Widget `v2.8.2-ha1198`. Select **Central** in
Helper and the iPhone-Central/ANCS route in Status Widget.

## Connection contract

- The first and only `CBCentralManager.connect` call uses
  `CBConnectPeripheralOptionRequiresANCS = true`.
- `PAIR` and the protected B3 current-link challenge run on that same `CBPeripheral`.
- `ANCS-READY` is withheld until `CBPeripheral.ancsAuthorized` becomes true. The
  `didUpdateANCSAuthorizationFor` callback resumes that original owner; there is no
  bootstrap-to-ANCS disconnect or replacement connection.
- On iOS 17 and later Core Bluetooth auto-reconnect is enabled. On earlier iOS releases the same
  retained peripheral is reconnected with bounded backoff.
- System AutoReconnect and the older-iOS manual backoff are mutually exclusive; Helper never
  issues a second `connect` while Core Bluetooth owns recovery.
- A pending `.connecting` owner has a 15-second watchdog. When Core Bluetooth stalls, Helper
  cancels that same attempt, invalidates the stale dynamic namespace, and waits for a fresh D2D9
  advertisement before reconnecting. It never opens a second owner in parallel.
- `stopScan` and connection cancellation are issued only while `CBCentralManager` is
  `poweredOn`. Restoration callbacks arriving earlier retain their exact owner and defer commands
  until `centralManagerDidUpdateState` reports `poweredOn`.
- A namespace that produced `CBError.uuidNotAllowed` is quarantined. Repeated advertisements for
  that same generation are ignored, reconnect backoff is preserved, and only a different Android
  generation can start the next attempt.
- Stable Central and Peripheral restoration identifiers, the selected role, the Android identity,
  and the dynamic D2D9 namespace survive app relaunches.
- Both stable Core Bluetooth managers are recreated synchronously during a restoration launch, so
  background recovery does not depend on opening Helper's screen.
- An ordinary radio loss preserves the iPhone F05/B4 peripheral service. Only an explicit hard
  reset after a stale/invalid GATT database requests a fresh Android namespace.
- Helper subscribes to Android's dynamic B4 wake characteristic. Its one-byte notifications wake
  `bluetooth-central` in the background, after which Helper samples fresh public battery/network
  state and publishes it through the iPhone-owned F05/B4 relay.

## Honest readiness

Green status is shown only after all six proofs are present on the current runtime:

1. iOS reports `CBPeripheral.ancsAuthorized == true` for the original RequiresANCS owner.
2. Helper completed B3 and received `ANCS-READY` on that same owner.
3. Android enabled both ANCS Notification Source and Data Source CCCDs.
4. Android subscribed to the iPhone-owned F05/B4 CCCD.
5. B4 contains a valid battery value.
6. B4 contains a known network type.

A B4 read alone is diagnostic and is not treated as a subscription. An unsubscribe clears only
the B4 proof and does not tear down the protected ANCS owner.

## Build

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone, keep the existing bundle identifier
`ru.natro.kx11ancshelper`, and install over the previous Helper so the Core Bluetooth restoration
identity and iOS ANCS authorization remain associated with the app.
