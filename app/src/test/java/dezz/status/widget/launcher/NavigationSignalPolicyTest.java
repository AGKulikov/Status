/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavigationSignalPolicyTest {
    @Test
    public void semicolonLaneFieldsRemainParallelRecords() {
        NavigationSignalPolicy.LaneSelection result = NavigationSignalPolicy.selectLanes(
                "left|through;through|right;right", "55.10;;55.30", "37.10;;37.30",
                "800;250;1200", "false;true;false", "", Double.NaN, Double.NaN,
                Double.NaN);

        assertEquals(3, result.records.size());
        assertEquals(0, result.records.get(0).rawIndex);
        assertEquals(1, result.records.get(1).rawIndex);
        assertTrue(Double.isNaN(result.records.get(1).latitude));
        assertNotNull(result.selected);
        assertEquals("through|right", result.selected.lanes);
        assertEquals(250.0d, result.selected.distanceMeters, 0.0d);
    }

    @Test
    public void currentJunctionIsStickyUntilMhudHandoffZone() {
        NavigationSignalPolicy.LaneSelection far = NavigationSignalPolicy.selectLanes(
                "new;current", "55.1;55.2", "37.1;37.2", "600;", "", "current",
                55.2d, 37.2d, 600.0d);
        assertEquals("current", far.selected.lanes);

        NavigationSignalPolicy.LaneSelection near = NavigationSignalPolicy.selectLanes(
                "new;current", "55.1;55.2", "37.1;37.2", "200;", "", "current",
                55.2d, 37.2d, 200.0d);
        assertEquals("new", near.selected.lanes);
    }

    @Test
    public void samePhaseAllowsClockDriftButRejectsCountdownJump() {
        long old = 10_000L;
        assertTrue(NavigationSignalPolicy.acceptsTransition("GREEN", "12", "", old,
                "GREEN", "10", 12_000L));
        assertFalse(NavigationSignalPolicy.acceptsTransition("GREEN", "12", "", old,
                "GREEN", "22", 12_000L));
    }

    @Test
    public void phaseChangesMustFollowPlausibleShortSequence() {
        long old = 10_000L;
        assertTrue(NavigationSignalPolicy.acceptsTransition("GREEN", "3", "", old,
                "YELLOW", "2", 11_000L));
        assertFalse(NavigationSignalPolicy.acceptsTransition("GREEN", "20", "", old,
                "RED", "19", 11_000L));
        assertTrue(NavigationSignalPolicy.acceptsTransition("YELLOW", "2", "GREEN", old,
                "RED", "15", 11_000L));
        assertFalse(NavigationSignalPolicy.acceptsTransition("YELLOW", "2", "GREEN", old,
                "GREEN", "15", 11_000L));
    }

    @Test
    public void windshieldOutranksUiOnlyForTheSameLogicalSlot() {
        assertTrue(NavigationSignalPolicy.sourceRank("yandex_windshield")
                > NavigationSignalPolicy.sourceRank("ui"));
        assertTrue(NavigationSignalPolicy.sourceMayReplace("ui", "yandex_windshield"));
        assertFalse(NavigationSignalPolicy.sourceMayReplace("yandex_windshield", "ui"));
        assertFalse(NavigationSignalPolicy.sourceRequiresTransitionValidation(
                "ui", "yandex_windshield"));
        assertTrue(NavigationSignalPolicy.sourceRequiresTransitionValidation(
                "yandex_windshield", "yandex_windshield"));
        assertTrue(NavigationSignalPolicy.sameSlot("", 2, "", 2));
        assertFalse(NavigationSignalPolicy.sameSlot("first", 2, "second", 2));
        assertTrue(NavigationSignalPolicy.sameSlot("same", 1, "same", 5));
    }
}
