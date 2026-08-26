/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test public void importedOtherDisplayCannotOverrideVerifiedHudId() throws Exception {
        HudPanelConfig value = HudPanelConfig.defaults();
        value.displayId = 4;
        value.displayUniqueId = "local:hud";
        value.displayName = "HUD";
        value.displayWidth = 728;
        value.displayHeight = 910;

        HudPanelConfig restored = HudPanelConfig.fromJson(value.toJson().toString());

        assertEquals(HudViewportPolicy.VERIFIED_DISPLAY_ID, restored.displayId);
        assertEquals("", restored.displayUniqueId);
        assertEquals(0, restored.displayWidth);
        assertEquals(0, restored.displayHeight);
        assertFalse(restored.elements.isEmpty());
    }

    @Test public void backdropRoundTripKeepsDecorationAndAlwaysDrawsBelowWidgets()
            throws Exception {
        HudPanelConfig value = HudPanelConfig.defaults();
        HudElementConfig backdrop = HudElementConfig.create(
                HudElementType.BACKDROP, 1, value.gridColumns, value.gridRows);
        backdrop.backgroundColor = "#FF123456";
        backdrop.backgroundOpacityPercent = 61;
        backdrop.cornerRadiusPx = 24;
        backdrop.borderColor = "#FFFFFFFF";
        backdrop.borderOpacityPercent = 70;
        backdrop.borderWidthPx = 3;
        backdrop.zIndex = 9_000;
        value.elements.add(backdrop);

        HudPanelConfig restored = HudPanelConfig.fromJson(value.toJson().toString());
        HudElementConfig decoded = null;
        for (HudElementConfig item : restored.elements) {
            if (item.type == HudElementType.BACKDROP) decoded = item;
        }

        assertTrue(decoded != null);
        assertEquals("#FF123456", decoded.backgroundColor);
        assertEquals(61, decoded.backgroundOpacityPercent);
        assertEquals(24, decoded.cornerRadiusPx);
        assertEquals(HudElementType.BACKDROP, restored.drawingOrder().get(0).type);
    }

    @Test public void ordinaryHudWidgetCannotRetainAutomaticBackground() {
        HudElementConfig clock = HudElementConfig.create(
                HudElementType.CLOCK, 1, 44, 18);
        clock.backgroundColor = "#FF0000FF";
        clock.backgroundOpacityPercent = 100;
        clock.normalize(44, 18);

        assertEquals("#00000000", clock.backgroundColor);
        assertEquals(0, clock.backgroundOpacityPercent);
    }

    @Test public void legacyFrameCopyMapIsDroppedWithoutLosingOtherElements() {
        HudPanelConfig restored = HudPanelConfig.fromJson("{"
                + "\"schema\":4,"
                + "\"gridColumns\":44,"
                + "\"gridRows\":18,"
                + "\"elements\":["
                + "{\"id\":\"legacy_map\",\"type\":\"NAV_MAP\"},"
                + "{\"id\":\"clock\",\"type\":\"CLOCK\"}"
                + "]"
                + "}");

        assertEquals(1, restored.elements.size());
        for (HudElementConfig item : restored.elements) {
            assertEquals(HudElementType.CLOCK, item.type);
        }
    }
}
