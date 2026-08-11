# KX11 ANCS Helper v43 for iPhone

Helper v43 is matched to Status Widget `v2.8.2-ha1208`. Install it over the previous Helper: the
bundle ID (`ru.natro.kx11ancshelper`), Core Bluetooth restoration IDs, saved Geely identity and
the system Bluetooth pair are unchanged.

The normal production default remains **Peripheral** in Helper together with **iPhone
Peripheral** in Status Widget. The reverse **Central** route is retained for diagnosis and for
the RequiresANCS connection path described below.

## HA1208 publication identity

Android keeps the GATT service and characteristics fixed at F04 (`B0/B2/B3/B4 ... 2F04`). It no
longer rotates the UUID. Each successful `onServiceAdded(SUCCESS)` publication receives a
separate persistent UInt24 nonce. Android advertises it in manufacturer data for company
`0xFFFF`:

```
[02, 2F, 04, nonceHi, nonceMid, nonceLo]
```

Protocol, generation and nonce are big-endian; nonce `0` and `FFFFFF` are invalid. The
scan-response keeps legacy protocol 1 (`[01, 2F, 04] + Geely_ANCS`) so Helper v42 can still find
the car, but v43 never treats protocol 1 as proof of a new publication.

Helper accepts protocol-2 data only from the exact persisted `CBPeripheral` object. Nonces use
monotonic UInt24 ring ordering: duplicates are idempotent, a strictly newer value invalidates old
delayed proof without resetting its budget, and a delayed older advertisement cannot roll the
current publication back. A persisted last-observed nonce helps reject old frames after relaunch;
it is not recovery proof until protocol 2 is observed again in the current process.

## Missing `didConnect` recovery

The 2026-08-11 07:01 trace showed a manual `centralManager.connect` request whose physical link
was visible on Android, while iOS delivered no `didConnect`, `didFailToConnect` or
`didDisconnect` for more than seven minutes. v43 therefore arms evidence observers immediately
after every actual app-issued `centralManager.connect`, including manual reconnects.

One recovery root binds all of the following:

- exact saved owner object and identifier;
- actual issued-attempt token;
- terminal and F04-invalidation generations;
- current fixed generation `2F04` and valid protocol-2 publication nonce;
- one root-claim token and its origin (automatic publication, explicit manual, or post-green
  reconnect).

A queued intent, restoration wrapper, foreign owner, protocol 1, wrong/stale nonce, wrong token,
wrong terminal generation, timeout alone or a pre-boundary frame cannot cancel anything. Exact
same-ID `retrieveConnectedPeripherals(F04)` remains useful physical-link proof, but it cannot
replace protocol-2 publication authority. Recovery scanning permits duplicate advertisements so
a frame can be bound after the request is issued; a matching `didConnect` synchronously stops
that scan. Late duplicate scan callbacks for the retained root are swallowed and never re-enter
`connectCentral` or reset an in-progress handshake.

After physical/publication proof, v43 waits a short grace for a late `didConnect`. It then
consumes the root before issuing exactly one cancel. Automatic adoption writes `(owner, nonce)`
as one UserDefaults record and requires a successful durability barrier before cancel; storage
failure is fail-closed. A terminal callback or read-only `.disconnected` observation permits one
deferred `RequiresANCS` reopen. That reopen gets a new attempt token but inherits the spent root,
so it cannot cancel again.

Automatic and explicit budgets are separate. The same nonce cannot re-arm automatic publication
adoption. A later explicit manual action may create one new root on that same publication. A
fully green session also authorizes one future same-publication radio-loss root; if that root
observes a different nonce, it is upgraded to durable automatic publication adoption.

Bluetooth power changes, terminal callbacks and late callbacks invalidate delayed evidence, not
the root budget. Stop/role changes clear in-memory work. No recovery path deletes the Classic or
LE pair.

## Existing handshake and readiness

All compatible v42/v41 contracts remain: one RequiresANCS owner,
`PAIR -> encrypted B3 -> ANCS-READY`, exact F05/B4 relay subscription, Android proof that both
real ANCS CCCDs are active, restoration claim #1 for the stale wrapper, two-sided restored-B4
proof, exact service-object validation after Service Changed, powered-on command gates and one
protocol-recovery budget. The old restoration claim #2 is intentionally superseded before its
fresh replacement is issued; the v43 publication root is then the sole destructive owner, so two
1.5-second grace timers cannot cancel the same request twice.

Green requires the exact owner, current-link B3, acknowledged ANCS-READY, Android ANCS/B4 CCCD
proof and valid telemetry. Reaching green closes the current missing-callback root; a mere
`didConnect`, service discovery, B3 or Helper ACK does not.

## Build and verification

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone and install without changing the bundle
ID. Do not force-quit Helper from the iOS app switcher.

Run:

```
sh ./verify-v43-contract.sh
```
