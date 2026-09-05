# Natro 2.3.4 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.4`
- Android version code: `208021258`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Fixes from the 2026-08-24 21:58 road journals

- ANCS Route A is unchanged from 2.3.3. All three new Android sessions reached READY, and the
  Helper activated telemetry immediately after each authenticated subscription.
- HFP signal backfill now stops only after a live HFP AG sample, never because Helper LTE telemetry
  arrived first. The finite exact-phone reads continue at 1/3/7/15/23/39/71 seconds and record
  whether each AG bundle actually contained service and signal fields.
- Yandex Music keeps mSaver's exact package receiver as the first PLAY. If that does not create a
  session within two seconds, Natro starts the exported background MusicBrowserService and binds
  its MediaBrowser. As soon as an exact Yandex MediaSession exists, Natro sends only
  `TransportControls.play()` to that token. It never opens an Activity or emits a global media key.
- Notification pause now also tracks `PrkgDstCtrlSts` property 28995. Recorded values 2/3 mean
  the parktronic overlay is active; 1 means inactive. This is independent of the two 360-camera
  properties, which remain at inactive baselines during a parktronic-only overlay.

## ANCS companion

Helper 69 is intentionally unchanged. Natro 2.3.4 does not alter pairing, delete bonds, reset
Bluetooth, accept an arbitrary peer, or allow multiple simultaneous GATT owners.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
