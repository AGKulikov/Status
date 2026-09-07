/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NavigationSignAppearanceTest {
    @Test public void oldConfigurationKeepsNativeAppearance() throws Exception {
        NavigationIntegrationConfig config = NavigationIntegrationConfig.fromJson("{}");
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(config.toJson().toString());
        for (NavigationIntegrationConfig.MapProfile map : new NavigationIntegrationConfig.MapProfile[]{
                restored.hudMap, restored.clusterMap}) {
            assertNull(map.laneGuidanceCardColor);
            assertNull(map.laneGuidanceSignsColor);
            assertNull(map.laneGuidanceBorderColor);
            assertEquals(0, map.laneGuidanceBorderWidthPx);
            assertEquals(-1, map.laneGuidanceCornerRadiusPx);
        }
    }

    @Test public void twoMapsKeepIndependentColorsAlphaAndGeometry() throws Exception {
        NavigationIntegrationConfig config = new NavigationIntegrationConfig();
        config.hudMap.laneGuidanceCardColor = "#80332211";
        config.hudMap.laneGuidanceSignsColor = "#abcdef";
        config.hudMap.laneGuidanceBorderColor = "#40010203";
        config.hudMap.laneGuidanceBorderWidthPx = 4;
        config.hudMap.laneGuidanceCornerRadiusPx = 0;
        config.clusterMap.laneGuidanceCardColor = "#FF994455";
        config.clusterMap.laneGuidanceSignsColor = "#00000000";
        config.clusterMap.laneGuidanceBorderWidthPx = 7;
        config.clusterMap.laneGuidanceCornerRadiusPx = 25;
        config.normalize();
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(config.toJson().toString());
        assertEquals("#80332211", restored.hudMap.laneGuidanceCardColor);
        assertEquals("#FFABCDEF", restored.hudMap.laneGuidanceSignsColor);
        assertEquals("#40010203", restored.hudMap.laneGuidanceBorderColor);
        assertEquals(4, restored.hudMap.laneGuidanceBorderWidthPx);
        assertEquals(0, restored.hudMap.laneGuidanceCornerRadiusPx);
        assertEquals("#FF994455", restored.clusterMap.laneGuidanceCardColor);
        assertEquals("#00000000", restored.clusterMap.laneGuidanceSignsColor);
        assertEquals(7, restored.clusterMap.laneGuidanceBorderWidthPx);
        assertEquals(25, restored.clusterMap.laneGuidanceCornerRadiusPx);
        assertNull(restored.mainMap.laneGuidanceCardColor);
    }

    @Test public void badImportsAreBoundedAndResetCanRestoreNative() throws Exception {
        JSONObject hud = new JSONObject().put("laneGuidanceCardColor", "invalid")
                .put("laneGuidanceSignsColor", "#010203")
                .put("laneGuidanceBorderWidthPx", 999)
                .put("laneGuidanceCornerRadiusPx", -999);
        NavigationIntegrationConfig config = NavigationIntegrationConfig.fromJson(
                new JSONObject().put("hudMap", hud).toString());
        assertNull(config.hudMap.laneGuidanceCardColor);
        assertEquals("#FF010203", config.hudMap.laneGuidanceSignsColor);
        assertEquals(24, config.hudMap.laneGuidanceBorderWidthPx);
        assertEquals(-1, config.hudMap.laneGuidanceCornerRadiusPx);
        config.hudMap.laneGuidanceSignsColor = null;
        config.hudMap.laneGuidanceBorderWidthPx = 0;
        NavigationIntegrationConfig restored = NavigationIntegrationConfig.fromJson(config.toJson().toString());
        assertNull(restored.hudMap.laneGuidanceSignsColor);
        assertEquals(0, restored.hudMap.laneGuidanceBorderWidthPx);
    }
}
