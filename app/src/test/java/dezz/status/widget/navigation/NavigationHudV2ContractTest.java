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
        config.hudMap.trafficFreeColor = "#FF010203";
        config.hudMap.trafficLightColor = "#FF111213";
        config.hudMap.trafficHardColor = "#FF212223";
        config.hudMap.trafficVeryHardColor = "#FF313233";
        config.hudMap.trafficBlockedColor = "#FF414243";
        config.hudMap.trafficUnknownColor = "#FF515253";
        config.hudMap.trafficGradientLength = 24d;
        config.hudMap.roadColor = "#FF556677";
        config.hudMap.routeStreetLabelsOnly = true;
        config.hudMap.showTraffic = false;
        config.hudMap.showRouteTraffic = true;
        config.hudMap.showDestination = false;
        config.hudMap.showTrafficLights = false;
        config.hudMap.showLaneGuidance = false;
        config.hudMap.enabled = true;
        config.hudMap.roadsOnly = true;
        config.hudMap.setRoadEventMode("SPEED_CONTROL",
                NavigationIntegrationConfig.RoadEventMode.ALWAYS);
        config.hudMap.setRoadEventMode("ACCIDENT",
                NavigationIntegrationConfig.RoadEventMode.ROUTE_ONLY);
        config.clusterMap.zoomDelta = .75d;
        config.clusterMap.tiltDegrees = 37;
        config.clusterMap.focusXPercent = 42;
        config.clusterMap.focusYPercent = 79;
        config.clusterMap.mapScalePercent = 135;
        config.clusterMap.cameraMode = "NORTH_UP";
        config.clusterMap.maximumFps = 60;
        config.clusterMap.showModels = false;
        config.clusterMap.showDestination = true;
        config.clusterMap.showTrafficLights = true;
        config.clusterMap.showLaneGuidance = true;
        config.clusterMap.trafficFreeColor = "#FF616263";
        config.clusterMap.trafficLightColor = "#FF717273";
        config.clusterMap.trafficHardColor = "#FF818283";
        config.clusterMap.trafficVeryHardColor = "#FF919293";
        config.clusterMap.trafficBlockedColor = "#FFA1A2A3";
        config.clusterMap.trafficUnknownColor = "#FFB1B2B3";
        config.clusterMap.roadColor = "#FF223344";
        config.clusterMap.routeStreetLabelsOnly = true;
        config.clusterMap.setRoadEventMode("RECONSTRUCTION",
                NavigationIntegrationConfig.RoadEventMode.ALWAYS);
        config.mainFloatingWindow.movementLocked = true;
        config.mainFloatingWindow.cornerRadiusDp = 38;
        config.mainFloatingWindow.modeButtonVisible = true;
        config.mainFloatingWindow.modeButtonPosition = "BOTTOM_LEFT";
        config.mainFloatingWindow.modeButtonSizeDp = 52;

        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                config.toJson().toString());

        assertNotSame(restored.mainMap, restored.hudMap);
        assertNotSame(restored.hudMap, restored.clusterMap);
        assertEquals(2.5d, restored.mainMap.zoomDelta, 0d);
        assertEquals(-1.25d, restored.hudMap.zoomDelta, 0d);
        assertEquals("#FF112233", restored.mainMap.routeColor);
        assertEquals("#FFAABBCC", restored.hudMap.routeColor);
        assertEquals("#FF334455", restored.mainMap.trafficHardColor);
        assertEquals("#FF010203", restored.hudMap.trafficFreeColor);
        assertEquals("#FF111213", restored.hudMap.trafficLightColor);
        assertEquals("#FF212223", restored.hudMap.trafficHardColor);
        assertEquals("#FF313233", restored.hudMap.trafficVeryHardColor);
        assertEquals("#FF414243", restored.hudMap.trafficBlockedColor);
        assertEquals("#FF515253", restored.hudMap.trafficUnknownColor);
        assertEquals(24d, restored.hudMap.trafficGradientLength, 0d);
        assertEquals("#FF556677", restored.hudMap.roadColor);
        assertTrue(restored.hudMap.routeStreetLabelsOnly);
        assertFalse(restored.hudMap.showTraffic);
        assertTrue(restored.hudMap.showRouteTraffic);
        assertFalse(restored.hudMap.showDestination);
        assertFalse(restored.hudMap.showTrafficLights);
        assertFalse(restored.hudMap.showLaneGuidance);
        assertTrue(restored.hudMap.enabled);
        assertTrue(restored.hudMap.roadsOnly);
        assertEquals(NavigationIntegrationConfig.RoadEventMode.ALWAYS,
                restored.hudMap.roadEventMode("SPEED_CONTROL"));
        assertEquals(NavigationIntegrationConfig.RoadEventMode.ROUTE_ONLY,
                restored.hudMap.roadEventMode("ACCIDENT"));
        assertEquals(.75d, restored.clusterMap.zoomDelta, 0d);
        assertEquals(37, restored.clusterMap.tiltDegrees);
        assertEquals(42, restored.clusterMap.focusXPercent);
        assertEquals(79, restored.clusterMap.focusYPercent);
        assertEquals(135, restored.clusterMap.mapScalePercent);
        assertEquals("NORTH_UP", restored.clusterMap.cameraMode);
        assertEquals(60, restored.clusterMap.maximumFps);
        assertFalse(restored.clusterMap.showModels);
        assertTrue(restored.clusterMap.showDestination);
        assertTrue(restored.clusterMap.showTrafficLights);
        assertTrue(restored.clusterMap.showLaneGuidance);
        assertEquals("#FF616263", restored.clusterMap.trafficFreeColor);
        assertEquals("#FF717273", restored.clusterMap.trafficLightColor);
        assertEquals("#FF818283", restored.clusterMap.trafficHardColor);
        assertEquals("#FF919293", restored.clusterMap.trafficVeryHardColor);
        assertEquals("#FFA1A2A3", restored.clusterMap.trafficBlockedColor);
        assertEquals("#FFB1B2B3", restored.clusterMap.trafficUnknownColor);
        assertEquals("#FF223344", restored.clusterMap.roadColor);
        assertTrue(restored.clusterMap.routeStreetLabelsOnly);
        assertEquals(NavigationIntegrationConfig.RoadEventMode.ALWAYS,
                restored.clusterMap.roadEventMode("RECONSTRUCTION"));
        assertTrue(restored.mainFloatingWindow.movementLocked);
        assertEquals(38, restored.mainFloatingWindow.cornerRadiusDp);
        assertTrue(restored.mainFloatingWindow.modeButtonVisible);
        assertEquals("BOTTOM_LEFT", restored.mainFloatingWindow.modeButtonPosition);
        assertEquals(52, restored.mainFloatingWindow.modeButtonSizeDp);
    }

    @Test public void hudAndClusterMapEditorsUseVisualControlsForEveryCameraValue()
            throws Exception {
        String hud = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/HudPanelSettingsActivity.java"));
        String cluster = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/InstrumentPanelSettingsActivity.java"));

        for (String source : new String[]{hud, cluster}) {
            assertTrue(source.contains("SliderField zoom = slider("));
            assertTrue(source.contains("SliderField tilt = slider("));
            assertTrue(source.contains("SliderField focusX = slider("));
            assertTrue(source.contains("SliderField focusY = slider("));
            assertTrue(source.contains("SliderField mapScale = slider("));
            assertTrue(source.contains("Быстрые варианты камеры"));
            assertTrue(source.contains("2D сверху"));
            assertTrue(source.contains("3D город"));
            assertTrue(source.contains("3D трасса"));
            assertTrue(source.contains("dayNightSpinner("));
            assertTrue(source.contains("map.automaticDayNight = selection == 0"));
            assertTrue(source.contains("map.nightMode = selection == 2"));
            assertTrue(source.contains("navigationColorField("));
            assertTrue(source.contains("navigationIntegrationConfigJson.commit("));
            assertTrue(source.contains("navigationIntegrationConfigJson.get()"));
            assertTrue(source.contains("encoded.equals(verified)")
                    || source.contains("encodedNavigation.equals("));
            assertTrue(source.contains("persistNavigationConfiguration("));
        }
        assertFalse(cluster.contains("android.widget.EditText"));
        assertTrue(cluster.contains("map.cameraMode = navigationCameraModeValue("));
        assertTrue(cluster.contains("map.zoomDelta = zoom.value()"));
        assertTrue(cluster.contains("map.tiltDegrees = tilt.intValue()"));
        assertTrue(cluster.contains("map.focusXPercent = focusX.intValue()"));
        assertTrue(cluster.contains("map.focusYPercent = focusY.intValue()"));
        assertTrue(cluster.contains("map.mapScalePercent = mapScale.intValue()"));
        assertTrue(cluster.contains("map.cursorScalePercent = cursorScale.intValue()"));
        assertTrue(cluster.contains("map.trafficGradientLength = trafficGradient.value()"));
        assertTrue(cluster.contains("AppleColorPickerDialog.show("));
        String normalizedHud = hud.replace("profile.", "map.")
                .replace("rendererEnabled.isChecked()", "mapEnabled.isChecked()")
                .replace("showRoute.isChecked()", "route.isChecked()")
                .replace("showRouteTraffic.isChecked()", "routeTraffic.isChecked()")
                .replace("showTraffic.isChecked()", "traffic.isChecked()")
                .replace("showTrafficLights.isChecked()", "trafficLights.isChecked()")
                .replace("showLaneGuidance.isChecked()", "laneGuidance.isChecked()")
                .replace("showLabels.isChecked()", "labels.isChecked()")
                .replace("showPois.isChecked()", "pois.isChecked()")
                .replace("showBuildings.isChecked()", "buildings.isChecked()")
                .replace("showParks.isChecked()", "parks.isChecked()")
                .replace("showWater.isChecked()", "water.isChecked()")
                .replace("showModels.isChecked()", "models.isChecked()")
                .replace("showCursor.isChecked()", "cursor.isChecked()");
        for (String assignment : new String[]{
                "map.enabled = mapEnabled.isChecked()",
                "map.showRoute = route.isChecked()",
                "map.showDestination = destination.isChecked()",
                "map.showRouteTraffic = routeTraffic.isChecked()",
                "map.showTraffic = traffic.isChecked()",
                "map.showTrafficLights = trafficLights.isChecked()",
                "map.showLaneGuidance = laneGuidance.isChecked()",
                "map.showLabels = labels.isChecked()",
                "map.routeStreetLabelsOnly = routeStreetLabelsOnly.isChecked()",
                "map.showPois = pois.isChecked()",
                "map.showBuildings = buildings.isChecked()",
                "map.showParks = parks.isChecked()",
                "map.showWater = water.isChecked()",
                "map.showModels = models.isChecked()",
                "map.showCursor = cursor.isChecked()",
                "map.roadsOnly = roadsOnly.isChecked()",
                "map.cursorColor = cursorColor.value",
                "map.cursorOutlineColor = cursorOutline.value",
                "map.routeColor = routeColor.value",
                "map.routeOutlineColor = routeOutline.value",
                "map.roadColor = roadColor.value",
                "map.routeWidth = routeWidth.value()",
                "map.routeOutlineWidth = routeOutlineWidth.value()",
                "map.trafficFreeColor = trafficFreeColor.value",
                "map.trafficLightColor = trafficLightColor.value",
                "map.trafficHardColor = trafficHardColor.value",
                "map.trafficVeryHardColor = trafficVeryHardColor.value",
                "map.trafficBlockedColor = trafficBlockedColor.value",
                "map.trafficUnknownColor = trafficUnknownColor.value",
                "map.maximumFps = maximumFps.intValue()"
        }) {
            assertTrue("Missing cluster visual setting assignment: " + assignment,
                    cluster.contains(assignment));
            assertTrue("Missing HUD visual setting assignment: " + assignment,
                    normalizedHud.contains(assignment));
        }
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
        assertTrue(NavigationBridgeContract.MSG_ATTACH_CLUSTER_SURFACE
                > NavigationBridgeContract.MSG_DIAGNOSTIC);
        assertTrue((NavigationBridgeContract.CAP_CLUSTER_INDEPENDENT_MAP_WINDOW
                & NavigationBridgeContract.CAP_CLUSTER_DIRECT_SURFACE) == 0L);
        assertTrue(NavigationBridgeContract.CAP_NATRO_CLUSTER_SURFACE_PROVIDER != 0L);
        assertTrue(NavigationBridgeContract.CAP_EXTERNAL_INSTRUMENT_LAUNCHER != 0L);
        assertTrue(NavigationBridgeContract.MSG_PREPARE_INSTRUMENT_PANEL_LAUNCH
                > NavigationBridgeContract.MSG_CLUSTER_SURFACE_LOST);
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

    @Test public void exportedNatroEndpointAuthenticatesHelloThenPinsUidAndSession()
            throws Exception {
        Path project = projectRoot();
        String manifest = read(project.resolve("app/src/main/AndroidManifest.xml"));
        String service = read(sourceRoot().resolve(
                "navigation/NavigationHudEndpointService.java"));
        String relay = read(sourceRoot().resolve(
                "navigation/NavigationConfigurationRelayService.java"));
        String provider = read(sourceRoot().resolve(
                "navigation/NavigationConfigurationProvider.java"));
        String verifier = read(sourceRoot().resolve(
                "navigation/NavigationBridgeCallerVerifier.java"));
        String compactManifest = manifest.replaceAll("\\s+", "");

        assertTrue(manifest.contains(".navigation.NavigationHudEndpointService"));
        assertTrue(manifest.contains("android:exported=\"true\""));
        assertTrue(service.contains("message.sendingUid"));
        assertTrue(service.contains("isTrustedNavigator(this, sendingUid)"));
        assertEquals(service.indexOf("isTrustedNavigator(this, sendingUid)"),
                service.lastIndexOf("isTrustedNavigator(this, sendingUid)"));
        assertTrue(service.contains("message.what == NavigationBridgeContract.MSG_HELLO"));
        assertTrue(service.contains("current.uid != sendingUid"));
        assertTrue(service.contains("current.sessionId.equals(sessionFrom(message))"));
        assertTrue(service.contains("NATRO_BIND_ACTION.equals(intent.getAction())"));
        assertTrue(verifier.contains("getPackagesForUid(sendingUid)"));
        assertTrue(verifier.contains("checkSignatures("));
        assertTrue(service.contains(
                "| NavigationBridgeContract.CAP_NATRO_HUD_SURFACE_PROVIDER"));
        assertTrue(service.contains("data.putParcelable("));
        assertTrue(service.contains("supportsDirectHudMap(current)"));
        assertTrue(service.contains("navigation-bridge-parser"));
        assertTrue(service.contains("pendingSnapshot = new PendingPayload"));
        assertTrue(service.contains("pendingRouteGeometry = new PendingPayload"));
        assertTrue(service.contains("worker.post(snapshotDrain)"));
        assertTrue(service.contains("worker.post(routeGeometryDrain)"));
        assertTrue(service.contains("Parse at most the newest waiting snapshot"));
        assertFalse(service.contains("getStringExtra("));
        assertTrue(compactManifest.contains("android:name=\".navigation."
                + "NavigationConfigurationRelayService\"android:directBootAware=\"true\""
                + "android:enabled=\"true\"android:exported=\"false\""));
        assertFalse(compactManifest.contains("android:process=\":hud\""));
        assertTrue(relay.contains("getStringExtra(EXTRA_CONFIGURATION_JSON)"));
        assertTrue(relay.contains("acceptRelayedConfiguration(raw)"));
        assertTrue(compactManifest.contains("android:name=\".navigation."
                + "NavigationConfigurationProvider\"android:authorities=\""
                + "ru.natro.statuswidget.navigation.configuration\""));
        assertTrue(provider.contains("Binder.getCallingUid()"));
        assertTrue(provider.contains("isTrustedNavigator("));
        assertTrue(provider.contains("navigationIntegrationConfigJson.get()"));
    }

    @Test public void navigatorPatchHasButtonAndConsumesExistingNatroWindowContract()
            throws Exception {
        Path patchRoot = projectRoot().resolve(
                "navigator-mod/src/main/java/ru/natro/navigation");
        String controller = read(patchRoot.resolve("FloatingWindowController.java"));
        String windowProfile = read(patchRoot.resolve("FloatingWindowProfile.java"));
        String entry = read(patchRoot.resolve("NatroEntryPoint.java"));
        String client = read(patchRoot.resolve("NavigationBridgeClient.java"));
        String renderer = read(patchRoot.resolve("HudMapRenderer.java"));
        String publisher = read(patchRoot.resolve("NavigatorStatePublisher.java"));
        String mainMap = read(patchRoot.resolve("MainMapController.java"));
        String cursor = read(patchRoot.resolve("MapCursorStyler.java"));
        String routeStyler = read(patchRoot.resolve("RoutePolylineStyler.java"));
        String mapProfile = read(patchRoot.resolve("NavigationMapProfile.java"));
        String trafficLights = read(patchRoot.resolve("TrafficLightMapLayer.java"));
        String cameraDirections = read(patchRoot.resolve("CameraDirectionMapLayer.java"));
        String laneGuidance = read(patchRoot.resolve("LaneGuidanceMapLayer.java"));
        String backgroundLease = read(patchRoot.resolve("BackgroundMapLease.java"));
        String mapViewPatch = read(projectRoot().resolve(
                "tools/patch_navigation_map_view.py"));

        assertTrue(controller.contains("ACTION_FLOATING = \"navi_win/ru.yandex.yandexnavi\""));
        assertTrue(controller.contains("EXTRA_WINDOWED = \"ddnavwin\""));
        assertTrue(controller.contains("EXTRA_FORCE_FULLSCREEN = \"ddnavforcewinfull\""));
        assertTrue(controller.contains("modeButton.setOnClickListener"));
        assertTrue(controller.contains("Развернуть Навигатор на весь экран"));
        assertTrue(controller.contains("profile.enabled && profile.modeButtonVisible"));
        assertTrue(controller.contains("mode != MODE_FULLSCREEN"));
        assertTrue(controller.contains("attributes.type = floatingWindowType()"));
        assertTrue(controller.contains("attributes.format = PixelFormat.TRANSLUCENT"));
        assertTrue(controller.contains("FLAG_LAYOUT_NO_LIMITS"));
        assertTrue(controller.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"));
        assertTrue(controller.contains("WindowManager.LayoutParams.TYPE_SYSTEM_ALERT"));
        assertTrue(controller.contains("FLAG_WATCH_OUTSIDE_TOUCH"));
        assertTrue(controller.contains("FLAG_FORCE_NOT_FULLSCREEN"));
        assertTrue(controller.contains("FLAG_FULLSCREEN"));
        assertTrue(controller.contains("enforceFloatingWindowContract()"));
        assertTrue(controller.contains("View.SYSTEM_UI_FLAG_VISIBLE"));
        assertTrue(controller.contains("natro_floating_window_v3"));
        assertTrue(controller.contains("new ColorDrawable(Color.TRANSPARENT)"));
        assertTrue(controller.contains("activity_search_map_view"));
        assertTrue(controller.contains("map_activity_root"));
        assertTrue(controller.contains("android.R.id.content"));
        assertFalse(controller.contains("convertToTranslucent"));
        assertTrue(controller.contains("window.setLayout(attributes.width, attributes.height)"));
        assertTrue(controller.contains("attributes.dimAmount = 0f"));
        assertTrue(controller.contains("floating surface committed decor="));
        assertTrue(controller.contains("decor.setClipToOutline(false)"));
        assertTrue(controller.contains("decor.setClipToOutline(true)"));
        assertTrue(controller.contains("roundedOutlineProvider"));
        assertTrue(controller.contains("NatroEntryPoint.usesMovableMap(activity)"));
        assertTrue(client.contains("readHostedConfiguration(Context source)"));
        assertTrue(client.contains("getContentResolver().call("));
        assertTrue(entry.contains("applyHostedConfiguration(activity, controller)"));
        assertTrue(entry.contains("public static boolean onNewIntent"));
        assertTrue(entry.contains("return isPureWindowCommand(intent)"));
        assertFalse(controller.contains("decor.setClipToOutline(profile.cornerRadiusDp > 0)"));
        assertFalse(controller.contains("View.SYSTEM_UI_FLAG_HIDE_NAVIGATION"));
        assertFalse(controller.contains("containsReadySurface"));
        assertFalse(controller.contains("view instanceof SurfaceView"));
        assertFalse(controller.contains("prepareWindowBeforeContent"));
        assertTrue(controller.contains("floatingIdentityRejected"));
        assertTrue(controller.contains("rawConfiguration.equals(appliedConfigurationRaw)"));
        assertTrue(controller.contains("profile.sameWindowContract(next)"));
        assertTrue(controller.contains("if (!windowContractChanged)"));
        assertTrue(windowProfile.contains("sameWindowContract(FloatingWindowProfile other)"));
        assertTrue(windowProfile.contains("backgroundColor = \"#00000000\""));
        assertTrue(controller.contains("navi_service_open_voice_search"));
        assertTrue(controller.contains("guidance_open_voice_search"));
        assertTrue(controller.contains("navi_service_add_road_event"));
        assertTrue(controller.contains("guidance_add_road_event"));
        assertTrue(controller.contains("TextView active = floating ? floatingModeButton"));
        assertTrue(controller.contains("host.addView(active, insertion"));
        assertFalse(controller.contains("controlLayer.addView(floatingModeButton)"));
        assertTrue(controller.contains("MODE_BUTTON_STABLE_MS = 5_000L"));
        assertTrue(controller.contains("stableNavigatorHost"));
        assertTrue(controller.contains("replaceSystemWindowInsets("));
        assertTrue(controller.contains("insets.getSystemWindowInsetLeft(),"));
        assertTrue(controller.contains("requestNavigatorInsets()"));
        assertTrue(controller.contains("restartInMode("));
        assertTrue(controller.contains("activity.finish()"));
        assertTrue(controller.contains("activity.startActivity(restart)"));
        assertFalse(entry.contains("onActivityPreCreate"));
        assertTrue(controller.contains("end of onResumeFragments"));
        assertTrue(entry.contains("NavigationBridgeClient.attachActivity"));
        assertTrue(entry.contains("NavigationBridgeClient.detachActivity"));
        assertTrue(entry.contains("NavigationBridgeClient.onActivityStarting"));
        assertTrue(entry.contains("NavigationBridgeClient.onActivityStopped"));
        assertFalse(entry.contains("activity.finish()"));
        assertFalse(entry.contains("activity.startActivity(restart)"));
        assertTrue(entry.contains("controller.consumeIntent(intent)"));
        assertTrue(entry.contains("shouldUseMovableMap(Context context)"));
        assertTrue(entry.contains("MOVABLE_MAP_ACTIVITIES"));
        assertTrue(mapViewPatch.contains("PlatformViewFactory$Attribute;->MOVABLE"));
        assertTrue(mapViewPatch.contains("shouldUseMovableMap(Landroid/content/Context;)Z"));
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
        assertTrue(renderer.contains("shouldShowBackgroundTraffic"));
        assertTrue(renderer.contains("routeOnlyMode && activeRoute == null"));
        assertTrue(renderer.contains("applyTrafficPresentation()"));
        assertTrue(renderer.contains("setTrafficStyle"));
        assertTrue(renderer.contains("\\\"scale\\\":0.45"));
        assertFalse(renderer.contains("createUserLocationLayer"));
        assertTrue(renderer.contains("NavigationLayerFactory"));
        assertTrue(renderer.contains(
                "\"setUseLayerCamera\", new Class<?>[]{boolean.class}, true"));
        assertTrue(renderer.contains("setRoadEventVisibleOnRoute"));
        assertTrue(renderer.contains("createNativeNavigationLayer"));
        assertTrue(renderer.contains("parkNativeGuidanceCamera"));
        assertTrue(renderer.contains("if (!routeGuidanceActive || currentWindow == null"));
        assertTrue(renderer.contains("if (routeGuidanceActive) createNativeNavigationLayer()"));
        assertTrue(renderer.contains("else removeNativeNavigationLayer()"));
        assertTrue(renderer.contains("Enum.valueOf((Class) cameraModeClass, \"FREE\")"));
        assertTrue(renderer.contains("setSwitchModesAutomatically"));
        assertFalse(renderer.contains("routeAwareRoadEventStyleProvider"));
        assertFalse(renderer.contains("shouldStyleRoadEvent"));
        assertFalse(renderer.contains("properties, \"isOnRoute\""));
        assertTrue(renderer.contains("safe standalone road-events layer attached"));
        assertTrue(renderer.contains("nearby fallback active"));
        assertTrue(renderer.contains("setRoadEventVisible"));
        assertTrue(renderer.contains("r74.c"));
        assertTrue(renderer.contains("guidanceCamera"));
        assertFalse(renderer.contains("applyNativeCameraProfile"));
        assertTrue(renderer.contains("float tilt = profile.tiltDegrees"));
        assertTrue(renderer.contains("applyCamera(false)"));
        assertTrue(renderer.contains("cursorStyler.update(frame.latitude"));
        assertTrue(renderer.contains("com.yandex.mapkit.Animation$Type"));
        assertTrue(renderer.contains("\"SMOOTH\""));
        assertTrue(renderer.contains("lastAppliedCamera"));
        assertTrue(renderer.contains("else if (jamsChanged) restyleRoute()"));
        assertTrue(renderer.contains("Updates traffic colours in place"));
        assertTrue(renderer.contains("positionOnRoute"));
        assertTrue(renderer.contains("invoke(route, \"getPosition\""));
        assertTrue(renderer.contains("setGeometry"));
        assertTrue(renderer.contains("isForwardProgress"));
        assertTrue(renderer.contains("slice.firstSegmentIndex"));
        assertTrue(routeStyler.contains("profile.showRouteTraffic"));
        assertTrue(routeStyler.contains("firstSegmentIndex + index"));
        assertTrue(mapProfile.contains("showRouteTraffic"));
        assertTrue(mapProfile.contains("showDestination"));
        assertTrue(mapProfile.contains("showTrafficLights"));
        assertTrue(mapProfile.contains("showLaneGuidance"));
        assertTrue(renderer.contains("trafficLightMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("trafficLightMapLayer.apply(profile.showTrafficLights)"));
        assertTrue(renderer.contains("cameraDirectionMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("laneGuidanceMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("laneGuidanceMapLayer.apply(profile.showLaneGuidance)"));
        assertTrue(renderer.contains("profile.showDestination && slice.destinationPoint"));
        assertTrue(renderer.contains("addDestinationMarker(collection"));
        assertTrue(renderer.contains("points.get(points.size() - 1)"));
        assertTrue(renderer.contains("createDestinationBitmap()"));
        assertTrue(trafficLights.contains("addCollection"));
        assertTrue(trafficLights.contains("addPlacemark"));
        assertTrue(trafficLights.contains("FRESH_MS = 3_000L"));
        assertTrue(trafficLights.contains("if (fingerprint == latestVisualFingerprint) return"));
        assertTrue(trafficLights.contains("ImageProvider"));
        assertTrue(trafficLights.contains("setIcon"));
        assertFalse(trafficLights.contains("ViewProvider"));
        assertTrue(trafficLights.contains("light.secondsLeft >= 0"));
        assertTrue(trafficLights.contains("secondsLeft"));
        assertFalse(mainMap.contains("TrafficLightMapLayer"));
        assertFalse(mainMap.contains("CameraDirectionMapLayer"));
        assertFalse(mainMap.contains("LaneGuidanceMapLayer"));
        assertTrue(cameraDirections.contains("FRESH_MS = 3_000L"));
        assertTrue(cameraDirections.contains("setDirection"));
        assertTrue(cameraDirections.contains("camera.inFace || camera.inBack"));
        assertTrue(laneGuidance.contains("FRESH_MS = 1_500L"));
        assertTrue(laneGuidance.contains("RotationType"));
        assertTrue(laneGuidance.contains("NO_ROTATION"));
        assertTrue(laneGuidance.contains("setGeometry"));
        assertFalse(renderer.contains("drivingRoute != activeRoute"));
        assertFalse(routeStyler.contains("Double.doubleToLongBits"));
        assertTrue(renderer.contains("applyStyleSlot"));
        assertTrue(renderer.contains("visibilityStyleJson"));
        assertTrue(renderer.contains("profile.roadsOnly"));
        assertTrue(renderer.contains("setTransparentBackgroundEnabled"));
        assertTrue(renderer.contains("profile.showModels && !profile.roadsOnly"));
        assertTrue(renderer.contains("profile.roadsOnly || !profile.showPois"));
        assertTrue(mapProfile.contains("ROADS_ONLY_STYLE"));
        assertTrue(mapProfile.contains("road_surface"));
        assertTrue(mapProfile.contains("if (roadsOnly) return ROADS_ONLY_STYLE"));
        assertTrue(renderer.contains("updateInitialCamera"));
        assertTrue(renderer.contains("updateNavigationState"));
        assertTrue(client.contains("refreshBackgroundMapLease()"));
        assertTrue(client.contains("hudMapRenderer.hasActiveMapWindow()"));
        assertTrue(client.contains("clusterMapRenderer.hasActiveMapWindow()"));
        assertTrue(backgroundLease.contains("HOST_STOP_SETTLE_MS = 1_000L"));
        assertTrue(backgroundLease.contains("main.postDelayed(reassertAfterHostStop"));
        assertTrue(backgroundLease.contains("!activityForeground && externalMapActive"));
        assertTrue(backgroundLease.contains("invoke(mapKit, \"onStart\")"));
        assertTrue(backgroundLease.contains("invoke(mapKit(), \"onStop\")"));
        assertFalse(backgroundLease.contains("postDelayed(this"));
        assertFalse(client.contains("parseNavigationState(snapshotJson)"));
        assertTrue(publisher.contains("readSnapshotInputs(currentGuidance, activeRoute)"));
        assertTrue(client.contains("hudMapRenderer.updateNavigationState(navigationFrame)"));
        assertTrue(client.contains("clusterMapRenderer.updateNavigationState(navigationFrame)"));
        assertTrue(client.contains("jamFingerprint, jamStyle"));
        assertTrue(client.contains("if (snapshotJson != null)"));
        assertTrue(publisher.contains("SNAPSHOT_INTERVAL_MS = 100L"));
        assertTrue(publisher.contains("elapsedNow - lastSnapshotDispatchElapsedMs"));
        assertTrue(client.contains("hudMapRenderer.updateNavigationRuntime(navigation)"));
        assertTrue(client.contains("clusterMapRenderer.updateNavigationRuntime(navigation)"));
        assertFalse(client.contains("hudMapRenderer.updatePrimaryCamera"));
        assertTrue(renderer.contains("getGeometry"));
        assertTrue(renderer.contains("addPolyline"));
        assertTrue(routeStyler.contains("getJamSegments"));
        assertTrue(routeStyler.contains("setStrokeColors"));
        assertTrue(routeStyler.contains("setPaletteColor"));
        assertTrue(routeStyler.contains("readJamStyle"));
        assertTrue(renderer.contains("applyProgressColors"));
        assertFalse(cursor.contains("UserLocationObjectListener"));
        assertFalse(cursor.contains("ViewProvider"));
        assertFalse(cursor.contains("setView"));
        assertTrue(cursor.contains("addPlacemark"));
        assertTrue(cursor.contains("setGeometry"));
        assertTrue(cursor.contains("setDirection"));
        assertTrue(cursor.contains("ImageProvider"));
        assertTrue(mainMap.contains("Stable main MapProfile applied"));
        assertFalse(mainMap.contains("rebuildRoute"));
        assertFalse(mainMap.contains("addCollection"));
        assertFalse(mainMap.contains("createUserLocationLayer"));
        assertFalse(mainMap.contains("invoke(currentMap, \"move\""));
        assertFalse(mainMap.contains("getJamsLayerId"));
        assertTrue(publisher.contains("MapActivity.x()"));
        assertTrue(publisher.contains("getMapWindow"));
        assertTrue(publisher.contains("getGuidance"));
        assertTrue(publisher.contains("getRouteStatus"));
        assertTrue(publisher.contains("invoke(currentNaviKitGuidance, \"route\")"));
        assertTrue(publisher.contains("invoke(currentNaviKitGuidance, \"freeDriveRoute\")"));
        assertTrue(publisher.contains("invoke(currentGuidance, \"getCurrentRoute\")"));
        assertTrue(publisher.contains("userActive="));
        assertTrue(publisher.contains("scheduleRouteReconcile"));
        assertTrue(publisher.contains("main.post(routeReconcile)"));
        assertTrue(publisher.contains("main.postDelayed(routeReconcileConfirmation"));
        assertTrue(publisher.contains("distanceToFinish"));
        assertTrue(publisher.contains("timeToFinish"));
        assertTrue(publisher.contains("getManoeuvres"));
        assertTrue(publisher.contains("getLaneSigns"));
        assertTrue(publisher.contains("getActiveSpeedCameras"));
        assertTrue(publisher.contains("getActiveDirections"));
        assertTrue(publisher.contains("getInFace"));
        assertTrue(publisher.contains("getInBack"));
        assertTrue(publisher.contains("LaneGuidanceFrame"));
        assertTrue(publisher.contains("invoke(position, \"getPoint\")"));
        assertTrue(publisher.contains("invoke(position, \"heading\")"));
        assertTrue(publisher.contains("getTrafficLightsWithSignal"));
        assertTrue(publisher.contains("setMaxNumberOfUpcomingTrafficLights"));
        assertTrue(publisher.contains("MAX_UPCOMING_TRAFFIC_LIGHTS = 8"));
        assertTrue(publisher.contains("activeRoute == null"));
        assertTrue(publisher.contains("Collections.unmodifiableList("));
        assertTrue(publisher.contains("readTrafficLights(inputs.routePosition)"));
        assertTrue(publisher.contains("TRAFFIC_LIGHT_INTERVAL_MS = 500L"));
        assertTrue(publisher.contains("validTrafficSignal"));
        assertTrue(publisher.contains("\"latitude\""));
        assertTrue(publisher.contains("\"longitude\""));
        assertTrue(publisher.contains("ConditionsListener"));
        assertTrue(publisher.contains("trafficSegmentsJson"));
        assertTrue(publisher.contains("STATE_INTERVAL_MS = 33L"));
        assertTrue(publisher.contains("scheduleStatePublish(false)"));
        assertTrue(publisher.contains("activeJamFingerprint"));
        assertTrue(publisher.contains("routeId.equals(activeRouteId)"));
        assertTrue(publisher.contains("destinationForRoute(route)"));
        assertTrue(renderer.contains("currentRouteProgress(route)"));
        assertTrue(renderer.contains("progress.segmentPosition"));
        assertTrue(renderer.contains("0.05d"));
        assertTrue(publisher.contains("addCameraListener"));
        assertTrue(publisher.contains("getValue\"), 0d) * 3.6d"));
        assertFalse(publisher.contains("getDeclaredField"));
        assertFalse(renderer.contains("captureScreenshot("));
        assertFalse(renderer.contains("PlatformGLSurface"));
        assertTrue(cursor.contains("Bitmap"));
        assertFalse(controller.contains("ImageReader"));
        assertFalse(controller.contains("MediaProjection"));
        assertFalse(controller.contains("Bitmap"));
    }

    @Test public void bootAdmitsAutostartHudBeforeTheIntegrationGraph() throws Exception {
        String boot = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/BootReceiver.java"));
        String hudService = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/hud/HudPresentationService.java"));
        int immediate = boot.indexOf("restoreHudSurfaceImmediately(context, action);");
        int coordinated = boot.indexOf(
                "StartupWorkCoordinator.scheduleForLifecycle(context, action);", immediate);
        assertTrue(immediate >= 0);
        assertTrue(coordinated > immediate);
        assertTrue(boot.contains("HudPresentationService.reconcileAutomaticLifecycle(context)"));
        assertTrue(hudService.contains("prefs.hudPanelEnabled.get()"));
        assertTrue(hudService.contains("prefs.hudPanelAutostart.get()"));
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
        assertTrue(composite.contains("mapTexture.setOpaque(!transparentMap)"));
        assertTrue(composite.contains("transparentBackground"));
        assertTrue(composite.contains("width <= 1 || height <= 1"));
        assertTrue(composite.contains("texture.setDefaultBufferSize(width, height)"));
        assertTrue(composite.contains("addOnLayoutChangeListener"));
        assertTrue(composite.contains("publishLaidOutSurface"));
        assertFalse(composite.contains(
                "item == null || getWidth() <= 0 || getHeight() <= 0"));
        assertTrue(composite.contains(
                "Apply it while the overlay tree is still being built"));
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
        assertTrue(elementTypes.contains("NAV_COMBINED(\"Карточка ближайшего манёвра\""));
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
        assertTrue(canvas.contains("drawManeuverCardText"));
        assertTrue(canvas.contains("nav.turnDistance"));
        assertTrue(canvas.contains("showRoadBadge"));
        assertTrue(canvas.contains("HudNavigationVisuals.lane"));
        assertTrue(canvas.contains("drawTrafficArrow"));
        assertTrue(canvas.contains("isTrafficArrow"));
    }

    @Test public void settingsExposeIndependentMapsAndNavigatorWindowButton() throws Exception {
        String settings = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/HudPanelSettingsActivity.java"));
        String windowSettings = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/NavigatorWindowSettingsActivity.java"));
        assertTrue(settings.contains("Независимая карта HUD"));
        assertTrue(settings.contains("Основная карта и окно Навигатора"));
        assertTrue(settings.contains("Кнопка окно / полный экран"));
        assertTrue(settings.contains("navigation.hudMap"));
        assertTrue(settings.contains("navigation.mainMap"));
        assertTrue(settings.contains("navigation.mainFloatingWindow"));
        assertTrue(settings.contains("Только дороги — прозрачный фон"));
        assertTrue(settings.contains("Дорожные события — выбрать типы и режимы"));
        assertTrue(settings.contains("Только с маршрутом"));
        assertTrue(settings.contains("Направление камер берётся из данных Яндекса"));
        assertTrue(settings.contains("profile.roadsOnly = roadsOnly.isChecked()"));
        assertTrue(settings.contains("Цвет рекомендуемой полосы"));
        assertTrue(settings.contains("Красный сигнал ARGB"));
        assertTrue(settings.contains("Тяжёлая пробка ARGB"));
        assertTrue(settings.contains("SeekBar control = new SeekBar(this)"));
        assertTrue(settings.contains("SliderField fontSize = slider"));
        assertTrue(settings.contains("SliderField brightness = slider"));
        assertTrue(settings.contains("SliderField borderOpacity = slider"));
        assertTrue(settings.contains("SliderField zoom = slider"));
        assertTrue(settings.contains("SliderField focusX = slider"));
        assertTrue(settings.contains("SliderField width = slider"));
        assertTrue(settings.contains("SliderField columns = slider"));
        assertTrue(settings.contains("SliderField gap = slider"));
        assertTrue(settings.contains("SliderField marginBottom = slider"));
        assertTrue(settings.contains("SliderField buttonOpacity = slider"));
        assertFalse(settings.contains("EditText focusX = field"));
        assertFalse(settings.contains("EditText buttonOpacity = field"));
        assertFalse(settings.contains("compactNumber("));
        assertTrue(windowSettings.contains("Оконный режим Навигатора"));
        assertTrue(windowSettings.contains("Скругление углов"));
        assertTrue(windowSettings.contains("Зафиксировать окно"));
        assertTrue(windowSettings.contains("window.movementLocked = fullyLocked"));
        assertTrue(windowSettings.contains("window.resizeLocked = fullyLocked"));
        assertTrue(windowSettings.contains("window.dragHandleVisible"));
        assertTrue(windowSettings.contains("window.resizeHandleVisible"));
    }

    @Test public void routeLabelsAndCursorFollowCanonicalRouteState() throws Exception {
        Path navigator = navigatorModRoot();
        String publisher = read(navigator.resolve("NavigatorStatePublisher.java"));
        String renderer = read(navigator.resolve("HudMapRenderer.java"));
        String profile = read(navigator.resolve("NavigationMapProfile.java"));
        String labels = read(navigator.resolve("RouteStreetLabelMapLayer.java"));

        assertTrue(profile.contains("routeStreetLabelsOnly"));
        assertTrue(profile.contains("roadColor"));
        assertTrue(profile.contains("MapKit styles require #RRGGBBAA"));
        assertTrue(profile.contains("\\\"elements\\\":\\\"label\\\""));
        assertTrue(publisher.contains("readRouteStreetLabels("));
        assertTrue(publisher.contains("RoutePosition.getPoint()"));
        assertTrue(publisher.contains("invoke(routePosition, \"getPoint\")"));
        assertTrue(publisher.contains("ROUTE_MATCH_HOLD_MS = 2_500L"));
        assertTrue(publisher.contains("lastRouteMatchedLatitude"));
        assertTrue(publisher.contains("latitude = routeLatitude"));
        assertTrue(publisher.contains("longitude = routeLongitude"));
        assertTrue(renderer.contains("routeStreetLabelMapLayer.update("));
        assertTrue(labels.contains("FRESH_MS = 2_500L"));
        assertTrue(labels.contains("Screen-facing street names sourced exclusively"));
        assertTrue(labels.contains("setGeometry"));
        assertTrue(labels.contains("com.yandex.mapkit.map.TextStyle"));
        assertFalse(labels.contains("Bitmap.createBitmap"));
    }

    @Test public void backwardProgressImmediatelyRestoresRouteFromCursor() throws Exception {
        String renderer = read(navigatorModRoot().resolve("HudMapRenderer.java"));
        assertTrue(renderer.contains("routeProgressChanged(progress)"));
        assertTrue(renderer.contains("isBehindRenderedProgress(progress)"));
        assertTrue(renderer.contains("if (!movingBackward"));
        assertTrue(renderer.contains("route geometry is not historical progress"));
        assertTrue(renderer.contains("Object cursorPoint = frame.currentRoutePoint"));
        assertTrue(renderer.contains("newInstance(frame.latitude, frame.longitude)"));
        assertTrue(renderer.contains("remainingRoute(route, progress)"));
        assertFalse(renderer.contains("BACKWARD_PROGRESS_CONFIRMATIONS"));
        assertFalse(renderer.contains("pendingBackwardConfirmations"));
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
