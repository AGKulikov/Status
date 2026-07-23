#!/usr/bin/env python3
"""Inject Status startup hooks into the final NaviApplication smali class."""

from __future__ import annotations

import argparse
from pathlib import Path

ATTACH_METHOD = "attachBaseContext(Landroid/content/Context;)V"
CREATE_METHOD = "onCreate()V"
RESOURCE_CALL = (
    "    invoke-static {p0}, "
    "Ldezz/status/widget/launcher/MergedResourceInstaller;"
    "->install(Landroid/app/Application;)Z"
)
RUNTIME_CALL = (
    "    invoke-static {p0}, "
    "Ldezz/status/widget/StatusWidgetApplication;"
    "->initializeStatusRuntime(Landroid/app/Application;)V"
)
ACTIVITY_RESOURCE_CALL = (
    "    invoke-static {p0}, "
    "Ldezz/status/widget/launcher/MergedResourceInstaller;"
    "->installContext(Landroid/content/Context;)Z"
)


def inject_before_returns(text: str, method: str, call: str) -> str:
    signature = next(
        (line for line in text.splitlines()
         if line.startswith(".method ") and line.endswith(method)),
        None,
    )
    if signature is None:
        raise SystemExit(f"Method not found: {method}")
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Method not found: {method}")
    end = text.find(".end method", start)
    if end < 0:
        raise SystemExit(f"Unterminated method: {method}")
    body = text[start:end]
    if call in body:
        return text
    if "return-void" not in body:
        raise SystemExit(f"No return-void in {method}")
    body = body.replace("    return-void", f"{call}\n    return-void")
    return text[:start] + body + text[end:]


def inject_at_method_start(text: str, method: str, call: str) -> str:
    signature = next(
        (line for line in text.splitlines()
         if line.startswith(".method ") and line.endswith(method)),
        None,
    )
    if signature is None:
        return text
    start = text.find(signature)
    end = text.find(".end method", start)
    if end < 0:
        raise SystemExit(f"Unterminated method: {method}")
    body = text[start:end]
    if call in body:
        return text
    lines = body.splitlines(keepends=True)
    register_index = next(
        (index for index, line in enumerate(lines)
         if line.lstrip().startswith((".locals ", ".registers "))),
        None,
    )
    if register_index is None:
        raise SystemExit(f"No locals/registers directive in {method}")
    lines.insert(register_index + 1, f"\n{call}\n")
    patched = "".join(lines)
    return text[:start] + patched + text[end:]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded_root", type=Path)
    args = parser.parse_args()
    navi_matches = list(args.decoded_root.glob(
        "smali*/ru/yandex/yandexnavi/NaviApplication.smali"))
    base_matches = list(args.decoded_root.glob(
        "smali*/ru/yandex/yandexmaps/app/x2.smali"))
    if len(navi_matches) != 1 or len(base_matches) != 1:
        raise SystemExit(
            f"Expected one NaviApplication and x2 base, found "
            f"{len(navi_matches)} and {len(base_matches)}"
        )

    navi_file = navi_matches[0]
    text = navi_file.read_text(encoding="utf-8")
    if ".class public final Lru/yandex/yandexnavi/NaviApplication;" not in text:
        raise SystemExit("Unexpected NaviApplication class declaration")
    text = inject_before_returns(text, CREATE_METHOD, RUNTIME_CALL)
    navi_file.write_text(text, encoding="utf-8")

    base_file = base_matches[0]
    text = base_file.read_text(encoding="utf-8")
    text = inject_before_returns(text, ATTACH_METHOD, RESOURCE_CALL)
    base_file.write_text(text, encoding="utf-8")

    patched_activities = 0
    for activity_file in args.decoded_root.glob(
            "smali*/dezz/status/widget/**/*Activity.smali"):
        text = activity_file.read_text(encoding="utf-8")
        updated = inject_at_method_start(
            text, "onCreate(Landroid/os/Bundle;)V", ACTIVITY_RESOURCE_CALL
        )
        if updated != text:
            activity_file.write_text(updated, encoding="utf-8")
            patched_activities += 1
    if patched_activities == 0:
        # On an idempotent second pass every matching Activity already contains the call.
        already_patched = any(
            ACTIVITY_RESOURCE_CALL in path.read_text(encoding="utf-8")
            for path in args.decoded_root.glob(
                "smali*/dezz/status/widget/**/*Activity.smali")
        )
        if not already_patched:
            raise SystemExit("No imported Status Activity onCreate method was patched")


if __name__ == "__main__":
    main()
