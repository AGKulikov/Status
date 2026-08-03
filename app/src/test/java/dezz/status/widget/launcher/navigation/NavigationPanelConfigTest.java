/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class NavigationPanelConfigTest {
    @Test public void maneuverAndLaneBitmapsAreIndependentGridElements() {
        assertNotEquals(NavigationPanelConfig.MANEUVER_IMAGE,
                NavigationPanelConfig.LANES_IMAGE);
        assertNotNull(NavigationPanelConfig.spec(NavigationPanelConfig.MANEUVER_IMAGE));
        assertNotNull(NavigationPanelConfig.spec(NavigationPanelConfig.LANES_IMAGE));
    }

    @Test public void defaultEnabledLayoutDoesNotOverlap() {
        assertNoEnabledOverlap(new NavigationPanelConfig());
    }

    @Test public void dragIntoOccupiedCellDisplacesInsteadOfStacking() {
        NavigationPanelConfig config = new NavigationPanelConfig();
        NavigationPanelConfig.Element arrival =
                config.element(NavigationPanelConfig.ARRIVAL);
        NavigationPanelConfig.Element duration =
                config.element(NavigationPanelConfig.DURATION);
        int oldArrivalColumn = arrival.column;
        int oldArrivalRow = arrival.row;

        assertTrue(config.setPlacement(NavigationPanelConfig.ARRIVAL,
                duration.column, duration.row, duration.columnSpan, duration.rowSpan));
        assertEquals(oldArrivalColumn,
                config.element(NavigationPanelConfig.DURATION).column);
        assertEquals(oldArrivalRow,
                config.element(NavigationPanelConfig.DURATION).row);
        assertNoEnabledOverlap(config);
    }

    @Test public void contentScaleAndRectangleAreClampedSeparately() {
        NavigationPanelConfig config = new NavigationPanelConfig();
        config.setScale(NavigationPanelConfig.MANEUVER_IMAGE, 500);
        assertTrue(config.setPlacement(NavigationPanelConfig.MANEUVER_IMAGE,
                999, 999, 999, 999));
        NavigationPanelConfig.Element maneuver =
                config.element(NavigationPanelConfig.MANEUVER_IMAGE);
        assertEquals(NavigationPanelConfig.MAX_SCALE, maneuver.scalePercent);
        assertTrue(maneuver.column >= 0);
        assertTrue(maneuver.row >= 0);
        assertTrue(maneuver.column + maneuver.columnSpan <= config.gridColumns);
        assertTrue(maneuver.row + maneuver.rowSpan <= config.gridRows);
        assertNoEnabledOverlap(config);
    }

    @Test public void impossibleGridResizeRollsBackAtomically() {
        NavigationPanelConfig config = new NavigationPanelConfig();
        int columns = config.gridColumns;
        int rows = config.gridRows;
        assertFalse(config.setGridSize(1, 1));
        assertEquals(columns, config.gridColumns);
        assertEquals(rows, config.gridRows);
        assertNoEnabledOverlap(config);
    }

    private static void assertNoEnabledOverlap(NavigationPanelConfig config) {
        List<NavigationPanelConfig.Element> elements = config.enabledElements();
        for (int first = 0; first < elements.size(); first++) {
            for (int second = first + 1; second < elements.size(); second++) {
                assertFalse(elements.get(first).id + " overlaps " + elements.get(second).id,
                        NavigationPanelConfig.overlaps(
                                elements.get(first), elements.get(second)));
            }
        }
    }
}
