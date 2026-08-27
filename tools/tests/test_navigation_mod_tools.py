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

.method public final onResumeFragments()V
    .locals 2
    invoke-virtual {v1, v0}, Lio/reactivex/disposables/a;->c(Lio/reactivex/disposables/b;)Z

    return-void
.end method

.method public final onSaveInstanceState(Landroid/os/Bundle;)V
    .locals 0
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
        self.assertIn("onActivityResumed(Landroid/app/Activity;)V", result)
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

    def test_pair_signer_requires_one_stable_certificate_and_exact_baseline(self):
        pair = (TOOLS / "sign_navigation_hud_v2_pair.sh").read_text()
        navigator = (TOOLS / "sign_navigation_mod_30_3.sh").read_text()

        self.assertIn(
            "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75",
            pair,
        )
        self.assertIn(
            "663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f",
            pair,
        )
        self.assertIn('KEY_ALIAS="${KEY_ALIAS:-status-widget-ha}"', pair)
        self.assertIn('KEY_ALIAS="${KEY_ALIAS:-status-widget-ha}"', navigator)
        self.assertIn("--v2-signing-enabled true", pair)
        self.assertIn("--v3-signing-enabled true", pair)
        self.assertIn('--min-sdk-version 24 "$NATRO_SIGNED"', pair)
        self.assertIn("build_navigation_mod_30_3.sh", pair)
        self.assertIn("sign_navigation_mod_30_3.sh", pair)
        self.assertIn("'AGKulikov/Status'", pair)
        self.assertIn("test \"$VERSION_NAME\" = '2.4.2'", pair)
        self.assertIn("test \"$VERSION_CODE\" = '208021266'", pair)
        self.assertNotIn('cp "$BASELINE_APK"', pair)

    def test_kx11_pair_gate_freezes_device_identity_and_hud_plane(self):
        verifier = (TOOLS / "verify_kx11_navigation_pair.py").read_text()
        pair = (TOOLS / "sign_navigation_hud_v2_pair.sh").read_text()

        self.assertIn('KX11_ANDROID_API = 28', verifier)
        self.assertIn('KX11_NAVIGATOR_ABIS = {"arm64-v8a"}', verifier)
        self.assertIn('"name": NATRO_PACKAGE', verifier)
        self.assertIn('Navigator binary AndroidManifest.xml changed', verifier)
        self.assertIn('android:sharedUserId', verifier)
        self.assertIn('Main Natro content area: 1760x720', verifier)
        self.assertIn('plane 728x190 @ (0,720)', verifier)
        self.assertIn('probe_api = 24 if scheme == "v2"', verifier)
        self.assertIn('b"navi_win/"', verifier)
        self.assertIn('b"Lru/natro/navigation/NatroEntryPoint;"', verifier)
        self.assertIn('b"ddnavforcewinfull"', verifier)
        self.assertIn('verify_kx11_navigation_pair.py', pair)
        self.assertIn('KX11-COMPATIBILITY.txt', pair)


if __name__ == "__main__":
    unittest.main()
