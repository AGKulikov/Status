/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LauncherActionsGridConfigTest {
    @Test public void legacyMigrationUsesExactRowMajorFirstFitThenAddAndSkipsHidden() {
        LauncherShortcutStore.Shortcut first = shortcut("first", true, 1, 1);
        LauncherShortcutStore.Shortcut second = shortcut("second", true, 1, 1);
        LauncherShortcutStore.Shortcut third = shortcut("third", true, 1, 1);
        LauncherShortcutStore.Shortcut hiddenWide = shortcut("hidden-wide", false, 2, 1);
        LauncherShortcutStore.Shortcut hiddenTall = shortcut("hidden-tall", false, 1, 2);

        LauncherActionsGridConfig config = LauncherActionsGridConfig.migrateLegacy(
                Arrays.asList(first, second, third, hiddenWide, hiddenTall), 3);

        assertPlacement(config, "first", 0, 0, 1, 1);
        assertPlacement(config, "second", 1, 0, 1, 1);
        assertPlacement(config, "third", 2, 0, 1, 1);
        assertPlacement(config, LauncherActionsGridConfig.ADD_TILE_ID, 0, 1, 1, 1);
        assertNull(config.placement("hidden-wide"));
        assertNull(config.placement("hidden-tall"));
        assertEquals(4, config.placements().size());
        assertNoOverlapOrOutOfBounds(config);
    }

    @Test public void migrationWithMixedSpansNeverOverlapsOrLeavesTheGrid() {
        List<LauncherShortcutStore.Shortcut> shortcuts = Arrays.asList(
                shortcut("wide", true, 2, 1),
                shortcut("tall", true, 1, 3),
                shortcut("full", true, 3, 1),
                shortcut("hidden-a", false, 2, 2),
                shortcut("hidden-b", false, 1, 1));

        LauncherActionsGridConfig config =
                LauncherActionsGridConfig.migrateLegacy(shortcuts, 3);

        assertEquals(3, config.columns);
        assertEquals(4, config.placements().size());
        assertNoOverlapOrOutOfBounds(config);
    }

    @Test public void firstEnableAllocatesHiddenShortcutAndLaterHidingKeepsItsCell() {
        LauncherShortcutStore.Shortcut visible = shortcut("visible", true, 1, 1);
        LauncherShortcutStore.Shortcut hidden = shortcut("hidden", false, 1, 1);
        List<LauncherShortcutStore.Shortcut> shortcuts =
                new ArrayList<>(Arrays.asList(visible, hidden));
        LauncherActionsGridConfig config =
                LauncherActionsGridConfig.migrateLegacy(shortcuts, 2);
        assertNull(config.placement(hidden.id));

        hidden.enabled = true;
        assertTrue(config.reconcile(shortcuts));
        LauncherActionsGridConfig.Placement reserved =
                config.placement(hidden.id).copy();

        hidden.enabled = false;
        assertFalse(config.reconcile(shortcuts));
        assertPlacement(config, hidden.id, reserved.column, reserved.row,
                reserved.columnSpan, reserved.rowSpan);
        LauncherShortcutStore.Shortcut added = shortcut("added", true, 1, 1);
        shortcuts.add(added);
        assertTrue(config.reconcile(shortcuts));
        assertPlacement(config, hidden.id, reserved.column, reserved.row,
                reserved.columnSpan, reserved.rowSpan);
        assertFalse(LauncherActionsGridConfig.overlaps(
                config.placement(hidden.id), config.placement(added.id)));
        assertNoOverlapOrOutOfBounds(config);
    }

    @Test public void collisionAndImpossibleGridShrinkAreTransactional() {
        LauncherActionsGridConfig config = new LauncherActionsGridConfig();
        config.columns = 2;
        config.rows = 1;
        config.put(new LauncherActionsGridConfig.Placement("left", 0, 0, 1, 1));
        config.put(new LauncherActionsGridConfig.Placement("right", 1, 0, 1, 1));
        LauncherActionsGridConfig before = config.copy();

        assertFalse(config.setPlacement("left", 1, 0, 1, 1));
        assertSameGeometry(before, config);

        assertFalse(config.setGridSize(1, 1));
        assertSameGeometry(before, config);
        assertNoOverlapOrOutOfBounds(config);
    }

    private static LauncherShortcutStore.Shortcut shortcut(
            String id, boolean enabled, int columnSpan, int rowSpan) {
        LauncherShortcutStore.Shortcut shortcut = new LauncherShortcutStore.Shortcut();
        shortcut.id = id;
        shortcut.enabled = enabled;
        shortcut.columnSpan = columnSpan;
        shortcut.rowSpan = rowSpan;
        return shortcut;
    }

    private static void assertPlacement(LauncherActionsGridConfig config, String id,
                                        int column, int row, int columnSpan, int rowSpan) {
        LauncherActionsGridConfig.Placement placement = config.placement(id);
        assertNotNull(id, placement);
        assertEquals(id + " column", column, placement.column);
        assertEquals(id + " row", row, placement.row);
        assertEquals(id + " columnSpan", columnSpan, placement.columnSpan);
        assertEquals(id + " rowSpan", rowSpan, placement.rowSpan);
    }

    private static void assertSameGeometry(LauncherActionsGridConfig expected,
                                           LauncherActionsGridConfig actual) {
        assertEquals(expected.columns, actual.columns);
        assertEquals(expected.rows, actual.rows);
        assertEquals(expected.gapPx, actual.gapPx);
        assertEquals(expected.placements().size(), actual.placements().size());
        for (LauncherActionsGridConfig.Placement expectedPlacement : expected.placements()) {
            assertPlacement(actual, expectedPlacement.id,
                    expectedPlacement.column, expectedPlacement.row,
                    expectedPlacement.columnSpan, expectedPlacement.rowSpan);
        }
    }

    private static void assertNoOverlapOrOutOfBounds(LauncherActionsGridConfig config) {
        List<LauncherActionsGridConfig.Placement> placements = config.placements();
        for (LauncherActionsGridConfig.Placement placement : placements) {
            assertTrue(placement.id + " starts before the grid", placement.column >= 0);
            assertTrue(placement.id + " starts before the grid", placement.row >= 0);
            assertTrue(placement.id + " exceeds columns",
                    placement.column + placement.columnSpan <= config.columns);
            assertTrue(placement.id + " exceeds rows",
                    placement.row + placement.rowSpan <= config.rows);
        }
        for (int first = 0; first < placements.size(); first++) {
            for (int second = first + 1; second < placements.size(); second++) {
                LauncherActionsGridConfig.Placement left = placements.get(first);
                LauncherActionsGridConfig.Placement right = placements.get(second);
                assertFalse(left.id + " overlaps " + right.id,
                        LauncherActionsGridConfig.overlaps(left, right));
            }
        }
    }
}
