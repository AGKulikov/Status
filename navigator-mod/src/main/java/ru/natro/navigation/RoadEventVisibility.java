/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            if (allowsTag(String.valueOf(tag), onRoute)) return true;
        }
        return false;
    }

    List<String> allowedTags(List<String> tags, boolean onRoute) {
        // Preserve the immutable frame list on the usual all-visible path.
        ArrayList<String> filtered = null;
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            if (!allowsTag(tag, onRoute)) {
                if (filtered == null) filtered = new ArrayList<>(tags.subList(0, i));
            } else if (filtered != null) {
                filtered.add(tag);
            }
        }
        return filtered == null ? tags : Collections.unmodifiableList(filtered);
    }

    private boolean allowsTag(String tag, boolean onRoute) {
        String mode = modes.get(tag);
        return "ALWAYS".equals(mode)
                || ("ROUTE_ONLY".equals(mode) && routeActive && onRoute);
    }
}
