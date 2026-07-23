#!/usr/bin/env python3
"""Apply the one deterministic Apktool round-trip fix required by the reviewed base APK."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded_root", type=Path)
    args = parser.parse_args()

    layout = args.decoded_root / "res/layout/care_offer_order.xml"
    text = layout.read_text(encoding="utf-8")
    invalid = ' android:gravity="0x0"'
    count = text.count(invalid)
    if count == 0:
        # Idempotence is useful when inspecting an interrupted local build.
        return
    if count != 1:
        raise SystemExit(
            f"Expected one invalid zero gravity in {layout}, found {count}"
        )
    layout.write_text(text.replace(invalid, "", 1), encoding="utf-8")


if __name__ == "__main__":
    main()
