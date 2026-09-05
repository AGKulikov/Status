/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.navigation.NavigationRouteGeometryV2;
import dezz.status.widget.navigation.NavigationSnapshotV2;

public final class HudNavigationStateTest {
    @Test public void directSnapshotRequiresStockArtworkForTheArrowChannel() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                9, 2, 1_000, true, 55.75, 37.61, 30, 72,
                "RIGHT", "Направо", "на улицу", "Тверская", "Дом",
                250, 10_000, 7_500, 900, -1, -1, 3_600_000, 60, 500,
                "[{\"kind\":\"NORMAL\",\"highlightedDirection\":\"RIGHT90\","
                        + "\"directions\":[\"STRAIGHT_AHEAD\",\"RIGHT90\"]}]",
                "[{\"id\":\"tl-1\",\"signal\":\"GREEN\",\"secondsLeft\":12,"
                        + "\"arrow\":\"RIGHT\",\"distanceMeters\":80,"
                        + "\"latitude\":55.751,\"longitude\":37.617}]",
                "maneuver:2:4:500000:RIGHT", "Подольск",
                "[{\"kind\":\"TOPONYM\",\"text\":\"Подольск\","
                        + "\"bgColor\":\"#FF0B4DB5\","
                        + "\"textColor\":\"#FFFFFFFF\"}]",
                "EXIT_NUMBER", "2-й съезд", "", -1);
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
        assertEquals("maneuver:2:4:500000:RIGHT", state.maneuverIdentity);
        assertEquals("Подольск", state.maneuverNextRoad);
        assertEquals("Подольск", state.maneuverDirectionSigns.get(0).text);
        assertEquals("#FF0B4DB5", state.maneuverDirectionSigns.get(0).backgroundColor);
        assertEquals("EXIT_NUMBER", state.maneuverAuxiliaryType);
        assertEquals("2-й съезд", state.maneuverAuxiliaryText);
        assertFalse(state.hasDataFor(HudElementType.NAV_MANEUVER_ARROW));
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

    @Test public void directUnknownManeuverCannotShowAnOldTextGuessedArrow() {
        NavigationSnapshotV2 snapshot = new NavigationSnapshotV2(
                11, 4, 3_100, true, 55.75, 37.61, 30, 40,
                "UNKNOWN", "Старое направление", "", "", "", 200,
                5_000, 4_000, 600, 0, 0, -1, "[]", "[]");

        HudNavigationState state = HudNavigationState.fromBridge(snapshot, null);

        assertFalse(state.hasDataFor(HudElementType.NAV_MANEUVER_ARROW));
        assertFalse(state.hasDataFor(HudElementType.NAV_COMBINED));
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

    @Test public void nullableTrafficJamForecastDrivesOnlyItsOwnModule() {
        NavigationSnapshotV2 present = new NavigationSnapshotV2(
                13, 6, 4_000, true, 55.75, 37.61, 30, 40,
                "STRAIGHT", "Прямо", "", "", "", 100,
                9_000, 6_000, 1_200, 600, 1_200,
                5_000_000, 60, -1, "[]", "[]");
        HudNavigationState available = HudNavigationState.fromBridge(present, null);

        assertTrue(available.trafficJamAvailable);
        assertEquals("10 мин", available.trafficJamDuration);
        assertEquals("1.2 км", available.trafficJamDistance);
        assertTrue(available.hasDataFor(HudElementType.NAV_TRAFFIC_JAM));

        NavigationSnapshotV2 absent = new NavigationSnapshotV2(
                14, 6, 4_100, true, 55.75, 37.61, 30, 40,
                "STRAIGHT", "Прямо", "", "", "", 100,
                9_000, 6_000, 1_200, 5_000_000, 60, -1, "[]", "[]");
        HudNavigationState hidden = HudNavigationState.fromBridge(absent, null);

        assertFalse(hidden.trafficJamAvailable);
        assertFalse(hidden.hasDataFor(HudElementType.NAV_TRAFFIC_JAM));
    }
}
