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
- If Core Bluetooth omits both terminal delegate callbacks after that single cancel, a sparse
  read-only observer checks only the same `CBPeripheral.state`. It reuses the terminal reopen path
  after the state becomes `.disconnected`; every other state keeps waiting without another cancel
  or connect and without an elapsed-time deadline.
- Bluetooth power loss cancels the pending observer. After the manager returns to `poweredOn`, the
  retained `centralRestorationReconnectPending` route re-arms only that read-only observer; it does
  not send another cancel or connect.
- Every delayed restoration, manual or hard-reset reconnect is first stored synchronously as one
  RAM intent containing the exact `CBPeripheral`, reason and earliest execution time. Therefore a
  Bluetooth power transition cannot erase the owner in the gap before a delayed closure runs.
- `issueCentralConnect` itself requires `CBCentralManager.state == .poweredOn`. If unavailable, it
  retains that exact-owner intent and sends no Core Bluetooth command. Once F05 is published after
  `poweredOn`, the normal Central route atomically removes and consumes the intent exactly once.
  Stop and role change cancel the intent and its wake item.
- If power returns after a manual/hard-reset cancel has already changed the exact owner to
  `.disconnected` but before its terminal callback, the poweredOn/F05 route atomically clears the
  source flag, materializes one exact-owner intent and consumes it before ordinary routing. While
  the owner is still connected/connecting/disconnecting, a read-only state observer waits; it does
  not issue another cancel or connect. A late `didConnect` also waits and cannot accept stale
  manual/hard-reset state.
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
  restoration rescue are the only normal paths that cancel a live owner. Manual reconnect waits
  for a terminal callback; restoration waits for that callback or the same owner's confirmed
  `.disconnected` state before reopening.
- Manual reconnect consumes its pending intent on either `didDisconnect` or `didFailToConnect`, so
  a cancelled pending connection cannot make a later system reconnect look manual.
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

Run `sh ./verify-v38-contract.sh` before packaging to check the restoration/power-resume invariants.
