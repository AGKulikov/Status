#!/usr/bin/env python3

import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


TOOLS = Path(__file__).resolve().parents[1]


def load(name):
    path = TOOLS / name
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


PATCHER = load("patch_navigation_map_activity.py")


class NavigationModToolsTest(unittest.TestCase):
    def test_map_activity_patch_has_only_three_reviewed_hooks(self):
        source = """.class public final Lru/yandex/yandexmaps/app/MapActivity;
.super Landroidx/appcompat/app/s;

.method public final onDestroy()V
    .locals 3
    return-void
.end method

.method public final onNewIntent(Landroid/content/Intent;)V
    .locals 3
    invoke-super {p0, p1}, Landroidx/activity/t;->onNewIntent(Landroid/content/Intent;)V
    return-void
.end method
"""
        original_digest = PATCHER.EXPECTED_SMALI_SHA256
        PATCHER.EXPECTED_SMALI_SHA256 = hashlib.sha256(
            source.encode("utf-8")
        ).hexdigest()
        try:
            result = PATCHER.patch(source)
        finally:
            PATCHER.EXPECTED_SMALI_SHA256 = original_digest

        self.assertEqual(3, result.count(PATCHER.ENTRY_POINT))
        self.assertIn("onActivityReady(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityDestroyed(Landroid/app/Activity;)V", result)
        self.assertIn("onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V", result)
        with self.assertRaisesRegex(ValueError, "already contains"):
            PATCHER.patch(result)

    def test_repacker_replaces_and_appends_without_changing_other_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = root / "baseline.apk"
            replacement = root / "classes4.dex"
            addition = root / "classes19.dex"
            output = root / "output.apk"
            with zipfile.ZipFile(baseline, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("classes4.dex", b"old")
                archive.writestr("assets/protected", b"same")
            replacement.write_bytes(b"new")
            addition.write_bytes(b"isolated")

            subprocess.run(
                [
                    sys.executable,
                    str(TOOLS / "repack_apk_entries.py"),
                    "--baseline", str(baseline),
                    "--output", str(output),
                    "--replace", f"classes4.dex={replacement}",
                    "--replace", f"classes19.dex={addition}",
                ],
                check=True,
                stdout=subprocess.PIPE,
                text=True,
            )
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(b"manifest", archive.read("AndroidManifest.xml"))
                self.assertEqual(b"same", archive.read("assets/protected"))
                self.assertEqual(b"new", archive.read("classes4.dex"))
                self.assertEqual(b"isolated", archive.read("classes19.dex"))


if __name__ == "__main__":
    unittest.main()
