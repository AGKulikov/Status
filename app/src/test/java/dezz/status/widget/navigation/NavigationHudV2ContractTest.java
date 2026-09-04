/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
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
        config.hudMap.fixedZoomEnabled = true;
        config.hudMap.fixedZoomLevel = 17.25d;
        config.hudMap.laneGuidanceScalePercent = 135;
        config.hudMap.cameraScalePercent = 145;
        config.hudMap.cameraDirectionLengthPercent = 180;
        config.hudMap.cameraDirectionWidthPercent = 65;
        config.hudMap.cameraDirectionColor = "#2468AC";
        config.hudMap.cameraDirectionOpacityPercent = 45;
        config.hudMap.trafficLightScalePercent = 95;
        config.hudMap.trafficLightCardColor = "#CC123456";
        config.hudMap.speedBumpScalePercent = 150;
        config.hudMap.routeTurnLengthPercent = 115;
        config.hudMap.routeTurnHeadSizePercent = 70;
        config.hudMap.routeTurnFillColor = "#123456";
        config.hudMap.routeTurnOutlineColor = "#FFABCDEF";
        config.hudMap.routeTurnOutlineWidth = 3.5d;
        config.hudMap.routeLabelScalePercent = 125;
        config.hudMap.roadEventScalePercent = 80;
        config.hudMap.destinationScalePercent = 165;
        config.hudMap.routeWidthPercent = 145;
        config.hudMap.roadWidthPercent = 70;
        config.hudMap.manualLayerPrioritiesEnabled = true;
        config.hudMap.cameraDirectionLayerPriority = 11;
        config.hudMap.roadEventLayerPriority = 17;
        config.hudMap.routeLayerPriority = 22;
        config.hudMap.destinationLayerPriority = 27;
        config.hudMap.trafficLightLayerPriority = 33;
        config.hudMap.speedBumpLayerPriority = 29;
        config.hudMap.routeTurnLayerPriority = 38;
        config.hudMap.laneGuidanceLayerPriority = 88;
        config.hudMap.cursorLayerPriority = 99;
        config.hudMap.showTraffic = false;
        config.hudMap.showRouteTraffic = true;
        config.hudMap.showDestination = false;
        config.hudMap.showTrafficLights = false;
        config.hudMap.showSpeedBumps = false;
        config.hudMap.showRouteTurns = false;
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
        config.clusterMap.showSpeedBumps = true;
        config.clusterMap.showLaneGuidance = true;
        config.clusterMap.trafficLightCardColor = "#FFABC123";
        config.clusterMap.trafficFreeColor = "#FF616263";
        config.clusterMap.trafficLightColor = "#FF717273";
        config.clusterMap.trafficHardColor = "#FF818283";
        config.clusterMap.trafficVeryHardColor = "#FF919293";
        config.clusterMap.trafficBlockedColor = "#FFA1A2A3";
        config.clusterMap.trafficUnknownColor = "#FFB1B2B3";
        config.clusterMap.roadColor = "#FF223344";
        config.clusterMap.laneGuidanceScalePercent = 85;
        config.clusterMap.speedBumpScalePercent = 75;
        config.clusterMap.speedBumpLayerPriority = 64;
        config.clusterMap.cameraDirectionLengthPercent = 75;
        config.clusterMap.cameraDirectionWidthPercent = 155;
        config.clusterMap.cameraDirectionColor = "#FF13579B";
        config.clusterMap.routeTurnLengthPercent = 175;
        config.clusterMap.routeTurnHeadSizePercent = 140;
        config.clusterMap.routeTurnFillColor = "#FF654321";
        config.clusterMap.routeTurnOutlineColor = "#FF102030";
        config.clusterMap.routeTurnOutlineWidth = 6d;
        config.clusterMap.routeWidthPercent = 75;
        config.clusterMap.roadWidthPercent = 125;
        config.clusterMap.setRoadEventMode("RECONSTRUCTION",
                NavigationIntegrationConfig.RoadEventMode.ALWAYS);
        config.mainFloatingWindow.movementLocked = true;
        config.mainFloatingWindow.cornerRadiusDp = 38;
        config.mainFloatingWindow.modeButtonVisible = true;
        config.mainFloatingWindow.modeButtonPosition = "BOTTOM_LEFT";
        config.mainFloatingWindow.modeButtonSizeDp = 52;
        config.mainFloatingWindow.borderColor = "#abcdef";

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
        assertTrue(restored.hudMap.fixedZoomEnabled);
        assertEquals(17.25d, restored.hudMap.fixedZoomLevel, 0d);
        assertEquals(135, restored.hudMap.laneGuidanceScalePercent);
        assertEquals(145, restored.hudMap.cameraScalePercent);
        assertEquals(180, restored.hudMap.cameraDirectionLengthPercent);
        assertEquals(65, restored.hudMap.cameraDirectionWidthPercent);
        assertEquals("#FF2468AC", restored.hudMap.cameraDirectionColor);
        assertEquals(45, restored.hudMap.cameraDirectionOpacityPercent);
        assertEquals(95, restored.hudMap.trafficLightScalePercent);
        assertEquals("#CC123456", restored.hudMap.trafficLightCardColor);
        assertEquals(150, restored.hudMap.speedBumpScalePercent);
        assertEquals(115, restored.hudMap.routeTurnLengthPercent);
        assertEquals(70, restored.hudMap.routeTurnHeadSizePercent);
        assertEquals("#FF123456", restored.hudMap.routeTurnFillColor);
        assertEquals("#FFABCDEF", restored.hudMap.routeTurnOutlineColor);
        assertEquals(3.5d, restored.hudMap.routeTurnOutlineWidth, 0d);
        assertEquals(125, restored.hudMap.routeLabelScalePercent);
        assertEquals(80, restored.hudMap.roadEventScalePercent);
        assertEquals(165, restored.hudMap.destinationScalePercent);
        assertEquals(145, restored.hudMap.routeWidthPercent);
        assertEquals(70, restored.hudMap.roadWidthPercent);
        assertTrue(restored.hudMap.manualLayerPrioritiesEnabled);
        assertEquals(11, restored.hudMap.cameraDirectionLayerPriority);
        assertEquals(17, restored.hudMap.roadEventLayerPriority);
        assertEquals(22, restored.hudMap.routeLayerPriority);
        assertEquals(27, restored.hudMap.destinationLayerPriority);
        assertEquals(33, restored.hudMap.trafficLightLayerPriority);
        assertEquals(29, restored.hudMap.speedBumpLayerPriority);
        assertEquals(22, restored.hudMap.routeTurnLayerPriority);
        assertEquals(88, restored.hudMap.laneGuidanceLayerPriority);
        assertEquals(99, restored.hudMap.cursorLayerPriority);
        assertFalse(restored.hudMap.showTraffic);
        assertTrue(restored.hudMap.showRouteTraffic);
        assertFalse(restored.hudMap.showDestination);
        assertFalse(restored.hudMap.showTrafficLights);
        assertFalse(restored.hudMap.showSpeedBumps);
        assertFalse(restored.hudMap.showRouteTurns);
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
        assertEquals("#FFABC123", restored.clusterMap.trafficLightCardColor);
        assertEquals("#FF616263", restored.clusterMap.trafficFreeColor);
        assertEquals("#FF717273", restored.clusterMap.trafficLightColor);
        assertEquals("#FF818283", restored.clusterMap.trafficHardColor);
        assertEquals("#FF919293", restored.clusterMap.trafficVeryHardColor);
        assertEquals("#FFA1A2A3", restored.clusterMap.trafficBlockedColor);
        assertEquals("#FFB1B2B3", restored.clusterMap.trafficUnknownColor);
        assertEquals("#FF223344", restored.clusterMap.roadColor);
        assertEquals(85, restored.clusterMap.laneGuidanceScalePercent);
        assertEquals(75, restored.clusterMap.speedBumpScalePercent);
        assertTrue(restored.clusterMap.showSpeedBumps);
        assertEquals(64, restored.clusterMap.speedBumpLayerPriority);
        assertEquals(75, restored.clusterMap.cameraDirectionLengthPercent);
        assertEquals(155, restored.clusterMap.cameraDirectionWidthPercent);
        assertEquals("#FF13579B", restored.clusterMap.cameraDirectionColor);
        assertEquals(175, restored.clusterMap.routeTurnLengthPercent);
        assertEquals(140, restored.clusterMap.routeTurnHeadSizePercent);
        assertEquals("#FF654321", restored.clusterMap.routeTurnFillColor);
        assertEquals("#FF102030", restored.clusterMap.routeTurnOutlineColor);
        assertEquals(6d, restored.clusterMap.routeTurnOutlineWidth, 0d);
        assertEquals(75, restored.clusterMap.routeWidthPercent);
        assertEquals(125, restored.clusterMap.roadWidthPercent);
        assertEquals(NavigationIntegrationConfig.RoadEventMode.ALWAYS,
                restored.clusterMap.roadEventMode("RECONSTRUCTION"));
        assertTrue(restored.mainFloatingWindow.movementLocked);
        assertEquals(38, restored.mainFloatingWindow.cornerRadiusDp);
        assertTrue(restored.mainFloatingWindow.modeButtonVisible);
        assertEquals("TOP_LEFT", restored.mainFloatingWindow.modeButtonPosition);
        assertEquals(52, restored.mainFloatingWindow.modeButtonSizeDp);
        assertEquals("#FFABCDEF", restored.mainFloatingWindow.borderColor);
    }

    @Test public void legacyRouteOnlyLabelsMigrateToStockYandexLabels() {
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                "{\"hudMap\":{\"showLabels\":false,\"routeStreetLabelsOnly\":true},"
                        + "\"clusterMap\":{\"showLabels\":false,"
                        + "\"routeStreetLabelsOnly\":true}}");

        assertTrue(restored.hudMap.showLabels);
        assertTrue(restored.clusterMap.showLabels);
        assertNull(restored.hudMap.trafficLightCardColor);
        assertNull(restored.clusterMap.trafficLightCardColor);
    }

    @Test public void legacyLayerPresetMigratesAndPolylineArrowsFollowRoutePriority() {
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                "{\"hudMap\":{\"manualLayerPrioritiesEnabled\":true,"
                        + "\"cameraDirectionLayerPriority\":20,"
                        + "\"roadEventLayerPriority\":30,"
                        + "\"routeLayerPriority\":40,"
                        + "\"destinationLayerPriority\":45,"
                        + "\"trafficLightLayerPriority\":50,"
                        + "\"routeTurnLayerPriority\":55,"
                        + "\"laneGuidanceLayerPriority\":80,"
                        + "\"cursorLayerPriority\":90}}");

        assertEquals(30, restored.hudMap.cameraDirectionLayerPriority);
        assertEquals(40, restored.hudMap.roadEventLayerPriority);
        assertEquals(50, restored.hudMap.routeLayerPriority);
        assertEquals(60, restored.hudMap.cursorLayerPriority);
        assertEquals(70, restored.hudMap.trafficLightLayerPriority);
        assertEquals(80, restored.hudMap.laneGuidanceLayerPriority);
        assertEquals(90, restored.hudMap.destinationLayerPriority);
        assertEquals(restored.hudMap.routeLayerPriority,
                restored.hudMap.routeTurnLayerPriority);
    }

    @Test public void legacyRouteTurnScaleMigratesToLengthAndHeadWithoutForcingColors() {
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                "{\"hudMap\":{\"routeTurnScalePercent\":135},"
                        + "\"clusterMap\":{\"routeTurnScalePercent\":85}}");

        assertEquals(135, restored.hudMap.routeTurnLengthPercent);
        assertEquals(135, restored.hudMap.routeTurnHeadSizePercent);
        assertEquals(85, restored.clusterMap.routeTurnLengthPercent);
        assertEquals(85, restored.clusterMap.routeTurnHeadSizePercent);
        assertNull(restored.hudMap.routeTurnFillColor);
        assertNull(restored.hudMap.routeTurnOutlineColor);
    }

    @Test public void legacyCameraDirectionScaleMigratesToIndependentLengthAndWidth()
            throws Exception {
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                "{\"hudMap\":{\"cameraDirectionScalePercent\":175},"
                        + "\"clusterMap\":{\"cameraDirectionScalePercent\":60}}"
        );

        assertEquals(175, restored.hudMap.cameraDirectionLengthPercent);
        assertEquals(175, restored.hudMap.cameraDirectionWidthPercent);
        assertEquals(60, restored.clusterMap.cameraDirectionLengthPercent);
        assertEquals(60, restored.clusterMap.cameraDirectionWidthPercent);
        assertFalse(restored.toJson().getJSONObject("hudMap")
                .has("cameraDirectionScalePercent"));
    }

    @Test public void opaquePickerColorsAreCanonicalizedBeforePersistence() throws Exception {
        NavigationIntegrationConfig config = new NavigationIntegrationConfig();
        config.hudMap.trafficHardColor = "#a1b2c3";
        config.hudMap.roadColor = "#123456";
        config.mainFloatingWindow.borderColor = "#abcdef";

        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(
                config.toJson().toString());

        assertEquals("#FFA1B2C3", restored.hudMap.trafficHardColor);
        assertEquals("#FF123456", restored.hudMap.roadColor);
        assertEquals("#FFABCDEF", restored.mainFloatingWindow.borderColor);
    }

    @Test public void hudAndClusterMapEditorsUseVisualControlsForEveryCameraValue()
            throws Exception {
        String hud = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/HudPanelSettingsActivity.java"));
        String cluster = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/InstrumentPanelSettingsActivity.java"));

        for (String source : new String[]{hud, cluster}) {
            assertTrue(source.contains("SliderField zoom = slider("));
            assertTrue(source.contains("SliderField fixedZoomLevel = slider("));
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
        assertTrue(cluster.contains("map.fixedZoomEnabled = fixedZoom.isChecked()"));
        assertTrue(cluster.contains("map.fixedZoomLevel = fixedZoomLevel.value()"));
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
                .replace("showSpeedBumps.isChecked()", "speedBumps.isChecked()")
                .replace("showRouteTurns.isChecked()", "routeTurns.isChecked()")
                .replace("showLaneGuidance.isChecked()", "laneGuidance.isChecked()")
                .replace("showLabels.isChecked()", "labels.isChecked()")
                .replace("showPois.isChecked()", "pois.isChecked()")
                .replace("showBuildings.isChecked()", "buildings.isChecked()")
                .replace("showParks.isChecked()", "parks.isChecked()")
                .replace("showWater.isChecked()", "water.isChecked()")
                .replace("showModels.isChecked()", "models.isChecked()")
                .replace("showCursor.isChecked()", "cursor.isChecked()");
        String flatCluster = cluster.replaceAll("\\s+", " ");
        String flatHud = normalizedHud.replaceAll("\\s+", " ");
        for (String assignment : new String[]{
                "map.enabled = mapEnabled.isChecked()",
                "map.showRoute = route.isChecked()",
                "map.showDestination = destination.isChecked()",
                "map.showRouteTraffic = routeTraffic.isChecked()",
                "map.showTraffic = traffic.isChecked()",
                "map.showTrafficLights = trafficLights.isChecked()",
                "map.showSpeedBumps = speedBumps.isChecked()",
                "map.showRouteTurns = routeTurns.isChecked()",
                "map.showLaneGuidance = laneGuidance.isChecked()",
                "map.showLabels = labels.isChecked()",
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
                "map.laneGuidanceScalePercent = laneGuidanceScale.intValue()",
                "map.cameraScalePercent = cameraScale.intValue()",
                "map.cameraDirectionLengthPercent = cameraDirectionLength.intValue()",
                "map.cameraDirectionWidthPercent = cameraDirectionWidth.intValue()",
                "map.cameraDirectionColor = cameraDirectionColor.value",
                "map.cameraDirectionOpacityPercent = cameraDirectionOpacity.intValue()",
                "map.trafficLightScalePercent = trafficLightScale.intValue()",
                "map.trafficLightCardColor = trafficLightCardColor.value",
                "map.speedBumpScalePercent = speedBumpScale.intValue()",
                "map.routeTurnLengthPercent = routeTurnLength.intValue()",
                "map.routeTurnHeadSizePercent = routeTurnHeadSize.intValue()",
                "map.routeTurnFillColor = routeTurnFillColor.value",
                "map.routeTurnOutlineColor = routeTurnOutlineColor.value",
                "map.routeTurnOutlineWidth = routeTurnOutlineWidth.value()",
                "map.routeLabelScalePercent = routeLabelScale.intValue()",
                "map.roadEventScalePercent = roadEventScale.intValue()",
                "map.destinationScalePercent = destinationScale.intValue()",
                "map.routeWidthPercent = routeWidthPercent.intValue()",
                "map.roadWidthPercent = roadWidthPercent.intValue()",
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
                    flatCluster.contains(assignment));
            assertTrue("Missing HUD visual setting assignment: " + assignment,
                    flatHud.contains(assignment));
        }
        String compactCluster = cluster.replaceAll("\\s+", "");
        String compactHud = normalizedHud.replaceAll("\\s+", "");
        for (String assignment : new String[]{
                "map.manualLayerPrioritiesEnabled=manualLayerPriorities.isChecked();",
                "map.cameraDirectionLayerPriority=cameraDirectionLayerPriority.intValue();",
                "map.roadEventLayerPriority=roadEventLayerPriority.intValue();",
                "map.routeLayerPriority=routeLayerPriority.intValue();",
                "map.destinationLayerPriority=destinationLayerPriority.intValue();",
                "map.trafficLightLayerPriority=trafficLightLayerPriority.intValue();",
                "map.speedBumpLayerPriority=speedBumpLayerPriority.intValue();",
                "map.routeTurnLayerPriority=map.routeLayerPriority;",
                "map.laneGuidanceLayerPriority=laneGuidanceLayerPriority.intValue();",
                "map.cursorLayerPriority=cursorLayerPriority.intValue();"
        }) {
            assertTrue("Missing cluster layer assignment: " + assignment,
                    compactCluster.contains(assignment));
            assertTrue("Missing HUD layer assignment: " + assignment,
                    compactHud.contains(assignment));
        }
    }

    @Test public void nonFiniteMapValuesRestoreFieldDefaults() {
        NavigationIntegrationConfig config = new NavigationIntegrationConfig();
        config.hudMap.zoomDelta = Double.NaN;
        config.hudMap.fixedZoomLevel = Double.NaN;
        config.hudMap.routeWidth = Double.POSITIVE_INFINITY;
        config.hudMap.routeOutlineWidth = Double.NEGATIVE_INFINITY;
        config.hudMap.trafficGradientLength = Double.NaN;
        config.hudMap.laneGuidanceScalePercent = 999;
        config.hudMap.speedBumpScalePercent = 999;
        config.hudMap.routeWidthPercent = 0;
        config.hudMap.roadWidthPercent = 999;
        config.hudMap.cameraDirectionLayerPriority = -1;
        config.hudMap.cameraDirectionLengthPercent = 999;
        config.hudMap.cameraDirectionWidthPercent = 0;
        config.hudMap.cameraDirectionColor = "not-a-color";
        config.hudMap.cameraDirectionOpacityPercent = -1;
        config.hudMap.routeTurnLengthPercent = 0;
        config.hudMap.routeTurnHeadSizePercent = 999;
        config.hudMap.routeTurnFillColor = "not-a-color";
        config.hudMap.routeTurnOutlineColor = "#123456";
        config.hudMap.routeTurnOutlineWidth = Double.POSITIVE_INFINITY;
        config.hudMap.laneGuidanceLayerPriority = 101;
        config.hudMap.speedBumpLayerPriority = -5;

        config.normalize();

        assertEquals(0d, config.hudMap.zoomDelta, 0d);
        assertEquals(16d, config.hudMap.fixedZoomLevel, 0d);
        assertEquals(8d, config.hudMap.routeWidth, 0d);
        assertEquals(2d, config.hudMap.routeOutlineWidth, 0d);
        assertEquals(12d, config.hudMap.trafficGradientLength, 0d);
        assertEquals(250, config.hudMap.laneGuidanceScalePercent);
        assertEquals(250, config.hudMap.speedBumpScalePercent);
        assertEquals(25, config.hudMap.routeWidthPercent);
        assertEquals(300, config.hudMap.roadWidthPercent);
        assertEquals(0, config.hudMap.cameraDirectionLayerPriority);
        assertEquals(0, config.hudMap.speedBumpLayerPriority);
        assertEquals(300, config.hudMap.cameraDirectionLengthPercent);
        assertEquals(10, config.hudMap.cameraDirectionWidthPercent);
        assertEquals("#FF168BFF", config.hudMap.cameraDirectionColor);
        assertEquals(0, config.hudMap.cameraDirectionOpacityPercent);
        assertEquals(10, config.hudMap.routeTurnLengthPercent);
        assertEquals(250, config.hudMap.routeTurnHeadSizePercent);
        assertNull(config.hudMap.routeTurnFillColor);
        assertEquals("#FF123456", config.hudMap.routeTurnOutlineColor);
        assertEquals(2d, config.hudMap.routeTurnOutlineWidth, 0d);
        assertEquals(100, config.hudMap.laneGuidanceLayerPriority);
    }

    @Test public void featureAwareMapKitOrderIsDefaultAndManualOrderIsSafe()
            throws Exception {
        NavigationIntegrationConfig defaults = new NavigationIntegrationConfig();
        assertFalse(defaults.hudMap.manualLayerPrioritiesEnabled);
        assertFalse(defaults.clusterMap.manualLayerPrioritiesEnabled);

        Path navigator = navigatorModRoot();
        String profile = read(navigator.resolve("NavigationMapProfile.java"));
        String renderer = read(navigator.resolve("HudMapRenderer.java"));
        String factory = read(navigator.resolve("MapObjectLayerFactory.java"));
        String trafficLights = read(navigator.resolve("TrafficLightMapLayer.java"));
        String cameras = read(navigator.resolve("CameraDirectionMapLayer.java"));
        String speedBumps = read(navigator.resolve("SpeedBumpMapLayer.java"));
        String laneSigns = read(navigator.resolve("LaneGuidanceMapLayer.java"));
        String routeTurns = read(navigator.resolve("RouteTurnMapLayer.java"));
        String sublayerOrder = read(navigator.resolve("MapSublayerOrder.java"));
        String hudSettings = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/HudPanelSettingsActivity.java"));
        String clusterSettings = read(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/InstrumentPanelSettingsActivity.java"));

        assertTrue(profile.contains("\"manualLayerPrioritiesEnabled\", false"));
        assertTrue(profile.contains("manualLayerPrioritiesEnabled ?"));
        assertTrue(factory.contains("addMapObjectLayer"));
        assertTrue(factory.contains("setConflictResolutionMode"));
        assertTrue(trafficLights.contains("MapObjectLayerFactory.IGNORE"));
        assertTrue(cameras.contains("MapObjectLayerFactory.EQUAL"));
        assertTrue(cameras.contains("MapObjectLayerFactory.IGNORE"));
        assertTrue(cameras.contains("sectorCollection"));
        assertTrue(cameras.contains("signCollection"));
        assertTrue(cameras.contains("MIN_CAMERA_TEXTURE_DIAMETER_PX = 80"));
        assertTrue(cameras.contains("Math.max(displayDiameter,"
                + " MIN_CAMERA_TEXTURE_DIAMETER_PX)"));
        assertTrue(cameras.contains("Float.valueOf(textureScale)"));
        assertTrue(cameras.contains("MapSublayerOrder.CAMERA_SECTORS"));
        assertTrue(cameras.contains("MapSublayerOrder.CAMERA_SIGNS"));
        assertTrue(speedBumps.contains("MapSublayerOrder.SPEED_BUMPS"));
        assertTrue(speedBumps.contains("MapObjectLayerFactory.IGNORE"));
        assertTrue(laneSigns.contains("MapObjectLayerFactory.MAJOR"));
        assertTrue(renderer.contains("MapObjectLayerFactory.MINOR"));
        assertFalse(Files.exists(navigator.resolve("RouteStreetLabelMapLayer.java")));
        assertFalse(routeTurns.contains("MapObjectLayerFactory"));
        assertTrue(routeTurns.contains("applyManeuverStyle"));
        assertTrue(renderer.contains("applySublayerOrder()"));
        assertTrue(renderer.contains("getSublayerManager"));
        assertTrue(sublayerOrder.contains("SublayerFeatureType"));
        assertTrue(sublayerOrder.contains("new Class<?>[]{String.class, featureClass}"));
        assertTrue(sublayerOrder.contains("moveAfter"));
        assertTrue(sublayerOrder.contains("moveBefore"));
        assertTrue(sublayerOrder.contains("STOCK_GUIDANCE_BALLOONS"));
        assertTrue(sublayerOrder.contains("ref(SPEED_BUMPS, PLACEMARKS)"));
        assertTrue(sublayerOrder.contains("STOCK_ARRIVAL_DESTINATION"));
        assertFalse(sublayerOrder.contains("moveToEnd"));
        assertFalse(renderer.contains("automaticOrderRestored"));
        assertFalse(routeTurns.contains("ignoredLayerPriority"));
        assertTrue(routeTurns.contains("priority is exactly the priority of"));
        for (String settings : new String[]{hudSettings, clusterSettings}) {
            assertTrue(settings.contains("Ручной порядок слоёв"));
            assertTrue(settings.contains("Стрелки полилинии всегда следуют приоритету маршрута"));
            assertTrue(settings.contains("field.setEnabled(manualLayerPriorities.isChecked())"));
            assertFalse(settings.contains("SliderField routeTurnLayerPriority = slider"));
            assertTrue(settings.contains("Длина стрелок поворотов на маршруте"));
            assertTrue(settings.contains("Размер наконечника стрелок поворотов"));
            assertTrue(settings.contains("Цвет стрелок поворотов"));
            assertTrue(settings.contains("Цвет обводки стрелок"));
            assertTrue(settings.contains("Толщина обводки стрелок"));
            assertTrue(settings.contains("Ширина стрелки всегда равна толщине линии маршрута"));
            assertTrue(settings.contains("Options.opaqueInheritable()"));
        }
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
        String speedNormalizer = read(patchRoot.resolve("CameraSpeedNormalizer.java"));
        String mainMap = read(patchRoot.resolve("MainMapController.java"));
        String cursor = read(patchRoot.resolve("MapCursorStyler.java"));
        String routeStyler = read(patchRoot.resolve("RoutePolylineStyler.java"));
        String mapProfile = read(patchRoot.resolve("NavigationMapProfile.java"));
        String trafficLights = read(patchRoot.resolve("TrafficLightMapLayer.java"));
        String cameraDirections = read(patchRoot.resolve("CameraDirectionMapLayer.java"));
        String speedBumps = read(patchRoot.resolve("SpeedBumpMapLayer.java"));
        String laneGuidance = read(patchRoot.resolve("LaneGuidanceMapLayer.java"));
        String overlayPlacement = read(
                patchRoot.resolve("MapOverlayPlacementCoordinator.java"));
        String routeTurns = read(patchRoot.resolve("RouteTurnMapLayer.java"));
        String backgroundLease = read(patchRoot.resolve("BackgroundMapLease.java"));
        String mapViewPatch = read(projectRoot().resolve(
                "tools/patch_navigation_map_view.py"));

        assertTrue(controller.contains("ACTION_FLOATING = \"navi_win/ru.yandex.yandexnavi\""));
        assertTrue(controller.contains("EXTRA_WINDOWED = \"ddnavwin\""));
        assertTrue(controller.contains("EXTRA_FORCE_FULLSCREEN = \"ddnavforcewinfull\""));
        assertTrue(controller.contains("modeButton.setOnClickListener"));
        assertTrue(controller.contains("Развернуть Навигатор на весь экран"));
        assertTrue(controller.contains("!profile.enabled || !profile.modeButtonVisible"));
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
        assertTrue(controller.contains("View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN"));
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
        assertTrue(entry.contains("onMapTouch(Activity activity, MotionEvent event)"));
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
        assertFalse(controller.contains("@Override public boolean dispatchTouchEvent"));
        assertTrue(controller.contains("MotionEvent.ACTION_DOWN"));
        assertTrue(controller.contains("MotionEvent.ACTION_POINTER_DOWN"));
        assertTrue(controller.contains("MotionEvent.ACTION_MOVE"));
        assertTrue(controller.contains("MotionEvent.ACTION_UP"));
        assertTrue(controller.contains("mapTouchSlopSquared"));
        assertTrue(controller.contains("mainHandler.post(() ->"));
        assertFalse(entry.contains(
                "event.getActionMasked() != MotionEvent.ACTION_DOWN"));
        assertTrue(controller.contains("ensureModeButtonAttachedToStockRail()"));
        assertFalse(controller.contains("floatingModeButton"));
        assertTrue(controller.contains("restartInMode(!floating, null)"));
        assertTrue(controller.contains("findStockModeButtonRail"));
        assertTrue(controller.contains("STOCK_RECT_CONTROL_CLASS"));
        assertTrue(controller.contains("MapControlsFrameLayoutRect"));
        assertTrue(controller.contains("navigatorDimension(\"control_rect_size\", 48)"));
        assertTrue(controller.contains("navigatorDimension(\"control_rect_padding\", 4)"));
        assertTrue(controller.contains("guidance_open_voice_search"));
        assertTrue(controller.contains("navi_service_open_voice_search"));
        assertFalse(controller.contains("lockedModeButtonTopPx"));
        assertFalse(controller.contains("resolvedModeButtonTop"));
        assertFalse(controller.contains("leftControlColumnNextTop"));
        assertFalse(controller.contains("alice_fab_container"));
        assertFalse(controller.contains("guidance_search_map_control_ghost"));
        assertTrue(controller.contains("layer.addView(button"));
        assertTrue(controller.contains("rail.container.addView(button, targetIndex, params)"));
        assertTrue(controller.contains("int targetIndex = rail.voiceIndex + 1"));
        assertTrue(controller.contains("button.setVisibility(View.GONE)"));
        assertTrue(controller.contains("root.getParent() instanceof LinearLayout"));
        assertTrue(controller.contains("candidate.isAttachedToWindow()"));
        assertTrue(controller.contains("candidate.getOrientation() == LinearLayout.VERTICAL"));
        assertTrue(controller.contains("roadEventControl != null"));
        assertTrue(controller.contains(
                "hasAncestorId(candidate, ownerId, alternateOwnerId)"));
        assertFalse(controller.contains("upper.getLocationOnScreen"));
        assertFalse(controller.contains("lower.getLocationOnScreen"));
        assertFalse(controller.contains("layer.getLocationOnScreen"));
        assertFalse(controller.contains("MODE_BUTTON_FALLBACK_TOP_FRACTION"));
        assertFalse(controller.contains("profile.modeButtonOpacityPercent / 100f"));
        assertTrue(controller.contains("if (controlLayer == null) install()"));
        assertTrue(windowProfile.contains("modeButtonPosition = \"TOP_LEFT\""));
        assertFalse(controller.contains("MODE_BUTTON_AUTO_HIDE_MS"));
        assertTrue(controller.contains("MODE_BUTTON_REBIND_MS = 5_000L"));
        assertTrue(controller.contains("modeButtonStatePreDrawObserver"));
        assertTrue(controller.contains("installModeButtonStatePreDrawObserver()"));
        assertTrue(controller.contains("removeModeButtonStatePreDrawObserver()"));
        assertTrue(controller.contains("reportCallbackFailure(\"modeButtonStatePreDraw\""));
        assertTrue(controller.contains("syncModeButtonWithStockRail()"));
        assertTrue(controller.contains("visibilityWithinRail"));
        assertTrue(controller.contains("combinedControlVisibility"));
        assertTrue(controller.contains("rail.roadEventControl"));
        assertTrue(controller.contains("rail.voiceControl"));
        assertTrue(controller.contains("button.isPressed()"));
        assertTrue(controller.contains("button.setAlpha(source.getAlpha())"));
        assertTrue(controller.contains("button.setScaleX(source.getScaleX())"));
        assertTrue(controller.contains("button.setScaleY(source.getScaleY())"));
        assertTrue(controller.contains("button.setTranslationX(source.getTranslationX())"));
        assertTrue(controller.contains("button.setTranslationY(source.getTranslationY())"));
        int attachStart = controller.indexOf(
                "private boolean ensureModeButtonAttachedToStockRail()");
        int attachEnd = controller.indexOf(
                "private void installModeButtonStatePreDrawObserver()", attachStart);
        assertTrue(attachStart >= 0 && attachEnd > attachStart);
        String attachMethod = controller.substring(attachStart, attachEnd);
        assertFalse(attachMethod.contains("button.setVisibility(View.VISIBLE)"));
        assertTrue(attachMethod.contains("activeModeButtonRail = rail"));
        assertTrue(attachMethod.contains("syncModeButtonWithStockRail()"));
        assertTrue(controller.contains("replaceSystemWindowInsets("));
        assertTrue(controller.contains("insets.getSystemWindowInsetLeft(),"));
        assertTrue(controller.contains("removeFloatingTopInset(contentRoot)"));
        assertTrue(controller.contains("restorePadding(contentRoot"));
        assertTrue(controller.contains("requestNavigatorInsets()"));
        assertTrue(controller.contains("ensureControlLayerAttached()"));
        assertTrue(controller.contains("dispatchFloatingInsetsToNavigatorRoots()"));
        assertTrue(controller.contains("controls_engine_container"));
        assertTrue(controller.contains("controlsInsetHost = (View) controlsEngine.getParent()"));
        assertTrue(controller.contains("maps_activity_top_notification_container"));
        assertTrue(controller.contains("activity_container_controller"));
        assertTrue(controller.contains("activityControllerRoot"));
        assertTrue(controller.contains("navi_guidance_controls_touch_container"));
        assertTrue(controller.contains("guidanceInsetHost"));
        assertTrue(controller.contains("nextGuidanceControls != guidanceControls"));
        assertTrue(controller.contains("neutralizePaddingtonTree(activityControllerRoot)"));
        assertTrue(controller.contains("neutralizePaddingtonTree(exactGuidanceInsetRoot)"));
        assertTrue(controller.contains("paddingtonBaseTopByChild.put(guidanceControls, 0)"));
        assertTrue(controller.contains("activeGuidanceVisualRoot"));
        assertTrue(controller.contains("contextmaneuverview"));
        assertTrue(controller.contains("speed_group"));
        assertTrue(controller.contains("removeFloatingTopInset(activityControllerRoot)"));
        assertTrue(controller.contains("removeFloatingTopInset(guidanceVisualRoot)"));
        assertTrue(controller.contains("STOCK_GUIDANCE_TOP_MARGIN_DP = 12"));
        assertTrue(controller.contains("normalizeGuidanceTopGeometry()"));
        assertTrue(controller.contains("mapViewport.getLocationInWindow(mapLocation)"));
        assertTrue(controller.contains("rawParams.height == ViewGroup.LayoutParams.MATCH_PARENT"));
        assertTrue(controller.contains("int targetMargin = params.topMargin - excessTop"));
        assertTrue(controller.contains("sameUnappliedCorrection"));
        assertTrue(controller.contains("restoreGuidanceTopMargins()"));
        assertFalse(controller.contains("root.setTranslationY"));
        assertTrue(controller.contains("top_notification_container"));
        assertTrue(controller.contains("removeFloatingTopInset(controlsInsetHost)"));
        assertTrue(controller.contains("paddingtonBaseTop"));
        assertTrue(controller.contains("floatingTopInsetGuard"));
        assertTrue(controller.contains("floatingTopInsetPreDrawGuard"));
        assertTrue(controller.contains("installFloatingTopInsetPreDrawGuard()"));
        assertTrue(controller.contains("bestLiveViewById"));
        assertTrue(controller.contains("neutralizePaddingtonTree"));
        assertTrue(controller.contains("floatingPaddingtonInsetsListener"));
        assertTrue(controller.contains("child.setOnApplyWindowInsetsListener"));
        assertTrue(controller.contains("reportCallbackFailure(\"modeAwareInsets\""));
        assertTrue(controller.contains("reportCallbackFailure(\"paddingtonInsets\""));
        assertTrue(controller.contains("reportCallbackFailure(\"floatingTopInsetGuard\""));
        assertTrue(controller.contains(
                "reportCallbackFailure(\"floatingTopInsetPreDraw\""));
        assertTrue(controller.contains("reportCallbackFailure(\"createStockModeButton\""));
        assertTrue(controller.contains("reportCallbackFailure(\"attachStockModeButton\""));
        assertTrue(controller.contains("reportCallbackFailure(\"parkModeButton\""));
        assertFalse(controller.contains(
                "reportCallbackFailure(\"controlLayerModeButtonReattach\""));
        assertTrue(controller.contains("reportCallbackFailure(\"modeButtonClick\""));
        assertTrue(controller.contains("reportCallbackFailure(\"floatingSurfaceCommitter\""));
        assertTrue(controller.contains("reportCallbackFailure(\"mapTouchReattach\""));
        assertTrue(controller.contains("reportCallbackFailure(\"modeButtonPoller\""));
        assertTrue(controller.contains("finally {"));
        assertTrue(controller.contains("setTopPadding(guidanceControls"));
        assertTrue(controller.contains("dispatchAdjustedInsets(controlsInsetHost != null"));
        assertTrue(controller.contains("guidance_add_road_event"));
        assertFalse(controller.contains("map_controls_menu_button"));
        assertTrue(controller.contains("View host = window.getDecorView()"));
        assertTrue(controller.contains("setFitsSystemWindows(contentRoot, false)"));
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
        assertTrue(client.contains("INSTRUMENT_BRIDGE_RECONNECT_MS = 180L"));
        assertTrue(client.contains("main.postDelayed(this::reconnectAfterInstrumentLaunch"));
        assertTrue(client.contains("binder.isBinderAlive() && binder.pingBinder()"));
        assertTrue(client.contains("retryMs = MIN_RETRY_MS"));
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
        assertFalse(renderer.contains("NavigationLayerFactory"));
        assertFalse(renderer.contains("setUseLayerCamera"));
        assertFalse(renderer.contains("setRoadEventVisibleOnRoute"));
        assertFalse(renderer.contains("createNativeNavigationLayer"));
        assertFalse(renderer.contains("parkNativeGuidanceCamera"));
        assertFalse(renderer.contains("routeAwareRoadEventStyleProvider"));
        assertFalse(renderer.contains("shouldStyleRoadEvent"));
        assertFalse(renderer.contains("properties, \"isOnRoute\""));
        assertTrue(renderer.contains("safe standalone road-events layer attached"));
        assertTrue(renderer.contains("Automotive NavigationLayer is deliberately forbidden"));
        assertTrue(renderer.contains("routeGuidanceActive && \"ROUTE_ONLY\".equals(mode)"));
        assertTrue(renderer.contains("setRoadEventVisible"));
        assertTrue(renderer.contains("r74.c"));
        assertFalse(renderer.contains("guidanceCamera"));
        assertFalse(renderer.contains("applyNativeCameraProfile"));
        assertTrue(renderer.contains("float tilt = profile.tiltDegrees"));
        assertTrue(renderer.contains("freeCameraSource"));
        assertFalse(renderer.contains("if (free && freeCameraInitialized) return"));
        assertTrue(renderer.contains("readAppliedCamera(currentMap)"));
        assertTrue(renderer.contains("getCameraPosition"));
        assertTrue(renderer.contains("profile.fixedZoomEnabled"));
        assertTrue(renderer.contains("profile.fixedZoomLevel"));
        assertTrue(renderer.contains("applyCamera(false)"));
        assertTrue(renderer.contains("cursorStyler.update(frame.latitude"));
        assertTrue(renderer.contains("com.yandex.mapkit.Animation$Type"));
        assertTrue(renderer.contains("\"SMOOTH\""));
        assertTrue(renderer.contains("lastAppliedCamera"));
        assertTrue(renderer.contains("else if (jamsChanged) restyleRoute()"));
        assertTrue(renderer.contains("Updates traffic colours in place"));
        assertTrue(renderer.contains("positionOnRoute"));
        assertTrue(renderer.contains("invoke(route, \"getPosition\""));
        assertFalse(renderer.contains("\"setGeometry\""));
        assertTrue(renderer.contains("fullRoute(route)"));
        assertTrue(renderer.contains("com.yandex.mapkit.geometry.Subpolyline"));
        assertTrue(renderer.contains("invoke(line, \"hide\""));
        assertTrue("Missing reversible route progress in " + patchRoot.toAbsolutePath(),
                renderer.contains("routeProgressChanged"));
        assertTrue(renderer.contains("slice.firstSegmentIndex"));
        assertTrue(routeStyler.contains("profile.showRouteTraffic"));
        assertTrue(routeStyler.contains("firstSegmentIndex + index"));
        assertTrue(mapProfile.contains("showRouteTraffic"));
        assertTrue(mapProfile.contains("showDestination"));
        assertTrue(mapProfile.contains("showTrafficLights"));
        assertTrue(mapProfile.contains("showSpeedBumps"));
        assertTrue(mapProfile.contains("showRouteTurns"));
        assertTrue(mapProfile.contains("showLaneGuidance"));
        assertTrue(renderer.contains("trafficLightMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("trafficLightMapLayer.apply(profile.showTrafficLights, night,"));
        assertTrue(renderer.contains("profile.effectiveTrafficLightPriority()"));
        assertTrue(renderer.contains("speedBumpMapLayer.updateRoute(routeEpoch, drivingRoute)"));
        assertTrue(renderer.contains("speedBumpMapLayer.updateNavigationState(frame.routeActive"));
        assertTrue(renderer.contains("speedBumpMapLayer.apply(profile.showSpeedBumps"));
        assertTrue(renderer.contains("profile.effectiveSpeedBumpPriority()"));
        assertTrue(renderer.contains("routeTurnMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("routeTurnMapLayer.apply(profile.showRouteTurns"));
        assertTrue(renderer.contains("profile.routeTurnLengthPercent"));
        assertTrue(renderer.contains("profile.routeTurnHeadSizePercent"));
        assertTrue(renderer.contains("profile.routeTurnFillColor"));
        assertTrue(renderer.contains("profile.routeTurnOutlineColor"));
        assertTrue(renderer.contains("profile.routeTurnOutlineWidth"));
        assertFalse(renderer.contains("profile.effectiveRouteTurnPriority()"));
        assertTrue(renderer.contains("MapSublayerOrder.apply(currentMap, profile)"));
        assertTrue(renderer.contains("cameraDirectionMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("profile.effectiveCameraPriority()"));
        assertTrue(renderer.contains("laneGuidanceMapLayer.update(frame.routeActive"));
        assertTrue(renderer.contains("laneGuidanceMapLayer.apply(profile.showLaneGuidance,"));
        assertTrue(renderer.contains("profile.laneGuidanceScalePercent, night"));
        assertTrue(renderer.contains("profile.effectiveLanePriority()"));
        assertTrue(renderer.contains("profile.showDestination && slice.destinationPoint"));
        assertTrue(renderer.contains("addDestinationMarker(destinations"));
        assertTrue(renderer.contains("points.get(points.size() - 1)"));
        assertTrue(renderer.contains("createDestinationBitmap()"));
        assertTrue(trafficLights.contains("MapObjectLayerFactory.create(map"));
        assertTrue(trafficLights.contains("MapObjectLayerFactory.IGNORE"));
        assertFalse(trafficLights.contains("addCollection"));
        assertTrue(trafficLights.contains("addPlacemark"));
        assertTrue(trafficLights.contains("FRESH_MS = 3_000L"));
        assertTrue(trafficLights.contains("if (fingerprint == latestVisualFingerprint) return"));
        assertTrue(trafficLights.contains("ImageProvider"));
        assertTrue(trafficLights.contains("setIcon"));
        assertFalse(trafficLights.contains("ViewProvider"));
        assertTrue(trafficLights.contains("TrafficLightViewImpl"));
        assertTrue(trafficLights.contains("applyCompactFallbackViews"));
        assertTrue(trafficLights.contains("compactTrafficLightBitmap"));
        assertTrue(trafficLights.contains("useCompositeIcon"));
        assertTrue(trafficLights.contains("traffic-light-connector"));
        assertTrue(trafficLights.contains("traffic-light-body"));
        assertFalse(trafficLights.contains("ConnectorTexture.OVERSAMPLE"));
        assertTrue(trafficLights.contains(
                "invoke(connectorStyle, \"setScale\", new Class<?>[]{Float.class}, Float.valueOf(1f))"));
        assertTrue(trafficLights.contains("profile.trafficLightCardColor")
                || renderer.contains("profile.trafficLightCardColor"));
        assertTrue(trafficLights.contains("normalizedCardColor"));
        assertTrue(trafficLights.contains("resolvedCardColor()"));
        assertTrue(trafficLights.contains("applyConfiguredCardColor"));
        assertTrue(trafficLights.contains("backgroundPaintPrimary$delegate"));
        assertTrue(trafficLights.contains("setLegColor"));
        assertTrue(trafficLights.contains("if (cardColor.isEmpty())"));
        assertTrue(trafficLights.contains("Float.valueOf(zIndex - .01f)"));
        assertEquals(trafficLights.indexOf("MapObjectLayerFactory.create(map"),
                trafficLights.lastIndexOf("MapObjectLayerFactory.create(map"));
        assertTrue(trafficLights.contains("traffic_light_leg_size"));
        assertTrue(trafficLights.contains("traffic_light_bg_primary"));
        assertTrue(trafficLights.contains("new PointF(offsetX / width, offsetY / height)"));
        for (String legName : new String[]{"LEFT_CENTER", "RIGHT_CENTER", "BOTTOM_LEFT",
                "BOTTOM_RIGHT", "TOP_LEFT", "TOP_RIGHT", "BOTTOM_CENTER", "TOP_CENTER"}) {
            assertTrue(trafficLights.contains("\"" + legName + "\".equals(legName)"));
        }
        assertTrue(trafficLights.contains("setSignal"));
        assertTrue(trafficLights.contains("setTime"));
        assertTrue(trafficLights.contains("createTexture"));
        assertTrue(trafficLights.contains("getAnchor"));
        assertTrue(trafficLights.contains("setLegPlacement"));
        assertTrue(trafficLights.contains("placement.legName"));
        assertTrue(trafficLights.contains("scalePercent / 100f"));
        assertTrue(trafficLights.contains(
                "float safeScale = Math.max(.01f, textureScale)"));
        assertTrue(trafficLights.contains("floatValue() * safeScale"));
        assertFalse(trafficLights.contains(
                "Enum.valueOf((Class<? extends Enum>) legClass, \"NONE\")"));
        assertTrue(trafficLights.contains(
                "compactTrafficLightBitmap(light, placement.legName)"));
        assertTrue(trafficLights.contains("copyRenderableLights"));
        assertTrue(trafficLights.contains("target.add(candidate)"));
        assertFalse(trafficLights.contains("target.size() >= MAX_LIGHTS"));
        assertFalse(trafficLights.contains("MIN_SEPARATION_METERS"));
        assertFalse(trafficLights.contains("selectSeparatedLights"));
        assertFalse(trafficLights.contains("distanceMeters(candidate"));
        assertTrue(trafficLights.contains("light.secondsLeft < 0 ? null"));
        assertTrue(trafficLights.contains("secondsLeft"));
        assertFalse(trafficLights.contains("drawTrafficLight"));
        assertFalse(mainMap.contains("TrafficLightMapLayer"));
        assertFalse(mainMap.contains("SpeedBumpMapLayer"));
        assertFalse(mainMap.contains("CameraDirectionMapLayer"));
        assertFalse(mainMap.contains("LaneGuidanceMapLayer"));
        assertTrue(cursor.contains(
                "int size = Math.max(8, Math.round(baseSize * requestedScale))"));
        assertTrue(cursor.contains("Float.valueOf(1f)"));
        assertFalse(cursor.contains("Float.valueOf(scalePercent / 100f)"));
        assertTrue(renderer.contains("createDestinationBitmap() already renders"));
        assertTrue(renderer.contains("profile.destinationScalePercent / 100f"));
        assertTrue(renderer.contains("destinationImageProvider = null"));
        assertTrue(renderer.contains("destinationIconBitmap = null"));
        assertTrue(cameraDirections.contains("YANDEX_FRESH_MS = 3_000L"));
        assertTrue(cameraDirections.contains("SOURCE_HUD_SPEED"));
        assertTrue(cameraDirections.contains("mergeIntoNearbyHudSpeed"));
        assertTrue(cameraDirections.contains("addOrMergeDuplicate"));
        assertTrue(cameraDirections.contains("CameraMarker.merge"));
        assertTrue(cameraDirections.contains("SAME_SOURCE_DUPLICATE_DISTANCE_METERS"));
        assertTrue(cameraDirections.contains("addPolygon"));
        assertTrue(cameraDirections.contains("LinearRing"));
        assertTrue(cameraDirections.contains("directionLengthPercent"));
        assertTrue(cameraDirections.contains("directionWidthPercent"));
        assertTrue(cameraDirections.contains("double[] farCenter"));
        assertTrue(cameraDirections.contains("directionDegrees - 90d"));
        assertTrue(cameraDirections.contains("directionDegrees + 90d"));
        assertTrue(cameraDirections.contains("opaqueRgb(nextDirectionColor"));
        assertTrue(cameraDirections.contains("directionOpacityPercent"));
        assertTrue(cameraDirections.contains("No direction supplied means exactly one sign"));
        assertTrue(cameraDirections.contains("ImageProvider"));
        assertTrue(cameraDirections.contains("createCameraBitmap"));
        assertTrue(cameraDirections.contains("camera.speedLimit > 0"));
        assertTrue(cameraDirections.contains("STANDARD_SIGN_RED"));
        assertTrue(cameraDirections.contains("contentWidth"));
        assertTrue(cameraDirections.contains("diameter - overlap"));
        assertTrue(cameraDirections.contains("new_pin_alerts_lanecamera_40"));
        assertTrue(cameraDirections.contains("new_pin_alerts_crossroad_camera_40"));
        assertFalse(cameraDirections.contains("badgeSize"));
        assertFalse(cameraDirections.contains("badgeCx"));
        assertFalse(cameraDirections.contains("drawCameraGlyph"));
        assertTrue(cameraDirections.contains("canvas.drawCircle(speedCx, cy, radius, paint)"));
        assertFalse(cameraDirections.contains("visibleControlTags"));
        assertFalse(cameraDirections.contains("drawControlGlyph"));
        assertFalse(cameraDirections.contains("Path pin"));
        assertFalse(cameraDirections.contains("canvas.drawText(\"H\""));
        assertTrue(cameraDirections.contains("One compact marker"));
        assertTrue(speedBumps.contains("getSpeedBumps"));
        assertTrue(speedBumps.contains("pointByPolylinePosition"));
        assertTrue(speedBumps.contains("mapkit_styling_automotive_route_speed_bump"));
        assertTrue(speedBumps.contains("MapObjectLayerFactory.IGNORE"));
        assertTrue(speedBumps.contains("Float.valueOf(1f)"));
        assertTrue(speedBumps.contains("scalePercent / 100f"));
        assertTrue(speedBumps.contains("placementCoordinator.reserveFixed"));
        assertTrue(speedBumps.contains("isPassed(marker.speedBump)"));
        assertFalse(speedBumps.contains("getSpeedLimits"));
        assertFalse(speedBumps.contains("NavigationLayer"));
        assertTrue(laneGuidance.contains("FRESH_MS = 1_500L"));
        assertTrue(laneGuidance.contains("LaneSignBalloonTextureFactory"));
        assertTrue(laneGuidance.contains("LaneSignBalloon"));
        assertTrue(laneGuidance.contains("getMethod(\"createTexture\""));
        assertTrue(laneGuidance.contains("getBalloonGeometry"));
        assertTrue(laneGuidance.contains("getImageAnchor"));
        assertTrue(laneGuidance.contains("BalloonAnchor"));
        assertFalse(laneGuidance.contains("getMethod(\"createView\""));
        assertTrue(laneGuidance.contains("scalePercent / 100f"));
        assertTrue(laneGuidance.contains("RotationType"));
        assertTrue(laneGuidance.contains("NO_ROTATION"));
        assertTrue(laneGuidance.contains("setGeometry"));
        assertTrue(laneGuidance.contains("placementCoordinator.reserve"));
        assertTrue(overlayPlacement.contains("worldToScreen"));
        assertTrue(overlayPlacement.contains("overlapArea"));
        assertTrue(overlayPlacement.contains("ROUTE_APPROACH_SEGMENTS"));
        assertTrue(overlayPlacement.contains("ROUTE_FORWARD_WEIGHT"));
        assertTrue(overlayPlacement.contains("ROUTE_TURN_BONUS"));
        assertTrue(overlayPlacement.contains("routeOcclusion"));
        assertTrue(overlayPlacement.contains("projectedBend"));
        assertTrue(overlayPlacement.contains("clippedSegmentLength"));
        assertTrue(overlayPlacement.contains("SLOT_CHANGE_PENALTY"));
        assertTrue(overlayPlacement.contains("reserveCentered"));
        assertTrue(overlayPlacement.contains("new Candidate(.50f, .50f, \"CENTER\")"));
        assertTrue(cameraDirections.contains("placementCoordinator.reserveCentered"));
        assertTrue(overlayPlacement.contains("BOTTOM_CENTER"));
        assertTrue(renderer.contains("overlayPlacement.beginLayout()"));
        assertTrue(renderer.contains("overlayPlacement.updateRoute(routeEpoch, drivingRoute)"));
        assertTrue(renderer.contains("frame.routeSegmentPosition"));
        assertTrue(renderer.contains("laneGuidanceMapLayer.relayout()"));
        assertTrue(renderer.contains("trafficLightMapLayer.relayout()"));
        assertTrue(renderer.contains("cameraDirectionMapLayer.relayout()"));
        assertTrue(renderer.contains("speedBumpMapLayer.relayout()"));
        assertTrue(renderer.indexOf("cameraDirectionMapLayer.relayout()")
                < renderer.indexOf("speedBumpMapLayer.relayout()"));
        assertTrue(renderer.indexOf("speedBumpMapLayer.relayout()")
                < renderer.indexOf("laneGuidanceMapLayer.relayout()"));
        assertTrue(routeTurns.contains("createDefaultManeuverStyle"));
        assertTrue(routeTurns.contains("addManeuvers"));
        assertTrue(routeTurns.contains("applyManeuverStyle"));
        assertTrue(routeTurns.contains("ArrowManeuverStyle"));
        assertTrue(routeTurns.contains("boolean.class"));
        assertTrue(routeTurns.contains("visible);"));
        assertTrue(routeTurns.contains("discardExpiredSource();"));
        assertTrue(routeTurns.contains("addArrow"));
        assertTrue(routeTurns.contains("PolylinePosition"));
        assertTrue(routeTurns.contains("configuredColor(fillColor"));
        assertTrue(routeTurns.contains("configuredColor(outlineColor"));
        assertTrue(routeTurns.contains("number(source, \"getLength\", 80f) * lengthScale"));
        assertTrue(routeTurns.contains(
                "number(source, \"getTriangleHeight\", 16f) * headScale"));
        assertFalse(routeTurns.contains("outlineWidth * lengthScale"));
        assertFalse(routeTurns.contains("getOutlineWidth"));
        assertTrue(routeTurns.contains(
                "arrow body tied to the route's current stroke width"));
        assertTrue(routeTurns.contains("Math.max(10, Math.min(250, nextLengthPercent))"));
        assertTrue(routeTurns.contains("Math.max(10, Math.min(250, nextHeadSizePercent))"));
        assertFalse(routeTurns.contains("Canvas"));
        assertFalse(routeTurns.contains("createArrowBitmap"));
        assertTrue(renderer.contains("routeTurnMapLayer.attachRoute"));
        assertFalse(Files.exists(patchRoot.resolve("RouteStreetLabelMapLayer.java")));
        assertFalse(renderer.contains("routeStreetLabelMapLayer"));
        assertTrue(renderer.contains("MapObjectLayerFactory.MINOR"));
        assertFalse(renderer.contains("drivingRoute != activeRoute"));
        assertFalse(routeStyler.contains("Double.doubleToLongBits"));
        assertTrue(renderer.contains("applyStyleSlot"));
        assertTrue(renderer.contains("visibilityStyleJson"));
        assertTrue(renderer.contains("profile.roadsOnly"));
        assertTrue(renderer.contains("setTransparentBackgroundEnabled"));
        assertTrue(renderer.contains("profile.showModels && !profile.roadsOnly"));
        assertTrue(renderer.contains("profile.roadsOnly || !profile.showPois"));
        assertTrue(mapProfile.contains("ROAD_TAGS_JSON"));
        assertTrue(mapProfile.contains("road_surface"));
        assertTrue(mapProfile.contains("roadWidthPercent"));
        assertTrue(mapProfile.contains("stylers.append(\"\\\"scale\\\":\")"));
        assertTrue(mapProfile.contains("\\\"elements\\\":\\\"label.text\\\""));
        assertTrue(mapProfile.contains("routeLabelScalePercent / 100d"));
        assertTrue(mapProfile.contains("cameraDirectionLayerPriority"));
        assertTrue(mapProfile.contains("manualLayerPrioritiesEnabled"));
        assertTrue(mapProfile.contains("effectiveCameraPriority()"));
        assertTrue(renderer.contains("applySublayerOrder()"));
        assertTrue(renderer.contains("getSublayerManager"));
        assertFalse(renderer.contains("moveToEnd"));
        assertFalse(renderer.contains("automaticOrderRestored"));
        assertTrue(mapProfile.contains("laneGuidanceLayerPriority"));
        assertTrue(mapProfile.contains("static float layerZ"));
        assertTrue(routeStyler.contains("profile.routeWidthPercent / 100f"));
        assertTrue(mapProfile.contains("if (roadsOnly)"));
        assertTrue(mapProfile.contains("\\\"tags\\\":{\\\"none\\\":"));
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
        assertFalse(renderer.contains("applyProgressColors"));
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
        assertTrue(publisher.contains("CameraSpeedNormalizer.fromMapKitMetersPerSecond"));
        assertTrue(speedNormalizer.contains("metresPerSecond * MPS_TO_KMH"));
        assertTrue(speedNormalizer.contains("value * MPH_TO_KMH"));
        assertTrue(speedNormalizer.contains("fromExternal(double value, String rawUnit)"));
        assertTrue(publisher.contains("getActiveDirections"));
        assertTrue(publisher.contains("getInFace"));
        assertTrue(publisher.contains("getInBack"));
        assertTrue(publisher.contains("LaneGuidanceFrame"));
        assertTrue(publisher.contains("RouteTurnFrame"));
        assertTrue(publisher.contains("readRouteTurns("));
        assertTrue(publisher.contains("positionOnRoute"));
        assertTrue(publisher.contains("invoke(position, \"getPoint\")"));
        assertTrue(publisher.contains("invoke(position, \"heading\")"));
        assertTrue(publisher.contains("getTrafficLightsWithSignal"));
        assertTrue(publisher.contains("setMaxNumberOfUpcomingTrafficLights"));
        assertTrue(publisher.contains("MAX_UPCOMING_TRAFFIC_LIGHTS = 16"));
        assertTrue(publisher.contains("trafficLightIdentity("));
        assertTrue(publisher.contains("routeEpoch"));
        assertFalse(publisher.contains("if (routePosition == null) return result"));
        assertTrue(publisher.contains("catch (Throwable invalidLight)"));
        assertTrue(publisher.contains("activeRoute == null"));
        assertTrue(publisher.contains("Collections.unmodifiableList("));
        assertTrue(publisher.contains(
                "readTrafficLights(inputs.routePosition, activeRoute)"));
        assertTrue(publisher.contains("readEventRouteProgress(route, position)"));
        assertTrue(publisher.contains("eventProgress.segmentIndex"));
        assertTrue(publisher.contains("eventProgress.segmentPosition"));
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
        assertTrue(renderer.contains("0.000001d"));
        assertFalse(renderer.contains("> renderedRouteSegmentPosition + 0.05d"));
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
                200, 20_000, 12_300, 1_020, 600, 1_200,
                234_567L, 60, 350,
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
        assertEquals(600, restoredSnapshot.trafficJamDurationSeconds);
        assertEquals(1_200, restoredSnapshot.trafficJamDistanceMeters);
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
        assertTrue(elementTypes.contains("NAV_TRAFFIC_JAM"));
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
        assertTrue(publisher.contains("leftInTrafficJam"));
        assertTrue(publisher.contains("trafficJamDurationSeconds"));
        assertTrue(publisher.contains("trafficJamDistanceMeters"));
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
        assertTrue(runtime.contains("case NAV_TRAFFIC_JAM"));
        assertTrue(runtime.contains("\"Пробка на \" + navigation.trafficJamDuration"));
        assertTrue(canvas.contains("drawTrafficJamForecast"));
        assertTrue(canvas.contains(
                "if (!editor && item.options.optBoolean(\"hideWhenInactive\", false))"));
        assertTrue(canvas.contains(
                "if (!editor && item.options.optBoolean(\"hideWhenEmpty\", false))"));
        assertTrue(canvas.contains("Paint.SUBPIXEL_TEXT_FLAG"));
    }

    @Test public void sharpnessAuditHasNoRepeatedNavigationBitmapResize() throws Exception {
        Path project = projectRoot();
        Path navigator = navigatorModRoot();
        String trafficLights = read(navigator.resolve("TrafficLightMapLayer.java"));
        String lanes = read(navigator.resolve("LaneGuidanceMapLayer.java"));
        String speedBumps = read(navigator.resolve("SpeedBumpMapLayer.java"));
        String cursor = read(navigator.resolve("MapCursorStyler.java"));
        String renderer = read(navigator.resolve("HudMapRenderer.java"));
        String roadEvents = read(navigator.resolve("ScaledRoadEventStyleProvider.java"));
        String graphics = read(project.resolve("app/src/main/java/dezz/status/widget/launcher/"
                + "NavigationGraphicStore.java"));
        String audit = read(project.resolve("docs/NAVIGATION_SHARPNESS_AUDIT_RU.md"));

        for (String source : new String[]{trafficLights, lanes, speedBumps, cursor,
                renderer, roadEvents, graphics}) {
            assertFalse(source.contains("Bitmap.createScaledBitmap"));
        }
        assertTrue(trafficLights.contains("Float.valueOf(1f)"));
        assertTrue(lanes.contains("Float.valueOf(1f)"));
        assertTrue(speedBumps.contains("Float.valueOf(1f)"));
        assertTrue(cursor.contains("Float.valueOf(1f)"));
        assertTrue(renderer.contains("createDestinationBitmap() already renders"));
        assertTrue(roadEvents.contains("method.invoke(delegate, arguments)"));
        assertTrue(graphics.contains("parcelable bitmaps are rejected"));
        assertTrue(audit.contains("GATE-020"));
        assertTrue(audit.contains("GATE-032"));
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
        assertTrue(settings.contains("Скорость, тип и направление камер берутся только из"));
        assertTrue(settings.contains("Ручной порядок слоёв"));
        assertTrue(settings.contains("SliderField cameraDirectionLayerPriority = slider"));
        assertTrue(settings.contains("SliderField cameraDirectionLength = slider"));
        assertTrue(settings.contains("SliderField cameraDirectionWidth = slider"));
        assertTrue(settings.contains("ColorField cameraDirectionColor ="));
        assertTrue(settings.contains("Ширина основания треугольника"));
        assertFalse(settings.contains("Размер полупрозрачного направления камер"));
        assertTrue(settings.contains("SliderField cameraDirectionOpacity = slider"));
        assertTrue(settings.contains("SliderField trafficLightLayerPriority = slider"));
        assertFalse(settings.contains("SliderField routeTurnLayerPriority = slider"));
        assertTrue(settings.contains("SliderField laneGuidanceLayerPriority = slider"));
        assertTrue(settings.contains("SliderField cursorLayerPriority = slider"));
        assertTrue(settings.contains("Штатные названия улиц Яндекса"));
        assertTrue(settings.contains("рисует сам слой карты"));
        assertFalse(settings.contains("Названия улиц только на маршруте"));
        assertTrue(settings.contains("profile.roadsOnly = roadsOnly.isChecked()"));
        assertTrue(settings.contains("Цвет рекомендуемой полосы"));
        assertTrue(settings.contains("Красный сигнал ARGB"));
        assertTrue(settings.contains("Тяжёлая пробка ARGB"));
        assertTrue(settings.contains("SeekBar control = new SeekBar(this)"));
        assertTrue(settings.contains("SliderField fontSize = slider"));
        assertTrue(settings.contains("SliderField brightness = slider"));
        assertTrue(settings.contains("SliderField borderOpacity = slider"));
        assertTrue(settings.contains("SliderField zoom = slider"));
        assertTrue(settings.contains("SliderField fixedZoomLevel = slider"));
        assertTrue(settings.contains("SliderField focusX = slider"));
        assertTrue(settings.contains("SliderField width = slider"));
        assertTrue(settings.contains("SliderField columns = slider"));
        assertTrue(settings.contains("SliderField gap = slider"));
        assertTrue(settings.contains("SliderField marginBottom = slider"));
        assertFalse(settings.contains("SliderField buttonOpacity = slider"));
        assertFalse(windowSettings.contains("SliderField buttonOpacity = slider"));
        assertFalse(settings.contains("SliderField buttonSize = slider"));
        assertFalse(windowSettings.contains("SliderField buttonSize = slider"));
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

    @Test public void stockRoadLabelsAndCursorFollowCanonicalRouteState() throws Exception {
        Path navigator = navigatorModRoot();
        String publisher = read(navigator.resolve("NavigatorStatePublisher.java"));
        String renderer = read(navigator.resolve("HudMapRenderer.java"));
        String profile = read(navigator.resolve("NavigationMapProfile.java"));

        assertFalse(profile.contains("boolean routeStreetLabelsOnly"));
        assertTrue(profile.contains("routeStreetLabelsOnly"));
        assertTrue(profile.contains("roadColor"));
        assertTrue(profile.contains("MapKit styles require #RRGGBBAA"));
        assertTrue(profile.contains("\\\"elements\\\":\\\"label\\\""));
        assertTrue(profile.contains("\\\"elements\\\":\\\"label.text\\\""));
        assertTrue(profile.contains("routeLabelScalePercent / 100d"));
        assertTrue(publisher.contains("RoutePosition.getPoint()"));
        assertTrue(publisher.contains("invoke(routePosition, \"getPoint\")"));
        assertTrue(publisher.contains("ROUTE_MATCH_HOLD_MS = 2_500L"));
        assertTrue(publisher.contains("lastRouteMatchedLatitude"));
        assertTrue(publisher.contains("latitude = routeLatitude"));
        assertTrue(publisher.contains("longitude = routeLongitude"));
        assertTrue(publisher.contains("polylinePosition = invoke(routePosition, \"positionOnRoute\""));
        assertTrue(publisher.contains("closestPositionOnRoute(route, currentPoint)"));
        assertTrue(publisher.contains("CLOSEST_TO_RAW_POINT"));
        assertTrue(publisher.contains("geoDistanceMeters("));
        assertTrue(publisher.indexOf("positionOnRoute")
                < publisher.indexOf("polylinePosition = invoke(route, \"getPosition\")"));
        assertTrue(renderer.contains("MapObjectLayerFactory.MINOR"));
        assertFalse(renderer.contains("routeStreetLabelMapLayer"));
        assertFalse(publisher.contains("readRouteStreetLabels"));
        assertFalse(Files.exists(navigator.resolve("RouteStreetLabelMapLayer.java")));
    }

    @Test public void routeProgressIsReversibleWithoutRecreatingGeometry() throws Exception {
        String renderer = read(navigatorModRoot().resolve("HudMapRenderer.java"));
        assertTrue(renderer.contains("routeProgressChanged(progress)"));
        assertTrue(renderer.contains("applyRouteProgressMask(line, progress)"));
        assertTrue(renderer.contains("Subpolyline"));
        assertTrue(renderer.contains("Collections.emptyList()"));
        assertTrue(renderer.contains("cancels the previous mask"));
        assertTrue(renderer.contains("fullRoute(route)"));
        assertTrue(renderer.contains("The cursor-owned RoutePosition is reversible"));
        assertFalse(renderer.contains("remainingRoute("));
        assertFalse(renderer.contains("isBehindRenderedProgress"));
        assertFalse(renderer.contains("ROUTE_GEOMETRY_INTERVAL_MS"));
        assertFalse(renderer.contains("\"setGeometry\""));
        assertFalse(renderer.contains("BACKWARD_PROGRESS_CONFIRMATIONS"));
        assertFalse(renderer.contains("pendingBackwardConfirmations"));
    }

    @Test public void externalMapResizePublishesANewViewportGenerationWithoutDetachGap()
            throws Exception {
        Path root = projectRoot();
        String composite = read(root.resolve(
                "app/src/main/java/dezz/status/widget/hud/HudCompositeView.java"));
        String panel = read(root.resolve(
                "app/src/main/java/dezz/status/widget/instrument/InstrumentPanelView.java"));
        String endpoint = read(root.resolve("app/src/main/java/dezz/status/widget/navigation/"
                + "NavigationHudEndpointService.java"));
        String renderer = read(navigatorModRoot().resolve("HudMapRenderer.java"));

        assertTrue(composite.contains("leasedSurface != null && leasedTexture == texture"));
        assertTrue(composite.contains("immutable creation dimensions"));
        assertTrue(composite.contains("if (generation < 0L)"));
        assertTrue(endpoint.contains("SURFACE_RESIZE_SETTLE_MS = 80L"));
        assertTrue(endpoint.contains("resizedExistingSurface"));
        assertTrue(endpoint.contains("++nextSurfaceGeneration"));
        assertTrue(endpoint.contains("immutable per generation"));
        assertTrue(endpoint.contains("sendLatestHudSurface"));
        assertFalse(endpoint.contains("current.width = width"));
        assertFalse(endpoint.contains("volatile int width"));
        assertTrue(renderer.contains("if (detachedGeneration < generation) return"));
        assertTrue(panel.contains("replaceLeaseIfReady()"));
        assertTrue(panel.contains("Keep the producer lease until the View/Surface"));
        assertFalse(panel.contains("else revokeLease();"));
    }

    @Test public void hudSpeedWakeIsBoundedAndYandexCameraFallbackIsIndependent()
            throws Exception {
        Path root = projectRoot();
        String client = read(root.resolve("app/src/main/java/dezz/status/widget/navigation/"
                + "HudSpeedCameraBridgeClient.java"));
        String endpoint = read(root.resolve("app/src/main/java/dezz/status/widget/navigation/"
                + "NavigationHudEndpointService.java"));
        String widget = read(root.resolve(
                "app/src/main/java/dezz/status/widget/WidgetService.java"));
        String bridge = read(root.resolve("hud-speed-bridge/src/main/java/air/StrelkaSD/bridge/"
                + "HudSpeedCameraBridgeService.java"));
        String cameraLayer = read(navigatorModRoot().resolve("CameraDirectionMapLayer.java"));

        assertTrue(client.contains("0L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L"));
        assertTrue(client.contains("dueRuntimeWakeAttempt()"));
        assertTrue(client.contains("hudRuntimeRunning"));
        assertTrue(client.contains("publishEmpty()"));
        assertTrue(bridge.contains("startForegroundService(runtime)"));
        assertTrue(bridge.contains("air.StrelkaSD.MainService"));
        assertTrue(bridge.contains("startFromReceiver"));
        assertTrue(bridge.contains("catch (RuntimeException unavailable)"));
        assertFalse(bridge.contains("startActivity("));
        assertTrue(endpoint.contains("OPTIONAL_HUD_SPEED_BOOTSTRAP_MS = 135_000L"));
        assertTrue(endpoint.contains("return START_NOT_STICKY"));
        assertTrue(widget.indexOf("startForeground(NOTIFICATION_ID, createNotification())")
                < widget.indexOf("startOptionalHudSpeedBootstrap(this)"));

        assertTrue(cameraLayer.contains("private boolean yandexEnabled"));
        assertTrue(cameraLayer.contains("private boolean externalEnabled"));
        assertTrue(cameraLayer.contains("if (externalEnabled && latestExternalSampleElapsedMs"));
        assertTrue(cameraLayer.contains("if (yandexEnabled && latestRouteActive"));
        assertTrue(cameraLayer.contains("latestExternal = Collections.emptyList()"));
        assertTrue(cameraLayer.contains("latestYandex = Collections.emptyList()"));
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
