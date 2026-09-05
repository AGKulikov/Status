/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, per-MapWindow filter. A live route is not proof of event membership. */
final class RoadEventVisibility {
    private final Map<String, String> modes;
    private final boolean routeActive;

    RoadEventVisibility(Map<String, String> modes, boolean routeActive) {
        this.modes = Collections.unmodifiableMap(new LinkedHashMap<>(modes));
        this.routeActive = routeActive;
    }

    boolean matches(Map<String, String> nextModes, boolean nextRouteActive) {
        return routeActive == nextRouteActive && modes.equals(nextModes);
    }

    boolean allows(Iterable<?> tags, boolean onRoute) {
        if (tags == null) return false;
        for (Object tag : tags) {
            String mode = modes.get(String.valueOf(tag));
            if ("ALWAYS".equals(mode)
                    || ("ROUTE_ONLY".equals(mode) && routeActive && onRoute)) return true;
        }
        return false;
    }
}
