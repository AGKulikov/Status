/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import okhttp3.Request;

public final class SprutHubRpcClientTest {
    @Test public void selectsCloudEnvelopeOnlyForOfficialCloudHosts() {
        assertEquals(SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                SprutHubRpcClient.envelopeModeForUrl(
                        "wss://beta.spruthub.ru/spruthub"));
        assertEquals(SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                SprutHubRpcClient.envelopeModeForUrl(
                        "wss://WEB.SPRUTHUB.RU:443/spruthub"));

        assertEquals(SprutHubRpcClient.EnvelopeMode.LOCAL_OR_CUSTOM,
                SprutHubRpcClient.envelopeModeForUrl(
                        "ws://192.168.1.2/spruthub"));
        assertEquals(SprutHubRpcClient.EnvelopeMode.LOCAL_OR_CUSTOM,
                SprutHubRpcClient.envelopeModeForUrl(
                        "wss://sprut-proxy.example/spruthub"));
        assertEquals(SprutHubRpcClient.EnvelopeMode.LOCAL_OR_CUSTOM,
                SprutHubRpcClient.envelopeModeForUrl(
                        "wss://beta.spruthub.ru.example/spruthub"));
    }

    @Test public void officialCloudEnvelopeOmitsMethod() throws Exception {
        JSONObject params = new JSONObject().put("room",
                new JSONObject().put("list", new JSONObject()));

        JSONObject request = SprutHubRpcClient.buildRequestEnvelope(
                SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                params, 17L, " token ", " serial ", " client-id ");

        assertFalse(request.has("jsonrpc"));
        assertFalse(request.has("method"));
        assertSame(params, request.getJSONObject("params"));
        assertEquals(17L, request.getLong("id"));
        assertEquals("client-id", request.getString("cid"));
        assertEquals("token", request.getString("token"));
        assertEquals("serial", request.getString("serial"));
    }

    @Test public void localAndCustomEnvelopeKeepsEmptyMethod() throws Exception {
        JSONObject request = SprutHubRpcClient.buildRequestEnvelope(
                SprutHubRpcClient.EnvelopeMode.LOCAL_OR_CUSTOM,
                new JSONObject().put("hub", new JSONObject()),
                23L, null, null, "client-id");

        assertEquals("2.0", request.getString("jsonrpc"));
        assertTrue(request.has("method"));
        assertEquals("", request.getString("method"));
        assertFalse(request.has("cid"));
        assertFalse(request.has("token"));
        assertFalse(request.has("serial"));
    }

    @Test public void blankSessionValuesAreOmittedFromEitherEnvelope() throws Exception {
        JSONObject request = SprutHubRpcClient.buildRequestEnvelope(
                SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                new JSONObject(), 1L, "  ", "", "  ");

        assertFalse(request.has("cid"));
        assertFalse(request.has("token"));
        assertFalse(request.has("serial"));
    }

    @Test public void officialCloudAdvertisesJsonRpcSubprotocol() {
        Request cloud = SprutHubRpcClient.buildWebSocketRequest(
                "wss://beta.spruthub.ru/spruthub",
                SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD);
        Request local = SprutHubRpcClient.buildWebSocketRequest(
                "ws://192.168.1.2/spruthub",
                SprutHubRpcClient.EnvelopeMode.LOCAL_OR_CUSTOM);

        assertEquals("json-rpc", cloud.header("Sec-WebSocket-Protocol"));
        assertNull(local.header("Sec-WebSocket-Protocol"));
    }

    @Test public void buildsOfficialClientInfoAndDiagnosticCommandName() throws Exception {
        JSONObject params = SprutHubRpcClient.buildClientInfoParams("client-id");
        JSONObject info = params.getJSONObject("server").getJSONObject("clientInfo");

        assertEquals("client-id", info.getString("id"));
        assertEquals("Status Widget HA", info.getString("name"));
        assertEquals("CLIENT_DESKTOP", info.getString("type"));
        assertEquals("", info.getString("auth"));
        assertEquals("server.clientInfo", SprutHubRpcClient.commandName(params));
        assertEquals("room.list", SprutHubRpcClient.commandName(
                SprutProtocolAdapter.buildRoomListParams()));
    }

    @Test public void normalizesOfficialMethodEventsForTheExistingReducer() throws Exception {
        JSONObject update = new JSONObject().put("event", "EVENT_UPDATE")
                .put("characteristics", new org.json.JSONArray());
        JSONObject cloudEvent = new JSONObject()
                .put("method", "characteristic.event")
                .put("params", update);

        JSONObject normalized = SprutHubRpcClient.normalizeEventMessage(cloudEvent);

        assertSame(update, normalized.getJSONObject("event").getJSONObject("characteristic"));
        assertSame(cloudEvent, SprutHubRpcClient.normalizeEventMessage(cloudEvent
                .put("event", new JSONObject())));
    }

    @Test public void normalizeUrlAddsDefaultPathBeforeModeSelection() {
        String normalized = SprutHubRpcClient.normalizeUrl("  wss://beta.spruthub.ru  ");

        assertEquals("wss://beta.spruthub.ru/spruthub", normalized);
        assertEquals(SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                SprutHubRpcClient.envelopeModeForUrl(normalized));
    }
}
