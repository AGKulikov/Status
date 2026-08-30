#!/usr/bin/env python3
"""Place V2 cluster concepts into the user's KX11 photo.

The permanent factory regions are cut out of the warped concept before compositing, so the
photograph's real G-Pilot strip, gear selector and lower telemetry wings remain untouched.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
GENERATED = ROOT / "docs" / "instrument-design" / "v2"
PHOTO = ROOT.parent / "upload" / "02-IMG_8113.jpeg"

# Screen glass corners in the supplied 1536×2048 photograph, clockwise from top-left.
SCREEN = [(101, 469), (1293, 540), (1263, 960), (93, 881)]
SOURCE = [(0, 0), (1920, 0), (1920, 720), (0, 720)]


def perspective_coefficients(destination, source):
    matrix = []
    target = []
    for (x, y), (u, v) in zip(destination, source):
        matrix.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        target.append(u)
        matrix.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        target.append(v)
    return np.linalg.solve(np.asarray(matrix, dtype=float), np.asarray(target, dtype=float))


def cut_out_factory_regions(concept: Image.Image) -> Image.Image:
    alpha = Image.new("L", concept.size, 255)
    draw = ImageDraw.Draw(alpha)
    # Slightly oversized masks preserve the exact photographed pixels around each factory item.
    draw.rounded_rectangle((500, 0, 1495, 148), radius=42, fill=0)
    draw.rounded_rectangle((1795, 165, 1920, 560), radius=30, fill=0)
    # Preserve the factory readouts themselves, not the whole dark wing: the source photo was
    # taken while an Android confirmation dialog was open and its grey backing must disappear.
    draw.rounded_rectangle((20, 515, 292, 720), radius=22, fill=0)
    draw.rounded_rectangle((294, 518, 600, 704), radius=22, fill=0)
    draw.rounded_rectangle((1380, 525, 1708, 710), radius=22, fill=0)
    draw.rounded_rectangle((1702, 500, 1920, 720), radius=22, fill=0)
    # A very small feather hides perspective/photographic softness differences at the boundary.
    alpha = alpha.filter(ImageFilter.GaussianBlur(3))
    concept.putalpha(alpha)
    return concept


def composite(concept_path: Path, output_path: Path) -> None:
    photo = Image.open(PHOTO).convert("RGB")
    concept = Image.open(concept_path).convert("RGBA")
    concept = cut_out_factory_regions(concept)
    concept = ImageEnhance.Brightness(concept).enhance(.84)
    coeffs = perspective_coefficients(SCREEN, SOURCE)
    warped = concept.transform(photo.size, Image.Transform.PERSPECTIVE, coeffs,
                               Image.Resampling.BICUBIC, fillcolor=(0, 0, 0, 0))
    warped = warped.filter(ImageFilter.GaussianBlur(.22))
    output = Image.alpha_composite(photo.convert("RGBA"), warped).convert("RGB")
    output.save(output_path, quality=94, subsampling=0, optimize=True)


def main() -> None:
    selected = [
        "a1-aurelia-analog.png",
        "a2-velum-analog.png",
        "d1-nyx-digital.png",
        "d2-atlas-digital.png",
        "d3-serein-digital.png",
    ]
    for name in selected:
        composite(GENERATED / name, GENERATED / name.replace(".png", "-in-car.jpg"))


if __name__ == "__main__":
    main()
