/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

import dezz.status.widget.automation.AutomationState;

public final class DriverPanelStylePolicyTest {
    @Test public void panelScenarioOverridesAndRemovalRestoresProfile() throws Exception {
        AutomationState override = AutomationState.fromJson(new JSONObject()
                .put("background_color", "#FF010203")
                .put("border_color", "#FF040506")
                .put("border_width", 7));
        DriverPanelStylePolicy.PanelStyle styled = DriverPanelStylePolicy.panel(
                "#FF101010", "#00000000", 0, override);
        assertEquals("#FF010203", styled.backgroundColor);
        assertEquals("#FF040506", styled.borderColor);
        assertEquals(7, styled.borderWidthPx);

        DriverPanelStylePolicy.PanelStyle restored = DriverPanelStylePolicy.panel(
                "#FF101010", "#00000000", 0, AutomationState.missing());
        assertEquals("#FF101010", restored.backgroundColor);
        assertEquals(0, restored.borderWidthPx);
    }

    @Test public void iconScenarioWinsLiveAndRemovalRestoresLive() throws Exception {
        AutomationState override = AutomationState.fromJson(new JSONObject()
                .put("icon_tint", "#FF00FF00")
                .put("icon_background_color", "#FF0000FF")
                .put("icon_outline_color", "#FFFFFFFF")
                .put("icon_outline_width", 3));
        DriverPanelStylePolicy.IconStyle styled = DriverPanelStylePolicy.icon(
                "#FFFF0000", "#FF111111", override);
        assertEquals("#FF00FF00", styled.tint);
        assertEquals("#FF0000FF", styled.backgroundColor);
        assertEquals(3, styled.outlineWidthPx);

        DriverPanelStylePolicy.IconStyle restored = DriverPanelStylePolicy.icon(
                "#FFFF0000", "#FF111111", AutomationState.missing());
        assertEquals("#FFFF0000", restored.tint);
        assertEquals("#FF111111", restored.backgroundColor);
        assertEquals(0, restored.outlineWidthPx);
    }
}
