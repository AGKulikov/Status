# Natro 2.2.16

- Android package: `ru.natro.statuswidget`
- Android version: `2.2.16`
- Android version code: `208021250`
- Compatible Helper: `67`; recommended Helper: `68`

## Fixes verified from the 2026-08-23 road logs

- Information tiles share one upstream connector subscription, preventing the 64-listener crash.
- The music delay starts at `LOCKED_BOOT_COMPLETED`; a later `BOOT_COMPLETED` cannot move it.
- Media timing state uses non-blocking SharedPreferences writes on the exact-timer path.
- A due music plan is kicked immediately when Bluetooth ACL/A2DP reports a connected route and
  remains active until actual `PLAYING` is observed or its bounded retry budget expires.
- A transient HWGPS `notFixed` edge must remain stable for 2.5 seconds before DR becomes inactive.

The stable update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
