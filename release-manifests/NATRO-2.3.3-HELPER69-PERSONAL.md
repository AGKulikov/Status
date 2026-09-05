# Natro 2.3.3 + Helper 69 Personal Team

- Android package: `ru.natro.statuswidget`
- Android version: `2.3.3`
- Android version code: `208021257`
- iPhone bundle ID: `ru.natro.kx11ancshelper`
- Helper marketing/build version: `69.0` / `69` (unchanged)

## Fixes from the 2026-08-24 20:51 road journals

- Route A now distinguishes a completely unregistered `BluetoothGatt` wrapper from Android 9's
  silent-but-registered `clientIf`. A positive private registration at the existing eight-second
  watchdog safely permits guarded cache refresh, exact-owner close and a fresh selected-identity
  attempt instead of waiting for the platform's late 30-second status 133 callback. The one-wrapper
  invariant remains intact; an unprovable registration is still retained rather than churned.
- Enrolled presence scans are bounded to eight seconds and alternate with direct exact-identity
  attempts. A failed scan can no longer trap all remaining retries in consecutive long scans.
- The Headset Client AG bundle is re-read on a finite 1/3/7/15-second schedule until live signal
  strength or an explicit no-service state arrives, fixing boots where HFP connected before its
  signal fields were populated.
- Yandex Music receives package-scoped, idempotent `KEYCODE_MEDIA_PLAY` retries every two seconds
  during the first cold-start window. A real exact session remains preferred once usable;
  MediaBrowser is only a receiver-unavailable fallback. Natro never opens the player Activity and
  never emits a global media key that could control the paired phone.
- Notification pause now also follows the recorded ECARX external-display responses:
  `SwtDispOnAndOffStsResp` property 29021 (`3` means covered) with
  `VisnImgDispModResp` property 29043 as startup fallback. This covers the stock panel that emits no
  Accessibility window event. Existing notification, popup and queue lifetimes freeze and resume.
- The cellular value reserves the configured `LTE`/operator width before the initial em dash is
  attached, preventing delayed Helper telemetry from being clipped by the first narrow layout.

## ANCS companion

Helper 69 is intentionally unchanged. The supplied Helper journal publishes battery, LTE and lock
telemetry immediately after a successful Route A subscription; the failures are on Android's GATT
registration/recovery path. Natro 2.3.3 does not alter pairing, delete bonds, reset Bluetooth or
accept an arbitrary peer.

The stable Android update certificate SHA-256 remains
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

The Personal Team Helper contains no Push Notifications, `aps-environment`, App Group entitlement
or APNs private key. It must be signed with the user's Apple Personal Team in Xcode.
