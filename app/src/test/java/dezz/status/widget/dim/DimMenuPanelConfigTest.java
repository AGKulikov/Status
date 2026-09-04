/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DimMenuPanelConfigTest {
    @Test public void stockLikeDefaultsUseTheLowerNavigationTabArea() {
        DimMenuPanelConfig value = new DimMenuPanelConfig();
        assertEquals(2, value.displayId);
        assertEquals(740, value.x);
        assertEquals(720, value.y);
        assertEquals(414, value.width);
        assertEquals(284, value.height);
        assertEquals(48, value.rowHeightPx);
        assertEquals(0, value.contentPaddingPx);
        assertEquals(0, value.rowGapPx);
        assertEquals(14, value.titleTextSizeSp);
        assertEquals(24, value.rowTextSizeSp);
        assertEquals("#00FFFFFF", value.backgroundColor);
        assertEquals("#FF197BC5", value.selectedColor);
        assertEquals("#FF6C7984", value.mutedTextColor);
        assertTrue(value.mnaviStyle);
        assertFalse(value.showIcons);
        assertEquals(2, DimMenuPanelConfig.STOCK_NAVIGATION_TAB);
        assertTrue(value.navigationTabOnly);
        assertTrue(value.hideForControlCenter);
        assertTrue(value.hideForMnav);
        assertFalse(value.closeAfterAction);
    }

    @Test public void jsonRoundTripPreservesUserLayoutAndBehavior() {
        DimMenuPanelConfig source = new DimMenuPanelConfig();
        source.x = 612;
        source.y = 688;
        source.width = 530;
        source.title = "Дом и маршруты";
        source.visibleRows = 6;
        source.invertScroll = true;
        source.closeAfterAction = true;
        source.panelOpacityPercent = 73;
        source.selectedColor = "#AA123456";
        source.mnaviStyle = false;
        DimMenuPanelConfig restored = DimMenuPanelConfig.fromJson(source.toJson());
        assertEquals(612, restored.x);
        assertEquals(688, restored.y);
        assertEquals(530, restored.width);
        assertEquals("Дом и маршруты", restored.title);
        assertEquals(6, restored.visibleRows);
        assertEquals(73, restored.panelOpacityPercent);
        assertEquals("#AA123456", restored.selectedColor);
        assertFalse(restored.mnaviStyle);
        assertTrue(restored.invertScroll);
        assertTrue(restored.closeAfterAction);
    }

    @Test public void importedGeometryIsBoundedBeforeWindowManagerSeesIt() {
        DimMenuPanelConfig value = DimMenuPanelConfig.fromJson(
                "{\"version\":2,\"displayId\":999,\"width\":1,\"height\":99999,"
                        + "\"visibleRows\":0,\"panelOpacityPercent\":0,"
                        + "\"backgroundColor\":\"bad\"}");
        assertEquals(32, value.displayId);
        assertEquals(220, value.width);
        assertEquals(1200, value.height);
        assertEquals(1, value.visibleRows);
        assertEquals(10, value.panelOpacityPercent);
        assertEquals("#00FFFFFF", value.backgroundColor);
    }

    @Test public void legacyAppearanceMigratesToMnaviWithoutMovingThePanel() {
        DimMenuPanelConfig value = DimMenuPanelConfig.fromJson(
                "{\"version\":1,\"x\":612,\"y\":688,\"width\":530,"
                        + "\"backgroundColor\":\"#FF11151B\"}");
        assertEquals(612, value.x);
        assertEquals(688, value.y);
        assertEquals(530, value.width);
        assertEquals("#00FFFFFF", value.backgroundColor);
        assertEquals("#FF197BC5", value.selectedColor);
        assertTrue(value.mnaviStyle);
    }
}
