# Natro instrument-panel design

## Non-removable KX11 regions

Every preset and every editor template must reserve these factory-owned areas at 1920×720:

- top ADAS/status strip (time, G-Pilot, temperature and indicator glyphs);
- right-side P/R/N/D selector;
- lower-left RPM and Eco Assistant wing;
- lower-right range, fuel and speed wing.

The Android confirmation dialog visible in the reference photograph is temporary and is not
part of the reserved geometry.

`v2/kx11-safe-area.png` is the design contract for these regions. Important Natro content must
stay in the green field. Decorative backgrounds may extend behind reserved regions only when
their loss cannot hide data or break the composition.

## V2 review

The V2 review intentionally contains three analog and three digital flagship directions before
expanding them into the requested 10+10 production set. This avoids multiplying an unapproved
visual system.

All render-time art uses vector-like geometry, gradients and type. In the Android renderer,
static dial art and surfaces will be cached; only changed telemetry, needles, navigation and map
surfaces will redraw.

Run:

```bash
python3 tools/instrument_concepts/render_v2.py
```

Local photo composites can be generated with `composite_v2.py`; they are excluded from Git
because the source is a user-supplied photograph.
