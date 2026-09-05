# Natro 2.3.5 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.5`
- Android version code: `208021259`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Fixes from the 2026-08-24/25 journals

- A silently registered Android P GATT client is now retired behind a bounded two-second
  process-wide fence before the one exact enrolled iPhone owner is retried. Startup quiet time is
  three seconds. This avoids a rapid close/reopen race without introducing a second GATT owner.
- Helper telemetry is requested immediately after ANCS reaches READY, before optional C5 traffic.
  Helper radio type no longer claims that HFP supplied availability, operator or signal bars, and
  failed hidden HFP reads now record their actual reflection failure class.
- A decoded ANCS notification is presented immediately. Its bundle identifier supplies a safe app
  name until the serialized App Display Name response arrives; a missing cosmetic name can no
  longer expire and discard the notification.
- Yandex Music ignores its inert `STATE_NONE` boot token, keeps sending PLAY only to its exact
  package receiver while the exact MediaBrowser bind is pending, and uses the exact session only
  after it becomes usable. No Activity or global media key is used.

## ANCS companion

Helper 69 is intentionally unchanged. Natro 2.3.5 does not alter pairing, delete bonds, reset
Bluetooth, accept an arbitrary peer, or allow multiple simultaneous GATT owners.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
