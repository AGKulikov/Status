# KX11 ANCS Helper v38 for iPhone

Helper v38 is the matched companion for Status Widget `v2.8.2-ha1201`. Select **Central** in
Helper and the iPhone-Central/ANCS route in Status Widget.

## Production ANCS connection model

- Android advertises one permanent link-anchor service. Its UUID does not rotate.
- Helper opens one `CBCentralManager` connection with
  `CBConnectPeripheralOptionRequiresANCS = true` and keeps that request pending.
- After `didConnect`, Helper discovers only Android's permanent F04 service, writes `PAIR`, and
  reads encrypted B3 on that same owner. Android may pre-attach its direct reverse GATT client to
  the current ACL, but ANCS discovery remains gated until B3 succeeds and Helper writes
  `ANCS-READY` with real iOS authorization.
- There is no dynamic namespace, UUID quarantine or elapsed-time pending-connect watchdog.
- If Core Bluetooth restores the saved owner as `.connecting` but never delivers a terminal
  callback, Helper does not cancel it merely because time passed. It first checks
  `retrieveConnectedPeripherals(withServices: [F04])` for the same saved identifier and scans for
  the same stable anchor beacon. While restoration remains pending, increasingly sparse read-only
  F04 table checks cover the case where Android establishes the ACL after startup and stops
  advertising before `didDiscover`; elapsed time itself never authorizes a cancel. Either positive
  result is current evidence that the car is present.
- After that evidence, one short reconciliation grace is allowed. If the restored app-local
  request is still stale, Helper cancels that owner exactly once, waits for `didDisconnect` (or
  `didFailToConnect` for a cancelled pending request), and reconnects the same `CBPeripheral` with
  `RequiresANCS` and iOS 17 `AutoReconnect`. No periodic cancel loop or identity rotation is used.
- A `retrieveConnectedPeripherals` result proves only a system-wide physical link; it never starts
  PAIR/B3 or marks readiness. Protocol discovery begins only after this app receives `didConnect`,
  or when `willRestoreState` originally returns an already `.connected` app-local owner.
- On iOS 17 and later Core Bluetooth owns reconnect through
  `CBConnectPeripheralOptionEnableAutoReconnect`. When `isReconnecting` is true, Helper issues no
  competing `connect` or `cancel` command.
- On older iOS versions one retained-peripheral retry is scheduled after a real disconnect or
  `didFailToConnect`; the resulting connect request is left pending without a timeout.
- Restoration uses stable Central and Peripheral identifiers. Resolution order is restored owner,
  saved `CBPeripheral` identifier, system-connected permanent service, then filtered scan.
- All Central commands are gated on `poweredOn`. A manual reconnect and the one evidence-driven
  restoration rescue are the only normal paths that cancel a live owner; both wait for a terminal
  Core Bluetooth callback before reconnecting.
- The iPhone-owned F05/B4 relay remains available on the same link for battery/network telemetry
  and Android's idempotent `ANCS-SUBSCRIBED` readiness proof.
- F05 publication completes before a fresh Central connect is issued, preventing Android 9 from
  caching a pre-relay iPhone GATT database.

## Readiness

Green status requires the current RequiresANCS owner, iOS ANCS authorization, Android's proof that
both ANCS CCCDs are active, Android's subscription to F05/B4, and a valid battery/network snapshot.
Loss of a readiness proof changes the UI state but does not tear down the physical connection.

## Build

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone, keep the existing bundle identifier
`ru.natro.kx11ancshelper`, and install over the previous Helper. Do not force-quit Helper from the
iOS app switcher: Apple does not relaunch a force-quit app for Bluetooth restoration.
