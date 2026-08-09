# KX11 ANCS Helper v36 for iPhone

Helper v36 is the matched companion for Status Widget `v2.8.2-ha1199`. Select **Central** in
Helper and the iPhone-Central/ANCS route in Status Widget.

## Production ANCS connection model

- Android advertises one permanent link-anchor service. Its UUID does not rotate.
- Helper opens one `CBCentralManager` connection with
  `CBConnectPeripheralOptionRequiresANCS = true` and keeps that request pending.
- Helper does not discover or access Android characteristics. Android attaches its ANCS GATT
  client to the same physical link and owns service discovery, security and CCCD setup.
- There is no PAIR/B3 bootstrap, namespace quarantine or automatic connection watchdog.
- On iOS 17 and later Core Bluetooth owns reconnect through
  `CBConnectPeripheralOptionEnableAutoReconnect`. When `isReconnecting` is true, Helper issues no
  competing `connect` or `cancel` command.
- On older iOS versions one retained-peripheral retry is scheduled after a real disconnect or
  `didFailToConnect`; the resulting connect request is left pending without a timeout.
- Restoration uses stable Central and Peripheral identifiers. Resolution order is restored owner,
  saved `CBPeripheral` identifier, system-connected permanent service, then filtered scan.
- All Central commands are gated on `poweredOn`. A manual reconnect is the only normal path that
  cancels a live owner, and it waits for `didDisconnect` before reconnecting.
- The iPhone-owned F05/B4 relay remains available on the same link for battery/network telemetry
  and Android's idempotent `ANCS-SUBSCRIBED` readiness proof.

## Readiness

Green status requires the current RequiresANCS owner, iOS ANCS authorization, Android's proof that
both ANCS CCCDs are active, Android's subscription to F05/B4, and a valid battery/network snapshot.
Loss of a readiness proof changes the UI state but does not tear down the physical connection.

## Build

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone, keep the existing bundle identifier
`ru.natro.kx11ancshelper`, and install over the previous Helper. Do not force-quit Helper from the
iOS app switcher: Apple does not relaunch a force-quit app for Bluetooth restoration.
