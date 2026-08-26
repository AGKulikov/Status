#!/usr/bin/env python3
"""Apply the one reviewed classes4 hook to the exact 30.3.0 MapActivity smali."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys


EXPECTED_SMALI_SHA256 = "da8af8dd309df6cb068bc133d5996f57c2e0a79004cc286a1f00641d27e06a04"
ENTRY_POINT = "Lru/natro/navigation/NatroEntryPoint;"


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def replace_once(source: str, needle: str, replacement: str, label: str) -> str:
    count = source.count(needle)
    if count != 1:
        raise ValueError(f"{label}: expected exactly one anchor, found {count}")
    return source.replace(needle, replacement, 1)


def patch(source: str) -> str:
    if ENTRY_POINT in source:
        raise ValueError("MapActivity already contains the Natro v2 hook")
    if digest(source) != EXPECTED_SMALI_SHA256:
        raise ValueError(
            "MapActivity smali is not the reviewed 30.3.0 baseline; refusing fuzzy patch"
        )

    destroy = ".method public final onDestroy()V\n    .locals 3\n"
    source = replace_once(
        source,
        destroy,
        destroy
        + "\n    invoke-static {p0}, " + ENTRY_POINT
        + "->onActivityDestroyed(Landroid/app/Activity;)V\n",
        "onDestroy",
    )

    new_intent_super = (
        "    invoke-super {p0, p1}, "
        "Landroidx/activity/t;->onNewIntent(Landroid/content/Intent;)V\n"
    )
    source = replace_once(
        source,
        new_intent_super,
        new_intent_super
        + "\n    invoke-static {p0, p1}, " + ENTRY_POINT
        + "->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V\n",
        "onNewIntent",
    )

    new_intent_method = ".method public final onNewIntent(Landroid/content/Intent;)V\n"
    post_create = (
        ".method protected final onPostCreate(Landroid/os/Bundle;)V\n"
        "    .locals 0\n\n"
        "    invoke-super {p0, p1}, "
        "Landroidx/appcompat/app/s;->onPostCreate(Landroid/os/Bundle;)V\n\n"
        "    invoke-static {p0}, " + ENTRY_POINT
        + "->onActivityReady(Landroid/app/Activity;)V\n\n"
        "    return-void\n"
        ".end method\n\n"
    )
    source = replace_once(
        source,
        new_intent_method,
        post_create + new_intent_method,
        "onPostCreate insertion",
    )
    return source


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
    print(f"Patched reviewed MapActivity hook: {args.smali}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
