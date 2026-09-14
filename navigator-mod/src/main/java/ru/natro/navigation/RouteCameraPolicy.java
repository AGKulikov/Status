/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.ArrayList;
import java.util.List;

/** Route membership and identity rules shared by camera inventory and rendering. */
final class RouteCameraPolicy {
    private RouteCameraPolicy() {}

    static boolean isControl(String tag) {
        return "SPEED_CONTROL".equals(tag) || "NO_STOPPING_CONTROL".equals(tag)
                || "LANE_CONTROL".equals(tag) || "ROAD_MARKING_CONTROL".equals(tag)
                || "MOBILE_CONTROL".equals(tag) || "CROSS_ROAD_CONTROL".equals(tag)
                || "TRAFFIC_CONTROL".equals(tag);
    }

    static boolean isCameraRecord(Iterable<?> tags) {
        for (Object tag : tags) if (isControl(String.valueOf(tag))) return true;
        return false;
    }

    /** POLICE attached to this same control record is its stock camera alias, not another pin. */
    static boolean isReplacedCameraTag(String tag) {
        return isControl(tag) || "POLICE".equals(tag);
    }

    /** A road speed value alone is not evidence that this event controls speed. */
    static boolean showSpeed(List<String> tags, int speedLimit) {
        return speedLimit > 0 && tags.contains("SPEED_CONTROL");
    }

    /**
     * Navigator 30.3's PlatformIconsProvider composes camera controls separately from the
     * max-priority ordinary-pin StyleProvider. LANE and ROAD_MARKING share its lane artwork;
     * neither removes an independently present SPEED_CONTROL. Keep one copy of each plate.
     */
    static List<String> detailDrawables(List<String> tags) {
        ArrayList<String> result = new ArrayList<>();
        for (String tag : tags) {
            String image = null;
            if ("LANE_CONTROL".equals(tag) || "ROAD_MARKING_CONTROL".equals(tag)) {
                image = "new_pin_alerts_lanecamera_40";
            } else if ("CROSS_ROAD_CONTROL".equals(tag)) {
                image = "new_pin_alerts_crossroad_camera_40";
            } else if ("NO_STOPPING_CONTROL".equals(tag)) {
                image = "new_pin_alerts_camera_stop_40";
            }
            if (image != null && !result.contains(image)) result.add(image);
        }
        return result;
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
