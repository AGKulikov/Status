# Natro 2.3.1 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.1`
- Android version code: `208021255`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69`

## Fixes from the 2026-08-24 18:18 road journals

- The first exact Yandex Music receiver PLAY remains at the configured boot deadline. Natro never
  opens the Yandex Music UI and never sends a global media key that could control the paired phone.
- Yandex's exported `MusicBrowserService` is requested once as a background process/session
  bootstrap. A connection request or failure is no longer logged as proof that playback started.
- After bootstrap, Natro polls only for the exact `ru.yandex.music` MediaSession. A session which
  advertises `ACTION_PLAY` or `ACTION_PLAY_PAUSE` is now actionable even while reporting
  `STATE_NONE`, matching the session observed in the supplied journal.
- Natro first sends `TransportControls.play()` to that exact session and verifies actual
  `STATE_PLAYING`. If playback is still inactive, it sends an idempotent MEDIA_PLAY press directly
  to the same resolved session. It does not use `AudioManager`, a global key, or an Activity launch.
- The Yandex-only recovery window is bounded to 20 attempts with five-second session polling and a
  two-second verification after an exact-session PLAY. Other players retain the five-attempt
  policy.
- Coalesced `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, and ECARX QuickBoot events no longer rewrite
  the original capture timestamp, so the journal reports the true end-to-end recovery duration.

## ANCS companion

The Android ANCS ownership/parser core is unchanged in this release. The supplied iPhone journal
identifies itself as Helper `68.0 (68)`, so Helper 69 must still be installed and run. Helper 69
keeps the same ANCS connection logic as Helper 68 while adding the Live Activity creation fence and
the slower C5 HELLO interval that remove the measured provisioning storm.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
