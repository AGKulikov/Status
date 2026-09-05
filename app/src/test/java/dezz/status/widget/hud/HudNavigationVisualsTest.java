/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudNavigationVisualsTest {
    @Test public void everyPublicYandexActionHasAnExplicitVisual() {
        String[] actions = {
                "STRAIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "LEFT", "RIGHT",
                "HARD_LEFT", "HARD_RIGHT", "FORK_LEFT", "FORK_RIGHT",
                "UTURN_LEFT", "UTURN_RIGHT", "ENTER_ROUNDABOUT", "LEAVE_ROUNDABOUT",
                "BOARD_FERRY", "LEAVE_FERRY", "EXIT_LEFT", "EXIT_RIGHT", "FINISH",
                "WAYPOINT"
        };
        for (String action : actions) {
            assertTrue(action, HudNavigationVisuals.maneuver(action).shape
                    != HudNavigationVisuals.ManeuverShape.UNKNOWN);
        }
    }

    @Test public void everyPublicYandexLaneDirectionHasAnExplicitVisual() {
        String[] directions = {
                "LEFT180", "LEFT135", "LEFT90", "LEFT45", "STRAIGHT_AHEAD",
                "RIGHT45", "RIGHT90", "RIGHT135", "RIGHT180", "LEFT_FROM_RIGHT",
                "RIGHT_FROM_LEFT", "LEFT_SHIFT", "RIGHT_SHIFT"
        };
        for (String direction : directions) {
            assertTrue(direction, HudNavigationVisuals.lane(direction).shape
                    != HudNavigationVisuals.LaneShape.UNKNOWN);
        }
        assertEquals(HudNavigationVisuals.LaneShape.UNKNOWN,
                HudNavigationVisuals.lane("UNKNOWN_DIRECTION").shape);
    }

    @Test public void trafficLightArrowsKeepDirectionAndUturnMeaning() {
        assertEquals(-1, HudNavigationVisuals.trafficArrowDirection("LEFT"));
        assertEquals(1, HudNavigationVisuals.trafficArrowDirection("RIGHT"));
        assertEquals(0, HudNavigationVisuals.trafficArrowDirection("FORWARD"));
        assertTrue(HudNavigationVisuals.isTrafficUturn("UTURN_LEFT"));
        assertTrue(HudNavigationVisuals.isTrafficArrow("FORWARD"));
        assertTrue(!HudNavigationVisuals.isTrafficArrow("UNKNOWN"));
    }
}
