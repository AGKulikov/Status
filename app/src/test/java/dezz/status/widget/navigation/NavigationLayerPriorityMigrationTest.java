/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NavigationLayerPriorityMigrationTest {
    @Test public void newDefaultKeepsOrdinaryLightsBelowOtherPlacemarks() {
        NavigationIntegrationConfig config = NavigationIntegrationConfig.fromJson("{}");
        for (NavigationIntegrationConfig.MapProfile map : new NavigationIntegrationConfig.MapProfile[]{
                config.hudMap, config.clusterMap}) {
            assertEquals(20, map.routeTrafficLightLayerPriority);
            assertTrue(map.routeTrafficLightLayerPriority < map.cameraDirectionLayerPriority);
            assertTrue(map.routeTrafficLightLayerPriority < map.speedBumpLayerPriority);
            assertEquals(70, map.trafficLightLayerPriority);
        }
    }

    @Test public void exactPreviousPresetMigratesBothMapsAndPreservesOtherSettings() throws Exception {
        NavigationIntegrationConfig old = new NavigationIntegrationConfig();
        old.hudMap.routeTrafficLightLayerPriority = 68;
        old.clusterMap.routeTrafficLightLayerPriority = 68;
        old.hudMap.manualLayerPrioritiesEnabled = true;
        old.hudMap.routeTrafficLightScalePercent = 135;
        NavigationIntegrationConfig updated = NavigationIntegrationConfig.fromJson(old.toJson().toString());
        assertEquals(20, updated.hudMap.routeTrafficLightLayerPriority);
        assertEquals(20, updated.clusterMap.routeTrafficLightLayerPriority);
        assertTrue(updated.hudMap.manualLayerPrioritiesEnabled);
        assertEquals(135, updated.hudMap.routeTrafficLightScalePercent);
        assertEquals(70, updated.hudMap.trafficLightLayerPriority);
    }

    @Test public void editedPrioritiesSurviveUpdateIndependently() throws Exception {
        NavigationIntegrationConfig old = new NavigationIntegrationConfig();
        old.hudMap.manualLayerPrioritiesEnabled = true;
        old.hudMap.routeTrafficLightLayerPriority = 95;
        old.clusterMap.manualLayerPrioritiesEnabled = true;
        old.clusterMap.routeTrafficLightLayerPriority = 68;
        old.clusterMap.cameraDirectionLayerPriority = 45;
        NavigationIntegrationConfig updated = NavigationIntegrationConfig.fromJson(old.toJson().toString());
        assertEquals(95, updated.hudMap.routeTrafficLightLayerPriority);
        assertEquals(68, updated.clusterMap.routeTrafficLightLayerPriority);
        assertEquals(45, updated.clusterMap.cameraDirectionLayerPriority);
    }
}
