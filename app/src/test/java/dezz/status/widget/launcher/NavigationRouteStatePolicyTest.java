/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavigationRouteStatePolicyTest {
    private static final long NOW = 2_000_000L;

    @Test public void explicitFreshRouteIsActiveWithoutSummaryFields() {
        assertTrue(NavigationRouteStatePolicy.isRouteActive(true, true, false,
                NOW - 1_000L, NOW, true));
    }

    @Test public void legacyFreshMainRouteEvidenceMigratesAsActive() {
        assertTrue(NavigationRouteStatePolicy.isRouteActive(false, false, true,
                NOW - 1_000L, NOW, true));
    }

    @Test public void auxiliaryChannelsCannotInventAnActiveRoute() {
        assertFalse(NavigationRouteStatePolicy.isRouteActive(false, false, false,
                NOW - 1_000L, NOW, true));
    }

    @Test public void routeEndedSignalWinsOverResidualEvidence() {
        assertFalse(NavigationRouteStatePolicy.isRouteActive(true, false, true,
                NOW - 1_000L, NOW, true));
    }

    @Test public void clearedRouteIsInactive() {
        assertFalse(NavigationRouteStatePolicy.isRouteActive(false, false, false,
                0L, NOW, true));
    }

    @Test public void staleRouteFallsBackToFavorites() {
        assertTrue(NavigationRouteStatePolicy.isRouteActive(true, true, false,
                NOW - NavigationRouteStatePolicy.ROUTE_STALE_MS, NOW, true));
        assertFalse(NavigationRouteStatePolicy.isRouteActive(true, true, false,
                NOW - NavigationRouteStatePolicy.ROUTE_STALE_MS - 1L, NOW, true));
    }

    @Test public void clockRollbackDoesNotResurrectRoute() {
        assertFalse(NavigationRouteStatePolicy.isRouteActive(true, true, false,
                NOW + 1L, NOW, true));
    }

    @Test public void previousBootRouteIsAlwaysInactive() {
        assertFalse(NavigationRouteStatePolicy.isRouteActive(true, true, true,
                NOW - 1_000L, NOW, false));
    }
}
