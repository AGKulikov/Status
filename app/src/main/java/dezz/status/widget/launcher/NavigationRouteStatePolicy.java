/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Pure policy for deciding whether HOME should show live navigation instead of favorites. */
final class NavigationRouteStatePolicy {
    static final long ROUTE_STALE_MS = 30L * 60L * 1000L;

    private NavigationRouteStatePolicy() {}

    /**
     * A route is active only while the main route channel is fresh in the current Android boot.
     * Auxiliary lane, traffic-light and jam-image channels deliberately are not inputs here: they
     * can outlive a route or arrive independently and must never hide the favorite destinations.
     *
     * <p>{@code explicitStateStored} distinguishes a new explicit route state from a snapshot
     * written by an older app version. Legacy snapshots may be inferred from their main route
     * fields until they expire, which keeps an in-progress route visible across an app update.</p>
     */
    static boolean isRouteActive(boolean explicitStateStored, boolean explicitlyActive,
            boolean hasMainRouteEvidence, long routeUpdatedAt, long now,
            boolean currentBoot) {
        if (!currentBoot || !isFresh(routeUpdatedAt, now)) return false;
        return explicitStateStored ? explicitlyActive : hasMainRouteEvidence;
    }

    static boolean isFresh(long routeUpdatedAt, long now) {
        return routeUpdatedAt > 0L && now >= routeUpdatedAt
                && now - routeUpdatedAt <= ROUTE_STALE_MS;
    }
}
