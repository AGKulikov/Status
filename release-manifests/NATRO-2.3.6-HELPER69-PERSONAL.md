# Natro 2.3.6 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.6`
- Android version code: `208021260`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Notification delivery fixes

- Notification deferral now has exactly two explicit sources: packages selected by the user and
  the live ECARX 360-camera/parktronic signals `29021`, `29043` and `28995`. Arbitrary
  Accessibility windows no longer pause, freeze or defer notifications.
- A confirmed vehicle overlay can never hold a notification indefinitely. The configured maximum
  wait is an absolute monotonic deadline; after it expires, delivery fails open until the vehicle
  overlay changes state.
- Low-battery warnings persist their one-shot latch only after the status row or popup actually
  presents the warning. Lock-state changes retry a previously rejected warning, and one migration
  clears ambiguous latches written by older builds before presentation.

## Yandex Music cold start

- The first boot-recovery deadline now reproduces mSaver 2.7: one explicit
  `DebugMediaButtonReceiver` MEDIA_PLAY press and the exact `MusicBrowserService` bind are started
  together. KEY_UP is sent asynchronously after 100 ms, so it no longer blocks the retry thread.
- The exact receiver is a one-shot kick. Later attempts address only Yandex's exact MediaSession or
  refresh the exact MediaBrowser bind; there is no 24-command receiver flood.
- The rejected direct `startService` prewarm was removed. No Activity, global media key, implicit
  receiver or unrelated player is used.

## ANCS companion

Helper 69 is intentionally unchanged. Natro 2.3.6 does not alter pairing, delete bonds, reset
Bluetooth, accept an arbitrary peer, or allow multiple simultaneous GATT owners.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
