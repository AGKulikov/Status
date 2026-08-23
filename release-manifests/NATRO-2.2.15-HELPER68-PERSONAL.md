# Natro 2.2.15 + Helper 68 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.15`
- Android version code: `208021249`
- Helper bundle: `ru.natro.kx11ancshelper`
- Helper build: `68`
- Helper marketing version: `68.0`

## Stable ANCS recovery

- Optional encrypted C5 vehicle frames use `WRITE_TYPE_NO_RESPONSE` with a local pacing slot.
  A missing optional Android characteristic-write callback can no longer reset a healthy ANCS
  GATT owner. Helper 67 and older remain ANCS-compatible, but Natro deliberately leaves C5 off
  when the safe write-without-response property is absent.
- Helper 68 holds a bounded iOS background task across same-role owner release, the fixed 750 ms
  drain and target-owner start. This prevents iOS suspension from stretching that drain to tens
  of seconds.
- Outer autonomous ANCS recovery keeps two immediate attempts, then uses capped 5, 10, 20 and
  30-second waits instead of 30, 60, 120 and 300 seconds.
- Successful but incomplete Helper F201 service inventories are counted as cache-sensitive on
  Android 9; absent ANCS alone remains a legitimate Service Changed wait. After repeated incomplete
  Helper discoveries the already-guarded cache refresh runs only as the exact failed owner closes.

## Diagnostic timing

- Natro records planned/actual/lateness timing for each Route-A timer and autonomous recovery
  timer, plus the complete Helper/ANCS/service-changed capability inventory.
- Helper records background-task lifetime, same-role drain planned/actual/lateness timing,
  owner-zero timing and target-start gaps. Identifiers, tokens, keys and protocol payloads remain
  excluded.
- Media auto-resume records the complete lifecycle: receiver queue entry/dequeue, frozen playback
  history, target plan and anchor, in-process and AlarmManager timers, delivery lateness,
  MediaSession/receiver selection, command dispatch duration, retries and first observed PLAYING.

## Music startup

- The countdown is armed from the boot receiver boundary on the dedicated high-priority media
  timer lane, before the shared launcher startup queue.
- The configured delay is measured from the persisted lifecycle anchor. Repeated boot-phase events
  re-arm the same absolute target and do not begin a new delay.
- Each retry remains an idempotent explicit PLAY command scoped to the selected player package;
  no global media key can be captured by the paired phone.

## Distribution

- The branch verification workflow compiles Helper with the Apple SDK and builds/tests Natro.
  When the complete repository signing-secret set is present, it signs only with the stable update
  certificate whose SHA-256 fingerprint is pinned before signing. With no repository signing
  secrets it preserves the verified unsigned handoff for the offline signer; a partial secret set
  still fails closed.
- The Android 9 update is v3-signed by exactly one signer, matching the installed stable Natro
  lineage; the certificate SHA-256 remains
  `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
- No APNs private key, Android keystore, password, enrollment key or device identity is stored in
  the repository or bundled in either application.
