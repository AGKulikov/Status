# Natro 2.3.2 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.2`
- Android version code: `208021256`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Fixes from the 2026-08-24 19:17 road journals

- ANCS recovery retains the single selected enrolled `BluetoothGatt` wrapper after an optional
  presence-scan failure. An unobserved wrapper can no longer enter drain or allocate a replacement;
  only a real terminal callback permits teardown.
- Repeated C5 state callbacks are coalesced by finite wire ID and value. The initial snapshot is
  still complete, while duplicate state echoes no longer fill the protected ATT queue.
- Routine pre-existing ANCS replay records use logarithmic diagnostic checkpoints instead of one
  journal write per old notification.
- Yandex Music recovery rejects a stale `STATE_NONE` session when its target process is absent.
  It retries the exact exported receiver and background `MusicBrowserService` on a bounded cadence
  for approximately six minutes, verifies actual `STATE_PLAYING`, never opens an Activity, and
  never emits a global media key.
- The floating status window now reflows on internal width growth, so a late `LTE` value is not
  clipped until a later unrelated layout pass.
- When a configured external overlay covers the head-unit screen, the remaining status-card,
  popup and notification-queue lifetimes pause and resume after that overlay disappears.
- Sprut cloud challenge preflight rejects the relay-unsafe `/` character anywhere in the standard
  Base64 proof and requests a fresh challenge before sending a predictably invalid answer.

## ANCS companion

Helper 69 is intentionally unchanged. Its latest supplied journal confirms the Live Activity
creation fence is working. Android 2.3.2 changes only recovery around the same authenticated,
selected-bond ANCS route; it does not alter pairing, delete bonds, reset Bluetooth, or accept an
arbitrary peer.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
