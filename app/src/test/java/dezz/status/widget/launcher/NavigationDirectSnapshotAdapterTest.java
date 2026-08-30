/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.navigation.NavigationSnapshotV2;

public final class NavigationDirectSnapshotAdapterTest {
    @Test public void activeDirectFramePopulatesOnlyValidatedLiveModules() {
        NavigationSnapshotV2 source = new NavigationSnapshotV2(
                3, 7, 100_000L, true, 55.75, 37.61, 10, 50,
                "RIGHT", "Направо", "на улицу", "Тверская", "Дом",
                320, 12_000, 7_500, 900, 3_600_000L, 60, 450,
                "[{\"highlightedDirection\":\"RIGHT90\","
                        + "\"directions\":[\"STRAIGHT_AHEAD\",\"RIGHT90\"]}]",
                "[{\"id\":\"tl-1\",\"signal\":\"GREEN\","
                        + "\"secondsLeft\":12,\"arrow\":\"RIGHT\"},"
                        + "{\"id\":\"empty\",\"signal\":\"UNKNOWN\"}]");

        NavigationDataRepository.Snapshot result =
                NavigationDataRepository.fromDirectSnapshot(source, true);

        assertTrue(result.routeActive);
        assertEquals("7.5 км", result.distance.replace(',', '.'));
        assertEquals("15 мин", result.duration);
        assertEquals("320 м", result.turnDistance);
        assertEquals("60", result.speedLimit);
        assertTrue(result.laneAvailable);
        assertEquals("→", result.lanes);
        assertEquals("450 м", result.laneDistance);
        assertTrue(result.trafficAvailable);
        assertEquals(1, result.trafficLights.size());
        assertEquals("GREEN", result.trafficLights.get(0).color);
        assertEquals("12", result.trafficLights.get(0).countdown);
        assertNull(result.maneuverImage);
        assertNull(result.lanesImage);
    }

    @Test public void inactiveOrExpiredDirectFrameCannotLeakRoutePayload() {
        NavigationSnapshotV2 source = new NavigationSnapshotV2(
                4, 8, 100_000L, true, 55.75, 37.61, 10, 50,
                "LEFT", "Старый поворот", "старый текст", "Тверская", "Дом",
                120, 1_000, 900, 60, 3_600_000L, 80, 100,
                "[{\"highlightedDirection\":\"LEFT90\"}]",
                "[{\"signal\":\"RED\",\"secondsLeft\":8}]");

        NavigationDataRepository.Snapshot expired =
                NavigationDataRepository.fromDirectSnapshot(source, false);

        assertFalse(expired.routeActive);
        assertTrue(expired.maneuverTitle.isEmpty());
        assertTrue(expired.turnDistance.isEmpty());
        assertTrue(expired.lanes.isEmpty());
        assertFalse(expired.laneAvailable);
        assertTrue(expired.trafficLights.isEmpty());
        assertFalse(expired.trafficAvailable);
        assertTrue(expired.speedLimit.isEmpty());

        NavigationDataRepository.Snapshot awaiting =
                NavigationDataRepository.emptyDirectSnapshot();
        assertFalse(awaiting.routeActive);
        assertTrue(awaiting.distance.isEmpty());
        assertTrue(awaiting.trafficLights.isEmpty());
    }
}
