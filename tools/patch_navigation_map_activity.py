#!/usr/bin/env python3
"""Apply the reviewed lifecycle hooks to the exact 30.3.0 MapActivity smali."""

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

    # ActivityTaskManager reads the translucent bootstrap theme from the manifest before launch.
    # Reapply Navigator's original theme before any onCreate work so its AppTheme attributes and
    # views are unchanged; the system's already-established translucent window classification is
    # retained for the later floating-window background clear.
    create = (
        ".method public final onCreate(Landroid/os/Bundle;)V\n"
        "    .locals 22\n\n"
        "    move-object/from16 v3, p0\n"
    )
    source = replace_once(
        source,
        create,
        create
        + "\n    const v0, 0x7f1605a2\n\n"
        + "    invoke-virtual {v3, v0}, Landroid/app/Activity;->setTheme(I)V\n",
        "onCreate original theme restore",
    )

    start = ".method public final onStart()V\n    .locals 3\n"
    source = replace_once(
        source,
        start,
        start
        + "\n    invoke-static {p0}, " + ENTRY_POINT
        + "->onActivityStarting(Landroid/app/Activity;)V\n",
        "onStart",
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
        + "->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)Z\n\n"
        + "    move-result v0\n\n"
        + "    if-eqz v0, :natro_continue_new_intent\n\n"
        + "    return-void\n\n"
        + "    :natro_continue_new_intent\n",
        "onNewIntent",
    )

    resume_tail = (
        "    invoke-virtual {v1, v0}, "
        "Lio/reactivex/disposables/a;->c(Lio/reactivex/disposables/b;)Z\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final onSaveInstanceState(Landroid/os/Bundle;)V\n"
    )
    source = replace_once(
        source,
        resume_tail,
        "    invoke-virtual {v1, v0}, "
        "Lio/reactivex/disposables/a;->c(Lio/reactivex/disposables/b;)Z\n\n"
        "    invoke-static {p0}, " + ENTRY_POINT
        + "->onActivityResumed(Landroid/app/Activity;)V\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final onSaveInstanceState(Landroid/os/Bundle;)V\n",
        "onResumeFragments insertion",
    )

    stop_tail = (
        "    invoke-static {}, Lz74/f;->c()V\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final onTrimMemory(I)V\n"
    )
    source = replace_once(
        source,
        stop_tail,
        "    invoke-static {}, Lz74/f;->c()V\n\n"
        "    invoke-static {p0}, " + ENTRY_POINT
        + "->onActivityStopped(Landroid/app/Activity;)V\n\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public final onTrimMemory(I)V\n",
        "onStop",
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
