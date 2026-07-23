/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Pure policy for deciding whether HOME should show live navigation instead of favorites. */
final class NavigationRouteStatePolicy {
    /**
     * Every supported live route source updates substantially faster than this. Keeping the
     * former thirty-minute window allowed one orphaned notification or a stand-alone speed-limit
     * broadcast to hide favorite destinations long after navigation had ended.
     */
    static final long ROUTE_STALE_MS = 3L * 60L * 1000L;

    private NavigationRouteStatePolicy() {}

    /**
     * A route is active only while the main route channel is fresh in the current Android boot.
     * Auxiliary lane, traffic-light and jam-image channels deliberately are not inputs here: they
     * can outlive a route or arrive independently and must never hide the favorite destinations.
     *
     * <p>{@code confirmedStateStored} distinguishes a snapshot written by a version which
     * separates primary route state from auxiliary speed/lane channels. Legacy snapshots may be
     * inferred only from their main route fields until they expire. This both keeps a real
     * in-progress route visible across an update and discards legacy speed-limit-only false
     * positives immediately.</p>
     */
    static boolean isRouteActive(boolean confirmedStateStored, boolean explicitlyActive,
            boolean hasMainRouteEvidence, long routeUpdatedAt, long now,
            boolean currentBoot) {
        if (!currentBoot || !isFresh(routeUpdatedAt, now)) return false;
        return confirmedStateStored ? explicitlyActive : hasMainRouteEvidence;
    }

    static boolean isFresh(long routeUpdatedAt, long now) {
        return routeUpdatedAt > 0L && now >= routeUpdatedAt
                && now - routeUpdatedAt <= ROUTE_STALE_MS;
    }

    /**
     * Only route summary/maneuver text is primary evidence. Speed limits, lane data and route
     * graphics can be published while free-driving and must never activate the route panel.
     */
    static boolean hasPrimaryEvidence(String arrival, String duration, String distance,
            String maneuverTitle, String maneuverText, String maneuverSubtext) {
        return present(arrival) || present(duration) || present(distance)
                || present(maneuverTitle) || present(maneuverText)
                || present(maneuverSubtext);
    }

    /**
     * A negative producer flag is authoritative. A positive flag still needs actual route
     * evidence because some head-unit publishers keep route_active=true on free-driving
     * speed-limit packets. A maneuver bitmap is accepted as evidence for publishers which split
     * its pixels from the accompanying text.
     */
    static boolean monjaroRouteActive(boolean explicitStateStored, boolean explicitlyActive,
            boolean hasParsedRoute, String maneuverTitle, String maneuverText,
            String maneuverSubtext, boolean hasManeuverImage) {
        if (explicitStateStored && !explicitlyActive) return false;
        return hasParsedRoute || hasPrimaryEvidence("", "", "", maneuverTitle,
                maneuverText, maneuverSubtext) || hasManeuverImage;
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
