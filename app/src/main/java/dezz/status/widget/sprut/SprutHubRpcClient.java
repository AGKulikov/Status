/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * <p>The protocol operation lives inside {@code params}. Local and custom {@code /spruthub}
 * endpoints use the conventional {@code jsonrpc} plus empty {@code method} envelope. Sprut's
 * official cloud relay instead expects the web client's compact envelope with neither field, a
 * stable {@code cid}, and the {@code json-rpc} WebSocket subprotocol. Authentication and reconnect
 * policy deliberately live in {@link SprutHubController} so this class remains a replaceable
 * transport if Sprut changes its private API.</p>
 */
public final class SprutHubRpcClient implements Closeable {
    enum EnvelopeMode { OFFICIAL_CLOUD, LOCAL_OR_CUSTOM }

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
    private final EnvelopeMode envelopeMode;
    private final String clientId;
    private final Object lock = new Object();

    @Nullable private volatile WebSocket socket;
    @Nullable private volatile String token;
    @Nullable private volatile String serial;
    private volatile boolean stopped;
    private volatile boolean open;

    public SprutHubRpcClient(@NonNull String url, @NonNull Listener listener) {
        this(url, UUID.randomUUID().toString(), listener);
    }

    SprutHubRpcClient(@NonNull String url, @NonNull String clientId,
                      @NonNull Listener listener) {
        this.url = normalizeUrl(url);
        envelopeMode = envelopeModeForUrl(this.url);
        String normalizedClientId = blankToNull(clientId);
        this.clientId = normalizedClientId == null
                ? UUID.randomUUID().toString() : normalizedClientId;
        this.listener = listener;
        // Validate the complete authority/path before a controller acquires long-lived wake locks.
        buildWebSocketRequest(this.url, envelopeMode);
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
            Request request = buildWebSocketRequest(url, envelopeMode);
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

    public boolean isOfficialCloud() {
        return envelopeMode == EnvelopeMode.OFFICIAL_CLOUD;
    }

    public boolean isBetaCloud() {
        try {
            return "beta.spruthub.ru".equalsIgnoreCase(new URI(url).getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    /** Registers the same client identity that Sprut's official web application announces. */
    @NonNull
    public CompletableFuture<JSONObject> registerClientInfo() {
        if (!isOfficialCloud()) return CompletableFuture.completedFuture(new JSONObject());
        final JSONObject params;
        try {
            params = buildClientInfoParams(clientId);
        } catch (JSONException e) {
            return failedFuture(e);
        }
        return call(params);
    }

    @NonNull
    public CompletableFuture<JSONObject> call(@NonNull JSONObject params) {
        return call(params, DEFAULT_TIMEOUT_MS);
    }

    @NonNull
    public CompletableFuture<JSONObject> call(@NonNull JSONObject params, long timeoutMs) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        long id = nextId.getAndIncrement();
        String command = commandName(params);
        final JSONObject request;
        try {
            request = buildRequestEnvelope(envelopeMode, params, id, token, serial, clientId);
        } catch (JSONException e) {
            result.completeExceptionally(e);
            return result;
        }

        Pending entry = new Pending(result, command);
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
                                new IOException("Sprut.hub reply timeout for "
                                        + timedOut.command + " (id=" + id + ")"));
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
                            new IOException("Could not queue Sprut.hub command "
                                    + failed.command));
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
        JSONObject event = normalizeEventMessage(message);
        if (event != null) {
            try {
                listener.onEvent(event);
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
            int code = error.optInt("code", -1);
            String serverMessage = error.optString("message", "Sprut.hub RPC error");
            String diagnosticCommand = entry.command
                    + (code == -1 ? "" : " [" + code + "]");
            entry.future.completeExceptionally(new RpcException(
                    code, diagnosticCommand + ": " + serverMessage,
                    error.optJSONObject("data")));
        } else if (!message.has("result")) {
            entry.future.completeExceptionally(new IOException(
                    entry.command + ": malformed Sprut.hub response"));
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
    static String normalizeUrl(@NonNull String raw) {
        String value = raw.trim();
        if (!(value.startsWith("ws://") || value.startsWith("wss://"))) {
            throw new IllegalArgumentException("Sprut.hub URL must start with ws:// or wss://");
        }
        int schemeEnd = value.indexOf("://") + 3;
        if (value.indexOf('/', schemeEnd) < 0) value += "/spruthub";
        return value;
    }

    @NonNull
    static EnvelopeMode envelopeModeForUrl(@NonNull String normalizedUrl) {
        final String host;
        try {
            host = new URI(normalizedUrl).getHost();
        } catch (URISyntaxException ignored) {
            // Request.Builder performs the authoritative validation in the constructor. If a
            // future custom URL is accepted by OkHttp but not java.net.URI, retain local/custom
            // compatibility instead of silently switching wire protocols.
            return EnvelopeMode.LOCAL_OR_CUSTOM;
        }
        if (host != null && ("beta.spruthub.ru".equalsIgnoreCase(host)
                || "web.spruthub.ru".equalsIgnoreCase(host))) {
            return EnvelopeMode.OFFICIAL_CLOUD;
        }
        return EnvelopeMode.LOCAL_OR_CUSTOM;
    }

    @NonNull
    static Request buildWebSocketRequest(@NonNull String url, @NonNull EnvelopeMode mode) {
        Request.Builder request = new Request.Builder().url(url);
        if (mode == EnvelopeMode.OFFICIAL_CLOUD) {
            // This is the sole JSON transport advertised by the official web client. Without the
            // subprotocol the upgrade succeeds, but the relay is not guaranteed to select its
            // JSON command codec.
            request.header("Sec-WebSocket-Protocol", "json-rpc");
        }
        return request.build();
    }

    @NonNull
    static JSONObject buildRequestEnvelope(@NonNull EnvelopeMode mode,
                                           @NonNull JSONObject params,
                                           long id,
                                           @Nullable String token,
                                           @Nullable String serial,
                                           @Nullable String clientId) throws JSONException {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(params, "params");
        JSONObject request = new JSONObject();
        if (mode == EnvelopeMode.LOCAL_OR_CUSTOM) {
            request.put("jsonrpc", "2.0");
            request.put("method", "");
        }
        request.put("params", params);
        request.put("id", id);
        String normalizedToken = blankToNull(token);
        String normalizedSerial = blankToNull(serial);
        String normalizedClientId = blankToNull(clientId);
        if (mode == EnvelopeMode.OFFICIAL_CLOUD && normalizedClientId != null) {
            request.put("cid", normalizedClientId);
        }
        if (normalizedToken != null) request.put("token", normalizedToken);
        if (normalizedSerial != null) request.put("serial", normalizedSerial);
        return request;
    }

    @NonNull
    static JSONObject buildClientInfoParams(@NonNull String clientId) throws JSONException {
        String normalizedClientId = blankToNull(clientId);
        if (normalizedClientId == null) {
            throw new IllegalArgumentException("Sprut.hub client id must not be blank");
        }
        JSONObject info = new JSONObject();
        info.put("id", normalizedClientId);
        info.put("name", "Status Widget HA");
        info.put("type", "CLIENT_DESKTOP");
        info.put("auth", "");
        return new JSONObject().put("server",
                new JSONObject().put("clientInfo", info));
    }

    @NonNull
    static String commandName(@NonNull JSONObject params) {
        Iterator<String> groups = params.keys();
        if (!groups.hasNext()) return "unknown";
        String group = groups.next();
        JSONObject body = params.optJSONObject(group);
        if (body == null) return group;
        Iterator<String> operations = body.keys();
        return operations.hasNext() ? group + "." + operations.next() : group;
    }

    /** Converts the official cloud {@code characteristic.event + params} shape to legacy event. */
    @Nullable
    static JSONObject normalizeEventMessage(@NonNull JSONObject message) {
        if (message.has("event")) return message;
        String method = message.optString("method", "");
        if (!method.endsWith(".event")) return null;
        JSONObject params = message.optJSONObject("params");
        if (params == null) return null;
        String group = method.substring(0, method.length() - ".event".length());
        if (group.isEmpty()) return null;
        try {
            return new JSONObject().put("event", new JSONObject().put(group, params));
        } catch (JSONException impossible) {
            return null;
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
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
        final String command;
        @Nullable ScheduledFuture<?> timeout;

        Pending(CompletableFuture<JSONObject> future, String command) {
            this.future = future;
            this.command = command;
        }

        void cancelTimeout() {
            if (timeout != null) timeout.cancel(false);
        }
    }
}
