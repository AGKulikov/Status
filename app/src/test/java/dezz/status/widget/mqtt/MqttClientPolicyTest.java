/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public final class MqttClientPolicyTest {
    @Test public void onlyStableSessionResetsAccumulatedReconnectDelay() {
        MqttClient.ReconnectBackoff backoff = new MqttClient.ReconnectBackoff();

        assertEquals(1_000L, backoff.takeDelay());
        assertEquals(2_000L, backoff.takeDelay());
        assertEquals(4_000L, backoff.takeDelay());

        backoff.onConnectionEnded(29_999L);
        assertEquals(8_000L, backoff.takeDelay());

        backoff.onConnectionEnded(30_000L);
        assertEquals(1_000L, backoff.takeDelay());
    }

    @Test public void socketMaintenanceCadenceIsBoundedByKeepAlive() {
        assertEquals(1_000, MqttClient.socketReadTimeoutMillis(1));
        assertEquals(5_000, MqttClient.socketReadTimeoutMillis(10));
        assertEquals(5_000, MqttClient.socketReadTimeoutMillis(600));
    }

    @Test public void pingMustReceiveItsOwnResponseBeforeDeadline() {
        MqttClient.KeepAliveState state = new MqttClient.KeepAliveState(10);
        state.reset(1_000L);

        assertFalse(state.shouldPing(5_999L));
        assertTrue(state.shouldPing(6_000L));
        state.onPingSent(6_000L);
        assertTrue(state.isPingOutstanding());

        // An unrelated publish proves there was traffic but is not a PINGRESP acknowledgement.
        state.onTraffic(9_000L);
        assertTrue(state.isPingOutstanding());
        assertFalse(state.hasTimedOut(15_999L));
        assertTrue(state.hasTimedOut(16_000L));

        state.onPingResponse(16_000L);
        assertFalse(state.isPingOutstanding());
        assertFalse(state.hasTimedOut(30_000L));
    }

    @Test public void matchingSuccessfulSubAckIsAccepted() throws Exception {
        MqttClient.validateSubAck(0x1234, 1, 0x90,
                new byte[] {0x12, 0x34, 0x01});
        MqttClient.validateSubAck(0x1234, 1, 0x90,
                new byte[] {0x12, 0x34, 0x00});
    }

    @Test public void rejectedOrMismatchedSubAckFailsConnection() {
        assertSubAckFails(0x1234, 1, 0x90, new byte[] {0x12, 0x34, (byte) 0x80});
        assertSubAckFails(0x1234, 1, 0x90, new byte[] {0x12, 0x35, 0x01});
        assertSubAckFails(0x1234, 0, 0x90, new byte[] {0x12, 0x34, 0x01});
        assertSubAckFails(0x1234, 1, 0x91, new byte[] {0x12, 0x34, 0x01});
    }

    private static void assertSubAckFails(int packetId, int qos, int header, byte[] body) {
        try {
            MqttClient.validateSubAck(packetId, qos, header, body);
            fail("Expected invalid SUBACK to be rejected");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
