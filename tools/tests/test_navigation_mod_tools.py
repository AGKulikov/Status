#!/usr/bin/env python3

import hashlib
import importlib.util
from pathlib import Path
import re
import shutil
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
HUD_SPEED_MANIFEST_PATCHER = load("patch_hud_speed_bridge_manifest.py")


class NavigationModToolsTest(unittest.TestCase):
    def test_living_requirements_ledger_is_unique_and_mandatory(self):
        root = TOOLS.parent
        ledger = (root / "PROJECT_REQUIREMENTS_RU.md").read_text()
        agents = (root / "AGENTS.md").read_text()
        readme = (root / "README.md").read_text()
        requirement_ids = re.findall(r"\| ([A-Z]+-[0-9]{3}) \|", ledger)
        self.assertGreaterEqual(len(requirement_ids), 100)
        self.assertEqual(len(requirement_ids), len(set(requirement_ids)))
        self.assertIn("25.08.2026–01.09.2026", ledger)
        self.assertIn("В КОДЕ / НУЖЕН KX11", ledger)
        self.assertIn("REL-004", ledger)
        self.assertIn("MAP-003", ledger)
        self.assertIn("PROJECT_REQUIREMENTS_RU.md", agents)
        self.assertIn("PROJECT_REQUIREMENTS_RU.md", readme)

    def test_camera_speed_units_are_normalized_to_kmh(self):
        source = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                  / "ru" / "natro" / "navigation"
                  / "CameraSpeedNormalizer.java")
        publisher = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                     / "ru" / "natro" / "navigation"
                     / "NavigatorStatePublisher.java").read_text()
        camera_layer = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                        / "ru" / "natro" / "navigation"
                        / "CameraDirectionMapLayer.java").read_text()
        self.assertIn("CameraSpeedNormalizer.fromMapKitMetersPerSecond", publisher)
        self.assertIn("CameraSpeedNormalizer.fromExternal", camera_layer)

        javac = shutil.which("javac")
        java = shutil.which("java")
        if javac is None or java is None:
            self.skipTest("JDK is unavailable locally; GitHub CI executes this Java contract")
        harness = """package ru.natro.navigation;
public final class CameraSpeedNormalizerHarness {
    private static void expect(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        expect(60, CameraSpeedNormalizer.fromMapKitMetersPerSecond(60d / 3.6d));
        expect(90, CameraSpeedNormalizer.fromMapKitMetersPerSecond(25d));
        expect(60, CameraSpeedNormalizer.fromExternal(60d, "KPH"));
        expect(90, CameraSpeedNormalizer.fromExternal(90d, "km/h"));
        expect(97, CameraSpeedNormalizer.fromExternal(60d, "MPH"));
        expect(60, CameraSpeedNormalizer.fromExternal(60d / 3.6d, "MPS"));
        expect(-1, CameraSpeedNormalizer.fromExternal(0d, "KPH"));
        expect(-1, CameraSpeedNormalizer.fromExternal(401d, "KPH"));
    }
}
"""
        with tempfile.TemporaryDirectory() as work:
            work_path = Path(work)
            harness_path = work_path / "CameraSpeedNormalizerHarness.java"
            harness_path.write_text(harness)
            subprocess.run(
                [javac, "-d", str(work_path), str(source), str(harness_path)],
                check=True,
            )
            subprocess.run(
                [java, "-cp", str(work_path),
                 "ru.natro.navigation.CameraSpeedNormalizerHarness"],
                check=True,
            )

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

    def test_map_activity_patch_has_six_reviewed_hooks(self):
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

.method public final dispatchTouchEvent(Landroid/view/MotionEvent;)Z
    .locals 3
    const/4 v0, 0x0
    return v0
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

        self.assertEqual(6, result.count(PATCHER.ENTRY_POINT))
        self.assertIn("const v0, 0x7f1605a2", result)
        self.assertIn("Landroid/app/Activity;->setTheme(I)V", result)
        self.assertNotIn("onActivityPreCreate(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityResumed(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityDestroyed(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityStarting(Landroid/app/Activity;)V", result)
        self.assertIn("onActivityStopped(Landroid/app/Activity;)V", result)
        self.assertIn(
            "onMapTouch(Landroid/app/Activity;Landroid/view/MotionEvent;)V", result
        )
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

    def test_hud_speed_bridge_manifest_patch_is_exact_and_idempotence_safe(self):
        source = (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="air.StrelkaHUDFREE">\n'
            '    <application>\n'
            '    </application>\n'
            '</manifest>\n'
        )
        patched = HUD_SPEED_MANIFEST_PATCHER.patch(source)
        self.assertEqual(1, patched.count(HUD_SPEED_MANIFEST_PATCHER.SERVICE_NAME))
        self.assertIn('android:exported="true"', patched)
        self.assertIn('ru.natro.hudspeed.camera.BIND_V1', patched)
        with self.assertRaisesRegex(ValueError, "already present"):
            HUD_SPEED_MANIFEST_PATCHER.patch(patched)
        with self.assertRaisesRegex(ValueError, "expected HUD Speed package"):
            HUD_SPEED_MANIFEST_PATCHER.patch(source.replace(
                "air.StrelkaHUDFREE", "unexpected.package"))

    def test_hud_speed_bridge_is_bounded_authenticated_and_reproducible(self):
        service = (TOOLS.parent / "hud-speed-bridge" / "src" / "main" / "java"
                   / "air" / "StrelkaSD" / "bridge"
                   / "HudSpeedCameraBridgeService.java").read_text()
        client = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                  / "status" / "widget" / "navigation"
                  / "HudSpeedCameraBridgeClient.java").read_text()
        dex_build = (TOOLS / "build_hud_speed_bridge_dex.sh").read_text()
        apk_build = (TOOLS / "build_hud_speed_bridge_apk.sh").read_text()
        signer = (TOOLS / "sign_hud_speed_bridge_apk.sh").read_text()
        workflow = (TOOLS.parent / ".github" / "workflows"
                    / "verify-navigation-hud-v2.yml").read_text()

        self.assertIn("MAX_CAMERAS = 64", service)
        self.assertIn("message.sendingUid", service)
        self.assertIn("NATRO_CERT_SHA256", service)
        self.assertIn("liveCameraSnapshot", service)
        self.assertIn("directions(type, camera)", service)
        self.assertIn("controlTags(typeId", service)
        self.assertIn("ensureMainServiceRunning()", service)
        self.assertIn("air.StrelkaSD.MainService", service)
        self.assertIn("startForegroundService(runtime)", service)
        self.assertIn("startFromReceiver", service)
        self.assertIn("KEY_RUNTIME_RUNNING", service)
        self.assertNotIn("startActivity(", service)
        self.assertIn("HUD_SPEED_CERT_SHA256", client)
        self.assertIn("isTrustedHudSpeedUid(message.sendingUid)", client)
        self.assertIn("MAX_RAW_CHARS", client)
        self.assertIn("ALLOWED_TAGS", client)
        self.assertIn("RUNTIME_WAKE_OFFSETS_MS", client)
        self.assertIn("0L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L", client)
        self.assertIn("dueRuntimeWakeAttempt()", client)
        self.assertIn("KEY_ENSURE_RUNTIME", client)
        self.assertIn("publishEmpty()", client)
        self.assertIn("classes3.dex", apk_build)
        self.assertIn(
            "9b8a4a4a636968e9b2ca92c8399cdaf18112e9519aec433a9ee7fe42adb413dd",
            apk_build,
        )
        self.assertIn("--min-api 28", dex_build)
        self.assertIn("EXPECTED_CERT_SHA256", signer)
        self.assertIn("--v2-signing-enabled true", signer)
        self.assertIn("--v3-signing-enabled true", signer)
        self.assertIn('--min-sdk-version 24 "$OUTPUT_APK"', signer)
        self.assertIn('--min-sdk-version 28 "$OUTPUT_APK"', signer)
        self.assertIn("Number of signers: 1", signer)
        self.assertIn("requires uninstalling a differently signed HUD Speed", signer)
        self.assertIn('cp build/hud-speed-bridge-ci/classes3.dex "$OUT/classes3.dex"',
                      workflow)
        self.assertIn("hud_speed_baseline_url", workflow)
        self.assertIn("sign_hud_speed_bridge_apk.sh", workflow)
        self.assertIn("HUD-Speed-76.0-L13-NatroBridge-signed.apk", workflow)

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
        self.assertIn('EXPECTED_NATRO_VERSION_NAME="${EXPECTED_NATRO_VERSION_NAME:-2.6.9}"', pair)
        self.assertIn('EXPECTED_NATRO_VERSION_CODE="${EXPECTED_NATRO_VERSION_CODE:-208021302}"', pair)
        self.assertIn('test "$VERSION_NAME" = "$EXPECTED_NATRO_VERSION_NAME"', pair)
        verifier = (TOOLS / "verify_kx11_navigation_pair.py").read_text()
        self.assertIn('os.environ.get("EXPECTED_NATRO_VERSION_NAME", "2.6.9")', verifier)
        self.assertIn('os.environ.get("EXPECTED_NATRO_VERSION_CODE", "208021302")', verifier)
        self.assertIn('test "$VERSION_CODE" = "$EXPECTED_NATRO_VERSION_CODE"', pair)
        self.assertNotIn('cp "$BASELINE_APK"', pair)

        build = (TOOLS.parent / "build.gradle").read_text()
        workflow = (TOOLS.parent / ".github" / "workflows"
                    / "verify-navigation-hud-v2.yml").read_text()
        self.assertIn("if (version == '2.6.9')", build)
        self.assertIn("VERSION_NAME: '2.6.9'", workflow)
        self.assertNotIn("2.5.10", build)
        self.assertNotIn("2.5.10", workflow)

    def test_hud_renderer_forbids_native_navigation_layer_on_external_map(self):
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
        traffic_lights = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                          / "ru" / "natro" / "navigation"
                          / "TrafficLightMapLayer.java").read_text()
        placement = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                     / "ru" / "natro" / "navigation"
                     / "MapOverlayPlacementCoordinator.java").read_text()
        layer_factory = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                         / "ru" / "natro" / "navigation"
                         / "MapObjectLayerFactory.java").read_text()
        layer_order = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                       / "ru" / "natro" / "navigation"
                       / "MapSublayerOrder.java").read_text()
        turns = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                 / "ru" / "natro" / "navigation" / "RouteTurnMapLayer.java").read_text()
        label_path = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                      / "ru" / "natro" / "navigation"
                      / "RouteStreetLabelMapLayer.java")
        profile = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                   / "ru" / "natro" / "navigation"
                   / "NavigationMapProfile.java").read_text()
        entry = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                 / "ru" / "natro" / "navigation" / "NatroEntryPoint.java").read_text()
        controller = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                      / "ru" / "natro" / "navigation"
                      / "FloatingWindowController.java").read_text()

        self.assertNotIn("NavigationLayerFactory", renderer)
        self.assertNotIn("NavigationLayerSettings", renderer)
        self.assertNotIn("createNavigationLayer", renderer)
        self.assertNotIn("setUseLayerCamera", renderer)
        self.assertNotIn("setUseLayerRoadEvents", renderer)
        self.assertNotIn("setRoadEventVisibleOnRoute", renderer)
        self.assertNotIn("createNativeNavigationLayer", renderer)
        self.assertNotIn("parkNativeGuidanceCamera", renderer)
        self.assertIn("safe standalone road-events layer attached", renderer)
        self.assertIn("Automotive NavigationLayer is deliberately forbidden", renderer)
        self.assertIn('routeGuidanceActive && "ROUTE_ONLY".equals(mode)', renderer)
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
        self.assertIn("addPolygon", camera)
        self.assertIn("directionLengthPercent", camera)
        self.assertIn("directionWidthPercent", camera)
        self.assertIn("double[] farCenter", camera)
        self.assertIn("directionDegrees - 90d", camera)
        self.assertIn("directionDegrees + 90d", camera)
        self.assertIn("opaqueRgb(nextDirectionColor", camera)
        self.assertIn("directionOpacityPercent", camera)
        self.assertIn("addOrMergeDuplicate", camera)
        self.assertIn("mergeIntoNearbyHudSpeed", camera)
        self.assertIn("CameraMarker.merge", camera)
        self.assertIn("setZIndex", camera)
        self.assertNotIn("setDirection", camera)
        self.assertIn("STANDARD_SIGN_RED", camera)
        self.assertIn("contentWidth", camera)
        self.assertIn("diameter - overlap", camera)
        self.assertIn("new_pin_alerts_lanecamera_40", camera)
        self.assertIn("new_pin_alerts_crossroad_camera_40", camera)
        self.assertIn("detailDrawableName(camera.controlTags)", camera)
        self.assertNotIn("badgeSize", camera)
        self.assertNotIn("badgeCx", camera)
        self.assertNotIn("drawCameraGlyph", camera)
        self.assertNotIn("visibleControlTags", camera)
        self.assertNotIn("drawControlGlyph", camera)
        self.assertNotIn("Path pin", camera)
        self.assertIn("LaneSignBalloonTextureFactory", lanes)
        self.assertIn("Balloon", lanes)
        self.assertIn('getMethod("createTexture"', lanes)
        self.assertIn('"getBalloonGeometry"', lanes)
        self.assertIn('"getImageAnchor"', lanes)
        self.assertIn("BalloonAnchor", lanes)
        self.assertNotIn('getMethod("createView"', lanes)
        self.assertIn("NO_ROTATION", lanes)
        self.assertIn("setGeometry", lanes)
        self.assertIn("setLegPlacement", traffic_lights)
        self.assertIn("placement.legName", traffic_lights)
        self.assertNotIn('Enum.valueOf((Class<? extends Enum>) legClass, "NONE")',
                         traffic_lights)
        self.assertIn("compactTrafficLightBitmap(light, placement.legName)",
                      traffic_lights)
        self.assertIn("worldToScreen", placement)
        self.assertIn("overlapArea", placement)
        for leg_name in ("LEFT_CENTER", "RIGHT_CENTER", "BOTTOM_LEFT",
                         "BOTTOM_RIGHT", "TOP_LEFT", "TOP_RIGHT",
                         "BOTTOM_CENTER", "TOP_CENTER"):
            self.assertIn(leg_name, placement)
        self.assertIn("overlayPlacement.beginLayout()", renderer)
        self.assertIn("laneGuidanceMapLayer.relayout()", renderer)
        self.assertIn("trafficLightMapLayer.relayout()", renderer)
        self.assertIn("cameraDirectionMapLayer.relayout()", renderer)
        self.assertIn("ImageProvider", cursor)
        self.assertIn("setGeometry", cursor)
        self.assertIn("setDirection", cursor)
        self.assertNotIn("UserLocationObjectListener", cursor)
        self.assertIn("addMapObjectLayer", layer_factory)
        self.assertIn("setConflictResolutionMode", layer_factory)
        self.assertIn("MapObjectLayerFactory.MAJOR", lanes)
        self.assertIn("applySublayerOrder", renderer)
        self.assertIn("getSublayerManager", renderer)
        self.assertIn("SublayerFeatureType", layer_order)
        self.assertIn("new Class<?>[]{String.class, featureClass}", layer_order)
        self.assertIn("moveAfter", layer_order)
        self.assertIn("moveBefore", layer_order)
        self.assertNotIn("moveToEnd", layer_order)
        self.assertIn("CAMERA_SECTORS", camera)
        self.assertIn("CAMERA_SIGNS", camera)
        self.assertIn("sectorCollection", camera)
        self.assertIn("signCollection", camera)
        self.assertIn("MapObjectLayerFactory.IGNORE", camera)
        self.assertIn("MIN_CAMERA_TEXTURE_DIAMETER_PX = 80", camera)
        self.assertIn("Math.max(displayDiameter, MIN_CAMERA_TEXTURE_DIAMETER_PX)",
                      camera)
        self.assertIn("Float.valueOf(textureScale)", camera)
        self.assertIn("routeTurnMapLayer.attachRoute", renderer)
        self.assertIn("createDefaultManeuverStyle", turns)
        self.assertIn("addManeuvers", turns)
        self.assertIn("applyManeuverStyle", turns)
        self.assertIn("ArrowManeuverStyle", turns)
        self.assertIn("boolean.class", turns)
        self.assertIn('"setVisible"', turns)
        self.assertIn("discardExpiredSource();", turns)
        self.assertIn("addArrow", turns)
        self.assertIn("PolylinePosition", turns)
        self.assertIn("configuredColor(fillColor", turns)
        self.assertIn("configuredColor(outlineColor", turns)
        self.assertIn('number(source, "getLength", 80f) * lengthScale', turns)
        self.assertIn('number(source, "getTriangleHeight", 16f) * headScale', turns)
        self.assertNotIn("outlineWidth * lengthScale", turns)
        self.assertNotIn("getOutlineWidth", turns)
        self.assertIn("arrow body tied to the route's current stroke width", turns)
        self.assertNotIn("ignoredLayerPriority", turns)
        self.assertIn("owning route polyline", turns)
        self.assertIn("Math.max(10, Math.min(250, nextLengthPercent))", turns)
        self.assertIn("Math.max(10, Math.min(250, nextHeadSizePercent))", turns)
        self.assertIn("profile.routeTurnLengthPercent", renderer)
        self.assertIn("profile.routeTurnHeadSizePercent", renderer)
        self.assertIn("profile.routeTurnFillColor", renderer)
        self.assertIn("profile.routeTurnOutlineColor", renderer)
        self.assertIn("profile.routeTurnOutlineWidth", renderer)
        self.assertNotIn("Canvas", turns)
        self.assertNotIn("createArrowBitmap", turns)
        self.assertIn("fullRoute(route)", renderer)
        self.assertIn("Subpolyline", renderer)
        self.assertIn('invoke(line, "hide"', renderer)
        self.assertNotIn('invoke(line, "setGeometry"', renderer)
        self.assertFalse(label_path.exists())
        self.assertNotIn("routeStreetLabelMapLayer", renderer)
        self.assertNotIn("readRouteStreetLabels", publisher)
        self.assertIn("MapObjectLayerFactory.MINOR", renderer)
        self.assertIn(r'\"elements\":\"label.text\"', profile)
        self.assertIn('source.optBoolean("routeStreetLabelsOnly", false)', profile)
        self.assertIn("positionOnRoute", publisher)
        self.assertIn("onMapTouch(Activity activity, MotionEvent event)", entry)
        self.assertIn("ensureControlLayerAttached", controller)
        self.assertIn("dispatchFloatingInsetsToNavigatorRoots", controller)
        self.assertIn('"controls_engine_container"', controller)
        self.assertIn("controlsInsetHost = (View) controlsEngine.getParent()", controller)
        self.assertIn('"maps_activity_top_notification_container"', controller)
        self.assertIn('"navi_guidance_controls_touch_container"', controller)
        self.assertIn('"top_notification_container"', controller)
        self.assertIn("paddingtonBaseTop", controller)
        self.assertIn("floatingTopInsetGuard", controller)
        self.assertIn("ensureModeButtonInOverlay", controller)
        self.assertIn("layer.addView(button", controller)
        self.assertNotIn("rail.addView(button", controller)
        self.assertIn("if (controlLayer == null) install()", controller)
        self.assertIn("neutralizePaddingtonTree", controller)
        self.assertIn("floatingPaddingtonInsetsListener", controller)
        self.assertIn("child.setOnApplyWindowInsetsListener", controller)
        self.assertIn('reportCallbackFailure("modeAwareInsets"', controller)
        self.assertIn('reportCallbackFailure("paddingtonInsets"', controller)
        self.assertIn('reportCallbackFailure("floatingTopInsetGuard"', controller)
        self.assertIn('reportCallbackFailure("hideModeButtons"', controller)
        self.assertIn('reportCallbackFailure("floatingSurfaceCommitter"', controller)
        self.assertIn('reportCallbackFailure("mapTouchReattach"', controller)
        self.assertIn('reportCallbackFailure("modeButtonPoller"', controller)
        self.assertIn("finally {", controller)
        self.assertIn("restartInMode(!floating, null)", controller)
        self.assertNotIn("floatingModeButton", controller)

    def test_kx11_pair_gate_freezes_device_identity_and_hud_plane(self):
        verifier = (TOOLS / "verify_kx11_navigation_pair.py").read_text()
        pair = (TOOLS / "sign_navigation_hud_v2_pair.sh").read_text()

        self.assertIn("for forbidden in (", verifier)
        self.assertIn("forbidden native crash marker", verifier)
        self.assertIn("if forbidden in classes19", verifier)
        self.assertIn('b"NavigationLayerSettings"', verifier)
        self.assertIn('b"createNavigationLayer"', verifier)
        self.assertIn('b"setUseLayerCamera"', verifier)
        self.assertIn('b"setUseLayerRoadEvents"', verifier)
        self.assertIn('b"GuidanceCamera"', verifier)
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
        self.assertIn('b"addManeuvers"', verifier)
        self.assertIn('verify_kx11_navigation_pair.py', pair)
        self.assertIn('KX11-COMPATIBILITY.txt', pair)


if __name__ == "__main__":
    unittest.main()
