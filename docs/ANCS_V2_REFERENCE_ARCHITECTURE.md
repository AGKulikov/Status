# ANCS v2: reference architecture and non-negotiable contracts

This document is the normative design record for the clean-room rewrite of both iPhone ANCS
routes.  It intentionally describes observable state machines and ownership rules instead of
copying implementation code from third-party projects.

## Primary and reference sources

The implementation is checked against the following public sources:

* Apple, *Apple Notification Center Service Specification*:
  <https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/>
* Apple, `CBConnectPeripheralOptionRequiresANCS` and Core Bluetooth connection options:
  <https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionrequiresancs>
  and <https://developer.apple.com/documentation/corebluetooth/peripheral-connection-options>
* AOSP Android 9 `BluetoothDevice` and `BluetoothGatt`:
  <https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r8/core/java/android/bluetooth/BluetoothDevice.java>
  and <https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r8/core/java/android/bluetooth/BluetoothGatt.java>
* Nordic nRF Connect SDK ANCS client sample and service library, audited at commit
  `af15896d9e6c14a96192f45b7c6081d0b55f43a0`:
  <https://github.com/nrfconnect/sdk-nrf/tree/af15896d9e6c14a96192f45b7c6081d0b55f43a0/samples/bluetooth/peripheral_ancs_client>
  and
  <https://github.com/nrfconnect/sdk-nrf/tree/af15896d9e6c14a96192f45b7c6081d0b55f43a0/subsys/bluetooth/services>
* Nordic Android BLE Library, audited at commit
  `4a86e6c6d191371698cfdbe9b30b46d0df41ec21`:
  <https://github.com/NordicSemiconductor/Android-BLE-Library/tree/4a86e6c6d191371698cfdbe9b30b46d0df41ec21>
* BlueKitchen BTstack ANCS client and tests, audited at commit
  `7fa68bd9ea40aa86672e6a47bef0ab9afc27506d`:
  <https://github.com/bluekitchen/btstack/tree/7fa68bd9ea40aa86672e6a47bef0ab9afc27506d/src/ble/gatt-service>

BTstack is used only as a behavioral reference.  No source is copied from it.  Its license is
not compatible with treating its implementation as drop-in production code for this project.

## Shared ANCS rules

Both routes implement the same ANCS consumer contract:

1. iOS is the Notification Provider and exposes ANCS as a GATT server.  The accessory is the
   Notification Consumer and performs GATT-client operations.
2. Access is attempted only on an encrypted, system-authorized link.  Pairing and ANCS consent
   remain operating-system responsibilities; the application never removes a bond to recover.
3. A new connection or service epoch owns newly discovered service, characteristic, and
   descriptor objects.  Objects from an older epoch are never reused.
4. The Generic Attribute Service Changed indication is enabled when available.  ANCS may be
   published or removed by iOS; one indication schedules one serialized rediscovery.
5. The parser and replay classifier are armed before the Notification Source CCCD is enabled,
   because iOS may send existing notifications immediately.
6. The mandatory sequence is serialized: discover ANCS and all required handles, subscribe to
   Notification Source, subscribe to Data Source, then declare the rich ANCS route ready.
7. There is exactly one raw GATT operation in flight.  It has an owner epoch, operation token,
   expected object identity, and bounded completion policy.  A late callback cannot complete a
   newer operation with the same UUID.
8. Control Point writes are serialized.  Data Source responses are assembled across arbitrary
   ATT fragments with explicit bounds and reset on session loss.
9. Notification UIDs, app-name cache, request queue, fragment accumulator, replay counters, and
   deduplication state are session-local and are cleared on disconnect or ANCS unpublish.
10. Replay-only journal output is aggregated.  Accepted realtime events, malformed input, and
    queue-pressure drops remain immediately visible.

## Route A: Helper Peripheral, Android Central

The Helper owns one `CBPeripheralManager` route and publishes a fixed helper service without a
BLE local name.  Android owns one public `BluetoothGatt` client and discovers both the helper
service and Apple's ANCS on that same iPhone link.

The route has two acquisition modes, both bounded and explicit:

* daily recovery targets the already selected system bond and registers one public Android GATT
  client only after startup quiet and role-switch quiescence;
* an explicit bootstrap may scan for the fixed helper service, but may connect only after the
  result is attributable to the selected bond.  Device names are never identity evidence.

On Android 9 the public identity resolver requires the callback facade address to equal the
unique selected system-bond address.  The encrypted `H` installation UUID and exactly one active
owner are additional continuity gates; they never replace that equality because the UUID is not
a secret and public APIs expose neither the iPhone IRK nor an RPA-to-bond mapping.  Thus an
unresolvable private/RPA facade fails closed in both bootstrap and daily recovery with an
actionable diagnostic to reselect or restore the existing selected system bond without deleting
the pair; a rotating facade requires vendor-provided direct identity mapping.  Neither a name
match nor an arbitrary bonded peer may be promoted to the selected owner.

No Android advertiser, GATT server, hidden API, adapter toggle, bond removal, or hidden cache
refresh belongs to this route.  Helper telemetry is optional and cannot delay or own ANCS
recovery.  Android serializes the v48 telemetry notification CCCD after the mandatory
route-control indication CCCD and before ANCS subscription readiness.  A missing or rejected
optional telemetry CCCD is reported but does not block ANCS.  Telemetry frames are accepted only
from the successfully subscribed exact characteristic, owner epoch, and active route generation;
freezing or losing the link clears the subscription fence and all late frames are ignored.

Android 9 has an additional ownership hazard which the adapter must model explicitly.
`BluetoothDevice.connectGatt()` asks the Bluetooth process to register a client asynchronously,
but the public application callback does not expose completion of that registration.  AOSP's
`BluetoothGatt.close()` can unregister only after its private non-zero `clientIf` exists.  The
adapter must therefore never implement a short no-connection watchdog as `close unknown wrapper
and immediately allocate another`.  One wrapper remains the sole acquisition owner; a later
reassertion may target that same owner, while replacement is allowed only after a confirmed
terminal/settle boundary.  An unprovable owner blocks a role switch instead of permitting overlap.

## Route B: Android Peripheral, Helper Central

Android publishes a separate fixed service and UUID-only role advertisement.  Helper owns one
`CBCentralManager` connection and uses Apple's `CBConnectPeripheralOptionRequiresANCS` option.
The Helper does not open a second `CBPeripheralManager` relay in this topology.  It reads
Android's 20-byte `H` proof, subscribes the control characteristic, writes its own 20-byte `H`
proof, and writes the optional 8-byte telemetry frame with response on that same connection.
Android consumes iPhone ANCS through its one exact reverse-client observer; ANCS payloads are not
proxied through a private Helper characteristic.  This follows the same
peripheral-ANCS-client topology demonstrated by Nordic and BTstack.

The first inbound Route-B callback is not an owner merely because it is bonded.  Before binding
it, Android consumes `selectedSystemBondAddress` and requires a single exact match in the system
bond set plus direct callback-address equality.  The later `H` write must arrive through the
encryption-required characteristic from exactly one active GATT-server facade and must match the
already anchored installation UUID, when present.  Zero/duplicate selected bonds, another bonded
peer, a mismatched UUID, and an unresolvable RPA are all explicit fail-closed states.

Android 9 does not expose the native connection handle from its GATT-server callback as a public
GATT-client owner.  Therefore any ECARX-specific client observation mechanism is isolated behind
one platform adapter.  The route reducer, ANCS parser, switch coordinator, identity proof, and
operation queue do not depend on reflection.  If the adapter cannot establish one exact client
owner, the route fails closed; there is no public/hidden fallback race and no second same-epoch
owner.

The Helper Peripheral route and Helper Central route have separate coordinators, managers,
objects, restoration records, and callback epochs.  They are never alive as active routes at the
same time.  Sharing telemetry input does not imply sharing Core Bluetooth ownership.

Both routes use the same fixed `H` frame for post-connect mutual installation identity.  In Route
A Android reads the Helper's `H`; in Route B Helper reads Android's `H` and writes its own `H` on
the encrypted exact inbound owner.  A BLE name, rotating address, advertisement claim, or a
separate variable-length HELLO format is never accepted as that proof.

Each installation UUID is generated once, synchronously persisted before its first on-air use,
and excluded from settings export.  Android may learn an empty Helper identity only during an
explicit selected-bond bootstrap after an encrypted exact-owner `H`.  A different later Helper
UUID is an identity conflict requiring explicit user action; it is never silently explained as
RPA rotation or overwritten by daily reconnect.

If ANCS is absent, the exact owner may wait only after the Generic Attribute Service Changed
indication is subscribed.  If Service Changed is unavailable too, the route reports a stable
fresh-link requirement; it does not poll forever or silently enter an unwakeable `WAIT_ANCS`.

## Confirmed-quiescence role switching

Keeping both routes is allowed only under a single top-level switch coordinator:

1. Persist `desiredRole` separately from `activeRole` and allocate a non-zero switch epoch.
2. Freeze the old route: cancel its timers, retries, scans, new operations, and advertising.
3. Ask the remote endpoint to close the same old-role epoch when a control path is still usable.
   A send is an asynchronous operation, not a method-call side effect: `ROLE_CLOSE` uses a
   write-with-response or an indication and the route must retain the exact owner until its
   completion is attributable.  Retry uses the same target/token and the original absolute
   deadline; it never allocates a second switch.
   If the old route is offline, skipping C/A requires a typed route-adapter proof that the exact
   control peer does not exist.  A UI status, missing name, elapsed timeout, or failed write is not
   `NO_REMOTE_OWNER` evidence.  This proves safe local teardown only; it cannot change a remote
   app's persisted role.  If both apps were offline, the user may select the same target on each
   side independently, and neither side starts its target before its own old owners drain.  A
   single-sided offline change remains an explicit role-mismatch state rather than invoking an
   automatic fallback.
   Freeze plus `NO_REMOTE_OWNER` is one atomic reducer input: the coordinator must persist that
   evidence before executing local stop, and must not briefly emit a C/A attempt from a preceding
   generic “frozen” callback.
4. The peer persists the same target/token and freezes ingress before returning
   `ROLE_CLOSE_ACK`.  An exact duplicate `ROLE_CLOSE` is idempotent and retransmits the same ACK,
   so a lost indication cannot strand the initiator.  The initiator waits until it actually
   receives that exact ACK before stopping its client route.  The responder waits until its ACK
   transmission is accepted before entering local teardown.  The ACK proves committed intent,
   not physical disconnection.  While the control path is still alive, the current GATT-client
   endpoint is the deterministic first closer: Android in Route A, Helper Central in Route B.
   The server endpoint never disconnects first; it waits for the exact control subscriber/peer to
   become terminal before releasing its server.
5. Retain exact old wrappers until their terminal callbacks arrive.  A callback from an older
   epoch may only satisfy that old drain or be ignored; it cannot mutate the new route.
6. Require both local terminal evidence and remote committed-intent acknowledgement.  Then verify zero
   app-owned GATT client/server owners and wait a bounded post-terminal stack-settle interval.
7. Destroy all old route objects and increment the publication/session generation.
8. Construct and start the target route from new manager/GATT/service objects only.
9. A timeout, contradictory acknowledgement, or impossible owner count enters `FAILED_CLOSED`.
   The target route is not started.  A later user choice may retry teardown under a new epoch.
10. The target becomes committed when switching starts.  Repeating that same target coalesces;
   selecting the opposite target is rejected until the coordinator is `ACTIVE` again or an
   explicit failed-switch retry allocates a new epoch.  No input extends the original deadline.
11. Application/process restoration resumes a persisted drain before either route is allowed to
    start.  Restored inactive-role owners are drain-only.

An ordinary link loss, radio restart, exhausted route retry, or hot-update owner replacement uses
the same local safety gates without changing topology.  The peer retains its current role; no
`ROLE_CLOSE`/`ROLE_CLOSE_ACK` is sent.  The local endpoint freezes the exact source, observes its
terminal callback, proves zero app-owned owners, waits the stack drain interval, destroys the old
adapter/manager, and starts the same role with a fresh epoch and generation.  A cached GATT,
service, characteristic, or Core Bluetooth manager is never promoted into the new generation.

The absence of a v2 snapshot is a migration event, not automatically a first install.  Android
must drain the role recorded by the legacy Status Widget preference, and Helper must drain the
legacy v46 role/restoration namespace (including reordered restoration callbacks), before either
side writes its first v2 ACTIVE snapshot.  A present-but-empty, malformed, torn, or unknown-schema
snapshot fails closed and drains restored managers; it never falls back to a default role.

The Android and Swift reducers must pass the same transition vectors even though they are
implemented independently in their platform languages.

The cross-platform control and telemetry byte vectors are frozen in
[`ancs-v2-wire-vectors.json`](ancs-v2-wire-vectors.json).  Both platform verifiers decode and
encode those exact vectors; a change requires a protocol-version change rather than an implicit
one-sided migration.

`ROLE_CLOSE` carries the desired target topology and a fresh 128-bit switch token.  The peer
accepts it only on the current encrypted owner, freezes the same source epoch, and echoes that
exact target and token in `ROLE_CLOSE_ACK`.  A different token or target during a committed
switch is a conflict and fails closed; it never retargets an in-progress drain.  The control
characteristic is `write` plus `indicate`, never an unacknowledged notification.  Client-to-server
frames use ATT write-with-response; server-to-client frames use indications.  No source owner is
released merely because a framework send method returned successfully.

## Explicitly rejected techniques

The rewrite must not use any of the following as a recovery mechanism:

* matching or selecting a peer by `iPhone_ANCS`, `Geely_ANCS`, or another BLE local name;
* deleting/recreating a bond, cycling the shared car Bluetooth adapter, or rebooting;
* Android hidden `refresh()` or pretending that an app can synchronously clear the OS cache;
* multiple simultaneous `BluetoothGatt` wrappers for the same route epoch;
* starting the target role after a fixed delay measured only from a settings write;
* clearing an old wrapper before its terminal callback can be attributed;
* unbounded scan, connect, service-discovery, descriptor, or reconnect loops;
* automatic fallback from one user-selected role to the other.

## Acceptance gates

Before replacing the legacy runtime, the new implementation must satisfy all of these:

* pure reducer and operation-queue tests, including stale callbacks, timer/callback races,
  generation rollover, restoration callback permutations, and contradictory close ACKs;
* source gates proving role separation and absence of the rejected mechanisms;
* Android hosted compile/test/`geelyRelease` and macOS Helper compile/test on the same committed
  snapshot;
* at least 20 confirmed switches in each direction without pair deletion or Bluetooth toggle;
* hot Android APK replacement and hot Helper replacement while each route is fully ready;
* at least 20 radio-off/on and out-of-range recoveries per route on the same bond;
* a real new notification after every recovery, not only replayed Notification Center history;
* logs showing old-role terminal, remote acknowledgement, zero app owners, quiescence, and only
  then target-role start.

The legacy transport is not retained as a runtime fallback.  Its production sources and active
contract gates are removed from the candidate tree; historical release manifests remain only as
records.  A failed v2 gate therefore stays failed closed instead of silently re-entering either
legacy topology.
