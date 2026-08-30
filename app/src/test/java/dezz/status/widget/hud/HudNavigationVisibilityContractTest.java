/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source gate for the live-only contract shared by every standalone navigation element. */
public final class HudNavigationVisibilityContractTest {
    @Test public void liveCanvasRequiresSemanticDataButEditorKeepsPlaceholders() throws Exception {
        Path root = projectRoot();
        String canvas = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudCanvasView.java"));
        String runtime = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudRuntimeData.java"));
        String state = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudNavigationState.java"));

        assertTrue(canvas.contains("!editor && isNavigation(item.type)"));
        assertTrue(canvas.contains("!data.navigationElementAvailable(item)"));
        assertTrue(canvas.contains("if (editor) drawMapPlaceholder"));
        assertTrue(runtime.contains("DIRECT_NAVIGATION_FRESH_MS = 3_000L"));
        assertTrue(runtime.contains("direct.isFreshAt("));
        assertTrue(runtime.contains("else if (!directSource)"));
        assertTrue(runtime.contains("NavigationBridgeStateStore.sessionId()"));
        assertTrue(runtime.contains("navigationExpiryPosted"));
        assertTrue(runtime.contains("navigation = null;"));
        assertTrue(state.contains("hasDataFor(@NonNull HudElementType type)"));
        assertTrue(state.contains("routeActive && trafficAvailable"));
        assertTrue(state.contains("routeActive && laneAvailable"));
        assertTrue(state.contains("meaningfulManeuverType"));
        assertTrue(state.contains("normalizedTrafficSignal"));
    }

    @Test public void mapOverlayLayersHaveIndependentHudAndClusterSwitches() throws Exception {
        Path root = projectRoot();
        String hudSettings = read(root.resolve(
                "app/src/main/java/dezz/status/widget/HudPanelSettingsActivity.java"));
        String clusterSettings = read(root.resolve(
                "app/src/main/java/dezz/status/widget/InstrumentPanelSettingsActivity.java"));
        String config = read(root.resolve(
                "app/src/main/java/dezz/status/widget/navigation/"
                        + "NavigationIntegrationConfig.java"));
        String mainMap = read(root.resolve(
                "navigator-mod/src/main/java/ru/natro/navigation/MainMapController.java"));

        assertTrue(hudSettings.contains("profile.showTrafficLights"));
        assertTrue(clusterSettings.contains("map.showTrafficLights"));
        assertTrue(hudSettings.contains("profile.showLaneGuidance"));
        assertTrue(clusterSettings.contains("map.showLaneGuidance"));
        assertTrue(hudSettings.contains("Светофоры с отсчётом — отдельный слой"));
        assertTrue(clusterSettings.contains("Светофоры с отсчётом — отдельный слой"));
        assertTrue(hudSettings.contains("Подсказки по полосам — слой на маршруте"));
        assertTrue(clusterSettings.contains("Подсказки по полосам — слой на маршруте"));
        assertTrue(config.contains(".put(\"showTrafficLights\", showTrafficLights)"));
        assertTrue(config.contains(".put(\"showLaneGuidance\", showLaneGuidance)"));
        assertFalse(mainMap.contains("showTrafficLights"));
        assertFalse(mainMap.contains("showLaneGuidance"));
    }

    @Test public void maneuverCardIsAStandaloneHudWidgetNotAMapLayer() throws Exception {
        Path root = projectRoot();
        String types = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudElementType.java"));
        String element = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudElementConfig.java"));
        String canvas = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudCanvasView.java"));
        String runtime = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudRuntimeData.java"));
        String state = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudNavigationState.java"));

        assertTrue(types.contains("NAV_COMBINED(\"Карточка ближайшего манёвра\""));
        assertTrue(element.contains("showCardBackground"));
        assertTrue(element.contains("showRoadBadge"));
        assertTrue(element.contains("cardOpacityPercent"));
        assertTrue(canvas.contains("drawManeuverCardText"));
        assertTrue(canvas.contains("nav.turnDistance"));
        assertTrue(canvas.contains("nav.maneuverSubtext"));
        assertTrue(canvas.contains("nav.street"));
        assertFalse(canvas.contains("NAV_COMBINED MapObject"));
        assertTrue(runtime.contains("navigation.turnDistance"));
        assertTrue(state.contains("case NAV_COMBINED:"));
        assertTrue(state.contains("return routeActive && (maneuverImage != null"));
    }

    private static Path projectRoot() {
        return Files.isRegularFile(Paths.get("app", "src", "main", "AndroidManifest.xml"))
                ? Paths.get("") : Paths.get("..");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
