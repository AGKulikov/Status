/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                "[{\"id\":\"tl-1\",\"signal\":\"GREEN\",\"secondsLeft\":12,"
                        + "\"arrow\":\"RIGHT\",\"distanceMeters\":80,"
                        + "\"latitude\":55.751,\"longitude\":37.617}]");
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
        assertEquals("tl-1", state.trafficLights.get(0).id);
        assertEquals(80, state.trafficLights.get(0).distanceMeters);
        assertEquals(55.751d, state.trafficLights.get(0).latitude, 0d);
        assertEquals("HARD", state.trafficRuns.get(1).type);
        assertTrue(state.hasDataFor(HudElementType.NAV_MANEUVER_ARROW));
        assertTrue(state.hasDataFor(HudElementType.NAV_LANES));
        assertTrue(state.hasDataFor(HudElementType.NAV_TRAFFIC_LIGHTS));
    }

    @Test public void inactiveRouteCannotLeakOldManeuverLanesOrTrafficLights() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                10, 3, 2_000, false, 55.75, 37.61, 30, 40,
                "RIGHT", "Старый поворот", "старый текст", "Тверская", "Дом",
                120, 10_000, 8_000, 600, 3_600_000, 60, 90,
                "[{\"highlightedDirection\":\"RIGHT90\","
                        + "\"directions\":[\"RIGHT90\"]}]",
                "[{\"signal\":\"RED\",\"secondsLeft\":9}]");

        HudNavigationState state = HudNavigationState.fromBridge(snapshot,
                new NavigationRouteGeometryV2(3, "stale", "[]"));

        assertFalse(state.routeActive);
        assertTrue(state.maneuverTitle.isEmpty());
        assertTrue(state.destination.isEmpty());
        assertTrue(state.laneItems.isEmpty());
        assertTrue(state.trafficLights.isEmpty());
        assertFalse(state.hasDataFor(HudElementType.NAV_MANEUVER_ARROW));
        assertFalse(state.hasDataFor(HudElementType.NAV_LANES));
        assertFalse(state.hasDataFor(HudElementType.NAV_TRAFFIC_LIGHTS));
        assertFalse(state.hasDataFor(HudElementType.NAV_ROUTE_GRAPHIC));
        assertTrue(state.hasDataFor(HudElementType.NAV_STREET));
        assertFalse(state.hasDataFor(HudElementType.NAV_JAM_PROGRESS));
    }

    @Test public void emptySignalShellsAndEmptyLanesAreRejected() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                11, 4, 3_000, true, 55.75, 37.61, 30, 40,
                "", "", "", "", "", -1, -1, -1, -1, 0, 0, -1,
                "[{\"kind\":\"UNKNOWN\",\"directions\":[]}]",
                "[{\"signal\":\"UNKNOWN\",\"secondsLeft\":-1},{}]");

        HudNavigationState state = HudNavigationState.fromBridge(snapshot, null);

        assertFalse(state.laneAvailable);
        assertFalse(state.trafficAvailable);
        assertTrue(state.laneItems.isEmpty());
        assertTrue(state.trafficLights.isEmpty());
    }

    @Test public void redAndYellowIsARealTrafficSignalRatherThanAnEmptyShell() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                12, 5, 3_500, true, 55.75, 37.61, 30, 40,
                "", "", "", "", "", -1, -1, -1, -1, 0, 0, -1,
                "[]", "[{\"signal\":\"RED_AND_YELLOW\",\"secondsLeft\":2}]");

        HudNavigationState state = HudNavigationState.fromBridge(snapshot, null);

        assertTrue(state.trafficAvailable);
        assertEquals("RED_AND_YELLOW", state.trafficLights.get(0).color);
        assertTrue(state.hasDataFor(HudElementType.NAV_TRAFFIC_LIGHTS));
    }

    @Test public void directSnapshotFreshnessFailsClosed() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                1, 1, 10_000, true, 55.75, 37.61, 0, 0,
                "", "", "", "", "", -1, -1, -1, -1,
                0, 0, -1, "[]", "[]");

        assertTrue(snapshot.isFreshAt(13_000, 3_000));
        assertFalse(snapshot.isFreshAt(13_001, 3_000));
        assertTrue(snapshot.isFreshAt(5_000, 3_000));
        assertFalse(snapshot.isFreshAt(4_999, 3_000));
    }
}
