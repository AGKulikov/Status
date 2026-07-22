/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavigationCollectionPolicyTest {
    @Test public void collectionStopsWhenNoConfiguredConsumerExists() {
        assertFalse(NavigationCollectionPolicy.shouldCollect(false, false, false, false));
        assertFalse(NavigationCollectionPolicy.shouldCollect(false, false, true, false));
        assertTrue(NavigationCollectionPolicy.shouldCollect(true, false, false, false));
        assertTrue(NavigationCollectionPolicy.shouldCollect(false, true, false, false));
        assertTrue(NavigationCollectionPolicy.shouldCollect(false, false, true, true));
        assertFalse(NavigationCollectionPolicy.shouldCollect(false, false, false, true));
    }

    @Test public void callbackStormKeepsOneTrailingScanAtTheMinimumCadence() {
        long last = 10_000L;
        assertEquals(4_000L, NavigationCollectionPolicy.eventDelay(11_000L, last));
        assertEquals(NavigationCollectionPolicy.EVENT_DEBOUNCE_MS,
                NavigationCollectionPolicy.eventDelay(15_000L, last));
        assertEquals(NavigationCollectionPolicy.EVENT_DEBOUNCE_MS,
                NavigationCollectionPolicy.eventDelay(9_000L, last));
    }

    @Test public void activeWatchdogIsFasterThanIdleRecovery() {
        assertEquals(NavigationCollectionPolicy.ACTIVE_WATCHDOG_MS,
                NavigationCollectionPolicy.watchdogDelay(true));
        assertEquals(NavigationCollectionPolicy.IDLE_WATCHDOG_MS,
                NavigationCollectionPolicy.watchdogDelay(false));
        assertTrue(NavigationCollectionPolicy.watchdogDelay(false)
                > NavigationCollectionPolicy.watchdogDelay(true));
    }
}
