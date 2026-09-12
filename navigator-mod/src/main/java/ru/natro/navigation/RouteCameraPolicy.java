/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Route membership and identity rules shared by camera inventory and rendering. */
final class RouteCameraPolicy {
    private RouteCameraPolicy() {}

    static boolean isControl(String tag) {
        return "SPEED_CONTROL".equals(tag) || "NO_STOPPING_CONTROL".equals(tag)
                || "LANE_CONTROL".equals(tag) || "ROAD_MARKING_CONTROL".equals(tag)
                || "MOBILE_CONTROL".equals(tag) || "CROSS_ROAD_CONTROL".equals(tag)
                || "TRAFFIC_CONTROL".equals(tag);
    }

    static boolean isAhead(int eventSegment, double eventFraction, int pointCount,
                           boolean progressValid, int segment, double fraction) {
        if (eventSegment < 0 || eventSegment >= pointCount - 1
                || !Double.isFinite(eventFraction) || eventFraction < 0 || eventFraction > 1) return false;
        return !progressValid || eventSegment > segment
                || (eventSegment == segment && eventFraction >= fraction);
    }

    static boolean sameSourceIdentity(String first, String second) {
        // Spatial proximity never proves that two sequential controls are the same object.
        return first != null && !first.isEmpty() && first.equals(second);
    }
}
