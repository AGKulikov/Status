# KX11 Instrument Cluster — Flat V6

V6 replaces the rejected volumetric V5 direction with ten strictly flat 2D
layouts. The functional reference is Audi Virtual Cockpit / Q8: clean black
canvas, precise white typography, restrained red highlights, switchable classic,
navigation, sport, and driver-assistance views. The designs are original and do
not reproduce Audi branding or proprietary assets.

Reference: https://www.audi-mediacenter.com/en/audi-technology-lexicon-7180/user-operation-displays-and-infotainment-16948

## Approval gate

Do not implement a V6 layout or assemble, sign, or publish an APK until the user
explicitly approves a direction. Native-resolution implementation review and APK
authorization are separate gates.

Generated renders are composition studies only. Typography, scales, icons, map
content, and geometry must be rebuilt deterministically at 1920 x 720. Generated
text and scale artifacts are not production assets.

## Immutable KX11 zones

- Top: time, status icons, chevrons, G-Pilot, temperature, and ADAS indicators.
- Right: E indicator and P/R/N/D selector.
- Lower left: RPM and ECO Assistant.
- Lower right: range, fuel, and speed.

These factory regions cannot be hidden, moved, restyled, overlapped, or
duplicated. Only the transient Android confirmation dialog is removed.

## Flat design rules

- No simulated instrument wells, glass tunnels, bevels, metal, shadows,
  extrusion, photoreal objects, or material depth inside the display.
- Use native 2D vectors, pure OLED black, crisp white/gray type, and restrained
  red/cyan/green/amber state colors.
- Maps use planar roads and labels. Any perspective view must remain line-based
  and must not add 3D buildings or lighting effects.
- Layout variants are view modes of one system, not separately running screens.

## Flat analog / circular views

17. **Classic Map** — medium open rings around a dominant planar traffic map.
18. **Classic Assist** — large open rings around a flat lane/vehicle diagram.
19. **Sport Angular** — angular red/white bar instruments and a central map.
20. **Navigation Max** — full planar map with tiny peripheral ring indicators.
21. **Classic Compact** — two large flat instruments and a compact route column.

## Flat digital views

22. **Sport Centerline** — one horizontal segmented performance scale over map.
23. **Dual Columns** — slim left/right rulers around the central route map.
24. **Panoramic Tapes** — horizontal speed/load tapes framing a wide route view.
25. **Assist Matrix** — map-free flat lane and surrounding-vehicle visualization.
26. **Route Essential** — large maneuver, simplified junction, minimal edge bars.

## Performance intent

- Prebuild tick marks, labels, separators, and backgrounds into cached static
  vector/display lists; update only needles, cursors, and changed values.
- Share one data subscription and one state snapshot across all views.
- Reuse the navigator map texture; do not create a second map renderer.
- Do not run or retain the map renderer in map-free Assist/Essential modes.
- Avoid blur, dynamic shadows, material shaders, and continuously animated
  decorative layers; V6 should be lighter than the volumetric concepts.

## Local review files

- `17-classic-map-flat.png`
- `18-classic-assist-flat.png`
- `19-sport-angular-flat.png`
- `20-navigation-max-flat.png`
- `21-classic-compact-flat.png`
- `22-sport-centerline-flat.png`
- `23-dual-columns-flat.png`
- `24-panoramic-tapes-flat.png`
- `25-assist-matrix-flat.png`
- `26-route-essential-flat.png`
