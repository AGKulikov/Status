/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Stable visual vocabulary for the public Yandex maneuver, lane and traffic-light enums. */
final class HudNavigationVisuals {
    enum ManeuverShape {
        STRAIGHT, TURN, SLIGHT, HARD, FORK, UTURN, ROUNDABOUT,
        EXIT, FINISH, WAYPOINT, FERRY, UNKNOWN
    }

    enum LaneShape {
        STRAIGHT, TURN_45, TURN_90, TURN_135, UTURN, MERGE, SHIFT, UNKNOWN
    }

    static final class Maneuver {
        @NonNull final ManeuverShape shape;
        /** -1 left, 0 neutral, +1 right. */
        final int direction;

        Maneuver(@NonNull ManeuverShape shape, int direction) {
            this.shape = shape;
            this.direction = Integer.compare(direction, 0);
        }
    }

    static final class Lane {
        @NonNull final LaneShape shape;
        /** -1 left, 0 neutral, +1 right. */
        final int direction;

        Lane(@NonNull LaneShape shape, int direction) {
            this.shape = shape;
            this.direction = Integer.compare(direction, 0);
        }
    }

    private HudNavigationVisuals() {}

    @NonNull static Maneuver maneuver(String raw) {
        String value = normalized(raw);
        if ("STRAIGHT".equals(value)) return maneuver(ManeuverShape.STRAIGHT, 0);
        if ("SLIGHT_LEFT".equals(value)) return maneuver(ManeuverShape.SLIGHT, -1);
        if ("SLIGHT_RIGHT".equals(value)) return maneuver(ManeuverShape.SLIGHT, 1);
        if ("LEFT".equals(value) || "TURN_LEFT".equals(value)) {
            return maneuver(ManeuverShape.TURN, -1);
        }
        if ("RIGHT".equals(value) || "TURN_RIGHT".equals(value)) {
            return maneuver(ManeuverShape.TURN, 1);
        }
        if ("HARD_LEFT".equals(value)) return maneuver(ManeuverShape.HARD, -1);
        if ("HARD_RIGHT".equals(value)) return maneuver(ManeuverShape.HARD, 1);
        if ("FORK_LEFT".equals(value)) return maneuver(ManeuverShape.FORK, -1);
        if ("FORK_RIGHT".equals(value)) return maneuver(ManeuverShape.FORK, 1);
        if ("UTURN_LEFT".equals(value)) return maneuver(ManeuverShape.UTURN, -1);
        if ("UTURN_RIGHT".equals(value)) return maneuver(ManeuverShape.UTURN, 1);
        if ("ENTER_ROUNDABOUT".equals(value) || "LEAVE_ROUNDABOUT".equals(value)) {
            return maneuver(ManeuverShape.ROUNDABOUT, 1);
        }
        if ("EXIT_LEFT".equals(value)) return maneuver(ManeuverShape.EXIT, -1);
        if ("EXIT_RIGHT".equals(value)) return maneuver(ManeuverShape.EXIT, 1);
        if ("FINISH".equals(value)) return maneuver(ManeuverShape.FINISH, 0);
        if ("WAYPOINT".equals(value)) return maneuver(ManeuverShape.WAYPOINT, 0);
        if ("BOARD_FERRY".equals(value) || "LEAVE_FERRY".equals(value)) {
            return maneuver(ManeuverShape.FERRY, 0);
        }
        return maneuver(ManeuverShape.UNKNOWN, 0);
    }

    @NonNull static Lane lane(String raw) {
        String value = normalized(raw);
        if ("STRAIGHT_AHEAD".equals(value)) return lane(LaneShape.STRAIGHT, 0);
        if ("LEFT45".equals(value)) return lane(LaneShape.TURN_45, -1);
        if ("RIGHT45".equals(value)) return lane(LaneShape.TURN_45, 1);
        if ("LEFT90".equals(value)) return lane(LaneShape.TURN_90, -1);
        if ("RIGHT90".equals(value)) return lane(LaneShape.TURN_90, 1);
        if ("LEFT135".equals(value)) return lane(LaneShape.TURN_135, -1);
        if ("RIGHT135".equals(value)) return lane(LaneShape.TURN_135, 1);
        if ("LEFT180".equals(value)) return lane(LaneShape.UTURN, -1);
        if ("RIGHT180".equals(value)) return lane(LaneShape.UTURN, 1);
        if ("LEFT_FROM_RIGHT".equals(value)) return lane(LaneShape.MERGE, -1);
        if ("RIGHT_FROM_LEFT".equals(value)) return lane(LaneShape.MERGE, 1);
        if ("LEFT_SHIFT".equals(value)) return lane(LaneShape.SHIFT, -1);
        if ("RIGHT_SHIFT".equals(value)) return lane(LaneShape.SHIFT, 1);
        return lane(LaneShape.UNKNOWN, 0);
    }

    /** Traffic-light arrow uses the same direction convention as the vector renderer. */
    static int trafficArrowDirection(String raw) {
        String value = normalized(raw);
        if ("LEFT".equals(value) || "UTURN_LEFT".equals(value)) return -1;
        if ("RIGHT".equals(value)) return 1;
        return 0;
    }

    static boolean isTrafficUturn(String raw) {
        return "UTURN_LEFT".equals(normalized(raw));
    }

    static boolean isTrafficArrow(String raw) {
        String value = normalized(raw);
        return "FORWARD".equals(value) || "LEFT".equals(value)
                || "RIGHT".equals(value) || "UTURN_LEFT".equals(value);
    }

    @NonNull private static Maneuver maneuver(ManeuverShape shape, int direction) {
        return new Maneuver(shape, direction);
    }

    @NonNull private static Lane lane(LaneShape shape, int direction) {
        return new Lane(shape, direction);
    }

    @NonNull private static String normalized(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}
