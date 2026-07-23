#!/usr/bin/env python3
"""Copy update identity and Android compatibility levels from Status into apktool.yml."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


def section_value(text: str, section: str, key: str) -> str:
    match = re.search(
        rf"(?ms)^{re.escape(section)}:\n"
        rf"(?:(?:  .*\n))*?  {re.escape(key)}: ([^\n]+)$",
        text,
    )
    if not match:
        raise SystemExit(f"Missing {section}.{key} in apktool.yml")
    return match.group(1)


def replace_section_value(text: str, section: str, key: str, value: str) -> str:
    pattern = (
        rf"(?ms)(^{re.escape(section)}:\n"
        rf"(?:(?:  .*\n))*?  {re.escape(key)}: )[^\n]+$"
    )
    updated, count = re.subn(pattern, rf"\g<1>{value}", text, count=1)
    if count != 1:
        raise SystemExit(f"Could not replace {section}.{key} in apktool.yml")
    return updated


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--navigator", type=Path, required=True)
    parser.add_argument("--status", type=Path, required=True)
    args = parser.parse_args()
    navigator = args.navigator.read_text(encoding="utf-8")
    status = args.status.read_text(encoding="utf-8")

    for section, key in (
        ("sdkInfo", "minSdkVersion"),
        ("sdkInfo", "targetSdkVersion"),
        ("versionInfo", "versionCode"),
        ("versionInfo", "versionName"),
    ):
        navigator = replace_section_value(
            navigator, section, key, section_value(status, section, key)
        )

    args.navigator.write_text(navigator, encoding="utf-8")


if __name__ == "__main__":
    main()
