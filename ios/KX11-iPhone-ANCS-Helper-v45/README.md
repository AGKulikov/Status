# KX11 ANCS Helper v45 for iPhone

Helper v45 is paired exactly with Status Widget `v2.8.2-ha1211`. Install it over v44 without
changing the bundle ID or removing the system Bluetooth pair. The production default remains
**Peripheral** in Helper together with **iPhone Peripheral** in Status Widget; the reverse
**Central** route is the RequiresANCS path covered below.

## HA1211 managed proof

v45 keeps v44's physical-owner, restoration-adoption and bounded-recovery behavior, but hardens
the reverse Central protocol against an Android facade/alias race:

1. Helper creates a cryptographically random 128-bit challenge `Q` for the exact
   `(Geely owner UUID, protocol-2 F04 publication nonce)` tuple.
2. It synchronously persists and rereads that single tuple record before sending the exact
   17-byte B2 frame `[0x50][Q]` (`P/Q`). Retries and exact process restoration reuse the same Q.
3. The existing encrypted B3 and `ANCS-READY` exchange proves the current F04/ATT owner.
4. Android must subscribe current F05/B4 and send exact `[0x4C][Q]` (`L/Q`). This binds only the
   current reverse alias; it does not grant ANCS access or make readiness green.
5. Only a later exact `[0x41][Q]` (`A/Q`) from the same owner, subscriber, F04 publication and F05
   generation confirms the ANCS CCCDs.

All managed frames are binary and exactly 17 bytes, so they fit the default ATT payload. Plain
ASCII `PAIR`/`ANCS` remains only in the separate Peripheral diagnostic/bootstrap route. Q is never
written to the Helper journal.

## Persistence and restoration

The Q record is one durable value keyed by exact owner UUID and publication nonce. A write is
accepted only after `set + synchronize + strict reread` reproduces the same owner, nonce and
16-byte Q. A malformed record, unknown nonce, random-generation error or persistence/reread error
fails closed with zero P/Q writes.

`CBCentralManager.willRestoreState` creates a one-shot capability for the exact restored
`.connected` or `.connecting` wrapper. It may bind once to the first exact current F05 generation:
an already installed service, a `CBPeripheralManager`-restored service, or the first fresh F05
`didAdd` for that restoration episode. Callback order does not matter and no restored subscriber
is inferred. Rehydration still requires the exact current F04 service object with B2/B3/B4, the
persisted owner/nonce/Q tuple and the same local F05 generation. Alias proof remains false, so a
fresh L/Q is mandatory.

If F05 is replaced after P/Q, Helper preserves the current F04/Q transcript, clears alias/B4/ANCS
proofs and atomically rebinds it to the new exact F05 generation; it does not replay P/B3/READY.
An F05 unsubscribe also clears the transcript and waits for an attributable F04 publication,
physical terminal or manual action—there is no automatic Pair/cancel/connect loop.

Power loss, route/role stop, manual reconnect, app-issued connect, accepted current-owner
terminal, owner/F04/nonce change and F04 invalidation clear all ephemeral restoration, Q and alias
lineage. The durable Q remains available only for the exact persisted tuple.

## Preserved v44 behavior and identity

- one long-lived RequiresANCS Central owner and fixed F04 generation `2F04`;
- the v44 durable strictly-newer publication adoption, one-cancel/one-reopen spent root, bounded
  duplicate scans, late-terminal quarantine, power gates and AutoReconnect arbitration;
- no new cancel, connect, recovery-budget or timer primitive in the HA1211 proof paths;
- bundle ID `ru.natro.kx11ancshelper`;
- restoration IDs `ru.natro.kx11ancshelper.peripheral.stable` and
  `ru.natro.kx11ancshelper.central.stable`;
- durable publication-adoption key `KX11ANCSHelper.lastAutomaticPublicationAdoption.v44` and its
  v43 migration reader;
- fixed F04/F05 UUIDs, saved Geely identity and the system Classic/LE pair.

## Build and verification

Open `KX11ANCSHelper.xcodeproj`, select a physical iPhone and install without changing the bundle
ID. Do not force-quit Helper from the iOS app switcher.

Run:

```sh
./verify-v45-contract.sh
```

The verifier first runs the immutable v44 → v43 → v42 → v41 chain. It then checks source order,
binary frame size/opcodes, persistence-before-write, exact restoration/F05 callback-order replays,
L/Q and A/Q negative gates, reset boundaries, legacy-route separation, preserved identities and
unchanged cancel/connect/timer primitive counts.
