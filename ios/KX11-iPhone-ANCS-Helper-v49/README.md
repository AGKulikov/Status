# KX11 ANCS Helper v49 — production Route-A candidate

Helper v49 pairs with HA1215 and makes Route A the production topology:

- the car keeps one ordinary system bond and one user-visible iPhone identity for Classic
  HFP/A2DP/PBAP;
- Helper publishes one encrypted BLE GATT service, `F201`, on that iPhone for peer proof,
  CONTROL and telemetry;
- Android connects to the exact selected bonded iPhone and owns ANCS discovery/recovery;
- Helper never creates another Bluetooth name, address identity, pair, or UI row.

Classic and BLE remain different Bluetooth bearers; the product contract is one logical phone and
one system bond, not a claim that HFP/A2DP and GATT can share one physical bearer.

This source checkpoint is a candidate, not a claim that reconnects are already field-stable. It
has portable source/replay coverage, but still needs the hosted unsigned Xcode build and the
physical iPhone/car acceptance matrix below.

## Production behavior

Route A is **iPhone Peripheral / Android Central**. Helper advertises only the F201 service UUID.
It does not advertise a local name, manufacturer alias, Route-B UUID, BLE address, or a second
pairing identity. The stable installation UUID is disclosed only by encrypted peer proof; CONTROL
ownership must match that proof before telemetry is released.

F201 uses stable Core Bluetooth restoration identifiers and both `bluetooth-peripheral` and
`bluetooth-central` background declarations remain present so the diagnostic route can be safely
reclaimed after an update. When no authenticated Android owner is attached, Route A remains
locally active and advertising. The absence of Android is not treated as a Helper startup error.

Helper cannot observe the car's HFP/A2DP/PBAP state. HA1215 is therefore authoritative for the
policy “Classic is connected but ANCS is not ready”: Android performs the bounded aggressive
recovery against the exact selected bond while Helper stays restorable and available. Helper does
not poll Classic state and does not invent a second proximity-based identity.

An authenticated F201 CONTROL owner is also not restarted merely because Android is temporarily
waiting for ANCS. ANCS is a dynamic iOS GATT service: Android keeps the live owner and waits for the GATT Service Changed indication when the service inventory changes. Only a real CONTROL
unsubscribe, radio loss, terminal Core Bluetooth error, or exact route failure enters the existing
full same-role fence → owner-zero → drain → fresh-generation recovery. Late callbacks from the old
generation are ignored.

## Experimental Route B

Route B (iPhone Central / Android Peripheral, F202 + `CBConnectPeripheralOptionRequiresANCS`) is
retained for diagnostics, not selected by the production UI. A developer can expose it only with
the launch default:

```text
-KX11ANCSHelper.experimentalRouteB YES
```

Without that explicit flag, a request to select Route B is rejected. If an older persisted build
starts in Route B, v49 first reclaims that exact namespace and runs the confirmed-quiescence switch
to Route A; the two Core Bluetooth roles are never acquired concurrently. Diagnostic switching
still uses durable BRS2 write-ahead state, exact C/A token matching, source terminal confirmation,
owner count zero, a fixed drain interval, and a fresh target generation.

Route B discovers only F202 and proves the Android installation UUID. It never accepts a local
name, advertisement name, MAC-like value, cached settings row, or nearest RSSI as identity.

## Pairing, restoration and privacy boundaries

An in-place v48 → v49 update keeps bundle ID `ru.natro.kx11ancshelper`, the version-independent
installation UUID and both stable Core Bluetooth restoration identifiers. The normal update and
recovery path does **not** delete the system pair, clear Bluetooth/GATT caches, toggle the global
Bluetooth radio, or touch unrelated BLE devices such as the GPS receiver.

Core Bluetooth restoration is reclaimed before a fresh manager generation is allowed to own a
route. Corrupt or contradictory durable evidence fails closed. A missing manager/publication
callback reaches a bounded visible failure; retry first drains the exact possibly-live namespace.
There is no unbounded timer that tears down a healthy CONTROL owner, and no direct reconnect of a
cached manager after exact link loss.

## Telemetry

The Helper reads battery percentage, charge/external-power state, network class and protected-data
lock state through public iOS APIs. The top-level owner assigns the 16-bit sequence and forwards a
sample only to the exact ACTIVE generation after encrypted CONTROL ownership is proven. Freezing,
failure and generation replacement clear pending telemetry and reset the sequence namespace.

## Build and portable verification

Run from this directory:

```sh
./verify-v49-contract.sh
```

The suite executes the shared H/C/A/T wire fixtures, strict negative decoding, switch/recovery
replays, restoration callback inversions, stale-generation fencing, exact owner-release ordering,
telemetry gates, UUID-only F201 advertising, production Route-A enforcement and the diagnostic
Route-B opt-in contract. It also audits the exact source set, Xcode Sources membership, build
version, Info.plist modes and workflow packaging.

The Linux suite cannot compile against Apple's SDK. The hosted macOS workflow builds an unsigned
iPhone-simulator app with signing disabled. Neither result is a signed device IPA or a physical
device test.

## Physical acceptance still required

Before calling v49 stable:

1. Install over v48 while preserving the existing iPhone/car pair and verify one visible phone
   identity, Classic HFP/A2DP/PBAP and F201/ANCS operation.
2. Start with Classic already connected and ANCS absent; verify HA1215 recovers it without another
   pair, global Bluetooth reset, GPS BLE interruption, or reconnect storm.
3. Repeat Android restart, Helper foreground/background restoration, iPhone lock/unlock,
   Bluetooth off/on and 20 exact CONTROL disconnect/reconnect cycles; every real loss must use a
   fresh generation and every stale callback must be ignored.
4. Trigger an iOS ANCS service inventory change and verify Android keeps the live owner, consumes
   Service Changed and rediscovers instead of repeatedly disconnecting.
5. Verify increasing telemetry sequence values and real notifications after each recovery.
6. In an explicitly diagnostic build, run Route A → B → A and prove source owner zero before each
   target starts.

## iOS/platform limits

- If the user force-quits Helper, iOS may suppress Bluetooth state restoration and background
  relaunch until Helper is opened manually. The app cannot override that rule.
- iOS controls background advertising contents and scheduling. UUID-only discovery, restoration
  and Android's exact-bond reconnect are used together; the design does not depend on a name.
- ANCS characteristic access requires iOS authorization. Helper cannot bypass a user denial; the
  paired Android UI must report the actionable authorization state.
- Core Bluetooth and the car settings UI own their historical display caches. Protocol-level
  UUID-only identity cannot promise how another UI renders an old row.
