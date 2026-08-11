# KX11 ANCS Helper v44 for iPhone

Helper v44 is matched to Status Widget `v2.8.2-ha1210`. Install it over the previous Helper. The
bundle ID (`ru.natro.kx11ancshelper`), Core Bluetooth restoration IDs, saved Geely identity and
the system Classic/LE pair are unchanged.

The production default remains **Peripheral** in Helper together with **iPhone Peripheral** in
Status Widget. The reverse **Central** route is retained for the RequiresANCS connection path.

## Why v44 exists

The 2026-08-11 19:32 trace restored the exact saved Central owner in Core Bluetooth
`.connecting`. A terminal callback then created a deferred reconnect intent, but Core Bluetooth
returned the same wrapper to system-owned `.connecting` before Helper could issue
`centralManager.connect`. Consequently there was no app-issued attempt token. When Android
later published fixed F04 protocol 2 nonce `000008`, Helper v43 correctly refused its ordinary
token-bound reclaim and remained pending until the user pressed Reconnect.

v44 adds one narrowly scoped restoration-publication adoption route. It does not weaken the v43
ordinary recovery contract.

## Restoration-publication adoption contract

The route can act only when all of these facts are simultaneously true:

- Core Bluetooth restored the exact persisted `CBPeripheral` owner as `.connecting`;
- the same exact wrapper is still retained and still system-owned `.connecting`;
- no app-issued connect token, manual reconnect, protocol recovery, restoration claim or pending
  cancel owns the request;
- the beacon is the exact manufacturer protocol-2 frame for fixed generation `2F04`;
- its UInt24 nonce is strictly newer than the last exact-owner nonce captured at the current
  restoration/terminal boundary;
- the `(owner, nonce)` tuple has not already been consumed in v44 or by v43 before upgrade.

Protocol 1, malformed frames, timeout alone, a system connection-table snapshot, a foreign owner,
an address-equal but different wrapper, and same/older/pre-boundary nonces are read-only.

During that exact restoration boundary, v44 observes advertisements in bounded low-duty windows
with `AllowDuplicates=true`. This lets Core Bluetooth deliver `N+1` for the same retained wrapper
after first reporting baseline `N`; every window is capped at 1.5 seconds and separated by
read-only 2/5/10/30-second backoff. A terminal boundary synchronously closes the old window and
rearms a fresh one, and generation/token guards prevent canceled stale work from stopping,
clearing, or overlapping the fresh scan. Scan timing never grants cancel or connect authority.

Before issuing any cancel, v44 writes `(owner, nonce)` as one durable UserDefaults record shared
with ordinary automatic publication adoption. A failed durability barrier is fail-closed. After
that record succeeds, the existing ordinary root is marked spent, deferred intents and competing
restoration timers are retired, and exactly one cancel is sent. A matching terminal callback or
read-only observation of the same wrapper becoming `.disconnected` permits exactly one deferred
reopen with `CBConnectPeripheralOptionRequiresANCS = true`.

The reopen inherits the spent root. A late `didConnect` cannot race a second cancel, duplicate
terminal callbacks cannot create a second intent, and a failed reopened request cannot issue a
second app-local connect for the same tuple. Core Bluetooth AutoReconnect may continue because it
does not submit another Helper connect call. The durable tuple remains spent after app relaunch,
Bluetooth power changes and upgrade from v43.

The sole reopen also has an exact phase and issued-token gate. If the old cancel's delayed
terminal arrives after the replacement request is already `.connecting` or `.connected`, it is
quarantined and cannot clear RequiresANCS ownership before the replacement `didConnect`. If
Bluetooth powers off after the replacement was submitted, powered-on routing treats a
`.disconnected` wrapper as exhausted and waits on `.connecting`/`.connected`; it never submits a
second app-local connect. The token quarantine is retired only by complete green proof or an
explicit manual/route reset.

An iOS 17 terminal with `isReconnecting=true` remains authoritative even if a read-only state
snapshot temporarily says `.disconnected` across Bluetooth power. v44 keeps the exact token and
RequiresANCS provenance and waits for the later system `didConnect`; the snapshot cannot exhaust
the sole reopen or trigger another Helper connect.

## Preserved contracts

The committed v43/v42/v41 verifiers still run unchanged. Their fixed F04 protocol-2 frame,
strict UInt24 ring ordering, one RequiresANCS owner, `PAIR -> encrypted B3 -> ANCS-READY`,
two-sided F05/B4 restoration proof, Service Changed validation, power gates, exact identity,
pair preservation and one-shot recovery contracts remain executable predecessors.

A matching app-issued `didConnect` still wins over all recovery evidence. Manual reconnect
remains an explicit user action and is independent of automatic publication adoption.

## Build and verification

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone and install without changing the bundle
ID. Do not force-quit Helper from the iOS app switcher.

Run:

```
sh ./verify-v44-contract.sh
```
