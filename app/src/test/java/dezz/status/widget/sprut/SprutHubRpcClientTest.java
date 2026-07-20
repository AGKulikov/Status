/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

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
                params, 17L, " token ", " serial ");

        assertEquals("2.0", request.getString("jsonrpc"));
        assertFalse(request.has("method"));
        assertSame(params, request.getJSONObject("params"));
        assertEquals(17L, request.getLong("id"));
        assertEquals("token", request.getString("token"));
        assertEquals("serial", request.getString("serial"));
    }

    @Test public void localAndCustomEnvelopeKeepsEmptyMethod() throws Exception {
        JSONObject request = SprutHubRpcClient.buildRequestEnvelope(
                SprutHubRpcClient.EnvelopeMode.LOCAL_OR_CUSTOM,
                new JSONObject().put("hub", new JSONObject()),
                23L, null, null);

        assertTrue(request.has("method"));
        assertEquals("", request.getString("method"));
        assertFalse(request.has("token"));
        assertFalse(request.has("serial"));
    }

    @Test public void blankSessionValuesAreOmittedFromEitherEnvelope() throws Exception {
        JSONObject request = SprutHubRpcClient.buildRequestEnvelope(
                SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                new JSONObject(), 1L, "  ", "");

        assertFalse(request.has("token"));
        assertFalse(request.has("serial"));
    }

    @Test public void normalizeUrlAddsDefaultPathBeforeModeSelection() {
        String normalized = SprutHubRpcClient.normalizeUrl("  wss://beta.spruthub.ru  ");

        assertEquals("wss://beta.spruthub.ru/spruthub", normalized);
        assertEquals(SprutHubRpcClient.EnvelopeMode.OFFICIAL_CLOUD,
                SprutHubRpcClient.envelopeModeForUrl(normalized));
    }
}
