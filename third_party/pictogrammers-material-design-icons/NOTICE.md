# Pictogrammers Material Design Icons

The automotive gallery uses a small set of exact vehicle-control glyphs from Material Design
Icons by Pictogrammers.

- Distribution: `@mdi/svg` version `7.4.47`
- Upstream tag: `v7.4.47`
- Pinned upstream commit: `9e04201d4557e729822fb57f62a316c3dea1d4a8`
- Upstream: https://github.com/Templarian/MaterialDesign-SVG
- Catalog: https://pictogrammers.com/library/mdi/category/automotive/
- Copyright: Pictogrammers and the individual icon contributors
- License: Apache License 2.0; see `LICENSE-APACHE-2.0.txt` in this directory

The upstream SVG path data was converted to Android `VectorDrawable` XML. The viewport remains
24 by 24, the fill was normalized to white so the application can apply its existing tint, and the
application's stable icon resource names and persisted catalog keys were retained.

| Android resource | Upstream glyph |
| --- | --- |
| `ic_car_doors.xml` | `car-door` |
| `ic_car_fog_light.xml` | `car-light-fog` |
| `ic_car_hazard.xml` | `hazard-lights` |
| `ic_car_headlight.xml` | `car-light-dimmed` |
| `ic_car_high_beam.xml` | `car-light-high` |
| `ic_car_horn.xml` | `air-horn` |
| `ic_car_rear.xml` | `car-back` |
| `ic_car_side.xml` | `car-side` |
| `ic_car_steering.xml` | `steering` |
| `ic_car_tire_pressure.xml` | `car-tire-alert` |
| `ic_car_trunk_closed.xml` | `car-back` |
| `ic_car_trunk_open.xml` | `car-back` + `arrow-up-bold` (adapted composition) |
| `ic_car_unlock.xml` | `car-door-lock-open` |
| `ic_car_wiper.xml` | `wiper` |
| `ic_car_wiper_wash.xml` | `wiper-wash` |

`ic_car_trunk_open.xml` keeps both upstream path shapes intact inside Android groups: the
`car-back` glyph is scaled down and shifted toward the bottom, and `arrow-up-bold` is scaled down
and placed above it. This is an application composition, not an upstream single-glyph asset.
