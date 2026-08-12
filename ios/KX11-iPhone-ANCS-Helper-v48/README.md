# KX11 ANCS Helper v48 — transport-v2 candidate

Helper v48 is a clean-room replacement for the previous dual-role BLE runtime. It keeps both
supported topologies, but gives each topology a separate coordinator and lets one persisted
switch reducer own every start, stop, restoration and role change. This source checkpoint is a
candidate, not a claim that reconnects are already field-stable.

v48 also repairs the v47 field failure where the UI could remain forever in `reducer=starting`
after Core Bluetooth logged `XPC connection invalid`, or simply while Android had not yet attached.
Local route operation and authenticated peer readiness are now separate: successful F201
advertising or F202 acquisition makes the selected role ACTIVE, while telemetry remains fenced
until the exact encrypted CONTROL owner is proven. Generation-keyed manager/publication and
target-start watchdogs turn a missing Core Bluetooth callback into a bounded visible failure with
safe retry instead of an unbounded disabled selector.

## Paired topology contract

Use v48 only with the matching Android transport-v2 implementation from the same source
checkpoint. A mixed v46/legacy Android runtime is not a supported wire combination.

- **Route A — iPhone Peripheral / Android Central.** Helper publishes encrypted service `F201`.
  Android owns the GATT client and is the deterministic first closer during a switch.
- **Route B — iPhone Central / Android Peripheral.** Helper connects to encrypted service `F202`
  with `CBConnectPeripheralOptionRequiresANCS`; Android owns the GATT server and consumes the
  iPhone ANCS service through its reverse observer.
- Both services use the same three logical characteristics: peer proof, CONTROL and telemetry.
  CONTROL is encrypted write + indication. H/C/A are fixed 20-byte protocol-2 frames and
  telemetry is a fixed 8-byte CRC-8/ATM frame.

The peer proof is the stable installation UUID carried in H. C and A echo the exact target mode
and 128-bit switch token. A Bluetooth display name, MAC/address guess, cached device row or
advertisement name is never accepted as identity.

## Name-free discovery and pairing

Route A advertises only its service UUID. v48 does not publish `iPhone_ANCS`, another local name,
manufacturer data, or a second alias. Route B scans only for `F202`. The visible iPhone name in a
car or Android Bluetooth screen is therefore not part of this protocol and may still be rendered
or cached by the operating system.

An in-place v46/v47 → v48 update keeps bundle ID `ru.natro.kx11ancshelper`, the stable Core Bluetooth
restoration identifiers and the version-independent v2 installation UUID. The first v48 launch
reads the v46 selected-role preference and performs a local-only same-role migration drain before
starting a fresh v48 generation. The normal update and reconnect path does **not** delete the system
Bluetooth pair, ask the user to clear Bluetooth cache, or recreate a pair.

## Confirmed-quiescence role switch

A role selection is written durably before a Core Bluetooth effect is issued. Acquisition is
fenced first. A local switch sends C; a remote switch persists the received C and sends A. The old
route cannot release its manager and the new route cannot start until the applicable control
handshake, exact local terminal callback, explicit local owner count zero and a fixed drain
deadline have all completed. Timeouts, contradictory evidence, corrupt persistence and
unattributable restoration fail closed instead of starting both routes.

Repeated requests for the same target coalesce. A conflicting target is rejected. Ordinary exact
link loss uses the same full same-role drain and a new generation; it is not a direct reconnect of
a cached manager. During CLOSED/corrupt restoration, both stable restoration namespaces are
reclaimed in drain-only mode and no target route is started.

The UI may select either mode while the reducer is ACTIVE even if no transport peer is currently
READY. During FREEZING, DRAINING, STARTING or FAILED, the selector is disabled so the two
coordinators cannot compete.

An absent Android peer is not a Core Bluetooth startup failure. Route A may remain ACTIVE while
advertising F201 and waiting for CONTROL subscription; Route B may remain ACTIVE while scanning
for F202. Conversely, a manager that never produces an actionable state, a publication that never
completes, or a reset that never recovers reaches a bounded `targetStartFailed`. Retry always
retires/drains the exact possibly-live namespace before allocating a fresh generation.

## Telemetry

The production source uses public iOS APIs for battery percentage, external-power/charge state,
network class and protected-data lock state. The top-level owner assigns the 16-bit sequence and
forwards a sample only to the exact ACTIVE role and generation after its v2 control owner is
ready. Freezing, link recovery and a new generation clear the pending route data and reset the
sequence namespace.

## Build and verification

The host-independent contract suite is:

```sh
./verify-v48-contract.sh
```

It reads the shared Android/Swift H/C/A/T fixtures and the canonical switch-transition JSON,
executes strict negative decodes, 40 alternating role switches, 20 same-role recoveries and the
stale/timeout/conflict/control-retry/restoration callback replays. The v48 additions replay a
missing XPC/manager callback, stale watchdog tokens, local-operational versus exact-peer readiness,
the telemetry fence and Route-B release-before-failure ordering. It also audits the exact v48 file
set, Xcode Sources membership, Info.plist modes and workflow packaging.

The hosted macOS workflow then performs the real unsigned iPhone-simulator `xcodebuild`. This
Linux workspace has no Apple SDK, so a passing source/replay suite alone is not an Xcode build or
physical-device acceptance result.

Before calling this transport stable, the following field acceptance is still required with the
paired Android v2 build and a physical iPhone:

1. Install over v46 or v47 once from each previously selected role; keep the existing system pair.
2. Complete at least 20 A → B → A cycles without pair deletion, Bluetooth-cache clearing,
   duplicate protocol identities, overlapping route owners or an unbounded reconnect loop.
3. Repeat exact-owner loss, Bluetooth off/on, Android process restart, Helper process restoration,
   foreground/background and hot-update cases; every run must either recover through a fresh
   generation or remain visibly fail-closed.
4. Verify all telemetry fields and increasing sequence values on both routes, and verify ANCS
   enable/disable plus notification replay against the product acceptance criteria.

## iOS/platform limits

- If the user force-quits Helper from the app switcher, iOS may suppress Bluetooth state
  restoration and background relaunch until Helper is opened manually. The app cannot override
  that platform rule.
- Bluetooth power loss, missing encrypted ownership, an absent callback, corrupt durable state or
  a mismatched Android build may leave the runtime fail-closed. That is intentional; v48 will not
  guess an owner or silently start the other route.
- Core Bluetooth and the car/Android settings UI own their display caches and system pair. UUID-
  only discovery prevents protocol dependence on those names, but cannot promise how another UI
  renders historical device rows.
- Background execution and the timing of battery/network updates remain subject to iOS scheduling.
