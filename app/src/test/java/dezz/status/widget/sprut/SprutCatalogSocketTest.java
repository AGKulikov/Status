/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import android.app.Application;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SprutCatalogSocketTest {
    private final MockWebServer server = new MockWebServer();
    private final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();
    private final CompletableFuture<WebSocket> peer = new CompletableFuture<>();
    private final CompletableFuture<Void> opened = new CompletableFuture<>();
    private final CompletableFuture<Void> observed = new CompletableFuture<>();
    private SprutHubRpcClient client;

    @Before public void connect() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String message) {
                peer.complete(socket);
                requests.add(message);
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }
        }));
        server.start();
        String url = server.url("/spruthub").toString().replaceFirst("^http", "ws");
        client = new SprutHubRpcClient(url, new SprutHubRpcClient.Listener() {
            @Override public void onOpen() { opened.complete(null); }
            @Override public void onEvent(JSONObject event) { observed.complete(null); }
            @Override public void onDisconnected(String detail) {}
        });
        client.connect();
        opened.get(10, TimeUnit.SECONDS);
        client.setSession("fixture-token", "fixture-hub");
    }

    @After public void close() throws Exception {
        if (client != null) client.close();
        server.shutdown();
    }

    private JSONObject nextRoomRequest() throws Exception {
        String text = requests.poll(10, TimeUnit.SECONDS);
        assertNotNull(text);
        JSONObject request = new JSONObject(text);
        assertEquals("fixture-token", request.getString("token"));
        assertEquals("room.list", SprutHubRpcClient.commandName(request.getJSONObject("params")));
        return request;
    }

    private CompletableFuture<JSONObject> offlineRoomList() throws Exception {
        CompletableFuture<JSONObject> room = client.readCatalogList(SprutProtocolAdapter.buildRoomListParams());
        JSONObject request = nextRoomRequest();
        WebSocket socket = peer.get(10, TimeUnit.SECONDS);
        assertTrue(socket.send(new JSONObject().put("id", request.getLong("id"))
                .put("error", new JSONObject().put("code", -666004)
                        .put("message", "Hub not connected to server")).toString()));
        // Ordered delivery: when this event is handled, the previous reply has armed the retry.
        assertTrue(socket.send("{\"event\":{}}"));
        observed.get(10, TimeUnit.SECONDS);
        return room;
    }

    @Test public void roomListRetriesUsingSameSessionAndSingleConnection() throws Exception {
        CompletableFuture<JSONObject> room = offlineRoomList();
        JSONObject retry = nextRoomRequest();
        assertTrue(peer.get().send(new JSONObject().put("id", retry.getLong("id"))
                .put("result", new JSONObject().put("restored", true)).toString()));
        assertTrue(room.get(10, TimeUnit.SECONDS).getJSONObject("result").getBoolean("restored"));
        assertEquals(1, server.getRequestCount());
        assertTrue(requests.isEmpty());
    }

    @Test public void disconnectedPeerAbortsWaitingReadAndWritesCannotEnterRetryPath() throws Exception {
        JSONObject write = new JSONObject().put("characteristic", new JSONObject().put("update", new JSONObject()));
        assertTrue(assertThrows(ExecutionException.class, () -> client.readCatalogList(write)
                .get(1, TimeUnit.SECONDS)).getCause() instanceof IllegalArgumentException);
        CompletableFuture<JSONObject> room = offlineRoomList();
        assertTrue(peer.get().close(1000, "session ended"));
        assertThrows(ExecutionException.class, () -> room.get(5, TimeUnit.SECONDS));
        assertEquals(1, server.getRequestCount());
        assertTrue(requests.isEmpty());
    }
}
