/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.navigation.NavigationRouteGeometryV2;
import dezz.status.widget.navigation.NavigationSnapshotV2;

public final class HudNavigationStateTest {
    @Test public void directSnapshotPopulatesEveryIndependentHudChannel() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                9, 2, 1_000, true, 55.75, 37.61, 30, 72,
                "RIGHT", "Направо", "на улицу", "Тверская", "Дом",
                250, 10_000, 7_500, 900, 3_600_000, 60, 500,
                "[{\"kind\":\"NORMAL\",\"highlightedDirection\":\"RIGHT90\","
                        + "\"directions\":[\"STRAIGHT_AHEAD\",\"RIGHT90\"]}]",
                "[{\"signal\":\"GREEN\",\"secondsLeft\":12,\"arrow\":\"RIGHT\"}]");
        NavigationRouteGeometryV2 route = new NavigationRouteGeometryV2(
                2, "polyline", "[{\"from\":0,\"to\":3,\"type\":\"FREE\"},"
                        + "{\"from\":3,\"to\":4,\"type\":\"HARD\"}]");

        HudNavigationState state = HudNavigationState.fromBridge(snapshot, route);

        assertTrue(state.direct);
        assertEquals("250 м", state.turnDistance);
        assertEquals("7.5 км", state.distance);
        assertEquals("500 м", state.laneDistance);
        assertEquals(0.25d, state.tripProgress, 0d);
        assertEquals("RIGHT90", state.laneItems.get(0).highlightedDirection);
        assertEquals("GREEN", state.trafficLights.get(0).color);
        assertEquals("12 с", state.trafficLights.get(0).countdown);
        assertEquals("HARD", state.trafficRuns.get(1).type);
    }
}
