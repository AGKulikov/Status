/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LauncherSafeAreaPolicyTest {
    @Test public void runningTopRowUsesTheLowerPhysicalBoundaryWithoutDoubleCounting() {
        assertEquals(88, LauncherSafeAreaPolicy.topInset(
                24, true, true, true, 88));
    }

    @Test public void disabledFloatingOrStoppedWidgetUsesOnlySystemInset() {
        assertEquals(24, LauncherSafeAreaPolicy.topInset(
                24, false, true, true, 88));
        assertEquals(24, LauncherSafeAreaPolicy.topInset(
                24, true, false, true, 88));
        assertEquals(24, LauncherSafeAreaPolicy.topInset(
                24, true, true, false, 88));
    }

    @Test public void invalidInputsNeverProduceNegativeInset() {
        assertEquals(0, LauncherSafeAreaPolicy.topInset(
                -20, true, true, true, -50));
    }

    @Test public void localTopClimateUsesLargestBoundaryInsteadOfAddingRows() {
        LauncherSafeAreaPolicy.Insets result = LauncherSafeAreaPolicy.insets(
                0, 24, 0, 0,
                true, true, true, 88,
                true, 1, 180);

        assertEquals(0, result.left);
        assertEquals(180, result.top);
        assertEquals(0, result.right);
        assertEquals(0, result.bottom);
    }

    @Test public void localClimateSupportsEveryNonTopEdge() {
        LauncherSafeAreaPolicy.Insets bottom = LauncherSafeAreaPolicy.insets(
                4, 8, 12, 16,
                false, false, false, 0,
                true, 0, 180);
        assertEquals(4, bottom.left);
        assertEquals(8, bottom.top);
        assertEquals(12, bottom.right);
        assertEquals(180, bottom.bottom);

        LauncherSafeAreaPolicy.Insets left = LauncherSafeAreaPolicy.insets(
                40, 8, 12, 16,
                false, false, false, 0,
                true, 2, 180);
        assertEquals(180, left.left);

        LauncherSafeAreaPolicy.Insets right = LauncherSafeAreaPolicy.insets(
                4, 8, 220, 16,
                false, false, false, 0,
                true, 3, 180);
        assertEquals(220, right.right);
    }

    @Test public void onlyHonestLocalFallbackTriggersHomeClimateMargins() {
        assertTrue(LauncherSafeAreaPolicy.isLocalClimateReservation("reserved_local"));
        assertFalse(LauncherSafeAreaPolicy.isLocalClimateReservation("reserved"));
        assertFalse(LauncherSafeAreaPolicy.isLocalClimateReservation("reserved_vendor"));
        assertFalse(LauncherSafeAreaPolicy.isLocalClimateReservation("fallback"));
        assertFalse(LauncherSafeAreaPolicy.isLocalClimateReservation(null));
        assertTrue(LauncherSafeAreaPolicy.isLocalClimateReservation(
                "reserved_local", 1, 1));
        assertFalse(LauncherSafeAreaPolicy.isLocalClimateReservation(
                "reserved_local", 1, 0));
    }
}
