#!/usr/bin/env python3

import hashlib
import importlib.util
from pathlib import Path
import struct
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
MAP_VIEW_PATCHER = load("patch_navigation_map_view.py")
MANIFEST_PATCHER = load("patch_navigation_manifest_theme.py")


class NavigationModToolsTest(unittest.TestCase):
    def test_binary_theme_patch_is_scoped_to_map_activity(self):
        splash = struct.pack("<I", MANIFEST_PATCHER.SPLASH_APP_THEME)
        bootstrap = struct.pack("<I", MANIFEST_PATCHER.TRANSLUCENT_BOOTSTRAP_THEME)
        manifest = b"HEAD" + splash + b"MIDL" + splash + b"TAIL"
        expected = b"HEAD" + bootstrap + b"MIDL" + splash + b"TAIL"

        original = (
            MANIFEST_PATCHER.EXPECTED_MANIFEST_SHA256,
            MANIFEST_PATCHER.EXPECTED_PATCHED_SHA256,
            MANIFEST_PATCHER.EXPECTED_SPLASH_OFFSETS,
            MANIFEST_PATCHER.MAP_ACTIVITY_THEME_OFFSET,
        )
        MANIFEST_PATCHER.EXPECTED_MANIFEST_SHA256 = hashlib.sha256(manifest).hexdigest()
        MANIFEST_PATCHER.EXPECTED_PATCHED_SHA256 = hashlib.sha256(expected).hexdigest()
        MANIFEST_PATCHER.EXPECTED_SPLASH_OFFSETS = (4, 12)
        MANIFEST_PATCHER.MAP_ACTIVITY_THEME_OFFSET = 4
        try:
            self.assertEqual(expected, MANIFEST_PATCHER.patch(manifest))
        finally:
            (
                MANIFEST_PATCHER.EXPECTED_MANIFEST_SHA256,
                MANIFEST_PATCHER.EXPECTED_PATCHED_SHA256,
                MANIFEST_PATCHER.EXPECTED_SPLASH_OFFSETS,
                MANIFEST_PATCHER.MAP_ACTIVITY_THEME_OFFSET,
            ) = original

    def test_map_activity_patch_has_five_reviewed_hooks(self):
        source = """.class public final Lru/yandex/yandexmaps/app/MapActivity;
.super Landroidx/appcompat/app/s;

.method public final onCreate(Landroid/os/Bundle;)V
    .locals 22

    move-object/from16 v3, p0
    return-void
.end method

.method public final onDestroy()V
    .locals 3
    return-void
.end method

.method public final onStart()V
    .locals 3
    return-void
.end method

.method public final onStop()V
    .locals 3
    invoke-static {}, Lz74/f;->c()V

    return-void
.end method

.method public final onTrimMemory(I)V
    .locals 1
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

        self.assertEqual(5, result.count(PATCHER.ENTRY_POINT))
        self.assertIn("const v0, 0x7f1605a2", result)
        self.assertIn("Landroid/app/Activity;->setTheme(I)V", result)
        self.assertNotIn("onActivityPreCreate(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityResumed(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityDestroyed(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityStarting(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityStopped(Landroid/app/Activity;)V", result)
        self.assertIn("onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)Z", result)
        self.assertIn("if-eqz v0, :natro_continue_new_intent", result)
        self.assertIn(":natro_continue_new_intent", result)
        with self.assertRaisesRegex(ValueError, "already contains"):
            PATCHER.patch(result)

    def test_map_view_patch_selects_movable_renderer_only_for_floating_launch(self):
        source = """.class public Lcom/yandex/mapkit/mapview/MapView;
.super Landroid/widget/RelativeLayout;

.method public constructor <init>(Landroid/content/Context;Landroid/util/AttributeSet;I)V
    .locals 0

    invoke-static {p1, p2}, Lcom/yandex/runtime/view/PlatformViewFactory;->convertAttributeSet(Landroid/content/Context;Landroid/util/AttributeSet;)Ljava/util/Set;

    move-result-object p2

    invoke-static {p1, p2}, Lcom/yandex/runtime/view/PlatformViewFactory;->getPlatformView(Landroid/content/Context;Ljava/util/Set;)Lcom/yandex/runtime/view/PlatformView;
    return-void
.end method
"""
        original_digest = MAP_VIEW_PATCHER.EXPECTED_SMALI_SHA256
        MAP_VIEW_PATCHER.EXPECTED_SMALI_SHA256 = hashlib.sha256(
            source.encode("utf-8")
        ).hexdigest()
        try:
            result = MAP_VIEW_PATCHER.patch(source)
        finally:
            MAP_VIEW_PATCHER.EXPECTED_SMALI_SHA256 = original_digest

        self.assertIn("shouldUseMovableMap(Landroid/content/Context;)Z", result)
        self.assertIn("PlatformViewFactory$Attribute;->MOVABLE", result)
        self.assertIn("if-eqz p3, :natro_renderer_ready", result)
        self.assertEqual(1, result.count(MAP_VIEW_PATCHER.ENTRY_POINT))
        with self.assertRaisesRegex(ValueError, "already contains"):
            MAP_VIEW_PATCHER.patch(result)

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
        self.assertIn('EXPECTED_NATRO_VERSION_NAME="${EXPECTED_NATRO_VERSION_NAME:-2.5.6}"', pair)
        self.assertIn('EXPECTED_NATRO_VERSION_CODE="${EXPECTED_NATRO_VERSION_CODE:-208021289}"', pair)
        self.assertIn('test "$VERSION_NAME" = "$EXPECTED_NATRO_VERSION_NAME"', pair)
        self.assertIn('test "$VERSION_CODE" = "$EXPECTED_NATRO_VERSION_CODE"', pair)
        self.assertNotIn('cp "$BASELINE_APK"', pair)

    def test_hud_renderer_uses_route_bound_layer_with_parked_camera(self):
        renderer = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                    / "ru" / "natro" / "navigation" / "HudMapRenderer.java").read_text()
        publisher = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                     / "ru" / "natro" / "navigation"
                     / "NavigatorStatePublisher.java").read_text()
        cursor = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                  / "ru" / "natro" / "navigation" / "MapCursorStyler.java").read_text()
        camera = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                  / "ru" / "natro" / "navigation"
                  / "CameraDirectionMapLayer.java").read_text()
        lanes = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                 / "ru" / "natro" / "navigation"
                 / "LaneGuidanceMapLayer.java").read_text()

        self.assertIn("NavigationLayerFactory", renderer)
        self.assertIn(
            '"setUseLayerCamera", new Class<?>[]{boolean.class}, true', renderer
        )
        self.assertIn("setRoadEventVisibleOnRoute", renderer)
        self.assertIn("createNativeNavigationLayer", renderer)
        self.assertIn("parkNativeGuidanceCamera", renderer)
        self.assertIn('Enum.valueOf((Class) cameraModeClass, "FREE")', renderer)
        self.assertIn("setSwitchModesAutomatically", renderer)
        self.assertIn("safe standalone road-events layer attached", renderer)
        self.assertIn("nearby fallback active", renderer)
        self.assertNotIn("routeAwareRoadEventStyleProvider", renderer)
        self.assertNotIn('properties, "isOnRoute"', renderer)
        self.assertIn("setMaxNumberOfUpcomingTrafficLights", publisher)
        self.assertIn("MAX_UPCOMING_TRAFFIC_LIGHTS = 8", publisher)
        self.assertIn("setRoadEventVisible", renderer)
        self.assertIn("getActiveSpeedCameras", publisher)
        self.assertIn("getActiveDirections", publisher)
        self.assertIn("cameraDirectionMapLayer.update", renderer)
        self.assertIn("laneGuidanceMapLayer.update", renderer)
        self.assertIn("addDestinationMarker", renderer)
        self.assertIn("showDestination", renderer)
        self.assertIn("destinationPoint", renderer)
        self.assertIn("setDirection", camera)
        self.assertIn("NO_ROTATION", lanes)
        self.assertIn("setGeometry", lanes)
        self.assertIn("ImageProvider", cursor)
        self.assertIn("setGeometry", cursor)
        self.assertIn("setDirection", cursor)
        self.assertNotIn("UserLocationObjectListener", cursor)

    def test_kx11_pair_gate_freezes_device_identity_and_hud_plane(self):
        verifier = (TOOLS / "verify_kx11_navigation_pair.py").read_text()
        pair = (TOOLS / "sign_navigation_hud_v2_pair.sh").read_text()

        self.assertIn("NavigationLayerFactory", verifier)
        self.assertIn("setRoadEventVisibleOnRoute", verifier)
        self.assertIn("route-matched road-events layer attached", verifier)
        self.assertNotIn('b"isOnRoute"', verifier)

        self.assertIn('KX11_ANDROID_API = 28', verifier)
        self.assertIn('KX11_NAVIGATOR_ABIS = {"arm64-v8a"}', verifier)
        self.assertIn('"name": NATRO_PACKAGE', verifier)
        self.assertIn('MapActivity does not use the reviewed translucent bootstrap theme', verifier)
        self.assertIn('android:sharedUserId', verifier)
        self.assertIn('Main Natro content area: 1760x720', verifier)
        self.assertIn('plane 728x190 @ (0,720)', verifier)
        self.assertIn('probe_api = 24 if scheme == "v2"', verifier)
        self.assertIn('b"navi_win/"', verifier)
        self.assertIn('b"Lru/natro/navigation/NatroEntryPoint;"', verifier)
        self.assertIn('b"Lru/natro/navigation/BackgroundMapLease;"', verifier)
        self.assertIn('GuidanceService must retain its location foreground-service type', verifier)
        self.assertIn('b"ddnavforcewinfull"', verifier)
        self.assertIn('b"showDestination"', verifier)
        self.assertIn('verify_kx11_navigation_pair.py', pair)
        self.assertIn('KX11-COMPATIBILITY.txt', pair)


if __name__ == "__main__":
    unittest.main()
