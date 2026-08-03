/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigationPanelConfigStoreTest {
    @Test public void roundTripPreservesPositionSpanScaleAndVisibility() {
        NavigationPanelConfig source = new NavigationPanelConfig();
        assertTrue(source.setPlacement(NavigationPanelConfig.MANEUVER,
                1, 4, 5, 2));
        source.setScale(NavigationPanelConfig.MANEUVER, 175);
        source.setEnabled(NavigationPanelConfig.TRAFFIC_LIGHT, false);

        NavigationPanelConfig restored = NavigationPanelConfigStore.decode(
                NavigationPanelConfigStore.encode(source).toString());
        NavigationPanelConfig.Element maneuver =
                restored.element(NavigationPanelConfig.MANEUVER);
        assertEquals(1, maneuver.column);
        assertEquals(4, maneuver.row);
        assertEquals(5, maneuver.columnSpan);
        assertEquals(2, maneuver.rowSpan);
        assertEquals(175, maneuver.scalePercent);
        assertFalse(restored.element(NavigationPanelConfig.TRAFFIC_LIGHT).enabled);
    }

    @Test public void builtInMissingFromSavedLayoutIsAppendedHidden() {
        String oldLayout = "{\"version\":1,\"gridColumns\":12,\"gridRows\":8,"
                + "\"elements\":[{\"id\":\"arrival\",\"enabled\":true,"
                + "\"scalePercent\":100,\"column\":0,\"row\":0,"
                + "\"columnSpan\":4,\"rowSpan\":1}]}";
        NavigationPanelConfig restored = NavigationPanelConfigStore.decode(oldLayout);
        assertTrue(restored.element(NavigationPanelConfig.ARRIVAL).enabled);
        assertFalse(restored.element(NavigationPanelConfig.MANEUVER_IMAGE).enabled);
        assertFalse(restored.element(NavigationPanelConfig.LANES_IMAGE).enabled);
    }

    @Test public void corruptJsonFallsBackToUsableDefaults() {
        NavigationPanelConfig restored = NavigationPanelConfigStore.decode("{oops");
        assertEquals(NavigationPanelConfig.DEFAULT_GRID_COLUMNS, restored.gridColumns);
        assertEquals(NavigationPanelConfig.DEFAULT_GRID_ROWS, restored.gridRows);
        assertTrue(restored.element(NavigationPanelConfig.ARRIVAL).enabled);
    }
}
