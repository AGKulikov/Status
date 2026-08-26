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
        config.mainMap.trafficHardColor = "#FF334455";
        config.hudMap.zoomDelta = -1.25d;
        config.hudMap.routeColor = "#FFAABBCC";
        config.hudMap.trafficBlockedColor = "#FF550011";
        config.hudMap.trafficGradientLength = 24d;
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
        assertEquals("#FF334455", restored.mainMap.trafficHardColor);
        assertEquals("#FF550011", restored.hudMap.trafficBlockedColor);
        assertEquals(24d, restored.hudMap.trafficGradientLength, 0d);
        assertTrue(restored.hudMap.enabled);
        assertTrue(restored.mainFloatingWindow.movementLocked);
        assertEquals(38, restored.mainFloatingWindow.cornerRadiusDp);
        assertTrue(restored.mainFloatingWindow.modeButtonVisible);
        assertEquals("BOTTOM_LEFT", restored.mainFloatingWindow.modeButtonPosition);
        assertEquals(52, restored.mainFloatingWindow.modeButtonSizeDp);
    }

    @Test public void nonFiniteMapValuesRestoreFieldDefaults() {
        NavigationIntegrationConfig config = new NavigationIntegrationConfig();
        config.hudMap.zoomDelta = Double.NaN;
        config.hudMap.routeWidth = Double.POSITIVE_INFINITY;
        config.hudMap.routeOutlineWidth = Double.NEGATIVE_INFINITY;
        config.hudMap.trafficGradientLength = Double.NaN;

        config.normalize();

        assertEquals(0d, config.hudMap.zoomDelta, 0d);
        assertEquals(8d, config.hudMap.routeWidth, 0d);
        assertEquals(2d, config.hudMap.routeOutlineWidth, 0d);
        assertEquals(12d, config.hudMap.trafficGradientLength, 0d);
    }

    @Test public void bridgeRequiresDirectSurfaceAndSnapshotCapabilities() {
        long required = NavigationBridgeContract.CAP_NAVIGATION_SNAPSHOT
                | NavigationBridgeContract.CAP_HUD_INDEPENDENT_MAP_WINDOW
                | NavigationBridgeContract.CAP_HUD_DIRECT_SURFACE;
        assertEquals(2, NavigationBridgeContract.PROTOCOL_VERSION);
        assertTrue((required & NavigationBridgeContract.CAP_NAVIGATION_SNAPSHOT) != 0L);
        assertTrue((required & NavigationBridgeContract.CAP_HUD_INDEPENDENT_MAP_WINDOW) != 0L);
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

    @Test public void natroTargetsTheSameNewMapActivityForWindowAndFullscreen()
            throws Exception {
        String launcher = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/launcher/YandexWindowLauncher.java"));
        String compact = launcher.replaceAll("\\s+", "");
        String exactTarget = "newTarget(NAVIGATOR_PACKAGE,"
                + "\"ru.yandex.yandexmaps.app.MapActivity\")";
        assertTrue(compact.indexOf(exactTarget) >= 0);
        assertTrue(compact.indexOf(exactTarget) != compact.lastIndexOf(exactTarget));
        assertTrue(compact.indexOf(exactTarget)
                < compact.indexOf("newTarget(NAVIGATOR_PACKAGE,"
                + "\"ru.yandex.yandexmaps.app.TransparentSplashActivity\")"));
        assertTrue(launcher.contains("putExtra(\"ddnavwin\", true)"));
        assertTrue(launcher.contains("putExtra(\"ddnavforcewinfull\", true)"));
    }

    @Test public void exportedNatroEndpointAuthenticatesEveryMessengerTransaction()
            throws Exception {
        Path project = projectRoot();
        String manifest = read(project.resolve("app/src/main/AndroidManifest.xml"));
        String service = read(sourceRoot().resolve(
                "navigation/NavigationHudEndpointService.java"));
        String relay = read(sourceRoot().resolve(
                "navigation/NavigationConfigurationRelayService.java"));
        String verifier = read(sourceRoot().resolve(
                "navigation/NavigationBridgeCallerVerifier.java"));
        String compactManifest = manifest.replaceAll("\\s+", "");

        assertTrue(manifest.contains(".navigation.NavigationHudEndpointService"));
        assertTrue(manifest.contains("android:exported=\"true\""));
        assertTrue(service.contains("message.sendingUid"));
        assertTrue(service.contains("isTrustedNavigator(this, sendingUid)"));
        assertTrue(service.contains("NATRO_BIND_ACTION.equals(intent.getAction())"));
        assertTrue(verifier.contains("getPackagesForUid(sendingUid)"));
        assertTrue(verifier.contains("checkSignatures("));
        assertTrue(service.contains(
                "| NavigationBridgeContract.CAP_NATRO_HUD_SURFACE_PROVIDER"));
        assertTrue(service.contains("data.putParcelable("));
        assertTrue(service.contains("supportsDirectHudMap(current)"));
        assertFalse(service.contains("getStringExtra("));
        assertTrue(compactManifest.contains("android:name=\".navigation."
                + "NavigationConfigurationRelayService\"android:directBootAware=\"true\""
                + "android:enabled=\"true\"android:exported=\"false\""
                + "android:process=\":hud\""));
        assertTrue(relay.contains("getStringExtra(EXTRA_CONFIGURATION_JSON)"));
        assertTrue(relay.contains("acceptRelayedConfiguration(raw)"));
    }

    @Test public void navigatorPatchHasButtonAndConsumesExistingNatroWindowContract()
            throws Exception {
        Path patchRoot = projectRoot().resolve(
                "navigator-mod/src/main/java/ru/natro/navigation");
        String controller = read(patchRoot.resolve("FloatingWindowController.java"));
        String entry = read(patchRoot.resolve("NatroEntryPoint.java"));
        String client = read(patchRoot.resolve("NavigationBridgeClient.java"));
        String renderer = read(patchRoot.resolve("HudMapRenderer.java"));
        String publisher = read(patchRoot.resolve("NavigatorStatePublisher.java"));
        String mainMap = read(patchRoot.resolve("MainMapController.java"));
        String cursor = read(patchRoot.resolve("MapCursorStyler.java"));
        String routeStyler = read(patchRoot.resolve("RoutePolylineStyler.java"));

        assertTrue(controller.contains("ACTION_FLOATING = \"navi_win/ru.yandex.yandexnavi\""));
        assertTrue(controller.contains("EXTRA_WINDOWED = \"ddnavwin\""));
        assertTrue(controller.contains("EXTRA_FORCE_FULLSCREEN = \"ddnavforcewinfull\""));
        assertTrue(controller.contains("modeButton.setOnClickListener"));
        assertTrue(controller.contains("Развернуть Навигатор на весь экран"));
        assertTrue(controller.contains("profile.enabled && profile.modeButtonVisible"));
        assertTrue(controller.contains("mode != MODE_FULLSCREEN"));
        assertTrue(entry.contains("NavigationBridgeClient.attachActivity"));
        assertTrue(entry.contains("NavigationBridgeClient.detachActivity"));
        assertTrue(client.contains("getPackagesForUid(sendingUid)"));
        assertTrue(client.contains("checkSignatures(NAVIGATOR_PACKAGE, NATRO_PACKAGE)"));
        assertTrue(client.contains("MSG_ATTACH_HUD_SURFACE"));
        assertTrue(client.contains("MSG_REQUEST_SNAPSHOT"));
        assertTrue(client.contains("MSG_REQUEST_ROUTE_GEOMETRY"));
        assertTrue(client.contains("CAP_NAVIGATION_SNAPSHOT"));
        assertTrue(renderer.contains("createOffscreenMapWindow"));
        assertTrue(renderer.contains("com.yandex.runtime.view.SurfaceFactory"));
        assertTrue(renderer.contains("addSurface"));
        assertTrue(renderer.contains("removeSurface"));
        assertTrue(renderer.contains("createTrafficLayer"));
        assertTrue(renderer.contains("createUserLocationLayer"));
        assertTrue(renderer.contains("applyStyleSlot"));
        assertTrue(renderer.contains("visibilityStyleJson"));
        assertTrue(renderer.contains("updatePrimaryCamera"));
        assertTrue(renderer.contains("getGeometry"));
        assertTrue(renderer.contains("addPolyline"));
        assertTrue(routeStyler.contains("getJamSegments"));
        assertTrue(routeStyler.contains("setStrokeColors"));
        assertTrue(routeStyler.contains("setPaletteColor"));
        assertTrue(cursor.contains("UserLocationObjectListener"));
        assertTrue(cursor.contains("ViewProvider"));
        assertTrue(cursor.contains("setView"));
        assertTrue(mainMap.contains("getDrivingNavigationBaseLayerId"));
        assertTrue(mainMap.contains("getDrivingNavigationUserPlacemarkLayerId"));
        assertTrue(mainMap.contains("Independent main MapProfile applied"));
        assertFalse(mainMap.contains("getJamsLayerId"));
        assertTrue(publisher.contains("MapActivity.x()"));
        assertTrue(publisher.contains("getMapWindow"));
        assertTrue(publisher.contains("getGuidance"));
        assertTrue(publisher.contains("distanceToFinish"));
        assertTrue(publisher.contains("timeToFinish"));
        assertTrue(publisher.contains("getManoeuvres"));
        assertTrue(publisher.contains("getLaneSigns"));
        assertTrue(publisher.contains("getTrafficLightsWithSignal"));
        assertTrue(publisher.contains("ConditionsListener"));
        assertTrue(publisher.contains("trafficSegmentsJson"));
        assertTrue(publisher.contains("addCameraListener"));
        assertTrue(publisher.contains("getValue\"), 0d) * 3.6d"));
        assertFalse(publisher.contains("getDeclaredField"));
        assertFalse(renderer.contains("captureScreenshot("));
        assertFalse(renderer.contains("PlatformGLSurface"));
        assertFalse(cursor.contains("Bitmap"));
        assertFalse(controller.contains("ImageReader"));
        assertFalse(controller.contains("MediaProjection"));
        assertFalse(controller.contains("Bitmap"));
    }

    @Test public void endpointRequestsFreshStateAfterAuthenticatedHello() throws Exception {
        String endpoint = read(sourceRoot().resolve(
                "navigation/NavigationHudEndpointService.java"));
        assertTrue(endpoint.contains("requestNavigationState(client)"));
        assertTrue(endpoint.contains("MSG_REQUEST_SNAPSHOT"));
        assertTrue(endpoint.contains("MSG_REQUEST_ROUTE_GEOMETRY"));
        assertTrue(endpoint.contains("current.capabilities"
                + " & NavigationBridgeContract.CAP_NAVIGATION_SNAPSHOT"));
    }

    @Test public void fastSnapshotAndRouteGeometryUseSeparateVersionedPayloads()
            throws Exception {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                17L, 4L, 123_456L, true, 55.751d, 37.617d, 361d, 42d,
                "TURN_RIGHT", "Направо", "через 200 м", "Тверская", "Дом",
                200, 20_000, 12_300, 1_020, 234_567L, 60, 350,
                "[{\"active\":true}]", "[]");
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
        assertEquals(20_000, restoredSnapshot.routeTotalDistanceMeters);
        assertEquals(350, restoredSnapshot.laneDistanceMeters);
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
        String composite = read(root.resolve("hud/HudCompositeView.java"));

        assertFalse(Files.exists(root.resolve("hud/NavigatorMapFrameProvider.java")));
        assertFalse(runtime.contains("NavigatorMapFrameProvider"));
        assertFalse(canvas.contains("navigatorMapFrame"));
        assertTrue(elementTypes.contains("NAV_MAP("));
        assertTrue(composite.contains("TextureView"));
        assertTrue(composite.contains("publishHudSurface"));
        assertFalse(composite.contains("Bitmap"));
        assertFalse(composite.contains("ImageReader"));
        assertFalse(composite.contains("MediaProjection"));
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

    @Test public void independentHudElementsConsumeDirectBridgeState() throws Exception {
        Path root = sourceRoot();
        String runtime = read(root.resolve("hud/HudRuntimeData.java"));
        String canvas = read(root.resolve("hud/HudCanvasView.java"));
        String state = read(root.resolve("hud/HudNavigationState.java"));
        String publisher = read(navigatorModRoot().resolve("NavigatorStatePublisher.java"));

        assertTrue(runtime.contains("NavigationBridgeStateStore.addListener"));
        assertTrue(runtime.contains("HudNavigationState.fromBridge"));
        assertFalse(canvas.contains("NavigationDataRepository.Snapshot"));
        assertTrue(state.contains("source.routeTotalDistanceMeters"));
        assertTrue(state.contains("parseLanes"));
        assertTrue(state.contains("parseLights"));
        assertTrue(state.contains("parseRuns"));
        assertTrue(publisher.contains("getMetadata"));
        assertTrue(publisher.contains("laneDistanceMeters"));
        assertTrue(publisher.contains(
                "activeRouteTotalDistanceMeters = readRouteTotalDistance"));
        assertTrue(publisher.contains("readDestination(route)"));
        assertTrue(publisher.contains("getRequestPoints"));
        assertTrue(canvas.contains("HudNavigationVisuals.maneuver"));
        assertTrue(canvas.contains("HudNavigationVisuals.lane"));
        assertTrue(canvas.contains("drawTrafficArrow"));
        assertTrue(canvas.contains("isTrafficArrow"));
    }

    @Test public void settingsExposeIndependentMapsAndNavigatorWindowButton() throws Exception {
        String settings = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/HudPanelSettingsActivity.java"));
        assertTrue(settings.contains("Независимая карта HUD"));
        assertTrue(settings.contains("Основная карта и окно Навигатора"));
        assertTrue(settings.contains("Кнопка окно / полный экран"));
        assertTrue(settings.contains("navigation.hudMap"));
        assertTrue(settings.contains("navigation.mainMap"));
        assertTrue(settings.contains("navigation.mainFloatingWindow"));
        assertTrue(settings.contains("Цвет рекомендуемой полосы"));
        assertTrue(settings.contains("Красный сигнал ARGB"));
        assertTrue(settings.contains("Тяжёлая пробка ARGB"));
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

    private static Path navigatorModRoot() {
        return projectRoot().resolve(
                "navigator-mod/src/main/java/ru/natro/navigation");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
