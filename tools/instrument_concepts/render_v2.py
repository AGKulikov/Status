#!/usr/bin/env python3
"""Render the second Natro cluster art-direction review.

Unlike the first exploratory set, every composition is built around the KX11 regions that
remain visible above third-party content: the top ADAS strip, the right gear selector and the
two lower factory telemetry wings.  The artwork is generated from shapes and typography that
can be reproduced by Android Canvas; it is not intended to become a bitmap skin.
"""

from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont


WIDTH = 1920
HEIGHT = 720
SCALE = 2
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "instrument-design" / "v2"

SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SANS_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
# The render container carries the core DejaVu family only.  Android implementation will use
# the system Roboto Condensed face; DejaVu Sans is a metrically safe preview fallback here.
SANS_CONDENSED = SANS
SANS_CONDENSED_BOLD = SANS_BOLD
SERIF = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"


CONCEPTS = [
    {
        "slug": "a1-aurelia-analog",
        "name": "AURELIA",
        "kind": "ANALOG",
        "tag": "двойная парящая оптика",
        "bg": (3, 5, 7),
        "surface": (10, 14, 17),
        "fg": (244, 241, 232),
        "muted": (114, 126, 133),
        "accent": (221, 181, 105),
        "accent2": (87, 205, 231),
    },
    {
        "slug": "a2-velum-analog",
        "name": "VELUM R",
        "kind": "ANALOG",
        "tag": "центральный тахометр и карта",
        "bg": (2, 4, 8),
        "surface": (7, 12, 20),
        "fg": (242, 247, 252),
        "muted": (87, 106, 127),
        "accent": (54, 158, 255),
        "accent2": (255, 63, 73),
    },
    {
        "slug": "a3-chronos-analog",
        "name": "CHRONOS",
        "kind": "ANALOG",
        "tag": "часовая механика",
        "bg": (8, 7, 5),
        "surface": (21, 18, 12),
        "fg": (242, 226, 192),
        "muted": (143, 125, 91),
        "accent": (204, 154, 75),
        "accent2": (190, 48, 39),
    },
    {
        "slug": "d1-nyx-digital",
        "name": "NYX",
        "kind": "DIGITAL",
        "tag": "чёрное стекло и навигация",
        "bg": (2, 5, 9),
        "surface": (8, 17, 25),
        "fg": (241, 248, 252),
        "muted": (87, 112, 130),
        "accent": (78, 205, 255),
        "accent2": (167, 116, 255),
    },
    {
        "slug": "d2-atlas-digital",
        "name": "ATLAS AR",
        "kind": "DIGITAL",
        "tag": "маршрут, полосы и события",
        "bg": (2, 7, 10),
        "surface": (6, 17, 21),
        "fg": (236, 250, 250),
        "muted": (79, 119, 127),
        "accent": (42, 222, 190),
        "accent2": (82, 144, 255),
    },
    {
        "slug": "d3-serein-digital",
        "name": "SEREIN",
        "kind": "DIGITAL",
        "tag": "тихая премиальная архитектура",
        "bg": (5, 6, 8),
        "surface": (13, 15, 18),
        "fg": (242, 238, 228),
        "muted": (121, 120, 115),
        "accent": (191, 166, 113),
        "accent2": (114, 158, 184),
    },
]


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return (*color, alpha)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], amount: float) -> tuple[int, int, int]:
    return tuple(round(x + (y - x) * amount) for x, y in zip(a, b))


def font(size: int, *, bold: bool = False, condensed: bool = False,
         serif: bool = False) -> ImageFont.FreeTypeFont:
    if serif:
        path = SERIF
    elif condensed and bold:
        path = SANS_CONDENSED_BOLD
    elif condensed:
        path = SANS_CONDENSED
    else:
        path = SANS_BOLD if bold else SANS
    return ImageFont.truetype(path, max(1, size * SCALE))


def text(draw: ImageDraw.ImageDraw, xy: tuple[float, float], value: str, size: int, fill,
         *, anchor: str = "mm", bold: bool = False, condensed: bool = False,
         serif: bool = False, stroke: int = 0, stroke_fill=None) -> None:
    draw.text((xy[0] * SCALE, xy[1] * SCALE), value,
              font=font(size, bold=bold, condensed=condensed, serif=serif), fill=fill,
              anchor=anchor, stroke_width=stroke * SCALE, stroke_fill=stroke_fill)


def line(draw: ImageDraw.ImageDraw, points, fill, width: int = 1) -> None:
    draw.line(tuple(round(v * SCALE) for v in points), fill=fill,
              width=max(1, round(width * SCALE)), joint="curve")


def rounded(draw: ImageDraw.ImageDraw, box, radius: int, *, fill=None, outline=None,
            width: int = 1) -> None:
    draw.rounded_rectangle(tuple(round(v * SCALE) for v in box), radius=radius * SCALE,
                           fill=fill, outline=outline, width=max(1, width * SCALE))


def point(cx: float, cy: float, radius: float, degrees: float) -> tuple[float, float]:
    angle = math.radians(degrees)
    return cx + math.cos(angle) * radius, cy + math.sin(angle) * radius


def gradient_background(c: dict) -> Image.Image:
    image = Image.new("RGBA", (WIDTH * SCALE, HEIGHT * SCALE), rgba(c["bg"]))
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT * SCALE):
        p = y / (HEIGHT * SCALE - 1)
        center = max(0.0, 1.0 - abs(p - .48) * 1.9)
        color = mix(c["bg"], c["surface"], .12 + center * .18)
        draw.line((0, y, WIDTH * SCALE, y), fill=rgba(color))

    glow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse((260 * SCALE, -260 * SCALE, 1660 * SCALE, 930 * SCALE),
               fill=rgba(c["accent"], 20))
    gd.ellipse((600 * SCALE, 10 * SCALE, 1320 * SCALE, 700 * SCALE),
               fill=rgba(c["accent2"], 12))
    glow = glow.filter(ImageFilter.GaussianBlur(180 * SCALE))
    image.alpha_composite(glow)

    # Almost invisible grain prevents the black surfaces from looking like flat rectangles.
    noise = Image.new("RGBA", image.size, (0, 0, 0, 0))
    nd = ImageDraw.Draw(noise)
    rng = random.Random(20260830)
    for _ in range(8200):
        x = rng.randrange(WIDTH * SCALE)
        y = rng.randrange(HEIGHT * SCALE)
        a = rng.randrange(2, 10)
        nd.point((x, y), fill=(255, 255, 255, a))
    image.alpha_composite(noise)
    return image


def glow_line(image: Image.Image, points, color, *, width: int = 4, glow: int = 18) -> None:
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    coords = [(x * SCALE, y * SCALE) for x, y in points]
    draw.line(coords, fill=rgba(color, 100), width=glow * SCALE, joint="curve")
    layer = layer.filter(ImageFilter.GaussianBlur(glow * SCALE // 2))
    image.alpha_composite(layer)
    ImageDraw.Draw(image).line(coords, fill=rgba(color), width=width * SCALE, joint="curve")


def glass(image: Image.Image, box, c: dict, *, radius: int = 28, accent=None,
          alpha: int = 202) -> None:
    x0, y0, x1, y1 = box
    shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    rounded(sd, (x0 + 8, y0 + 13, x1 + 8, y1 + 13), radius,
            fill=(0, 0, 0, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18 * SCALE))
    image.alpha_composite(shadow)
    draw = ImageDraw.Draw(image)
    rounded(draw, box, radius, fill=rgba(c["surface"], alpha),
            outline=rgba(accent or c["muted"], 112), width=2)
    # Top optical edge and bottom absorption edge make the surface read as glass.
    line(draw, (x0 + radius, y0 + 2, x1 - radius, y0 + 2),
         rgba(mix(c["fg"], accent or c["accent"], .55), 72), 1)
    line(draw, (x0 + radius, y1 - 2, x1 - radius, y1 - 2), (0, 0, 0, 130), 2)


def map_surface(c: dict, box, *, mode: str, route_color=None) -> Image.Image:
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    layer = Image.new("RGBA", (w * SCALE, h * SCALE), rgba(mix(c["bg"], (17, 28, 34), .62)))
    draw = ImageDraw.Draw(layer)
    rng = random.Random(9107 + len(mode))

    # A quiet city fabric: blocks, parks and a river.  It is detailed enough to evaluate
    # hierarchy, but deliberately not a screenshot of one proprietary map style.
    draw.polygon([(0, int(h * .80 * SCALE)), (int(w * .34 * SCALE), int(h * .58 * SCALE)),
                  (int(w * .65 * SCALE), int(h * .72 * SCALE)), (w * SCALE, int(h * .50 * SCALE)),
                  (w * SCALE, h * SCALE), (0, h * SCALE)], fill=(16, 35, 42, 150))
    for row in range(5):
        for col in range(11):
            bx = -20 + col * (w / 10.1) + rng.uniform(-13, 13)
            by = -18 + row * (h / 4.3) + rng.uniform(-11, 11)
            bw = rng.uniform(42, 92)
            bh = rng.uniform(24, 58)
            color = mix(c["surface"], (42, 57, 62), rng.uniform(.18, .42))
            poly = [(int((bx + 9) * SCALE), int(by * SCALE)),
                    (int((bx + bw) * SCALE), int((by + 8) * SCALE)),
                    (int((bx + bw - 7) * SCALE), int((by + bh) * SCALE)),
                    (int(bx * SCALE), int((by + bh - 8) * SCALE))]
            draw.polygon(poly, fill=rgba(color, rng.randrange(55, 105)),
                         outline=rgba(c["muted"], 25))

    road_minor = rgba(mix(c["muted"], (120, 138, 143), .38), 72)
    for i in range(-3, 12):
        yy = (i * h / 8.0 - h * .2) * SCALE
        draw.line((-60 * SCALE, yy, (w + 80) * SCALE, yy + h * .43 * SCALE),
                  fill=road_minor, width=2 * SCALE)
    for i in range(-2, 13):
        xx = (i * w / 10.0) * SCALE
        draw.line((xx, -40 * SCALE, xx - w * .24 * SCALE, (h + 60) * SCALE),
                  fill=road_minor, width=2 * SCALE)

    arterials = [
        [(-.05, .75), (.18, .66), (.36, .70), (.52, .49), (.71, .46), (1.05, .20)],
        [(-.03, .27), (.17, .35), (.39, .27), (.61, .37), (.80, .28), (1.03, .38)],
        [(.31, -.08), (.35, .20), (.31, .43), (.48, .62), (.51, 1.08)],
        [(.83, -.08), (.77, .22), (.79, .49), (.67, .72), (.64, 1.08)],
    ]
    for points in arterials:
        pts = [(int(px * w * SCALE), int(py * h * SCALE)) for px, py in points]
        draw.line(pts, fill=(0, 0, 0, 195), width=14 * SCALE, joint="curve")
        draw.line(pts, fill=rgba(mix(c["muted"], (135, 150, 155), .38), 135),
                  width=5 * SCALE, joint="curve")

    route = [(.04, .84), (.20, .70), (.36, .73), (.49, .52), (.66, .48), (.89, .25), (1.0, .20)]
    pts = [(int(px * w * SCALE), int(py * h * SCALE)) for px, py in route]
    draw.line(pts, fill=(0, 0, 0, 235), width=23 * SCALE, joint="curve")
    colors = route_color or [c["accent2"], c["accent2"], (54, 211, 130),
                             (239, 183, 49), (237, 77, 70), c["accent"]]
    for idx in range(len(pts) - 1):
        draw.line((pts[idx], pts[idx + 1]), fill=rgba(colors[min(idx, len(colors) - 1)]),
                  width=11 * SCALE, joint="curve")

    # Road labels are intentionally sparse at cluster distance.
    text(draw, (w * .17, h * .39), "Набережная", 13, rgba(c["muted"], 170), condensed=True)
    text(draw, (w * .73, h * .65), "Ленинградское ш.", 13, rgba(c["muted"], 170), condensed=True)
    text(draw, (w * .74, h * .12), "Речной вокзал", 12, rgba(c["fg"], 120), condensed=True)

    # Cursor with a readable dark keyline.
    cx, cy = pts[2]
    cursor = [(cx, cy - 23 * SCALE), (cx - 16 * SCALE, cy + 21 * SCALE),
              (cx, cy + 11 * SCALE), (cx + 16 * SCALE, cy + 21 * SCALE)]
    draw.polygon(cursor, fill=rgba(c["fg"]), outline=(0, 0, 0, 255))

    if mode in ("events", "glass"):
        # Camera with direction sector.
        ex, ey = int(w * .72 * SCALE), int(h * .40 * SCALE)
        draw.pieslice((ex - 30 * SCALE, ey - 30 * SCALE, ex + 30 * SCALE, ey + 30 * SCALE),
                      start=205, end=330, fill=rgba(c["accent2"], 48))
        draw.ellipse((ex - 15 * SCALE, ey - 15 * SCALE, ex + 15 * SCALE, ey + 15 * SCALE),
                     fill=(8, 12, 16, 245), outline=rgba(c["fg"], 220), width=2 * SCALE)
        draw.rectangle((ex - 8 * SCALE, ey - 5 * SCALE, ex + 8 * SCALE, ey + 6 * SCALE),
                       outline=rgba(c["fg"], 230), width=2 * SCALE)
        # Traffic light countdown.
        tx, ty = int(w * .54 * SCALE), int(h * .46 * SCALE)
        draw.rounded_rectangle((tx - 24 * SCALE, ty - 37 * SCALE, tx + 24 * SCALE, ty + 37 * SCALE),
                               radius=13 * SCALE, fill=(5, 8, 10, 245),
                               outline=rgba(c["accent"], 170), width=2 * SCALE)
        for offset, color in [(-20, (235, 70, 67)), (0, (236, 183, 51)), (20, (51, 214, 130))]:
            draw.ellipse((tx - 7 * SCALE, ty + (offset - 7) * SCALE,
                          tx + 7 * SCALE, ty + (offset + 7) * SCALE), fill=rgba(color))
        text(draw, (tx / SCALE + 39, ty / SCALE), "23", 22, rgba(c["fg"]),
             anchor="lm", bold=True, condensed=True)

    # Optical fade around the map avoids a pasted rectangular screenshot.
    mask = Image.new("L", layer.size, 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, layer.width - 1, layer.height - 1), radius=34 * SCALE, fill=238)
    mask = mask.filter(ImageFilter.GaussianBlur(2 * SCALE))
    layer.putalpha(mask)
    return layer


def paste_map(image: Image.Image, c: dict, box, *, mode: str) -> None:
    layer = map_surface(c, box, mode=mode)
    image.alpha_composite(layer, (box[0] * SCALE, box[1] * SCALE))
    draw = ImageDraw.Draw(image)
    rounded(draw, box, 34, outline=rgba(c["muted"], 90), width=2)
    x0, y0, x1, _ = box
    line(draw, (x0 + 36, y0 + 2, x1 - 36, y0 + 2), rgba(c["accent"], 105), 2)


def premium_dial(image: Image.Image, c: dict, cx: int, cy: int, radius: int, *,
                 tach: bool, value_fraction: float, label: str, value: str,
                 arc_start: float = 136, arc_sweep: float = 268, jewel: bool = False) -> None:
    # Depth halo and glass well.
    halo = Image.new("RGBA", image.size, (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    hd.ellipse(((cx - radius - 16) * SCALE, (cy - radius - 16) * SCALE,
                (cx + radius + 16) * SCALE, (cy + radius + 16) * SCALE),
               outline=rgba(c["accent"], 92), width=18 * SCALE)
    halo = halo.filter(ImageFilter.GaussianBlur(17 * SCALE))
    image.alpha_composite(halo)
    draw = ImageDraw.Draw(image)

    for inset, color, alpha, width in [(0, c["muted"], 70, 3), (7, c["accent"], 105, 2),
                                       (15, c["fg"], 33, 1), (26, c["muted"], 50, 2)]:
        r = radius - inset
        draw.arc(((cx - r) * SCALE, (cy - r) * SCALE, (cx + r) * SCALE, (cy + r) * SCALE),
                 start=arc_start, end=arc_start + arc_sweep, fill=rgba(color, alpha),
                 width=width * SCALE)

    # Machined ring: alternating warm/cool micro-segments.
    for idx in range(96):
        a0 = arc_start + arc_sweep * idx / 96
        a1 = arc_start + arc_sweep * (idx + .55) / 96
        rr = radius - 8
        color = c["accent"] if idx % 2 == 0 else c["fg"]
        draw.arc(((cx - rr) * SCALE, (cy - rr) * SCALE, (cx + rr) * SCALE, (cy + rr) * SCALE),
                 start=a0, end=a1, fill=rgba(color, 100 if idx % 2 == 0 else 48),
                 width=2 * SCALE)

    major_count = 9 if tach else 13
    subdivisions = 5
    total = (major_count - 1) * subdivisions
    for idx in range(total + 1):
        fraction = idx / total
        angle = arc_start + arc_sweep * fraction
        major = idx % subdivisions == 0
        warning = tach and fraction > .78
        outer = radius - 23
        inner = radius - (62 if major else 43)
        p0 = point(cx, cy, inner, angle)
        p1 = point(cx, cy, outer, angle)
        color = c["accent2"] if warning else c["fg"]
        line(draw, (*p0, *p1), rgba(color, 238 if major else 105), 4 if major else 2)

    for idx in range(major_count):
        fraction = idx / (major_count - 1)
        angle = arc_start + arc_sweep * fraction
        p = point(cx, cy, radius - 87, angle)
        number = idx if tach else round((260 * fraction) / 20) * 20
        text(draw, p, str(number), 17 if radius < 230 else 20, rgba(c["fg"], 205),
             condensed=True)

    # Needle shadow, polished needle and jewel.
    angle = arc_start + arc_sweep * value_fraction
    tip = point(cx, cy, radius - 64, angle)
    tail = point(cx, cy, radius * .12, angle + 180)
    line(draw, (tail[0] + 4, tail[1] + 5, tip[0] + 4, tip[1] + 5), (0, 0, 0, 190), 9)
    line(draw, (*tail, *tip), rgba(c["accent2"] if tach else c["accent"]), 6)
    line(draw, (cx, cy, *point(cx, cy, radius - 78, angle)), rgba(c["fg"], 190), 2)
    draw.ellipse(((cx - 20) * SCALE, (cy - 20) * SCALE, (cx + 20) * SCALE,
                  (cy + 20) * SCALE), fill=rgba(c["surface"]),
                 outline=rgba(c["accent"], 240), width=4 * SCALE)
    draw.ellipse(((cx - 8) * SCALE, (cy - 8) * SCALE, (cx + 8) * SCALE,
                  (cy + 8) * SCALE), fill=rgba(c["fg"], 235))
    if jewel:
        draw.ellipse(((cx - 4) * SCALE, (cy - 4) * SCALE, (cx + 4) * SCALE,
                      (cy + 4) * SCALE), fill=rgba(c["accent2"]))

    text(draw, (cx, cy + radius * .34), value, max(40, round(radius * .21)),
         rgba(c["fg"]), bold=True, condensed=True, serif=jewel)
    text(draw, (cx, cy + radius * .48), "×1000" if tach else "км/ч", 15,
         rgba(c["muted"], 230), condensed=True)
    text(draw, (cx, cy - radius * .35), label, 13, rgba(c["muted"], 230),
         bold=True, condensed=True)


def fixed_factory_overlay(image: Image.Image) -> None:
    """Draw the KX11 UI zones which cannot be hidden by the Natro panel."""
    draw = ImageDraw.Draw(image)
    cyan = (25, 199, 235)
    green = (33, 239, 76)
    white = (244, 247, 249)
    muted = (152, 165, 174)

    # Top ADAS/status island.
    top = Image.new("RGBA", image.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(top)
    td.rounded_rectangle((520 * SCALE, -30 * SCALE, 1465 * SCALE, 138 * SCALE),
                         radius=55 * SCALE, fill=(0, 0, 0, 172))
    top = top.filter(ImageFilter.GaussianBlur(10 * SCALE))
    image.alpha_composite(top)
    draw = ImageDraw.Draw(image)
    text(draw, (655, 50), "14:57", 31, rgba(white), bold=True, condensed=True)
    text(draw, (867, 50), "‹", 57, rgba(white), bold=True)
    text(draw, (1025, 50), "G-Pilot", 31, rgba(white), bold=True, condensed=True)
    text(draw, (1184, 50), "›", 57, rgba(white), bold=True)
    text(draw, (1385, 50), "24°C", 34, rgba(white), bold=True, condensed=True)
    # Simplified but positionally accurate permanent ADAS glyphs.
    rounded(draw, (750, 76, 781, 118), 8, outline=rgba(green), width=3)
    for yy in (84, 95, 106):
        line(draw, (757, yy, 774, yy), rgba(green), 3)
    text(draw, (1288, 99), "B/A", 23, rgba(green), bold=True, condensed=True)
    draw.ellipse((1320 * SCALE, 78 * SCALE, 1352 * SCALE, 110 * SCALE),
                 outline=rgba(white), width=3 * SCALE)
    line(draw, (1336, 110, 1324, 122), rgba(white), 3)
    line(draw, (1336, 110, 1348, 122), rgba(white), 3)

    # Bottom factory wings.  Their upper edges are deliberately asymmetrical like the car UI.
    left_pts = [(0, 477), (142, 481), (345, 515), (625, 590), (705, 720), (0, 720)]
    right_pts = [(1215, 720), (1300, 590), (1588, 512), (1778, 482), (1920, 472), (1920, 720)]
    draw.polygon([(x * SCALE, y * SCALE) for x, y in left_pts], fill=(2, 6, 9, 232))
    draw.polygon([(x * SCALE, y * SCALE) for x, y in right_pts], fill=(2, 6, 9, 232))
    line(draw, (15, 481, 142, 483, 344, 518, 622, 593), rgba((70, 99, 119), 105), 2)
    line(draw, (1302, 592, 1588, 516, 1778, 485, 1900, 476), rgba((70, 99, 119), 105), 2)

    # Left fixed powertrain block.
    text(draw, (168, 615), "0.7", 82, rgba(white), anchor="mm", bold=True, condensed=True)
    text(draw, (168, 666), "×1000rpm", 17, rgba(muted), condensed=True)
    # Minimal vehicle/road glyph.
    rounded(draw, (402, 548, 448, 580), 8, outline=rgba(white, 220), width=3)
    line(draw, (413, 545, 420, 536, 440, 536, 448, 548), rgba(white, 220), 3)
    line(draw, (392, 586, 411, 580), rgba(white, 180), 3)
    line(draw, (458, 586, 448, 580), rgba(white, 180), 3)
    text(draw, (426, 623), "ЭКО ассистент", 23, rgba(muted), condensed=True)
    line(draw, (314, 651, 535, 651), rgba((61, 84, 99), 180), 11)
    line(draw, (314, 651, 472, 651), rgba(cyan), 6)

    # Right fixed range/fuel/speed block.
    text(draw, (1510, 630), "577", 49, rgba(white), bold=True, condensed=True)
    text(draw, (1598, 634), "км", 19, rgba(muted), condensed=True)
    rounded(draw, (1640, 606, 1668, 644), 4, outline=rgba(white), width=3)
    line(draw, (1668, 614, 1678, 622, 1678, 645), rgba(white), 3)
    line(draw, (1458, 658, 1692, 658), rgba((61, 84, 99), 180), 11)
    line(draw, (1458, 658, 1628, 658), rgba(cyan), 6)
    text(draw, (1780, 608), "0", 84, rgba(white), bold=True, condensed=True)
    text(draw, (1780, 669), "km/h", 18, rgba(white), bold=True, condensed=True)

    # Right fixed gear selector.
    gear = Image.new("RGBA", image.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(gear)
    gd.rounded_rectangle((1812 * SCALE, 190 * SCALE, 1924 * SCALE, 548 * SCALE),
                         radius=34 * SCALE, fill=(0, 0, 0, 195))
    gear = gear.filter(ImageFilter.GaussianBlur(4 * SCALE))
    image.alpha_composite(gear)
    draw = ImageDraw.Draw(image)
    text(draw, (1870, 229), "E", 42, rgba(cyan), bold=True, condensed=True)
    for y, value in [(310, "P"), (359, "R"), (408, "N")]:
        text(draw, (1870, y), value, 27, rgba(white, 210), bold=True, condensed=True)
    rounded(draw, (1829, 438, 1911, 496), 16, fill=rgba(cyan), outline=rgba((104, 226, 249)), width=2)
    text(draw, (1870, 467), "D", 37, rgba(white), bold=True, condensed=True)


def common_navigation_chip(image: Image.Image, c: dict, *, x: int, y: int,
                           compact: bool = False) -> None:
    draw = ImageDraw.Draw(image)
    w, h = (300, 102) if compact else (360, 126)
    glass(image, (x, y, x + w, y + h), c, radius=25, accent=c["accent2"], alpha=224)
    draw = ImageDraw.Draw(image)
    text(draw, (x + 30, y + 25), "СЛЕДУЮЩИЙ МАНЁВР", 12, rgba(c["muted"]),
         anchor="la", bold=True, condensed=True)
    # Clean right-turn glyph rather than a font symbol.
    line(draw, (x + 36, y + 76, x + 36, y + 52, x + 72, y + 52), rgba(c["accent"]), 8)
    line(draw, (x + 72, y + 52, x + 60, y + 41), rgba(c["accent"]), 8)
    line(draw, (x + 72, y + 52, x + 60, y + 64), rgba(c["accent"]), 8)
    text(draw, (x + 100, y + 72), "420 м", 36, rgba(c["fg"]), anchor="lm",
         bold=True, condensed=True)
    text(draw, (x + w - 22, y + h - 22), "Ленинградское ш.", 13,
         rgba(c["muted"]), anchor="rs", condensed=True)


def lane_guidance(image: Image.Image, c: dict, box) -> None:
    glass(image, box, c, radius=24, alpha=218)
    draw = ImageDraw.Draw(image)
    x0, y0, x1, y1 = box
    text(draw, (x0 + 22, y0 + 20), "ПОЛОСЫ", 12, rgba(c["muted"]), anchor="la",
         bold=True, condensed=True)
    centers = [x0 + 68, x0 + 145, x0 + 222]
    for idx, cx in enumerate(centers):
        color = c["accent"] if idx in (1, 2) else c["muted"]
        line(draw, (cx, y1 - 24, cx, y0 + 58), rgba(color, 235 if idx else 100), 6)
        if idx == 0:
            line(draw, (cx, y0 + 58, cx - 16, y0 + 77), rgba(color, 100), 6)
        elif idx == 1:
            line(draw, (cx, y0 + 58, cx, y0 + 38), rgba(color), 6)
        else:
            line(draw, (cx, y0 + 58, cx + 18, y0 + 40), rgba(color), 6)


def render_aurelia(c: dict) -> Image.Image:
    image = gradient_background(c)
    paste_map(image, c, (610, 160, 1380, 558), mode="glass")
    premium_dial(image, c, 390, 375, 255, tach=False, value_fraction=.34,
                 label="СКОРОСТЬ", value="86")
    premium_dial(image, c, 1535, 375, 255, tach=True, value_fraction=.30,
                 label="ОБОРОТЫ", value="2.4")
    draw = ImageDraw.Draw(image)
    # Suspended metal bridge visually ties the dials to the map without a rectangular card.
    line(draw, (535, 171, 652, 171), rgba(c["accent"], 130), 2)
    line(draw, (1260, 171, 1390, 171), rgba(c["accent"], 130), 2)
    text(draw, (960, 586), "МАРШРУТ  18 мин  ·  12,4 км", 18, rgba(c["fg"], 185),
         bold=True, condensed=True)
    fixed_factory_overlay(image)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGB")


def render_velum(c: dict) -> Image.Image:
    image = gradient_background(c)
    paste_map(image, c, (220, 150, 1700, 575), mode="events")
    # Central tachometer is deliberately open at the bottom where the stock UI intrudes.
    premium_dial(image, c, 960, 390, 276, tach=True, value_fraction=.48,
                 label="ОБОРОТЫ", value="4.1", arc_start=154, arc_sweep=232)
    draw = ImageDraw.Draw(image)
    for idx in range(13):
        x0 = 663 + idx * 48
        active = idx < 9
        color = c["accent2"] if idx < 7 else c["accent"]
        rounded(draw, (x0, 142, x0 + 34, 154), 5,
                fill=rgba(color if active else c["muted"], 235 if active else 45))
    common_navigation_chip(image, c, x=1262, y=178, compact=True)
    text(draw, (507, 222), "86", 92, rgba(c["fg"]), bold=True, condensed=True)
    text(draw, (507, 284), "км/ч", 17, rgba(c["muted"]), condensed=True)
    fixed_factory_overlay(image)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGB")


def render_chronos(c: dict) -> Image.Image:
    image = gradient_background(c)
    # Fine guilloché-like field, extremely cheap as a pre-cached static layer at runtime.
    draw = ImageDraw.Draw(image)
    for radius in range(80, 760, 24):
        draw.arc(((960 - radius) * SCALE, (350 - radius * .42) * SCALE,
                  (960 + radius) * SCALE, (350 + radius * .42) * SCALE),
                 start=188, end=352, fill=rgba(c["accent"], 16), width=1 * SCALE)
    premium_dial(image, c, 725, 360, 188, tach=False, value_fraction=.34,
                 label="СКОРОСТЬ", value="86", jewel=True)
    premium_dial(image, c, 960, 360, 238, tach=True, value_fraction=.31,
                 label="ОБОРОТЫ", value="2.4", jewel=True)
    premium_dial(image, c, 1195, 360, 188, tach=False, value_fraction=.58,
                 label="РЕЗЕРВ МОЩНОСТИ", value="58", jewel=True)
    draw = ImageDraw.Draw(image)
    text(draw, (960, 608), "NATRO  /  GRAND TOURING", 15, rgba(c["accent"], 155),
         bold=True, condensed=True)
    fixed_factory_overlay(image)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGB")


def render_nyx(c: dict) -> Image.Image:
    image = gradient_background(c)
    paste_map(image, c, (458, 148, 1485, 575), mode="glass")
    glass(image, (98, 182, 428, 470), c, radius=34, accent=c["accent"], alpha=218)
    draw = ImageDraw.Draw(image)
    text(draw, (132, 216), "ТЕКУЩАЯ СКОРОСТЬ", 13, rgba(c["muted"]), anchor="la",
         bold=True, condensed=True)
    text(draw, (260, 326), "86", 122, rgba(c["fg"]), bold=True, condensed=True)
    text(draw, (260, 414), "км/ч", 19, rgba(c["muted"]), condensed=True)
    # Vertical power ribbon replaces another large box.
    for idx in range(12):
        y = 188 + idx * 25
        active = idx >= 5
        color = c["accent2"] if idx < 9 else c["accent"]
        line(draw, (1545, y, 1594 + (idx % 3) * 9, y),
             rgba(color if active else c["muted"], 230 if active else 50), 5 if active else 2)
    text(draw, (1608, 220), "МОЩНОСТЬ", 12, rgba(c["muted"]), anchor="la",
         bold=True, condensed=True)
    text(draw, (1610, 418), "+41", 58, rgba(c["fg"]), anchor="lm", bold=True,
         condensed=True)
    text(draw, (1610, 458), "кВт", 16, rgba(c["muted"]), anchor="lm", condensed=True)
    common_navigation_chip(image, c, x=1078, y=176, compact=True)
    fixed_factory_overlay(image)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGB")


def render_atlas(c: dict) -> Image.Image:
    image = gradient_background(c)
    paste_map(image, c, (95, 145, 1776, 585), mode="events")
    common_navigation_chip(image, c, x=120, y=170, compact=False)
    lane_guidance(image, c, (1432, 174, 1725, 350))
    draw = ImageDraw.Draw(image)
    # Route summary sits in the free central bottom valley between the factory wings.
    glass(image, (704, 493, 1218, 595), c, radius=28, accent=c["accent"], alpha=224)
    draw = ImageDraw.Draw(image)
    text(draw, (746, 526), "15:50", 34, rgba(c["fg"]), anchor="la", bold=True,
         condensed=True)
    text(draw, (942, 526), "48 мин", 31, rgba(c["fg"]), anchor="la", bold=True,
         condensed=True)
    text(draw, (1168, 526), "12,4 км", 23, rgba(c["accent"]), anchor="ra", bold=True,
         condensed=True)
    line(draw, (746, 568, 1168, 568), rgba(c["muted"], 65), 8)
    # Traffic gradient along the summary.
    for idx, color in enumerate([(50, 213, 129)] * 5 + [(238, 185, 46)] * 3 + [(236, 77, 70)] * 2):
        line(draw, (746 + idx * 42, 568, 784 + idx * 42, 568), rgba(color), 6)
    fixed_factory_overlay(image)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGB")


def render_serein(c: dict) -> Image.Image:
    image = gradient_background(c)
    draw = ImageDraw.Draw(image)
    # Calm horizon ribbon uses the permanent corner telemetry instead of duplicating it.
    line(draw, (170, 388, 1750, 388), rgba(c["muted"], 65), 2)
    line(draw, (500, 388, 1370, 388), rgba(c["accent"], 135), 3)
    paste_map(image, c, (520, 166, 1400, 510), mode="quiet")
    common_navigation_chip(image, c, x=1170, y=185, compact=True)
    text(draw, (170, 220), "МАРШРУТ", 13, rgba(c["muted"]), anchor="la", bold=True,
         condensed=True)
    text(draw, (170, 268), "12,4", 54, rgba(c["fg"]), anchor="la", serif=True)
    text(draw, (300, 273), "км", 17, rgba(c["muted"]), anchor="la", condensed=True)
    text(draw, (170, 327), "прибытие 15:50", 18, rgba(c["accent"]), anchor="la",
         condensed=True)
    text(draw, (1600, 235), "−2 мин", 38, rgba(c["accent2"]), anchor="mm", serif=True)
    text(draw, (1600, 290), "быстрее обычного", 15, rgba(c["muted"]), condensed=True)
    # Tiny media strip demonstrates secondary content without stealing attention from driving.
    glass(image, (690, 532, 1230, 610), c, radius=22, alpha=185)
    draw = ImageDraw.Draw(image)
    text(draw, (724, 558), "СЕЙЧАС ИГРАЕТ", 11, rgba(c["muted"]), anchor="la",
         bold=True, condensed=True)
    text(draw, (724, 588), "Яндекс Музыка  ·  Моя волна", 17, rgba(c["fg"], 190),
         anchor="la", condensed=True)
    fixed_factory_overlay(image)
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGB")


def render_safe_area() -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT), (8, 11, 15))
    draw = ImageDraw.Draw(image, "RGBA")
    # Free Natro composition field.
    draw.rounded_rectangle((80, 138, 1785, 610), radius=38,
                           fill=(44, 196, 137, 34), outline=(75, 226, 165, 190), width=3)
    text(draw, (960 / SCALE, 360 / SCALE), "", 1, (0, 0, 0, 0))
    draw.rectangle((520, 0, 1465, 140), fill=(231, 66, 74, 88), outline=(244, 94, 101, 230), width=3)
    draw.rectangle((1810, 180, 1919, 552), fill=(231, 66, 74, 88), outline=(244, 94, 101, 230), width=3)
    left_pts = [(0, 470), (150, 477), (350, 510), (650, 585), (720, 720), (0, 720)]
    right_pts = [(1200, 720), (1290, 585), (1580, 510), (1780, 477), (1920, 470), (1920, 720)]
    draw.polygon(left_pts, fill=(231, 66, 74, 88), outline=(244, 94, 101, 230))
    draw.polygon(right_pts, fill=(231, 66, 74, 88), outline=(244, 94, 101, 230))
    # Use unscaled fonts because this diagnostic image is already final resolution.
    f32 = ImageFont.truetype(SANS_BOLD, 32)
    f22 = ImageFont.truetype(SANS, 22)
    draw.text((960, 285), "СВОБОДНОЕ ПОЛЕ NATRO", font=f32, fill=(181, 247, 218), anchor="mm")
    draw.text((960, 329), "важные данные размещаются только здесь", font=f22,
              fill=(123, 193, 165), anchor="mm")
    draw.text((992, 72), "ШТАТНАЯ ВЕРХНЯЯ СТРОКА", font=f22, fill=(255, 213, 216), anchor="mm")
    draw.text((1865, 366), "P R N D", font=f22, fill=(255, 213, 216), anchor="mm")
    draw.text((260, 650), "ШТАТНЫЙ ЛЕВЫЙ БЛОК", font=f22, fill=(255, 213, 216), anchor="mm")
    draw.text((1655, 650), "ШТАТНЫЙ ПРАВЫЙ БЛОК", font=f22, fill=(255, 213, 216), anchor="mm")
    return image


def contact_sheet(items: list[tuple[dict, Image.Image]]) -> Image.Image:
    thumb_w, thumb_h = 960, 360
    margin = 28
    label_h = 70
    rows = math.ceil(len(items) / 2)
    sheet = Image.new("RGB", (thumb_w * 2 + margin * 3,
                              rows * (thumb_h + label_h + margin) + margin), (7, 9, 12))
    draw = ImageDraw.Draw(sheet)
    title_font = ImageFont.truetype(SANS_BOLD, 27)
    tag_font = ImageFont.truetype(SANS, 18)
    for idx, (c, image) in enumerate(items):
        col, row = idx % 2, idx // 2
        x = margin + col * (thumb_w + margin)
        y = margin + row * (thumb_h + label_h + margin)
        draw.text((x, y), f"{c['name']}  /  {c['kind']}", font=title_font,
                  fill=(238, 242, 246))
        draw.text((x, y + 36), c["tag"], font=tag_font, fill=(122, 137, 149))
        sheet.paste(image.resize((thumb_w, thumb_h), Image.Resampling.LANCZOS),
                    (x, y + label_h))
    return sheet


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    renderers = [render_aurelia, render_velum, render_chronos,
                 render_nyx, render_atlas, render_serein]
    items: list[tuple[dict, Image.Image]] = []
    for concept, renderer in zip(CONCEPTS, renderers):
        image = renderer(concept)
        image.save(OUT / f"{concept['slug']}.png", optimize=True)
        items.append((concept, image))
    render_safe_area().save(OUT / "kx11-safe-area.png", optimize=True)
    contact_sheet(items).save(OUT / "natro-cluster-v2-review.png", optimize=True)


if __name__ == "__main__":
    main()
