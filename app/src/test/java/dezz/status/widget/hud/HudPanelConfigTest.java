/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class HudPanelConfigTest {
    @Test public void importedGeometryCannotOverrideHardwareSafetyBoundary() {
        HudPanelConfig value = HudPanelConfig.fromJson("{"
                + "\"schema\":1,"
                + "\"logicalWidth\":8192,"
                + "\"logicalHeight\":4320,"
                + "\"contentWidth\":8192,"
                + "\"contentHeight\":4320,"
                + "\"offsetX\":5000,"
                + "\"offsetY\":-3000,"
                + "\"elements\":[]"
                + "}");

        assertEquals(HudViewportPolicy.SAFE_WIDTH, value.logicalWidth);
        assertEquals(HudViewportPolicy.SAFE_HEIGHT, value.logicalHeight);
        assertEquals(HudViewportPolicy.SAFE_WIDTH, value.contentWidth);
        assertEquals(HudViewportPolicy.SAFE_HEIGHT, value.contentHeight);
        assertEquals(0, value.offsetX);
        assertEquals(0, value.offsetY);
    }

    @Test public void exactDisplayIdentityAndMeasuredSurfaceRoundTrip() throws Exception {
        HudPanelConfig value = HudPanelConfig.defaults();
        value.displayId = 4;
        value.displayUniqueId = "local:hud";
        value.displayName = "HUD";
        value.displayWidth = 728;
        value.displayHeight = 910;

        HudPanelConfig restored = HudPanelConfig.fromJson(value.toJson().toString());

        assertEquals(4, restored.displayId);
        assertEquals("local:hud", restored.displayUniqueId);
        assertEquals(728, restored.displayWidth);
        assertEquals(910, restored.displayHeight);
        assertFalse(restored.elements.isEmpty());
    }
}
