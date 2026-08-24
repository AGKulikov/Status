# Natro 2.2.18

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.18`
- Android version code: `208021252`
- Compatible Helper: `67`; recommended Helper: `68`

## Fixes verified from the 2026-08-24 road logs

- ANCS Control Point writes which Android rejects synchronously as locally busy are retried under
  the existing request watchdog instead of terminating and poisoning a healthy exact owner.
- Optional C5 output is paced, coalesced, quarantined correctly and preempted whenever ANCS needs
  the shared Android 9 ATT slot.
- Enrolled-owner presence recovery starts after eight seconds, before Android's commonly delayed
  30-second GATT 133 callback.
- A locked-boot event only freezes the playback history. The configured delay begins at ordinary
  `BOOT_COMPLETED` or ECARX QuickBoot, when third-party players can accept commands.
- Yandex Music is never opened on screen. Natro sends the exact receiver a 100 ms PLAY key press,
  also requests background playback through `MusicBrowserService`, and makes at most five attempts
  ten seconds apart while waiting for an observed playing MediaSession.
- The one-upstream connector subscription hub and Android system share chooser remain covered by
  release regression tests.

The stable update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
