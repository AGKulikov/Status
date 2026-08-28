#!/usr/bin/env python3
"""Add the reviewed translucent MapActivity theme to the exact Navigator 30.3.0 baseline."""

from __future__ import annotations

import hashlib
from pathlib import Path
import sys


EXPECTED_MANIFEST_SHA256 = (
    "e38f29b5eee70a7fe20b62097ba43c0e2630ef1e6e3b42fadd7cf0025fbe04ca"
)
EXPECTED_STYLES_SHA256 = (
    "408c8da1c1bf4018b8259801725c4a5afa6e516f90ebf88df660b8b51c7a3498"
)
SOURCE_THEME = 'android:theme="@style/SplashAppTheme"'
TARGET_THEME = 'android:theme="@style/NatroTransparentAppTheme"'
MAP_ACTIVITY = 'android:name="ru.yandex.yandexmaps.app.MapActivity"'
STYLE = """    <style name="NatroTransparentAppTheme" parent="@style/AppTheme">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:backgroundDimEnabled">false</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/black</item>
        <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
    </style>
"""


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def patch_manifest(source: str) -> str:
    lines = source.splitlines(keepends=True)
    matches = [
        index for index, line in enumerate(lines)
        if MAP_ACTIVITY in line and SOURCE_THEME in line
    ]
    if len(matches) != 1:
        raise ValueError(
            "expected exactly one MapActivity with the protected SplashAppTheme"
        )
    index = matches[0]
    lines[index] = lines[index].replace(SOURCE_THEME, TARGET_THEME, 1)
    return "".join(lines)


def patch_styles(source: str) -> str:
    if "NatroTransparentAppTheme" in source:
        raise ValueError("transparent Navigator theme is already present")
    marker = "</resources>\n"
    if not source.endswith(marker):
        raise ValueError("styles.xml does not have the reviewed closing marker")
    return source[: -len(marker)] + STYLE + marker


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(f"Usage: {Path(sys.argv[0]).name} APKTOOL_DECODED_ROOT", file=sys.stderr)
        return 2
    root = Path(argv[0])
    manifest = root / "AndroidManifest.xml"
    styles = root / "res" / "values" / "styles.xml"
    if not manifest.is_file() or not styles.is_file():
        raise ValueError("decoded manifest and values/styles.xml must both exist")

    manifest_source = manifest.read_text(encoding="utf-8")
    styles_source = styles.read_text(encoding="utf-8")
    if sha256_text(manifest_source) != EXPECTED_MANIFEST_SHA256:
        raise ValueError("decoded AndroidManifest.xml is not the reviewed 30.3.0 source")
    if sha256_text(styles_source) != EXPECTED_STYLES_SHA256:
        raise ValueError("decoded styles.xml is not the reviewed 30.3.0 source")

    manifest.write_text(patch_manifest(manifest_source), encoding="utf-8")
    styles.write_text(patch_styles(styles_source), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
