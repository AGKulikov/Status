# Natro 2.3.7 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.7`
- Android version code: `208021261`
- Android target: Android 9 / SDK 28 / arm64-v8a
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Faster boot recovery

- The three-second media deadline remains anchored to the first `LOCKED_BOOT_COMPLETED` edge. A
  later `USER_UNLOCKED`, `BOOT_COMPLETED` or ECARX QuickBoot boundary can arm the command, but can no
  longer restart the delay.
- `USER_UNLOCKED` is accepted as the earliest safe player boundary. This removes the observed wait
  for a much later `BOOT_COMPLETED` broadcast.
- Yandex Music is warmed in the background by binding its exact exported `MusicBrowserService`.
  Warmup opens no Activity and sends no PLAY. At the deadline the existing connection is upgraded
  to exact-session PLAY and raced with the one-shot exact media receiver.
- The phone/ANCS controller is the first integration stage after the retained-state barrier. Sprut
  presence and the remaining integrations follow without weakening Bluetooth ownership fences.

## Diagnostics

- Expiry of an ordinary non-windowed ECARX navigator observation is now DEBUG, not a false warning.
  A real WINDOWED confirmation expiring remains WARN.

Helper 69 is intentionally unchanged. Natro 2.3.7 does not delete pairing, reset Bluetooth, accept
an arbitrary peer, or permit multiple simultaneous GATT owners.

Stable Android update certificate SHA-256:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
