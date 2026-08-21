# KX11 ANCS Helper v56 — Bluetooth vehicle control

Minimum supported iOS: 14.0. Helper v56 uses the system CryptoKit HKDF implementation for the
enrollment protocol; devices left on iOS 13.x cannot install this build.

Helper v56 pairs with the Natro C4-enrollment runtime and keeps Route A as the production
topology:

- the car keeps one ordinary system bond and one user-visible iPhone identity for Classic
  HFP/A2DP/PBAP;
- Helper publishes F201 with one plain enrollment-only characteristic plus encrypted peer proof,
  CONTROL and telemetry;
- Android bootstraps the rotating LE facade through explicit ECDH/SAS enrollment and then owns
  ANCS discovery/recovery;
- Helper never creates another Bluetooth name, address identity, pair, or UI row.

The application opens on one dark Live-Activity-style vehicle dashboard. Climate, Seats, Vehicle,
Comfort and Media sections share the same state and demo model as the Lock Screen controls, with
four scenarios and visible three-stage heat/ventilation levels. Route diagnostics, route selection,
first-time C4 enrollment, the detailed ANCS journal/export and binding reset are under Settings.
Everyday control adds no PIN, QR or Wi-Fi server; it reuses the exact authenticated Bluetooth owner.

Classic and BLE remain different Bluetooth bearers. The product contract is one logical phone and
one system bond, not a claim that HFP/A2DP and GATT share one physical bearer.

This source checkpoint is a candidate, not a claim that reconnects are already field-stable. It
has portable source/replay coverage, but still needs an Apple-SDK build and the physical
iPhone/KX11 acceptance matrix below.

The iPhone Simulator intentionally shows a diagnostic screen and does not construct CoreBluetooth
managers. Simulator cannot establish this ANCS accessory topology and otherwise emits the
misleading `XPC connection invalid` console line.

## Why enrollment is required

The supplied KX11 field dump shows a Classic-only iPhone record with no LE identity keys/IRK.
Direct LE `connectGatt` to that public Classic facade repeatedly reaches a bounded status 133.
v56 does not weaken identity to a BLE name, MAC/address equality, nearest RSSI or manufacturer
bytes. Instead, the user opens Helper and Natro and starts one foreground 60-second enrollment.

## C4 ECDH/SAS protocol

F201 adds C4, `D2D9E4C4-47F1-4E44-A8BB-A932FD5AF200`. C4 is the only unencrypted characteristic.
It transports only protocol version, installation UUIDs, fresh nonces, ephemeral P-256 public
keys and HMAC proofs. H, CONTROL, telemetry, ANCS content and long-term keys never travel over C4.
H and telemetry remain `readEncryptionRequired`; CONTROL remains `writeEncryptionRequired`.

Android must request ATT MTU 185 and observe `onMtuChanged` with MTU >= 103 before C4. Enrollment
HELLO and RESPONSE are exact 99-byte frames; no implicit long-write fragmentation is assumed.
Both peers derive a high-entropy secret through ephemeral P-256 ECDH, HKDF-SHA256 and the exact
domain `NATRO-F201-ENROLLMENT-V1`.

Both screens show the same unbiased, zero-padded 8-digit SAS. The user must explicitly confirm all
eight digits on both screens. The SAS, private keys and session master are memory-only and never
logged. Only one session/central is accepted, HELLO is rate-limited, the window is generation-
fenced, and security deadlines use monotonic uptime. Wall time is UI-only.

The two confirmations are order-independent. After a MAC-valid Android 0x03 CONFIRM, C4 returns
an authenticated 0x80 WAITING_SAS frame while the iPhone confirmation is still pending. Once both
users have confirmed, Helper returns authenticated 0x83 PREAUTH_ACK. Android must verify and read
that exact ACK before touching encrypted H. Plain C4 never returns ATT authentication,
authorization, encryption-key-size or encryption-required errors: Android 9 can otherwise start
SMP merely because it sees one of those statuses. Invalid C4 requests fail with a non-auth status.

Only the exact central/generation that actually read 0x83 may read encrypted H. An invalid,
partial or non-zero-offset H read never opens FINAL_COMMIT or CONTROL. Android verifies H and the
selected Classic `BOND_BONDED` state before FINAL_COMMIT. Helper then stages a ThisDeviceOnly
Keychain WAL containing the new pending long-term key while retaining the prior active binding.
The 0x85 ACK is keyed by the pending long-term key and a deterministic transaction ID. A following
routine C4 proof under the pending key returns authenticated 0x84 but does not yet replace the
active key. Only after Android actually reads 0x84 and then completes a new exact encrypted H read
does Helper atomically promote pending to active. ACK/H loss or either process dying is therefore
recoverable without replacing the prior active key too early.

The Helper screen has an explicit “Сбросить связь Natro” action with destructive confirmation.
When no live CONTROL owner or in-memory handshake exists, it atomically deletes both active and
pending Natro Keychain records. It never removes or changes the system Classic pair or Helper
installation identity. No API exports key material.

Exact frames and crypto outputs are frozen in `enrollment_v1_vectors.json` and checked by
`verify_enrollment_v1.py`; Android must consume the same fixture.

## Production behavior

Route A is **iPhone Peripheral / Android Central**. Helper advertises only the F201 service UUID.
It does not advertise a local name, manufacturer alias, Route-B UUID, BLE address, or a second
pairing identity. The Helper installation UUID in the C4 transcript is accepted as identity only
after ECDH/SAS or a long-term-key routine proof. CONTROL ownership must match that proof and an
encrypted H read before telemetry is released.

F201 uses stable Core Bluetooth restoration identifiers and both `bluetooth-peripheral` and
`bluetooth-central` background declarations remain present so the diagnostic route can be safely
reclaimed after an update. When no authenticated Android owner is attached, Route A remains
locally active and advertising. The absence of Android is not a Helper startup error.

Helper cannot observe the car's HFP/A2DP/PBAP state. Natro is authoritative for the policy
“Classic is connected but ANCS is not ready”. Helper does not poll Classic state, delete pairing,
reset the global radio, or invent a proximity-based identity.

An authenticated F201 CONTROL owner is not restarted merely because Android is temporarily
waiting for ANCS. ANCS is a dynamic iOS GATT service: Android keeps the live owner and waits for the GATT Service Changed indication. Only a real CONTROL unsubscribe, radio loss, terminal Core
Bluetooth error, or exact route failure enters fence → owner-zero → drain → fresh generation.

## Experimental Route B

Route B (iPhone Central / Android Peripheral, F202 + `CBConnectPeripheralOptionRequiresANCS`) is
retained for diagnostics. It is exposed only with:

```text
-KX11ANCSHelper.experimentalRouteB YES
```

Without that explicit developer flag, Route B selection is rejected. v56 never runs both roles
concurrently. Diagnostic switching retains durable BRS2 state, exact C/A token matching, source
terminal confirmation, owner count zero, a fixed drain interval and a fresh target generation.

## Persistence and privacy boundaries

An in-place v51 → v56 update keeps bundle ID `ru.natro.kx11ancshelper`, both stable Core Bluetooth
restoration identifiers, the device-only installation identity, and the active/pending enrollment
WAL unchanged. The v50 → v51 migration rule remains intact: the backup-restorable v50 UUID is
deleted rather than imported. Enrollment keys and active/pending WAL records remain
`ThisDeviceOnly`.

The normal path does **not** delete the system pair, clear Bluetooth/GATT caches, toggle the global
Bluetooth radio, or touch unrelated BLE devices such as the GPS receiver. Corrupt or contradictory
durable evidence fails closed. Re-enrollment preserves the old active key until pending recovery
is authenticated.

## Telemetry

Helper reads battery percentage, charge/external-power state, network class and protected-data
lock state through public iOS APIs. The top-level owner forwards samples only to the exact ACTIVE
generation after encrypted CONTROL ownership is proven. Freeze/failure/generation replacement
clears pending telemetry and resets its sequence namespace.

v56 removes the one-second telemetry timer. Battery, power, path and lock updates are event-driven.
After Android has observed 30 seconds without ANCS or telemetry traffic, it may serialize one
20-byte `R` (`0x52`) frame on the already encrypted CONTROL channel. The frame carries the current
Android role and a fresh non-zero 16-byte request token. Helper coalesces overlapping requests,
re-samples public iOS state, and emits one increasing `T` frame. `R` cannot enroll, switch roles,
open an unauthenticated route, or bypass the exact CONTROL owner/generation fence.

Android owns the 30-second quiet timer and must enqueue `R` behind ANCS discovery/data work; it
must never run a parallel GATT operation. Helper does not create its own poll. On diagnostic Route B
the existing single ATT-with-response queue keeps at most one write in flight and selects queued
C/A before telemetry. On production Route A, pending C/A indications are likewise drained before
the latest coalesced telemetry value.

## Live Activity, Dynamic Island and demo

Helper 56 embeds `NatroLiveActivityExtension` (minimum iOS 16.2) while the main Bluetooth app keeps
its iOS 14.0 deployment target. It maintains two simultaneous Lock Screen activities: the upper
climate panel contains the transparent Monjaro asset, cabin/outdoor/set temperatures, +/- and four
user-selectable climate buttons; the lower panel exposes ten configurable vehicle functions.
Three-stage seat heat/ventilation and steering-wheel heat show levels 1–3 and Auto instead of a
binary lamp. Dynamic Island has compact, minimal and expanded presentations for both panels.
Buttons use `LiveActivityIntent` on iOS 17+ and a small App Group mailbox; only the main Helper
process owns and writes to the protected C5 Bluetooth channel.

Settings contain automatic start, vehicle visibility/name, four upper and ten lower swappable
controls, explicit start/stop for both cards and a clearly marked demo mode. Demo starts without a
car with 19 °C cabin, 7 °C outside and 22 °C set point; both activities and the in-app dashboard
update from that same local state. Demo never changes the real ANCS boolean returned to Apple
Shortcuts.

Local `Activity.request` is permitted by iOS only while Helper is foreground-active. Helper starts
the card automatically on foreground activation and on a confirmed ANCS transition when the app is
active; an already-running card continues to receive Core Bluetooth updates in the background.
After force-quit, expiry, or a background relaunch with no existing activity, a fully background
first start requires an APNs push-to-start provider and cannot be implemented by a local Bluetooth
callback alone. Helper says this explicitly in Settings instead of claiming an impossible start.

Apple Shortcuts receives three App Intents: `Получить состояние ANCS`, `Ожидать подключения ANCS`
(bounded to 25 seconds), and `Запустить Live Activity Natro`. iOS 26 does not expose an API for a
third-party app to register a new Personal Automation event trigger named “ANCS connected”. The
supported automation is: the system car-Bluetooth trigger → wait for ANCS → condition on the Bool
result → the user's commands. No Shortcuts automation is required for Helper's own Live Activity
autostart.

## Connection journal

Helper keeps an always-on, bounded 800-line diagnostic journal covering Core Bluetooth power and
restoration, F201 publication/advertising, enrollment stages, CONTROL/C5 subscriptions, peer-ready
transitions and terminal/retry failures. Notification content, raw protocol payloads, keys, tokens
and device identifiers are filtered out. Settings can hide/show the journal, clear it and export a
plain `.log` file for troubleshooting; hiding affects presentation only, not collection.

## C5 vehicle control

Helper 56 retains C5, `D2D9E4C5-47F1-4E44-A8BB-A932FD5AF200`, without changing the occupied C2 role
protocol, and accepts a session only after the legacy 39 catalog IDs plus the final boundary
arrive. Thirteen v56 IDs are optional, capability-gated additions, so mixed v55/v56 operation does
not strand the session. C5 is encryption-required and is available only to the exact authenticated CONTROL owner
in the ACTIVE route generation. Route A uses indications toward Android and writes-with-response
toward Helper; diagnostic Route B reverses those directions. Both use the existing serialized ATT
owner and clear pending frames on freeze, disconnect or generation replacement.

`natro-car-remote-v1` is a fixed 20-byte frame with CRC-16, transaction ID, monotonic sequence and
bounded command age. It carries a finite one-byte control registry ID, never an arbitrary ECARX
function ID or zone. Values are exact signed 32-bit integers; range fields declare a scale so
temperature and percentage values do not sacrifice low ECARX enum bits to `Float32` rounding.

Natro advertises only runtime-supported entries. Helper disables unavailable controls, confirms
mechanical commands locally, rejects extra flags, and repeats HELLO when a catalog is torn instead
of exposing a partly synchronized UI. Android keeps its capability, vehicle-state and read-back
gates. The 52-entry finite registry adds rear-seat heat/ventilation, ambient mode/effect/color/theme
and close-only commands for four windows plus the sunroof. Massage is intentionally absent because
this vehicle does not have it. Window/sunroof opening was not exposed as an unsafe one-tap action.
Natro also sends three read-only STATE frames outside the command registry: `0xFC` cabin
temperature (tenths °C), `0xFD` outdoor temperature (tenths °C), and `0xFE` exact ANCS readiness.
They are never catalog or COMMAND IDs, so the original 39-control baseline remains frozen and older
Helpers can ignore both these states and optional v56 catalog entries.
The shared fixture is `docs/car-remote-v1-vectors.json` and the portable audit is
`verify_car_remote_v1.py`.

## Build and portable verification

Run:

```sh
./verify-v56-contract.sh
```

The suite executes the unchanged `docs/ancs-v2-wire-vectors-v52.json` H/C/A/R/T fixture and the
new `docs/car-remote-v1-vectors.json` C5 fixture;
deterministic ECDH/SAS/C4 enrollment and routine vectors; strict negative decoding;
switch/recovery replays; restoration inversions; stale-generation fencing; exact owner release;
UUID-only advertising; Route-A enforcement; and Route-B developer gating. It audits the exact
Xcode Sources membership and build version.

The Linux suite cannot compile against Apple's SDK. Its PASS is not a signed IPA or physical test.
Signing the app and extension requires enabling the App Group
`group.ru.natro.kx11ancshelper` for both bundle identifiers in the Apple developer profile.

## Physical acceptance still required

Before calling v56 stable:

1. Install over v51 without changing the existing system pair or Natro enrollment. Verify the
   existing protected CONTROL owner reconnects; then repeat a fresh enrollment with matching SAS and
   verify one visible phone identity, Classic HFP/A2DP/PBAP, F201 and ANCS.
2. Test mismatch/cancel/60-second expiry, wrong central, MTU below 103, five-attempt rate limit and
   background cancellation. None may expose H/CONTROL or replace the old binding.
3. Kill Helper/Natro before and after COMMIT/0x85/routine ACK/encrypted H. The pending WAL must
   converge or preserve the old binding without manual pair deletion.
4. Repeat Android restart, Helper restoration, iPhone lock/unlock, Bluetooth off/on and 20 exact
   CONTROL reconnects. Stale callbacks must not cross generation/session fences.
5. Trigger an ANCS service inventory change and verify Service Changed rediscovery instead of a
   reconnect storm. After 30 seconds of quiet, send one serialized R and verify exactly one fresh,
   increasing T response. Repeated R writes while a source callback is pending must coalesce.
6. In an explicitly diagnostic build, run Route A → B → A and prove owner zero before each target.
7. Enable demo away from the car and verify both cards start, show 19°/7°/22°, four upper and ten
   lower controls, level indicators, temperature steps, Dynamic Island states and a ДЕМО badge.
8. With demo disabled, run “Получить состояние ANCS” and “Ожидать подключения ANCS” in Shortcuts
   before and after the real C5 ANCS-ready transition; the returned Boolean must never use demo.
9. Enable journal presentation, repeat one successful and one interrupted connection, export the
   log and verify stage/error detail is present without notification text or protocol secrets.

## iOS/platform limits

- If the user force-quits Helper, iOS may suppress Bluetooth restoration/background relaunch until
  Helper is opened manually. The app cannot override that rule.
- iOS controls background advertising contents and scheduling. v56 does not depend on a name.
- ANCS characteristic access requires iOS authorization. Helper cannot bypass a user denial.
- Core Bluetooth and the car UI own historical display caches; UUID-only protocol identity cannot
  promise how another UI renders an old row.
