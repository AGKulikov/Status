# Natro 2.2.19 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.19`
- Android version code: `208021253`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69`

## Fixes from the 2026-08-24 08:17 road session

- Yandex Music keeps receiving its exported `DebugMediaButtonReceiver` PLAY press even after a
  cold `STATE_NONE` MediaSession appears. The receiver route and MediaBrowser fallback are now
  mutually exclusive, matching mSaver; Natro never opens the Yandex Music UI.
- The configured three-second playback deadline remains anchored at normal `BOOT_COMPLETED`; the
  supplied trace delivered it with one millisecond of lateness.
- An asynchronous ANCS Control Point status such as Android GATT `133` retires the failed exact
  transport owner immediately instead of being mislabeled as an iOS protocol rejection.
- Repeated C5 HELLO frames are coalesced for the measured full catalog drain window, preventing a
  second 100+ frame generation from filling the optional queue before `SYNC_COMPLETE` arrives.
- Helper starts and provisions Live Activity only on the C5 disconnected-to-connected edge. It no
  longer repeats foreground-only `Activity.request` on every state callback; the supplied Helper
  journal contained 890 such attempts with `existing=0`.
- Helper retains one explicit retry when it becomes foreground-active, fences concurrent Activity
  creation, and slows pre-sync HELLO polling from two to five seconds.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode; local Live
Activity creation is foreground-only by Apple's platform rule.
