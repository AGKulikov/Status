/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Boundary tests for compact and split-pane settings layouts. */
public final class SettingsResponsiveLayoutPolicyTest {
    @Test
    public void splitPaneStartsAtExactMinimumWidth() {
        assertFalse(SettingsResponsiveLayoutPolicy.useSplitPane(
                SettingsResponsiveLayoutPolicy.MIN_SPLIT_WIDTH_DP - 1));
        assertTrue(SettingsResponsiveLayoutPolicy.useSplitPane(
                SettingsResponsiveLayoutPolicy.MIN_SPLIT_WIDTH_DP));
    }

    @Test
    public void compactLayoutUsesTheWholeAvailableWidth() {
        assertEquals(480, SettingsResponsiveLayoutPolicy.sidebarWidthDp(480));
        assertEquals(SettingsResponsiveLayoutPolicy.MIN_SPLIT_WIDTH_DP - 1,
                SettingsResponsiveLayoutPolicy.sidebarWidthDp(
                        SettingsResponsiveLayoutPolicy.MIN_SPLIT_WIDTH_DP - 1));
    }

    @Test
    public void splitSidebarClampsAtMinimumPreferredAndMaximum() {
        assertEquals(SettingsResponsiveLayoutPolicy.MIN_SIDEBAR_WIDTH_DP,
                SettingsResponsiveLayoutPolicy.sidebarWidthDp(
                        SettingsResponsiveLayoutPolicy.MIN_SPLIT_WIDTH_DP));
        assertEquals(300, SettingsResponsiveLayoutPolicy.sidebarWidthDp(1000));
        assertEquals(SettingsResponsiveLayoutPolicy.MAX_SIDEBAR_WIDTH_DP,
                SettingsResponsiveLayoutPolicy.sidebarWidthDp(1200));
        assertEquals(SettingsResponsiveLayoutPolicy.MAX_SIDEBAR_WIDTH_DP,
                SettingsResponsiveLayoutPolicy.sidebarWidthDp(2400));
    }

    @Test
    public void splitSidebarAlwaysPreservesContentAndItsOwnBounds() {
        for (int width = SettingsResponsiveLayoutPolicy.MIN_SPLIT_WIDTH_DP;
             width <= 2400; width++) {
            int sidebar = SettingsResponsiveLayoutPolicy.sidebarWidthDp(width);
            assertTrue(sidebar >= SettingsResponsiveLayoutPolicy.MIN_SIDEBAR_WIDTH_DP);
            assertTrue(sidebar <= SettingsResponsiveLayoutPolicy.MAX_SIDEBAR_WIDTH_DP);
            assertTrue(width - sidebar
                    >= SettingsResponsiveLayoutPolicy.MIN_CONTENT_WIDTH_DP);
        }
    }
}
