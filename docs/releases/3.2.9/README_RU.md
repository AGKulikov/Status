# Natro3.2.9 /208021362 — касания музыки и результаты докачки

SOURCE подготовлен; Android CI, APK/signature и FIELD ещё OPEN.
Действующая ветка `fix/media-touch-329`. Подробности и источники:
[FIELD1911/1915](../../geely-kx11/research-2026-10-17-white-bar/FIELD_1911_1915_RU.md).

- Media button hit area follows glyph, screen scale and a48dp minimum; blank
  overlapping frame passes DOWN through. Cancel/outside release does not retarget.
  Text blank height and progress timeline no longer take another button's gesture.
  Whole editor geometry/persistence retained.
- Initialⓘ/ℹ removed from the shared display title in all Natro media views;
  raw identity,artist/album,embedded symbols and real letter i retained.
- QNX-first collector retains977exclusions; summary distinguishes copied/skipped/
  changing/unavailable and optional Android snapshots. logcat reads recent6000,
  filters before archive, retains partial allowlisted output and actual exit;
  kernel snapshot runs before logcat.15s/1MiB/no replay preserved.
- Reviewed backup335…361→362; no new settings/defaults/vehicle controls.

9local collector/parser tests PASS. New API28 MotionEvent, title and backup
regressions require full CI. Existing test enforcing whole-frame scrub superseded
by user17.10 MEDIA-TOUCH-329 and runtime dispatch test; seek commit remains guarded.
Navigator/Helper source unchanged; payload equality must be checked before delivery.
FIELD3.2.8 confirms collector isolation, not full archive or new3.2.9 behavior.
White bar, physical tap result and all-screen title appearance remain FIELD OPEN.
