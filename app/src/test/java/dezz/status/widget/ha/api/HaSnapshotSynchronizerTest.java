/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class HaSnapshotSynchronizerTest {
    @Test public void replaysBufferedEventsOnTopOfAuthoritativeSnapshot() throws Exception {
        HaSnapshotSynchronizer synchronizer = new HaSnapshotSynchronizer();
        synchronizer.beginSnapshot();
        assertTrue(synchronizer.buffer(event(HaApiFixtures.stateEvent(
                "sensor.fixture_temperature", "21.0", "2026-01-01T10:01:00Z"))));
        assertTrue(synchronizer.buffer(event(HaApiFixtures.removalEvent(
                "light.fixture_lamp", "2026-01-01T10:02:00Z"))));
        // A delayed older event must not regress the replayed value.
        assertTrue(synchronizer.buffer(event(HaApiFixtures.stateEvent(
                "sensor.fixture_temperature", "19.0", "2026-01-01T09:59:00Z"))));

        HaSnapshotSynchronizer.Completion completion = synchronizer.completeSnapshot(
                new JSONArray(HaApiFixtures.SNAPSHOT));
        assertEquals("21.0", completion.catalog()
                .find("sensor.fixture_temperature").state());
        assertNull(completion.catalog().find("light.fixture_lamp"));
        assertEquals(2, completion.replayedUpdates().size());
        assertEquals(0, synchronizer.bufferedCount());
    }

    @Test public void duplicateEventAlreadyInSnapshotDoesNotCreateSpuriousUpdate() throws Exception {
        JSONArray snapshot = new JSONArray("""
                [{"entity_id":"sensor.fixture_value","state":"2","attributes":{},
                  "last_updated":"2026-01-01T10:01:00Z"}]
                """);
        HaSnapshotSynchronizer synchronizer = new HaSnapshotSynchronizer();
        synchronizer.beginSnapshot();
        synchronizer.buffer(event(HaApiFixtures.stateEvent("sensor.fixture_value", "2",
                "2026-01-01T10:01:00Z")));

        HaSnapshotSynchronizer.Completion completion = synchronizer.completeSnapshot(snapshot);
        assertEquals("2", completion.catalog().find("sensor.fixture_value").state());
        assertTrue(completion.replayedUpdates().isEmpty());
    }

    @Test public void staleRemovalCannotDeleteARecreatedEntityFromSnapshot() throws Exception {
        JSONArray snapshot = new JSONArray("""
                [{"entity_id":"sensor.fixture_value","state":"recreated","attributes":{},
                  "last_updated":"2026-01-01T10:05:00Z"}]
                """);
        HaEntity oldState = HaEntity.fromJson(new JSONObject("""
                {"entity_id":"sensor.fixture_value","state":"old","attributes":{},
                 "last_updated":"2026-01-01T10:00:00Z"}
                """));
        HaSnapshotSynchronizer synchronizer = new HaSnapshotSynchronizer();
        synchronizer.beginSnapshot();
        synchronizer.buffer(new HaWebSocketProtocol.StateChange(
                "sensor.fixture_value", oldState, null, "2026-01-01T10:01:00Z"));

        HaSnapshotSynchronizer.Completion completion = synchronizer.completeSnapshot(snapshot);
        assertEquals("recreated", completion.catalog().find("sensor.fixture_value").state());
        assertTrue(completion.replayedUpdates().isEmpty());
    }

    @Test public void eventBacklogIsBoundedAndCannotPublishAnIncompleteSnapshot()
            throws Exception {
        HaSnapshotSynchronizer synchronizer = new HaSnapshotSynchronizer();
        synchronizer.beginSnapshot();
        HaWebSocketProtocol.StateChange change = event(HaApiFixtures.stateEvent(
                "sensor.fixture_temperature", "21.0", "2026-01-01T10:01:00Z"));
        for (int index = 0; index <= HaSnapshotSynchronizer.MAX_BUFFERED_CHANGES; index++) {
            assertTrue(synchronizer.buffer(change));
        }
        assertEquals(HaSnapshotSynchronizer.MAX_BUFFERED_CHANGES,
                synchronizer.bufferedCount());

        try {
            synchronizer.completeSnapshot(new JSONArray(HaApiFixtures.SNAPSHOT));
            fail("Expected an overflowing replay to reject the snapshot");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Too many"));
        }
        assertEquals(0, synchronizer.bufferedCount());
        assertFalse(synchronizer.snapshotPending());
    }

    private static HaWebSocketProtocol.StateChange event(String raw) throws Exception {
        return HaWebSocketProtocol.parseStateChange(new JSONObject(raw));
    }
}
