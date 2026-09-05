# Natro 2.3.0 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.0`
- Android version code: `208021254`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69`

## Fixes from the 2026-08-24 11:02 road journals

- The configured playback deadline is unchanged: Yandex Music first receives its exact exported
  receiver PLAY press at the selected three-second boundary.
- If playback is not observed within two seconds, Natro escalates to Yandex's exported background
  `MusicBrowserService`. A usable exact MediaSession wins immediately; no route opens Yandex UI.
- Media traces now include whether the Yandex process is observable, the active-session inventory,
  the selected receiver/session/browser route, and the verification retry source.
- A registered enrolled GATT owner which reports connection-stage status `133`, `22`, or another
  non-success status is retired safely and receives one fresh direct saved-owner attempt before
  Natro enters the unfiltered exact-identity presence scan.
- A silent enrolled `connectGatt` still retains its sole wrapper. Its autonomous same-wrapper
  reassertion now runs after five seconds instead of fifteen; no parallel `clientIf` is allocated.
- Helper 69 remains the required companion. Its Route A/B ANCS ownership and recovery core is
  unchanged from Helper 68; its Live Activity creation fence and slower C5 HELLO polling remove
  the provisioning pressure measured in the supplied Helper 68 journal.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
