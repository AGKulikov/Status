# Natro 2.3.8 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.8`
- Android version code: `208021262`
- Android target: Android 9 / SDK 28 / arm64-v8a
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Headless Yandex Music recovery

- The exact exported Yandex `DebugMediaButtonReceiver` receives one explicit PLAY press with
  `FLAG_RECEIVER_FOREGROUND` and the observed 100 ms key-up delay. This wakes the player without
  opening its Activity.
- The receiver and `MusicBrowserService` are no longer started in parallel. Natro first gives the
  receiver ten seconds to publish a usable session and uses the browser only once as a fallback.
- A successful browser connection is controlled through that browser's own session token. Global
  media keys remain forbidden so the connected iPhone cannot accidentally receive PLAY.

## ANCS after an APK update

- `MY_PACKAGE_REPLACED` records an eight-second, monotonic BLE cleanup window before the new phone
  transport registers with Android. The Natro surface itself remains immediate.
- This prevents a killed old process and its still-retiring GATT registration from overlapping the
  replacement process. Normal boot and later reconfiguration are not delayed; reboot-stale marks
  fail open.
- The persistent 1,600-line phone journal is no longer read from cold flash in a startup
  constructor. History is merged lazily only when the diagnostics screen or export requests it.

## Parktronic and diagnostics

- The recorded KX11 baseline is `PrkgDstCtrlSts=3`; only value `2` denotes the independent
  parktronic overlay. 360 remains covered by `SwtDispOnAndOffStsResp=3` or
  `VisnImgDispModResp=1/2`.
- A real `3 -> 2` parktronic edge now resets the bounded notification fail-open and pauses both an
  already visible phone notification and queued deliveries.
- Expiry of the time-limited ECARX navigator window confirmation is a DEBUG diagnostic, not a
  yellow warning. It is a normal fallback to accessibility/lifecycle evidence, not a crash.

Helper 69 is intentionally unchanged. Natro 2.3.8 does not delete pairing, reset Bluetooth, accept
an arbitrary peer, open Yandex Music UI, or send global media keys.

Stable Android update certificate SHA-256:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
