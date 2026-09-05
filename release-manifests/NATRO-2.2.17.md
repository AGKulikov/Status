# Natro 2.2.17

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.17`
- Android version code: `208021251`
- Compatible Helper: `67`; strongly recommended Helper: `68`

## Fixes verified from the 2026-08-23 23:03 road logs

- The configured music delay is anchored and armed inline at the `LOCKED_BOOT_COMPLETED`
  receiver boundary. It can no longer wait 5-6 seconds behind an older command on the single
  media timer lane before beginning the user's three-second countdown.
- The first explicit package-scoped `PLAY` remains the safe hot path. If the selected player has
  no exact MediaSession and its receiver does not wake it, Natro launches that exact player once
  and retries `PLAY` after 300 ms. No global media key is sent to the paired phone.
- Media diagnostics now record the package and playback state of up to six active sessions, the
  one-shot warm-launch result and its duration.
- ANCS recovery remains continuously bounded on Android. Helper 68 is strongly recommended because
  it holds an iOS background task across the 750 ms same-role drain; Helper 67 in the supplied log
  was suspended for about 116 seconds before publishing the complete F201 graph again.

The stable update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
