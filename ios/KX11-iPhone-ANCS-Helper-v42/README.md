# KX11 ANCS Helper v42 for iPhone

Helper v42 is the matched companion for Status Widget `v2.8.2-ha1207`. For normal daily use,
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
  connect intent. A matching late `didConnect` atomically cancels any pending reclaim grace,
  consumes that intent, and is accepted. Consuming an intent is not an issued request: only the
  code immediately following an actual `centralManager.connect` call captures the monotonic
  request token, exact saved owner and current F04 publication generation (`2F04`). This
  provenance is independent of the reclaim state, so a later ordinary terminal can bind the
  descendant iOS 17 AutoReconnect generation to that earlier actual request and the latest
  terminal generation. A fresh direct request issued while the lineage is already active does not
  require AutoReconnect support; only reuse of a pre-terminal token for a descendant generation
  does. If that request/descendant remains `.connecting` without `didConnect`, a later exact
  saved-ID/current-2F04 beacon may arm the short grace even when
  `retrieveConnectedPeripherals(withServices: [F04])` remains empty. System-table proof remains
  the stronger parallel route. A beacon seen before the latest app-issued token, for another
  owner/generation, or while only a queued/restored/system request exists is read-only and cannot
  cancel anything. Grace expiry can consume at most one
  `cancel -> terminal/.disconnected -> same-owner RequiresANCS connect` claim. `didConnect`,
  `didFailToConnect`, `didDisconnect` and Bluetooth power-off invalidate old request evidence and
  grace; terminal and power callbacks do not reset the one-shot budget. The budget is re-armed
  only by full green proof, a validated replacement F04 object, explicit manual reconnect, or a
  genuinely different owner/route. If the fresh beacon arrives while Core Bluetooth still reports
  `.disconnected`, Helper retains that same-token evidence and starts grace only after its
  read-only observer sees `.connecting`; duplicate advertisement delivery is not required. Probe
  and wait logs are emitted only on state transitions. When `isReconnecting == true`, iOS 17
  AutoReconnect remains the sole owner and Helper issues no competing command.
- Deferred connections retain the exact `CBPeripheral` in RAM and are consumed only after
  `poweredOn` and F05 publication. All Central commands are power-gated. Stop or role change
  clears pending work without deleting the system pair, saved identity, or restoration IDs. If
  Bluetooth is switched off after an ordinary connect request was issued, Helper discards its old
  F04 proof but retains the exact owner and one-shot lineage; on `poweredOn`, a now-disconnected
  request is converted back into one deferred exact-owner connect through the normal route.
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
  publication. Exact invalidation alone does not re-arm recovery: Helper must first receive and
  validate a different exact `CBService` object with the required B2/B3/B4 characteristics.
  Helper retries service discovery sparsely at a capped cadence while waiting; the invalidated
  object and incomplete/error replacement candidates remain read-only and can never send PAIR or
  consume another reconnect. Service Changed also clears the dead F04 generation's READY, B3,
  Helper, ANCS and B4 proofs, so the validated replacement must send a new PAIR -> B3 ->
  ANCS-READY sequence. That validated replacement, a genuinely different owner/route, or a fully
  proven current session
  (B3 + accepted ANCS-READY + Android ANCS/B4 CCCD proof) re-arms recovery. A mere `didConnect`,
  invalidation callback, service discovery, B3, or Helper ACK does not.
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

Run `sh ./verify-v42-contract.sh` before packaging.
