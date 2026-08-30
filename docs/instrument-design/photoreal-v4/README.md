# KX11 Instrument Cluster — Photoreal V4

This folder documents the fourth visual-direction review for the KX11 instrument
cluster. The six review renders are intentionally kept out of Git because they
contain the user's vehicle photograph. This specification is the source of truth
for the approved direction and its later deterministic implementation.

## Approval gate

**Do not assemble, sign, or publish an APK until the user explicitly approves a
render direction and separately authorizes the APK build.** The V4 images are
concept renders only. No V4 cluster artwork may be implemented before visual
approval.

After approval, all typography, scales, icons, geometry, and map overlays must be
rebuilt deterministically at the native 1920 x 720 cluster resolution. Generated
text, repeated scale values, or other image-model artifacts are not production
assets.

## Immutable KX11 factory zones

Every direction is composed around factory content that remains visible and
cannot be hidden, moved, restyled, or duplicated:

- Top strip: time, left status icon, left/right chevrons, G-Pilot, temperature,
  and driver-assistance indicators.
- Right rail: E indicator and the P/R/N/D selector.
- Lower left: RPM value and unit, vehicle/ECO Assistant, and cyan status line.
- Lower right: range, fuel indicator and status line, and vehicle speed.

The Android confirmation dialog visible in the source photograph is transient UI
and is removed from every concept.

## Analog directions

1. **Monolith** — faceted graphite/titanium instrument chambers, deep polygonal
   wells, jeweled needles, and a narrow navigation spine between the instruments.
2. **Celestial** — three haute-horlogerie instruments with dark-green guilloche,
   champagne/platinum bridges, ruby hubs, and deliberately restrained lighting.
3. **Mechanical R** — interlocking open performance rings, smoked-titanium
   bridges, small integrated oil/power instruments, and compact maneuver data.

## Digital directions

4. **Glass Ribbon Navigation** — one optically bonded navigation world with slim
   translucent edge instruments; route and spatial depth form the composition.
5. **Precision Matrix** — nested etched tracks, tapered propulsion/speed ladders,
   an articulated segmented band, and a topographic route carved into the matrix.
6. **Horizon** — a calm cinematic road scene with a vehicle silhouette, spatial
   guidance rails, minimal embedded blades, and warm smoked-glass atmosphere.

## Production and performance intent

- Cache static wells, bezels, tick marks, textures, and shadows as GPU-ready
  layers; do not regenerate them per frame.
- Keep needles, cursor positions, numbers, warnings, and navigation data in small
  independent dynamic layers so only changed regions redraw.
- Reuse the existing map surface/texture instead of rasterizing a second map.
- Animate state transitions on demand and stop animation clocks when values are
  stable or the layer is not visible.
- Use the fixed KX11 telemetry already supplied by the vehicle rather than drawing
  duplicate speed, RPM, range, fuel, gear, or ADAS values.

## Review files (local only)

- `01-monolith-analog.png`
- `02-celestial-analog.png`
- `03-mechanical-r-analog.png`
- `04-glass-ribbon-digital.png`
- `05-precision-matrix-digital.png`
- `06-horizon-digital.png`
