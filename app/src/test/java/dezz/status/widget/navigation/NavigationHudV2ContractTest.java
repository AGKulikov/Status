/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class NavigationHudV2ContractTest {
    @Test public void mainAndHudProfilesAreIndependentAndRoundTrip() throws Exception {
        NavigationIntegrationConfig config = new NavigationIntegrationConfig();
        config.mainMap.zoomDelta = 2.5d;
        config.mainMap.routeColor = "#FF112233";
        config.hudMap.zoomDelta = -1.25d;
        config.hudMap.routeColor = "#FFAABBCC";
        config.hudMap.enabled = true;
        config.mainFloatingWindow.movementLocked = true;
        config.mainFloatingWindow.cornerRadiusDp = 38;
        config.mainFloatingWindow.modeButtonVisible = true;
        config.mainFloatingWindow.modeButtonPosition = "BOTTOM_LEFT";
        config.mainFloatingWindow.modeButtonSizeDp = 52;

        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                config.toJson().toString());

        assertNotSame(restored.mainMap, restored.hudMap);
        assertEquals(2.5d, restored.mainMap.zoomDelta, 0d);
        assertEquals(-1.25d, restored.hudMap.zoomDelta, 0d);
        assertEquals("#FF112233", restored.mainMap.routeColor);
        assertEquals("#FFAABBCC", restored.hudMap.routeColor);
        assertTrue(restored.hudMap.enabled);
        assertTrue(restored.mainFloatingWindow.movementLocked);
        assertEquals(38, restored.mainFloatingWindow.cornerRadiusDp);
        assertTrue(restored.mainFloatingWindow.modeButtonVisible);
        assertEquals("BOTTOM_LEFT", restored.mainFloatingWindow.modeButtonPosition);
        assertEquals(52, restored.mainFloatingWindow.modeButtonSizeDp);
    }

    @Test public void bridgeRequiresDirectSurfaceAndSnapshotCapabilities() {
        long required = NavigationBridgeContract.CAP_NAVIGATION_SNAPSHOT
                | NavigationBridgeContract.CAP_HUD_OFFSCREEN_MAP
                | NavigationBridgeContract.CAP_HUD_DIRECT_SURFACE;
        assertEquals(2, NavigationBridgeContract.PROTOCOL_VERSION);
        assertTrue((required & NavigationBridgeContract.CAP_NAVIGATION_SNAPSHOT) != 0L);
        assertTrue((required & NavigationBridgeContract.CAP_HUD_OFFSCREEN_MAP) != 0L);
        assertTrue((required & NavigationBridgeContract.CAP_HUD_DIRECT_SURFACE) != 0L);
    }

    @Test public void navigatorBindsToNatroAndPreservesExistingWindowCommands() {
        assertEquals("ru.natro.statuswidget", NavigationBridgeContract.NATRO_PACKAGE);
        assertTrue(NavigationBridgeContract.NATRO_ENDPOINT_SERVICE_CLASS
                .endsWith("NavigationHudEndpointService"));
        assertEquals("navi_win/ru.yandex.yandexnavi",
                NavigationBridgeContract.LEGACY_FLOATING_ACTION);
        assertEquals("ddnavwin", NavigationBridgeContract.EXTRA_WINDOWED);
        assertEquals("ddnavforcewinfull",
                NavigationBridgeContract.EXTRA_FORCE_FULLSCREEN);
        assertTrue(NavigationBridgeContract.MSG_SET_MAIN_WINDOW_MODE
                != NavigationBridgeContract.MSG_APPLY_CONFIGURATION);
        assertEquals(2, NavigationBridgeContract.WINDOW_MODE_TOGGLE);
    }

    @Test public void exportedNatroEndpointAuthenticatesEveryMessengerTransaction()
            throws Exception {
        Path project = projectRoot();
        String manifest = read(project.resolve("app/src/main/AndroidManifest.xml"));
        String service = read(sourceRoot().resolve(
                "navigation/NavigationHudEndpointService.java"));
        String verifier = read(sourceRoot().resolve(
                "navigation/NavigationBridgeCallerVerifier.java"));

        assertTrue(manifest.contains(".navigation.NavigationHudEndpointService"));
        assertTrue(manifest.contains("android:exported=\"true\""));
        assertTrue(service.contains("message.sendingUid"));
        assertTrue(service.contains("isTrustedNavigator(this, sendingUid)"));
        assertTrue(service.contains("NATRO_BIND_ACTION.equals(intent.getAction())"));
        assertTrue(verifier.contains("getPackagesForUid(sendingUid)"));
        assertTrue(verifier.contains("checkSignatures("));
        assertFalse(service.contains(
                "| NavigationBridgeContract.CAP_NATRO_HUD_SURFACE_PROVIDER"));
    }

    @Test public void navigatorPatchHasButtonAndConsumesExistingNatroWindowContract()
            throws Exception {
        Path patchRoot = projectRoot().resolve(
                "navigator-mod/src/main/java/ru/natro/navigation");
        String controller = read(patchRoot.resolve("FloatingWindowController.java"));
        String entry = read(patchRoot.resolve("NatroEntryPoint.java"));
        String client = read(patchRoot.resolve("NavigationBridgeClient.java"));

        assertTrue(controller.contains("ACTION_FLOATING = \"navi_win/ru.yandex.yandexnavi\""));
        assertTrue(controller.contains("EXTRA_WINDOWED = \"ddnavwin\""));
        assertTrue(controller.contains("EXTRA_FORCE_FULLSCREEN = \"ddnavforcewinfull\""));
        assertTrue(controller.contains("modeButton.setOnClickListener"));
        assertTrue(controller.contains("Развернуть Навигатор на весь экран"));
        assertTrue(entry.contains("NavigationBridgeClient.ensureStarted"));
        assertTrue(client.contains("getPackagesForUid(sendingUid)"));
        assertTrue(client.contains("checkSignatures(NAVIGATOR_PACKAGE, NATRO_PACKAGE)"));
        assertFalse(controller.contains("ImageReader"));
        assertFalse(controller.contains("MediaProjection"));
        assertFalse(controller.contains("Bitmap"));
    }

    @Test public void fastSnapshotAndRouteGeometryUseSeparateVersionedPayloads()
            throws Exception {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                17L, 4L, 123_456L, true, 55.751d, 37.617d, 361d, 42d,
                "TURN_RIGHT", "Направо", "через 200 м", "Тверская", "Дом",
                200, 12_300, 1_020, 234_567L, 60, "[{\"active\":true}]", "[]");
        NavigationSnapshotV2 restoredSnapshot = NavigationSnapshotV2.fromJson(
                snapshot.toJson().toString());
        NavigationRouteGeometryV2 route = new NavigationRouteGeometryV2(
                4L, "encoded-route", "[{\"level\":2}]");
        NavigationRouteGeometryV2 restoredRoute = NavigationRouteGeometryV2.fromJson(
                route.toJson().toString());

        assertEquals(17L, restoredSnapshot.sequence);
        assertEquals(4L, restoredSnapshot.routeEpoch);
        assertEquals(1d, restoredSnapshot.bearingDegrees, 0d);
        assertEquals("TURN_RIGHT", restoredSnapshot.maneuverType);
        assertEquals(4L, restoredRoute.routeEpoch);
        assertEquals("encoded-route", restoredRoute.encodedPolyline);
        assertTrue(NavigationBridgeContract.MSG_ROUTE_GEOMETRY
                != NavigationBridgeContract.MSG_NAVIGATION_SNAPSHOT);
    }

    @Test public void abandonedFrameCopyBridgeCannotReturn() throws Exception {
        Path root = sourceRoot();
        String runtime = read(root.resolve("hud/HudRuntimeData.java"));
        String canvas = read(root.resolve("hud/HudCanvasView.java"));
        String elementTypes = read(root.resolve("hud/HudElementType.java"));

        assertFalse(Files.exists(root.resolve("hud/NavigatorMapFrameProvider.java")));
        assertFalse(runtime.contains("NavigatorMapFrameProvider"));
        assertFalse(canvas.contains("navigatorMapFrame"));
        assertFalse(elementTypes.contains("NAV_MAP("));
        assertFalse(runtime.contains("ImageReader"));
        assertFalse(runtime.contains("NavigatorHudBridgeService"));
    }

    @Test public void existingIndependentNavigationElementsRemainAvailable() throws Exception {
        String elementTypes = read(sourceRoot().resolve("hud/HudElementType.java"));
        assertTrue(elementTypes.contains("NAV_MANEUVER_ARROW"));
        assertTrue(elementTypes.contains("NAV_TURN_DISTANCE"));
        assertTrue(elementTypes.contains("NAV_LANES"));
        assertTrue(elementTypes.contains("NAV_SPEED_LIMIT"));
        assertTrue(elementTypes.contains("NAV_TRAFFIC_LIGHTS"));
    }

    private static Path sourceRoot() {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        return Files.isDirectory(root) ? root
                : Paths.get("src", "main", "java", "dezz", "status", "widget");
    }

    private static Path projectRoot() {
        Path root = Paths.get("app", "src", "main", "AndroidManifest.xml");
        return Files.isRegularFile(root) ? Paths.get("") : Paths.get("..");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
