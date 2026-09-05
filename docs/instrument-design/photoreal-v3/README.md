# Photoreal V3 art directions

V3 replaces the rejected flat V1/V2 visual language with production-grade automotive art
directions derived from the quality bar supplied by the user. The local preview PNG files use
the user's KX11 photograph and are deliberately excluded from the public repository.

## Permanent KX11 interface

Every direction preserves the factory-owned regions defined in `../v2/kx11-safe-area.png`:

- time, G-Pilot, temperature and ADAS indicators at the top;
- the E/P/R/N/D selector on the right;
- RPM and Eco Assistant at the lower left;
- range, fuel and speed at the lower right.

The Android confirmation dialog visible in the source photograph is temporary and is removed.

## Directions

1. **Titanium Performance / analog** — twin deep machined dials with a navigation world between
   them.
2. **Grand Touring / analog** — three independent horological instruments, calm typography and
   champagne-titanium detailing.
3. **Open Arc Performance / digital** — sculpted asymmetric arcs embracing a 3D navigation
   scene.
4. **Sapphire Chronograph / digital-analog** — radial sapphire texture, illuminated glass edges
   and a deep central route stage.
5. **Panoramic Navigation / digital** — map-first architecture using the permanent factory
   telemetry as part of the composition instead of duplicating it.

These are art-direction images, not production screenshots. AI-generated scale markings or
labels are not accepted as implementation assets.

## Production translation

After a direction is approved it is rebuilt at exactly 1920×720 using deterministic geometry:

- static wells, bezels, textures, shadows and optical highlights are pre-rendered once and kept
  in a cached GPU-ready layer;
- needles, numbers and warning accents are separate dynamic layers;
- the navigation map remains an independent `TextureView`/surface;
- telemetry invalidates only the values which changed;
- day/night variants share geometry and swap cached palettes/textures;
- fixed KX11 regions remain masked at both editor and runtime level.

This preserves the visual depth of V3 without increasing continuous CPU/GPU load.
