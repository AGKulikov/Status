# KX11 ANCS Helper v40 for iPhone

Helper v40 is the matched companion for Status Widget `v2.8.2-ha1205`. For normal daily use,
keep the proven defaults: select **Peripheral** in Helper and the **iPhone Peripheral** route in
Status Widget. In this production route, Helper owns the iPhone GATT server and Status Widget
reconnects to that exact saved iPhone owner; B4 telemetry and ANCS continue on the same link.

Select **Central** in Helper together with the iPhone-Central/ANCS route in Status Widget only as
the alternate diagnostic/recovery route. Its bounded recovery machinery is retained for testing
the reverse-role path, but it is not the recommended daily configuration.

## Alternate Central diagnostic/recovery model

- Android advertises one permanent F04 link anchor. Helper connects one exact `CBPeripheral` with
  `CBConnectPeripheralOptionRequiresANCS = true`.
- After app-local `didConnect`, `PAIR` and encrypted B3 prove the current ATT owner. Helper then
  writes `ANCS-READY` exactly once even if the immediate `CBPeripheral.ancsAuthorized` snapshot is
  false. That Boolean is diagnostic: iOS can update it after pairing through
  `didUpdateANCSAuthorizationFor`.
- The authoritative access proof is Android's `ANCS-SUBSCRIBED` command on F05/B4 after both real
  ANCS CCCDs are active. This proof permits green readiness even if the earlier Boolean snapshot
  remained stale. An observed false authorization callback invalidates the previous CCCD/access
  proof and turns readiness orange, but never disconnects the current owner; green requires a new
  current-owner `ANCS-SUBSCRIBED` proof.
- A restored `.connecting` owner has no generic elapsed-time watchdog. Exact F04 reachability may
  trigger ownership claim 1. If its fresh replacement reaches a physical system connection but
  still loses app-local `didConnect`, claim 2 is allowed only after
  `retrieveConnectedPeripherals(withServices: [F04])` returns the exact identifier. A beacon or
  elapsed time cannot authorize claim 2, and there is no third claim.
- Restoration accepts only the `CBPeripheral` whose identifier matches the previously persisted
  Geely identity. It never selects and persists an arbitrary restored `.connected`/`.connecting`
  fallback. Claim-2 system-table proof is hard-bound to that same persisted identifier.
- Each claim cancels at most one current request and waits for `didDisconnect`,
  `didFailToConnect`, or the exact owner's observed `.disconnected` state before reopening.
- A terminal callback with `isReconnecting == false` immediately stores one exact-owner deferred
  connect intent. A matching late `didConnect` atomically consumes that intent and is accepted;
  seeing `.connecting` alone retains the sole intent in a read-only wait and never creates a
  duplicate app-local connect. When `isReconnecting == true`, iOS 17 AutoReconnect remains the
  sole owner and Helper issues no competing command.
- Deferred connections retain the exact `CBPeripheral` in RAM and are consumed only after
  `poweredOn` and F05 publication. All Central commands are power-gated. Stop or role change
  clears pending work without deleting the system pair, saved identity, or restoration IDs.
- The iPhone-owned F05/B4 relay carries battery/network telemetry, the Android wake subscription,
  and the idempotent `ANCS-SUBSCRIBED` proof.
- A new physical `didConnect` always starts B4 readiness as false. For the special process-
  restoration path where iOS restores an already `.connected` Central owner and does not replay
  `didSubscribe`, Helper accepts one B4 hint only after two independent callbacks agree: Central
  restoration supplies the exact persisted owner wrapper, while Peripheral restoration supplies
  the exact restored F05 characteristic whose `subscribedCentrals` contains that same identifier.
  Either callback may arrive first. A `.connecting` restoration, scan/retrieve result, either half
  alone, or any fresh `didConnect` cannot seed B4. Even the complete hint cannot make readiness
  green without current B3/`ANCS-READY` and Android `ANCS-SUBSCRIBED` proof.
- Central callbacks and delayed discovery/security/wake operations require the exact current
  `CBPeripheral` wrapper rather than accepting another wrapper with the same stable identifier.
- Automatic destructive protocol recovery has one cancel/reconnect budget for the complete
  retained `CBPeripheral`/restoration lineage. A terminal `didDisconnect` or replacement
  `didConnect` does not re-arm it. If the replacement still returns `CBError.uuidNotAllowed`,
  Helper keeps the physical owner and waits for an exact current-object F04 Service Changed/new
  publication. That invalidation, a genuinely different owner/route, or a fully proven current
  session (B3 + accepted ANCS-READY + Android ANCS/B4 CCCD proof) re-arms recovery. A mere
  `didConnect`, service discovery, B3, or Helper ACK does not.
  The explicit reconnect button is an independent user action and remains available without
  re-arming the automatic budget.
- Local F04/F05 publication is similarly tied to the exact pending/published
  `CBMutableService` object and a monotonic generation. After stop/start, republish, or a role
  change, a late `didAdd` for an older same-UUID service is logging-only: it cannot clear the new
  pending state, remove the current service, or start the Central route. Peripheral-manager state
  restoration installs the exact restored service as its own current lineage.

## Readiness

Green explicitly requires the exact owner to be connected, current-link B3 security,
`ANCS-READY` write/ack, authoritative current-owner Android ANCS CCCD proof, Android's F05/B4
subscription, and valid battery/network data. Before the Android proof, an initial false iOS
snapshot is shown as a pending ANCS check rather than a reason to break the link or remove pairing.

## Build

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone, keep the existing bundle identifier
`ru.natro.kx11ancshelper`, and install over the previous Helper. Do not force-quit Helper from the
iOS app switcher.

Run `sh ./verify-v40-contract.sh` before packaging.
