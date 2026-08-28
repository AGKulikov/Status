#!/usr/bin/env python3
"""Select MapKit's reviewed movable TextureView for an exact 30.3.0 floating launch."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys


EXPECTED_SMALI_SHA256 = "c11d1009f97dc2f831a18013a2290db9282b3d769191eebb15511bc5e419a42d"
ENTRY_POINT = "Lru/natro/navigation/NatroEntryPoint;"
MOVABLE_ATTRIBUTE = (
    "Lcom/yandex/runtime/view/PlatformViewFactory$Attribute;->MOVABLE:"
    "Lcom/yandex/runtime/view/PlatformViewFactory$Attribute;"
)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def patch(source: str) -> str:
    if ENTRY_POINT in source:
        raise ValueError("MapView already contains the Natro movable-renderer hook")
    if digest(source) != EXPECTED_SMALI_SHA256:
        raise ValueError(
            "MapView smali is not the reviewed 30.3.0 baseline; refusing fuzzy patch"
        )

    anchor = (
        "    invoke-static {p1, p2}, Lcom/yandex/runtime/view/PlatformViewFactory;"
        "->convertAttributeSet(Landroid/content/Context;Landroid/util/AttributeSet;)"
        "Ljava/util/Set;\n\n"
        "    move-result-object p2\n\n"
        "    invoke-static {p1, p2}, Lcom/yandex/runtime/view/PlatformViewFactory;"
        "->getPlatformView(Landroid/content/Context;Ljava/util/Set;)"
        "Lcom/yandex/runtime/view/PlatformView;\n"
    )
    if source.count(anchor) != 1:
        raise ValueError(
            "MapView constructor: expected exactly one platform-view anchor"
        )

    replacement = (
        "    invoke-static {p1, p2}, Lcom/yandex/runtime/view/PlatformViewFactory;"
        "->convertAttributeSet(Landroid/content/Context;Landroid/util/AttributeSet;)"
        "Ljava/util/Set;\n\n"
        "    move-result-object p2\n\n"
        f"    invoke-static {{p1}}, {ENTRY_POINT}"
        "->shouldUseMovableMap(Landroid/content/Context;)Z\n\n"
        "    move-result p3\n\n"
        "    if-eqz p3, :natro_renderer_ready\n\n"
        f"    sget-object p3, {MOVABLE_ATTRIBUTE}\n\n"
        "    invoke-interface {p2, p3}, Ljava/util/Set;->add(Ljava/lang/Object;)Z\n\n"
        "    :natro_renderer_ready\n"
        "    invoke-static {p1, p2}, Lcom/yandex/runtime/view/PlatformViewFactory;"
        "->getPlatformView(Landroid/content/Context;Ljava/util/Set;)"
        "Lcom/yandex/runtime/view/PlatformView;\n"
    )
    return source.replace(anchor, replacement, 1)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("smali", type=Path)
    args = parser.parse_args(argv)
    try:
        raw = args.smali.read_text(encoding="utf-8")
        result = patch(raw)
        args.smali.write_text(result, encoding="utf-8")
    except (OSError, UnicodeError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"Patched reviewed MapView movable renderer: {args.smali}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
