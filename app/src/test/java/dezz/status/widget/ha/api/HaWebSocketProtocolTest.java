/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class HaWebSocketProtocolTest {
    @Test public void buildsDocumentedAuthSubscriptionAndSnapshotCommands() throws Exception {
        JSONObject auth = HaWebSocketProtocol.buildAuth("fixture-secret-token");
        assertEquals("auth", auth.getString("type"));
        assertEquals("fixture-secret-token", auth.getString("access_token"));

        JSONObject subscription = HaWebSocketProtocol.buildStateChangedSubscription(4);
        assertEquals(4, subscription.getInt("id"));
        assertEquals("subscribe_events", subscription.getString("type"));
        assertEquals("state_changed", subscription.getString("event_type"));

        JSONObject states = HaWebSocketProtocol.buildGetStates(5);
        assertEquals(5, states.getInt("id"));
        assertEquals("get_states", states.getString("type"));
    }

    @Test public void parsesUpdateAndRemovalEvents() throws Exception {
        HaWebSocketProtocol.StateChange changed = HaWebSocketProtocol.parseStateChange(
                new JSONObject(HaApiFixtures.stateEvent("sensor.fixture_value", "42",
                        "2026-01-01T10:01:00Z")));
        assertEquals("sensor.fixture_value", changed.entityId());
        assertEquals("42", changed.newState().state());

        HaWebSocketProtocol.StateChange removed = HaWebSocketProtocol.parseStateChange(
                new JSONObject(HaApiFixtures.removalEvent("light.fixture_lamp",
                        "2026-01-01T10:02:00Z")));
        assertEquals("light.fixture_lamp", removed.entityId());
        assertNull(removed.newState());

        assertNull(HaWebSocketProtocol.parseStateChange(
                new JSONObject("{\"type\":\"event\",\"event\":{\"event_type\":\"timer\"}}")));
    }

    @Test public void derivesWebSocketUrlsAndNeverPrintsToken() {
        HaWebSocketConnector.Config local =
                new HaWebSocketConnector.Config("homeassistant.invalid:8123", "secret-value");
        assertEquals("ws://homeassistant.invalid:8123/api/websocket", local.webSocketUrl());
        assertFalse(local.toString().contains("secret-value"));
        assertTrue(local.toString().contains("<redacted>"));

        assertEquals("wss://example.invalid/ha/api/websocket",
                HaWebSocketConnector.Config.deriveWebSocketUrl("https://example.invalid/ha"));
        assertEquals("wss://example.invalid/api/websocket",
                HaWebSocketConnector.Config.deriveWebSocketUrl(
                        "wss://example.invalid/api/websocket"));
    }

    @Test public void reconnectScheduleIsCappedButUnlimited() {
        assertEquals(1, HaWebSocketConnector.reconnectDelaySeconds(0));
        assertEquals(2, HaWebSocketConnector.reconnectDelaySeconds(1));
        assertEquals(5, HaWebSocketConnector.reconnectDelaySeconds(2));
        assertEquals(10, HaWebSocketConnector.reconnectDelaySeconds(3));
        assertEquals(30, HaWebSocketConnector.reconnectDelaySeconds(4));
        assertEquals(30, HaWebSocketConnector.reconnectDelaySeconds(1000));
    }
}
