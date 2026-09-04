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
    def test_speed_bumps_use_exact_route_positions_without_navigation_layer(self):
        root = TOOLS.parent
        navigator = root / "navigator-mod" / "src" / "main" / "java" / "ru" / "natro" / "navigation"
        layer = (navigator / "SpeedBumpMapLayer.java").read_text()
        renderer = (navigator / "HudMapRenderer.java").read_text()
        profile = (navigator / "NavigationMapProfile.java").read_text()
        order = (navigator / "MapSublayerOrder.java").read_text()
        config = (root / "app" / "src" / "main" / "java" / "dezz" / "status"
                  / "widget" / "navigation" / "NavigationIntegrationConfig.java").read_text()

        self.assertIn('invoke(route, "getSpeedBumps"', layer)
        self.assertIn('"pointByPolylinePosition"', layer)
        self.assertIn('"mapkit_styling_automotive_route_speed_bump"', layer)
        self.assertIn("MapObjectLayerFactory.IGNORE", layer)
        self.assertIn("Float.valueOf(1f)", layer)
        self.assertIn("scalePercent / 100f", layer)
        self.assertIn("presentationChanged || visibilityChanged", layer)
        self.assertIn("isPassed(marker.speedBump)", layer)
        self.assertIn("placementCoordinator.reserveFixed", layer)
        self.assertNotIn("getSpeedLimits", layer)
        self.assertNotIn("createNavigationLayer", layer)
        self.assertNotIn("NavigationLayerSettings", layer)
        self.assertIn("speedBumpMapLayer.updateRoute(routeEpoch, drivingRoute)", renderer)
        self.assertIn("profile.showSpeedBumps", renderer)
        self.assertIn("effectiveSpeedBumpPriority", profile)
        self.assertIn("SPEED_BUMPS", order)
        for key in ("showSpeedBumps", "speedBumpScalePercent",
                    "speedBumpLayerPriority"):
            self.assertIn(key, config)

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
        if javac is not None:
            compiler = [javac]
        elif java is not None:
            compiler = [java, "com.sun.tools.javac.Main"]
        else:
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
                compiler + ["-d", str(work_path), str(source), str(harness_path)],
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

    def test_route_aware_overlay_placement_geometry(self):
        source = (TOOLS.parent / "navigator-mod" / "src" / "main" / "java"
                  / "ru" / "natro" / "navigation"
                  / "MapOverlayPlacementCoordinator.java")
        javac = shutil.which("javac")
        java = shutil.which("java")
        if javac is not None:
            compiler = [javac]
        elif java is not None:
            # The local runtime contains jdk.compiler even when its launcher is omitted.
            compiler = [java, "com.sun.tools.javac.Main"]
        else:
            self.skipTest("JDK is unavailable locally; GitHub CI executes this Java contract")

        rect_stub = """package android.graphics;
public final class RectF {
    public float left, top, right, bottom;
    public RectF(float left, float top, float right, float bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public RectF(RectF other) {
        this(other.left, other.top, other.right, other.bottom);
    }
    public void inset(float dx, float dy) {
        left += dx; right -= dx; top += dy; bottom -= dy;
    }
}
"""
        point_stub = """package com.yandex.mapkit.geometry;
public final class Point {
    private final double latitude;
    private final double longitude;
    public Point(double latitude, double longitude) {
        this.latitude = latitude; this.longitude = longitude;
    }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
}
"""
        reflection_stub = """package ru.natro.navigation;
import java.lang.reflect.Method;
final class ReflectMethods {
    static Method publicMethod(Class<?> owner, String name, Class<?>[] parameters)
            throws NoSuchMethodException {
        return owner.getMethod(name, parameters);
    }
}
"""
        harness = """package ru.natro.navigation;
import android.graphics.RectF;
import com.yandex.mapkit.geometry.Point;
import java.util.Arrays;
import java.util.List;

public final class MapOverlayPlacementHarness {
    public static final class ScreenPoint {
        private final float x, y;
        ScreenPoint(float x, float y) { this.x = x; this.y = y; }
        public float getX() { return x; }
        public float getY() { return y; }
    }
    public static final class Window {
        private final double angle;
        Window(double angle) { this.angle = Math.toRadians(angle); }
        public ScreenPoint worldToScreen(Point point) {
            double x = point.getLongitude();
            double y = -point.getLatitude();
            double rotatedX = x * Math.cos(angle) - y * Math.sin(angle);
            double rotatedY = x * Math.sin(angle) + y * Math.cos(angle);
            return new ScreenPoint((float) (110d + rotatedX),
                    (float) (110d + rotatedY));
        }
    }
    public static final class RoutePoint {
        private final double latitude, longitude;
        RoutePoint(double latitude, double longitude) {
            this.latitude = latitude; this.longitude = longitude;
        }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
    }
    public static final class Geometry {
        private final List<RoutePoint> points;
        Geometry(RoutePoint... points) { this.points = Arrays.asList(points); }
        public List<RoutePoint> getPoints() { return points; }
    }
    public static final class Route {
        private final Geometry geometry;
        Route(RoutePoint... points) { geometry = new Geometry(points); }
        public Geometry getGeometry() { return geometry; }
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static RectF bounds(float x, float y, int width, int height,
                                MapOverlayPlacementCoordinator.Placement placement) {
        float left = x - placement.anchorX * width;
        float top = y - placement.anchorY * height;
        return new RectF(left, top, left + width, top + height);
    }
    private static void expectRouteClear(Window window, RectF card, RoutePoint... points) {
        double hidden = 0d;
        for (int index = 0; index < points.length - 1; index++) {
            ScreenPoint from = window.worldToScreen(
                    new Point(points[index].getLatitude(), points[index].getLongitude()));
            ScreenPoint to = window.worldToScreen(new Point(
                    points[index + 1].getLatitude(), points[index + 1].getLongitude()));
            hidden += MapOverlayPlacementCoordinator.clippedSegmentLength(card,
                    from.getX(), from.getY(), to.getX(), to.getY());
        }
        expect(hidden < .01d, "selected card hides " + hidden + " route pixels");
    }
    private static List<MapOverlayPlacementCoordinator.Footprint> stockFootprints(
            int width, int height) {
        return Arrays.asList(
                new MapOverlayPlacementCoordinator.Footprint(
                        "LEFT_CENTER", width, height, 0f, .5f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "RIGHT_CENTER", width, height, 1f, .5f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "BOTTOM_LEFT", width, height, .22f, 1f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "BOTTOM_RIGHT", width, height, .78f, 1f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "TOP_LEFT", width, height, .22f, 0f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "TOP_RIGHT", width, height, .78f, 0f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "BOTTOM_CENTER", width, height, .5f, 1f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "TOP_CENTER", width, height, .5f, 0f));
    }
    private static void exerciseTurn(double angle, boolean right) {
        Window window = new Window(angle);
        double exitLongitude = right ? 60d : -60d;
        RoutePoint[] points = {
                new RoutePoint(-60d, 0d), new RoutePoint(0d, 0d),
                new RoutePoint(0d, exitLongitude),
                new RoutePoint(0d, right ? 78d : -78d)
        };
        MapOverlayPlacementCoordinator coordinator =
                new MapOverlayPlacementCoordinator();
        coordinator.attach(window, 220, 220);
        coordinator.updateRoute(1L, new Route(points));
        coordinator.updateNavigationState(true, true, 0, .25d,
                -45d, 0d, 18);
        ScreenPoint source = window.worldToScreen(new Point(0d, 0d));
        for (int percent : new int[]{50, 75, 100}) {
            int width = Math.round(52f * percent / 100f);
            int height = Math.round(32f * percent / 100f);
            coordinator.beginLayout();
            MapOverlayPlacementCoordinator.Placement placement = coordinator.reserve(
                    MapOverlayPlacementCoordinator.OWNER_TRAFFIC_LIGHTS, "turn",
                    0d, 0d, width, height, true, 1, 0d, null,
                    stockFootprints(width, height));
            RectF card = bounds(source.getX(), source.getY(), width, height, placement);
            // The stock leg itself meets the approach exactly at the source point. The safety
            // invariant is that the card body does not cover the road the driver must follow.
            expectRouteClear(window, card, points[1], points[2], points[3]);

            coordinator.beginLayout();
            MapOverlayPlacementCoordinator.Placement stable = coordinator.reserve(
                    MapOverlayPlacementCoordinator.OWNER_TRAFFIC_LIGHTS, "turn",
                    0d, 0d, width, height, true, 1, 0d, placement,
                    stockFootprints(width, height));
            expect(placement.sameSlot(stable),
                    "stable route changed slot without a conflict");
        }
    }
    private static void exerciseSShape(double angle) {
        Window window = new Window(angle);
        RoutePoint[] points = {
                new RoutePoint(-65d, 0d), new RoutePoint(-30d, 0d),
                new RoutePoint(-8d, 24d), new RoutePoint(14d, -8d),
                new RoutePoint(52d, 0d)
        };
        MapOverlayPlacementCoordinator coordinator =
                new MapOverlayPlacementCoordinator();
        coordinator.attach(window, 220, 220);
        coordinator.updateRoute(2L, new Route(points));
        coordinator.updateNavigationState(true, true, 0, .4d,
                -50d, 0d, 18);
        ScreenPoint source = window.worldToScreen(new Point(-8d, 24d));
        for (int percent : new int[]{50, 75, 100}) {
            int width = Math.round(48f * percent / 100f);
            int height = Math.round(30f * percent / 100f);
            coordinator.beginLayout();
            MapOverlayPlacementCoordinator.Placement placement = coordinator.reserve(
                    MapOverlayPlacementCoordinator.OWNER_LANES, "s-shape",
                    -8d, 24d, width, height, false, 2, 0d, null,
                    stockFootprints(width, height));
            expectRouteClear(window,
                    bounds(source.getX(), source.getY(), width, height, placement),
                    points[2], points[3], points[4]);
        }
    }
    private static void exerciseViewport() {
        Window window = new Window(0d);
        MapOverlayPlacementCoordinator coordinator =
                new MapOverlayPlacementCoordinator();
        coordinator.attach(window, 220, 220);
        coordinator.updateNavigationState(false, false, 0, Double.NaN,
                Double.NaN, Double.NaN, 0);
        coordinator.beginLayout();
        MapOverlayPlacementCoordinator.Placement placement = coordinator.reserve(
                MapOverlayPlacementCoordinator.OWNER_TRAFFIC_LIGHTS, "edge",
                85d, -90d, 48, 30, true);
        ScreenPoint source = window.worldToScreen(new Point(85d, -90d));
        RectF card = bounds(source.getX(), source.getY(), 48, 30, placement);
        expect(card.left >= 6f && card.top >= 6f
                        && card.right <= 214f && card.bottom <= 214f,
                "edge placement escaped viewport");
    }
    private static void exerciseExactFootprints() {
        Window window = new Window(0d);
        MapOverlayPlacementCoordinator coordinator =
                new MapOverlayPlacementCoordinator();
        coordinator.attach(window, 220, 220);
        coordinator.updateNavigationState(false, false, 0, Double.NaN,
                Double.NaN, Double.NaN, 0);
        List<MapOverlayPlacementCoordinator.Footprint> footprints = Arrays.asList(
                new MapOverlayPlacementCoordinator.Footprint(
                        "LEFT_CENTER", 70, 30, 0f, .5f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "RIGHT_CENTER", 40, 30, 1f, .5f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "BOTTOM_LEFT", 70, 50, .2f, 1f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "BOTTOM_RIGHT", 40, 50, .8f, 1f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "TOP_LEFT", 70, 50, .2f, 0f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "TOP_RIGHT", 40, 50, .8f, 0f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "BOTTOM_CENTER", 55, 50, .5f, 1f),
                new MapOverlayPlacementCoordinator.Footprint(
                        "TOP_CENTER", 55, 50, .5f, 0f));
        coordinator.beginLayout();
        MapOverlayPlacementCoordinator.Placement placement = coordinator.reserve(
                MapOverlayPlacementCoordinator.OWNER_TRAFFIC_LIGHTS, "exact-edge",
                0d, 90d, 180, 180, true, -1, Double.NaN, null, footprints);
        expect("RIGHT_CENTER".equals(placement.legName),
                "exact stock footprint was not used at viewport edge");
        expect(Math.abs(placement.anchorX - 1f) < .0001f
                        && Math.abs(placement.anchorY - .5f) < .0001f,
                "returned anchor is not the exact stock anchor");
    }
    public static void main(String[] args) {
        RectF clip = new RectF(0f, 0f, 10f, 10f);
        expect(Math.abs(MapOverlayPlacementCoordinator.clippedSegmentLength(
                clip, -5f, 5f, 15f, 5f) - 10d) < .0001d, "horizontal clip");
        expect(MapOverlayPlacementCoordinator.clippedSegmentLength(
                clip, -5f, -5f, -1f, -1f) == 0d, "outside clip");
        for (double angle : new double[]{0d, 45d, 90d, 180d, 270d}) {
            exerciseTurn(angle, true);
            exerciseTurn(angle, false);
            exerciseSShape(angle);
        }
        exerciseViewport();
        exerciseExactFootprints();
    }
}
"""
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            rect_path = root / "android" / "graphics" / "RectF.java"
            point_path = root / "com" / "yandex" / "mapkit" / "geometry" / "Point.java"
            reflection_path = (root / "ru" / "natro" / "navigation"
                               / "ReflectMethods.java")
            harness_path = (root / "ru" / "natro" / "navigation"
                            / "MapOverlayPlacementHarness.java")
            for path in (rect_path, point_path, reflection_path, harness_path):
                path.parent.mkdir(parents=True, exist_ok=True)
            rect_path.write_text(rect_stub)
            point_path.write_text(point_stub)
            reflection_path.write_text(reflection_stub)
            harness_path.write_text(harness)
            subprocess.run(
                [*compiler, "-d", str(root), str(rect_path), str(point_path),
                 str(reflection_path), str(source), str(harness_path)],
                check=True,
            )
            subprocess.run(
                [java, "-cp", str(root),
                 "ru.natro.navigation.MapOverlayPlacementHarness"],
                check=True,
            )

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
        self.assertIn('EXPECTED_NATRO_VERSION_NAME="${EXPECTED_NATRO_VERSION_NAME:-2.7.5}"', pair)
        self.assertIn('EXPECTED_NATRO_VERSION_CODE="${EXPECTED_NATRO_VERSION_CODE:-208021308}"', pair)
        self.assertIn('test "$VERSION_NAME" = "$EXPECTED_NATRO_VERSION_NAME"', pair)
        verifier = (TOOLS / "verify_kx11_navigation_pair.py").read_text()
        self.assertIn('os.environ.get("EXPECTED_NATRO_VERSION_NAME", "2.7.5")', verifier)
        self.assertIn('os.environ.get("EXPECTED_NATRO_VERSION_CODE", "208021308")', verifier)
        self.assertIn('test "$VERSION_CODE" = "$EXPECTED_NATRO_VERSION_CODE"', pair)
        self.assertNotIn('cp "$BASELINE_APK"', pair)

        build = (TOOLS.parent / "build.gradle").read_text()
        workflow = (TOOLS.parent / ".github" / "workflows"
                    / "verify-navigation-hud-v2.yml").read_text()
        self.assertIn("if (version == '2.7.5')", build)
        self.assertIn("return 208021308", build)
        self.assertIn("VERSION_NAME: '2.7.5'", workflow)
        self.assertIn("VERSION_CODE: '208021308'", workflow)
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
        graphics = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                    / "status" / "widget" / "launcher"
                    / "NavigationGraphicStore.java").read_text()
        sharpness_audit = (TOOLS.parent / "docs"
                           / "NAVIGATION_SHARPNESS_AUDIT_RU.md").read_text()
        integration_config = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                              / "status" / "widget" / "navigation"
                              / "NavigationIntegrationConfig.java").read_text()
        hud_settings = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                        / "status" / "widget"
                        / "HudPanelSettingsActivity.java").read_text()
        cluster_settings = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                            / "status" / "widget"
                            / "InstrumentPanelSettingsActivity.java").read_text()
        hud_types = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                     / "status" / "widget" / "hud" / "HudElementType.java").read_text()
        hud_state = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                     / "status" / "widget" / "hud" / "HudNavigationState.java").read_text()
        hud_runtime = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                       / "status" / "widget" / "hud" / "HudRuntimeData.java").read_text()
        hud_canvas = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                      / "status" / "widget" / "hud" / "HudCanvasView.java").read_text()
        instrument_types = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                            / "status" / "widget" / "instrument"
                            / "InstrumentElementType.java").read_text()
        instrument_canvas = (TOOLS.parent / "app" / "src" / "main" / "java" / "dezz"
                             / "status" / "widget" / "instrument"
                             / "InstrumentClusterView.java").read_text()

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
        self.assertIn("MAX_UPCOMING_TRAFFIC_LIGHTS = 16", publisher)
        self.assertIn("trafficLightIdentity(", publisher)
        self.assertIn("routeEpoch", publisher)
        self.assertNotIn("if (routePosition == null) return result", publisher)
        self.assertIn("catch (Throwable invalidLight)", publisher)
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
        self.assertIn('invoke(style, "setScale"', lanes)
        self.assertIn("setLegPlacement", traffic_lights)
        self.assertIn("placement.legName", traffic_lights)
        self.assertIn('getMethod("getSize", legClass)', traffic_lights)
        self.assertIn("measureStockFootprints", traffic_lights)
        self.assertIn("scalePercent / 100f", traffic_lights)
        self.assertIn("float safeScale = Math.max(.01f, textureScale)",
                      traffic_lights)
        self.assertIn("floatValue() * safeScale", traffic_lights)
        self.assertIn("measureBalloonFootprints", lanes)
        self.assertIn("placementLegNames", placement)
        self.assertIn("List<Footprint> footprints", placement)
        self.assertIn("useCompositeIcon", traffic_lights)
        self.assertIn('"traffic-light-connector"', traffic_lights)
        self.assertIn('"traffic-light-body"', traffic_lights)
        self.assertNotIn("ConnectorTexture.OVERSAMPLE", traffic_lights)
        self.assertIn('invoke(connectorStyle, "setScale"', traffic_lights)
        self.assertIn("normalizedCardColor", traffic_lights)
        self.assertIn("resolvedCardColor", traffic_lights)
        self.assertIn("applyConfiguredCardColor", traffic_lights)
        self.assertIn("backgroundPaintPrimary$delegate", traffic_lights)
        self.assertIn("setLegColor", traffic_lights)
        self.assertIn("if (cardColor.isEmpty())", traffic_lights)
        self.assertIn("profile.trafficLightCardColor", renderer)
        self.assertIn("trafficLightCardColor", integration_config)
        for settings in (hud_settings, cluster_settings):
            self.assertIn("Цвет плашки и хвостика светофора", settings)
            self.assertIn("trafficLightCardColor.value", settings)
        self.assertIn("Float.valueOf(zIndex - .01f)", traffic_lights)
        self.assertEqual(traffic_lights.count("MapObjectLayerFactory.create(map"), 1)
        self.assertIn('"traffic_light_leg_size"', traffic_lights)
        self.assertIn('"traffic_light_bg_primary"', traffic_lights)
        self.assertNotIn('invoke(composite, "removeAll"', traffic_lights)
        self.assertIn("new PointF(offsetX / width, offsetY / height)", traffic_lights)
        for leg_name in ("LEFT_CENTER", "RIGHT_CENTER", "BOTTOM_LEFT",
                         "BOTTOM_RIGHT", "TOP_LEFT", "TOP_RIGHT",
                         "BOTTOM_CENTER", "TOP_CENTER"):
            self.assertIn(f'"{leg_name}".equals(legName)', traffic_lights)
        self.assertNotIn('Enum.valueOf((Class<? extends Enum>) legClass, "NONE")',
                         traffic_lights)
        self.assertIn("compactTrafficLightBitmap(light, placement.legName)",
                      traffic_lights)
        self.assertIn("worldToScreen", placement)
        self.assertIn("overlapArea", placement)
        self.assertIn("ROUTE_APPROACH_SEGMENTS", placement)
        self.assertIn("ROUTE_FORWARD_WEIGHT", placement)
        self.assertIn("ROUTE_TURN_BONUS", placement)
        self.assertIn("routeOcclusion", placement)
        self.assertIn("projectedBend", placement)
        self.assertIn("clippedSegmentLength", placement)
        self.assertIn("SLOT_CHANGE_PENALTY", placement)
        self.assertIn("reserveCentered", placement)
        self.assertIn("isPointInsideViewport", placement)
        self.assertIn('new Candidate(.50f, .50f, "CENTER")', placement)
        self.assertIn("placementCoordinator.reserveCentered", camera)
        self.assertIn("applyAtomicVisibility", camera)
        self.assertIn("sign.sectors.add(addSector", camera)
        for leg_name in ("LEFT_CENTER", "RIGHT_CENTER", "BOTTOM_LEFT",
                         "BOTTOM_RIGHT", "TOP_LEFT", "TOP_RIGHT",
                         "BOTTOM_CENTER", "TOP_CENTER"):
            self.assertIn(leg_name, placement)
        self.assertIn("overlayPlacement.beginLayout()", renderer)
        self.assertIn('invoke(currentNaviKitGuidance, "leftInTrafficJam")', publisher)
        self.assertIn('"trafficJamDurationSeconds"', publisher)
        self.assertIn('"trafficJamDistanceMeters"', publisher)
        self.assertIn("NAV_TRAFFIC_JAM", hud_types)
        self.assertIn("case NAV_TRAFFIC_JAM", hud_state)
        self.assertIn('return "Пробка на " + navigation.trafficJamDuration',
                      hud_runtime)
        self.assertIn("drawTrafficJamForecast", hud_canvas)
        self.assertIn("TRAFFIC_JAM", instrument_types)
        self.assertIn("drawTrafficJamForecast", instrument_canvas)
        self.assertIn("if (!available && !editorMode) return", instrument_canvas)
        self.assertNotIn("Bitmap.createScaledBitmap", traffic_lights)
        self.assertNotIn("Bitmap.createScaledBitmap", lanes)
        self.assertNotIn("Bitmap.createScaledBitmap", graphics)
        self.assertIn("parcelable bitmaps are rejected", graphics)
        self.assertIn("GATE-032", sharpness_audit)
        self.assertIn("overlayPlacement.updateRoute(routeEpoch, drivingRoute)", renderer)
        self.assertIn("frame.routeSegmentPosition", renderer)
        self.assertIn("laneGuidanceMapLayer.relayout()", renderer)
        self.assertIn("trafficLightMapLayer.relayout()", renderer)
        self.assertIn("cameraDirectionMapLayer.relayout()", renderer)
        self.assertLess(renderer.index("cameraDirectionMapLayer.relayout()"),
                        renderer.index("laneGuidanceMapLayer.relayout()"))
        self.assertIn("ImageProvider", cursor)
        self.assertIn("setGeometry", cursor)
        self.assertIn("setDirection", cursor)
        self.assertIn("int size = Math.max(8, Math.round(baseSize * requestedScale))",
                      cursor)
        self.assertIn("Float.valueOf(1f)", cursor)
        self.assertNotIn("Float.valueOf(scalePercent / 100f)", cursor)
        self.assertIn("createDestinationBitmap() already renders", renderer)
        self.assertIn("profile.destinationScalePercent / 100f", renderer)
        self.assertIn("destinationImageProvider = null", renderer)
        self.assertIn("destinationIconBitmap = null", renderer)
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
        self.assertIn("MapObjectLayerFactory.IGNORE", traffic_lights)
        self.assertIn("copyRenderableLights", traffic_lights)
        self.assertIn("target.add(candidate)", traffic_lights)
        self.assertNotIn("target.size() >= MAX_LIGHTS", traffic_lights)
        self.assertNotIn("MIN_SEPARATION_METERS", traffic_lights)
        self.assertNotIn("selectSeparatedLights", traffic_lights)
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
        self.assertIn('"activity_container_controller"', controller)
        self.assertIn("activityControllerRoot", controller)
        self.assertIn('"navi_guidance_controls_touch_container"', controller)
        self.assertIn("guidanceInsetHost", controller)
        self.assertIn("nextGuidanceControls != guidanceControls", controller)
        self.assertIn("neutralizePaddingtonTree(activityControllerRoot)", controller)
        self.assertIn("neutralizePaddingtonTree(exactGuidanceInsetRoot)", controller)
        self.assertIn("paddingtonBaseTopByChild.put(guidanceControls, 0)", controller)
        self.assertIn("activeGuidanceVisualRoot", controller)
        self.assertIn('"contextmaneuverview"', controller)
        self.assertIn('"speed_group"', controller)
        self.assertIn("removeFloatingTopInset(activityControllerRoot)", controller)
        self.assertIn("removeFloatingTopInset(guidanceVisualRoot)", controller)
        self.assertIn("STOCK_GUIDANCE_TOP_MARGIN_DP = 12", controller)
        self.assertIn("normalizeGuidanceTopGeometry()", controller)
        self.assertIn("mapViewport.getLocationInWindow(mapLocation)", controller)
        self.assertIn("rawParams.height == ViewGroup.LayoutParams.MATCH_PARENT", controller)
        self.assertIn("int targetMargin = params.topMargin - excessTop", controller)
        self.assertIn("sameUnappliedCorrection", controller)
        self.assertIn("restoreGuidanceTopMargins()", controller)
        self.assertNotIn("root.setTranslationY", controller)
        self.assertIn('"top_notification_container"', controller)
        self.assertIn("paddingtonBaseTop", controller)
        self.assertIn("floatingTopInsetGuard", controller)
        self.assertIn("floatingTopInsetPreDrawGuard", controller)
        self.assertIn("installFloatingTopInsetPreDrawGuard()", controller)
        self.assertIn("bestLiveViewById", controller)
        self.assertIn("ensureModeButtonAttachedToStockRail", controller)
        self.assertIn("findStockModeButtonRail", controller)
        self.assertIn("STOCK_RECT_CONTROL_CLASS", controller)
        self.assertIn("MapControlsFrameLayoutRect", controller)
        self.assertIn('navigatorDimension("control_rect_size", 48)', controller)
        self.assertIn('navigatorDimension("control_rect_padding", 4)', controller)
        self.assertNotIn("lockedModeButtonTopPx", controller)
        self.assertNotIn("resolvedModeButtonTop", controller)
        self.assertNotIn("MODE_BUTTON_AUTO_HIDE_MS", controller)
        self.assertIn("MODE_BUTTON_REBIND_MS = 5_000L", controller)
        self.assertIn("modeButtonStatePreDrawObserver", controller)
        self.assertIn("installModeButtonStatePreDrawObserver()", controller)
        self.assertIn("removeModeButtonStatePreDrawObserver()", controller)
        self.assertIn("activeModeButtonRail = null", controller)
        self.assertIn('reportCallbackFailure("modeButtonStatePreDraw"', controller)
        self.assertIn("syncModeButtonWithStockRail()", controller)
        self.assertIn("visibilityWithinRail", controller)
        self.assertIn("combinedControlVisibility", controller)
        self.assertIn("rail.roadEventControl", controller)
        self.assertIn("rail.voiceControl", controller)
        self.assertIn("button.isPressed()", controller)
        self.assertIn("button.setAlpha(source.getAlpha())", controller)
        self.assertIn("button.setScaleX(source.getScaleX())", controller)
        self.assertIn("button.setScaleY(source.getScaleY())", controller)
        self.assertIn("button.setTranslationX(source.getTranslationX())", controller)
        self.assertIn("button.setTranslationY(source.getTranslationY())", controller)
        self.assertIn('"navi_service_add_road_event"', controller)
        self.assertIn('"navi_service_open_voice_search"', controller)
        self.assertNotIn("leftControlColumnNextTop", controller)
        self.assertNotIn('"alice_fab_container"', controller)
        self.assertNotIn('"guidance_search_map_control_ghost"', controller)
        self.assertIn("layer.addView(button", controller)
        self.assertIn("rail.container.addView(button, targetIndex, params)", controller)
        self.assertIn("int targetIndex = rail.voiceIndex + 1", controller)
        self.assertIn("button.setVisibility(View.GONE)", controller)
        self.assertIn("root.getParent() instanceof LinearLayout", controller)
        self.assertIn("candidate.isAttachedToWindow()", controller)
        self.assertIn("candidate.getOrientation() == LinearLayout.VERTICAL", controller)
        self.assertIn("roadEventControl != null", controller)
        self.assertIn("hasAncestorId(candidate, ownerId, alternateOwnerId)", controller)
        self.assertNotIn("upper.getLocationOnScreen", controller)
        self.assertNotIn("lower.getLocationOnScreen", controller)
        self.assertNotIn("layer.getLocationOnScreen", controller)
        self.assertNotIn("MODE_BUTTON_FALLBACK_TOP_FRACTION", controller)
        self.assertNotIn("profile.modeButtonOpacityPercent / 100f", controller)
        self.assertIn("if (controlLayer == null) install()", controller)
        self.assertIn("neutralizePaddingtonTree", controller)
        self.assertIn("floatingPaddingtonInsetsListener", controller)
        self.assertIn("child.setOnApplyWindowInsetsListener", controller)
        self.assertIn('reportCallbackFailure("modeAwareInsets"', controller)
        self.assertIn('reportCallbackFailure("paddingtonInsets"', controller)
        self.assertIn('reportCallbackFailure("floatingTopInsetGuard"', controller)
        self.assertIn('reportCallbackFailure("floatingTopInsetPreDraw"', controller)
        self.assertIn('reportCallbackFailure("createStockModeButton"', controller)
        self.assertIn('reportCallbackFailure("attachStockModeButton"', controller)
        self.assertIn('reportCallbackFailure("parkModeButton"', controller)
        self.assertNotIn('reportCallbackFailure("controlLayerModeButtonReattach"', controller)
        self.assertIn('reportCallbackFailure("modeButtonClick"', controller)
        self.assertIn('reportCallbackFailure("floatingSurfaceCommitter"', controller)
        self.assertIn('reportCallbackFailure("mapTouchReattach"', controller)
        self.assertIn('reportCallbackFailure("modeButtonPoller"', controller)
        attach_start = controller.index("private boolean ensureModeButtonAttachedToStockRail()")
        attach_end = controller.index(
            "private void installModeButtonStatePreDrawObserver()", attach_start)
        attach_method = controller[attach_start:attach_end]
        self.assertNotIn("button.setVisibility(View.VISIBLE)", attach_method)
        self.assertIn("activeModeButtonRail = rail", attach_method)
        self.assertIn("syncModeButtonWithStockRail()", attach_method)
        self.assertNotIn("@Override public boolean dispatchTouchEvent", controller)
        self.assertIn("MotionEvent.ACTION_POINTER_DOWN", controller)
        self.assertIn("mapTouchSlopSquared", controller)
        self.assertNotIn(
            "event.getActionMasked() != MotionEvent.ACTION_DOWN", entry)
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
