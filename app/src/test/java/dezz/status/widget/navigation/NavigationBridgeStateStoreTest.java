/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class NavigationBridgeStateStoreTest {
    private static final String SESSION_A = "session-aaaaaaaa";
    private static final String SESSION_B = "session-bbbbbbbb";

    @After public void clear() {
        NavigationBridgeStateStore.endSession(NavigationBridgeStateStore.sessionId());
    }

    @Test public void snapshotsAreMonotonicInsideOneAuthenticatedSession() {
        NavigationBridgeStateStore.beginSession(SESSION_A);
        assertTrue(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(1, 7)));
        assertFalse(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(1, 7)));
        assertFalse(NavigationBridgeStateStore.publishSnapshot(SESSION_B, snapshot(2, 7)));
        assertTrue(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(2, 7)));
        assertEquals(2L, NavigationBridgeStateStore.snapshot().sequence);
    }

    @Test public void routeGeometryMustBelongToCurrentRouteEpoch() {
        NavigationBridgeStateStore.beginSession(SESSION_A);
        assertTrue(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(1, 11)));
        assertFalse(NavigationBridgeStateStore.publishRouteGeometry(SESSION_A,
                new NavigationRouteGeometryV2(10, "old", "[]")));
        assertTrue(NavigationBridgeStateStore.publishRouteGeometry(SESSION_A,
                new NavigationRouteGeometryV2(11, "current", "[]")));
        assertEquals("current",
                NavigationBridgeStateStore.routeGeometry().encodedPolyline);
        assertTrue(NavigationBridgeStateStore.publishRouteGeometry(SESSION_A,
                new NavigationRouteGeometryV2(11, "current", "[{\"type\":\"HARD\"}]")));
        assertEquals("[{\"type\":\"HARD\"}]",
                NavigationBridgeStateStore.routeGeometry().trafficSegmentsJson);

        assertTrue(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(2, 12)));
        assertNull(NavigationBridgeStateStore.routeGeometry());
    }

    @Test public void changingSessionDropsAllPreviousNavigationState() {
        NavigationBridgeStateStore.beginSession(SESSION_A);
        assertTrue(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(1, 2)));
        NavigationBridgeStateStore.beginSession(SESSION_B);
        assertNull(NavigationBridgeStateStore.snapshot());
        assertNull(NavigationBridgeStateStore.routeGeometry());
        assertFalse(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(2, 2)));
    }

    @Test public void listenersReceiveOnlyAcceptedStateChanges() {
        int[] changes = {0};
        NavigationBridgeStateStore.Listener listener = () -> changes[0]++;
        NavigationBridgeStateStore.addListener(listener);
        try {
            NavigationBridgeStateStore.beginSession(SESSION_A);
            assertTrue(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(1, 3)));
            assertFalse(NavigationBridgeStateStore.publishSnapshot(SESSION_A, snapshot(1, 3)));
            assertTrue(NavigationBridgeStateStore.publishRouteGeometry(SESSION_A,
                    new NavigationRouteGeometryV2(3, "route", "[]")));
            assertEquals(3, changes[0]);
        } finally {
            NavigationBridgeStateStore.removeListener(listener);
        }
    }

    private static NavigationSnapshotV2 snapshot(long sequence, long routeEpoch) {
        return new NavigationSnapshotV2(
                sequence, routeEpoch, 123L, true, 55.75d, 37.61d, 10d, 40d,
                "STRAIGHT", "Прямо", "", "Улица", "Финиш",
                100, 1_000, 120, 500_000L, 60, "[]", "[]");
    }
}
