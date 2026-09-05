#!/usr/bin/env python3
"""Patch only MapActivity's bootstrap theme in the exact 30.3.0 binary manifest.

Android decides whether an Activity can expose windows behind it before application lifecycle
code runs.  The baseline already contains a non-floating translucent AppCompat theme used by the
FinSDK bottom sheet.  MapActivity uses that existing style only as the system bootstrap theme;
the classes4 patch reapplies its original SplashAppTheme at the start of onCreate so Navigator's
own view/theme contract remains unchanged.  resources.arsc and every res/ entry stay byte-for-byte
identical to the reviewed baseline.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct
import sys


EXPECTED_MANIFEST_SHA256 = (
    "9c9cba4d0661429df8c39954b164563464ff4df11755bb63a775ba42efb94f0f"
)
EXPECTED_PATCHED_SHA256 = (
    "1e8d52eadd9f9561bc76cbeb6f22017d537af8b5eff8803e759be0694f8172e3"
)
SPLASH_APP_THEME = 0x7F1605A2
TRANSLUCENT_BOOTSTRAP_THEME = 0x7F160242  # Finsdk.PaymentKit.BottomSheet
EXPECTED_SPLASH_OFFSETS = (64784, 66984)  # MapActivity, PanoramaActivity
MAP_ACTIVITY_THEME_OFFSET = EXPECTED_SPLASH_OFFSETS[0]


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def patch(payload: bytes) -> bytes:
    if sha256_bytes(payload) != EXPECTED_MANIFEST_SHA256:
        raise ValueError(
            "AndroidManifest.xml is not the reviewed 30.3.0 baseline; refusing fuzzy patch"
        )

    splash = struct.pack("<I", SPLASH_APP_THEME)
    offsets = tuple(
        offset
        for offset in range(len(payload))
        if payload.startswith(splash, offset)
    )
    if offsets != EXPECTED_SPLASH_OFFSETS:
        raise ValueError(
            f"unexpected SplashAppTheme offsets: {offsets!r}; refusing ambiguous patch"
        )

    result = bytearray(payload)
    result[MAP_ACTIVITY_THEME_OFFSET:MAP_ACTIVITY_THEME_OFFSET + 4] = struct.pack(
        "<I", TRANSLUCENT_BOOTSTRAP_THEME
    )
    patched = bytes(result)
    if sha256_bytes(patched) != EXPECTED_PATCHED_SHA256:
        raise ValueError("patched manifest digest is not the reviewed deterministic output")
    return patched


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)
    try:
        payload = args.manifest.read_bytes()
        args.manifest.write_bytes(patch(payload))
    except (OSError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"Patched reviewed MapActivity bootstrap theme: {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
