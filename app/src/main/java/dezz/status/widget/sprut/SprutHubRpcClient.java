/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Small JSON-RPC 2.0 transport for Sprut.hub's internal {@code /spruthub} WebSocket endpoint.
 *
 * <p>The protocol operation lives inside {@code params}; the JSON-RPC {@code method} is an empty
 * string. Authentication and reconnect policy deliberately live in {@link SprutHubController} so
 * this class remains a replaceable transport if Sprut changes its private API.</p>
 */
public final class SprutHubRpcClient implements Closeable {
    public interface Listener {
        void onOpen();
        void onEvent(@NonNull JSONObject event);
        void onDisconnected(@NonNull String detail);
    }

    public static final class RpcException extends IOException {
        public final int code;
        @Nullable public final JSONObject data;

        RpcException(int code, @NonNull String message, @Nullable JSONObject data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }

    private static final long DEFAULT_TIMEOUT_MS = 20_000L;

    private final OkHttpClient http;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "spruthub-rpc-timeouts");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong nextId = new AtomicLong(1L);
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Listener listener;
    private final String url;
    private final Object lock = new Object();

    @Nullable private volatile WebSocket socket;
    @Nullable private volatile String token;
    @Nullable private volatile String serial;
    private volatile boolean stopped;
    private volatile boolean open;

    public SprutHubRpcClient(@NonNull String url, @NonNull Listener listener) {
        this.url = normalizeUrl(url);
        this.listener = listener;
        // Validate the complete authority/path before a controller acquires long-lived wake locks.
        new Request.Builder().url(this.url).build();
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void connect() {
        synchronized (lock) {
            if (stopped || socket != null) return;
            Request request = new Request.Builder().url(url).build();
            socket = http.newWebSocket(request, new SocketListener());
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void setSession(@Nullable String token, @Nullable String serial) {
        this.token = blankToNull(token);
        this.serial = blankToNull(serial);
    }

    public void clearSession() {
        token = null;
        serial = null;
    }

    @NonNull
    public CompletableFuture<JSONObject> call(@NonNull JSONObject params) {
        return call(params, DEFAULT_TIMEOUT_MS);
    }

    @NonNull
    public CompletableFuture<JSONObject> call(@NonNull JSONObject params, long timeoutMs) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        long id = nextId.getAndIncrement();
        JSONObject request = new JSONObject();
        try {
            request.put("jsonrpc", "2.0");
            request.put("method", "");
            request.put("params", params);
            request.put("id", id);
            String currentToken = token;
            String currentSerial = serial;
            if (currentToken != null) request.put("token", currentToken);
            if (currentSerial != null) request.put("serial", currentSerial);
        } catch (JSONException e) {
            result.completeExceptionally(e);
            return result;
        }

        Pending entry = new Pending(result);
        synchronized (lock) {
            WebSocket active = socket;
            if (stopped || !open || active == null) {
                result.completeExceptionally(new IOException("Sprut.hub socket is not open"));
                return result;
            }
            pending.put(id, entry);
            try {
                entry.timeout = scheduler.schedule(() -> {
                    Pending timedOut = pending.remove(id);
                    if (timedOut != null) {
                        timedOut.future.completeExceptionally(
                                new IOException("Sprut.hub reply timeout (id=" + id + ")"));
                    }
                }, Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stoppedScheduler) {
                pending.remove(id, entry);
                result.completeExceptionally(new IOException("Sprut.hub client is closed",
                        stoppedScheduler));
                return result;
            }
            if (!active.send(request.toString())) {
                Pending failed = pending.remove(id);
                if (failed != null) {
                    failed.cancelTimeout();
                    failed.future.completeExceptionally(
                            new IOException("Could not queue WebSocket message"));
                }
            }
        }
        return result;
    }

    /** Closes only the current socket. A controller may create a fresh client for reconnect. */
    public void disconnect() {
        WebSocket active;
        synchronized (lock) {
            active = socket;
            socket = null;
            open = false;
        }
        if (active != null) active.close(1000, "reconnect");
        failPending(new IOException("Sprut.hub socket closed"));
    }

    @Override
    public void close() {
        WebSocket active;
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            active = socket;
            socket = null;
            open = false;
        }
        if (active != null) active.close(1000, "closed");
        failPending(new IOException("Sprut.hub client closed"));
        scheduler.shutdownNow();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    private void handleText(@NonNull String text) {
        final JSONObject message;
        try {
            message = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        if (message.has("event")) {
            try {
                listener.onEvent(message);
            } catch (RuntimeException ignored) {
                // A presentation/reducer bug must not terminate OkHttp's WebSocket reader.
            }
            return;
        }
        long id = message.optLong("id", -1L);
        Pending entry = pending.remove(id);
        if (entry == null) return;
        entry.cancelTimeout();
        JSONObject error = message.optJSONObject("error");
        if (error != null) {
            entry.future.completeExceptionally(new RpcException(
                    error.optInt("code", -1),
                    error.optString("message", "Sprut.hub RPC error"),
                    error.optJSONObject("data")));
        } else if (!message.has("result")) {
            entry.future.completeExceptionally(new IOException("Malformed Sprut.hub response"));
        } else {
            entry.future.complete(message);
        }
    }

    private void failPending(@NonNull IOException error) {
        for (Map.Entry<Long, Pending> item : pending.entrySet()) {
            Pending removed = pending.remove(item.getKey());
            if (removed != null) {
                removed.cancelTimeout();
                removed.future.completeExceptionally(error);
            }
        }
    }

    @NonNull
    private static String normalizeUrl(@NonNull String raw) {
        String value = raw.trim();
        if (!(value.startsWith("ws://") || value.startsWith("wss://"))) {
            throw new IllegalArgumentException("Sprut.hub URL must start with ws:// or wss://");
        }
        int schemeEnd = value.indexOf("://") + 3;
        if (value.indexOf('/', schemeEnd) < 0) value += "/spruthub";
        return value;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private final class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            synchronized (lock) {
                if (stopped || webSocket != socket) {
                    webSocket.close(1000, "superseded");
                    return;
                }
                open = true;
            }
            try {
                listener.onOpen();
            } catch (RuntimeException ignored) {
                disconnect();
                try {
                    listener.onDisconnected("controller callback failed");
                } catch (RuntimeException callbackFailure) {
                    // Never let an application callback kill OkHttp's dispatcher thread.
                }
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            if (webSocket == socket) handleText(text);
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            disconnected(webSocket, "closed " + code + (reason.isEmpty() ? "" : ": " + reason));
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                              @Nullable Response response) {
            disconnected(webSocket, t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage());
        }

        private void disconnected(WebSocket webSocket, String detail) {
            synchronized (lock) {
                if (webSocket != socket) return;
                socket = null;
                open = false;
            }
            failPending(new IOException("Sprut.hub disconnected: " + detail));
            if (!stopped) {
                try {
                    listener.onDisconnected(detail);
                } catch (RuntimeException ignored) {
                    // Never let an application callback kill OkHttp's dispatcher thread.
                }
            }
        }
    }

    private static final class Pending {
        final CompletableFuture<JSONObject> future;
        @Nullable ScheduledFuture<?> timeout;

        Pending(CompletableFuture<JSONObject> future) {
            this.future = future;
        }

        void cancelTimeout() {
            if (timeout != null) timeout.cancel(false);
        }
    }
}
