/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Direct, retained-state-free Home Assistant WebSocket connector. */
public final class HaWebSocketConnector {
    private static final long PHASE_TIMEOUT_SECONDS = 45L;
    private static final int[] RECONNECT_SECONDS = {1, 2, 5, 10, 30};

    public enum ConnectionState {
        STOPPED,
        CONNECTING,
        AUTHENTICATING,
        SYNCING,
        ONLINE,
        AUTH_FAILED,
        RECONNECT_WAIT
    }

    public interface Listener {
        void onConnectionStateChanged(ConnectionState state, String detail);

        /** Full authoritative snapshot with all events received during get_states replayed. */
        void onSnapshot(HaEntityCatalog catalog);

        /** Live update after the connector has reached ONLINE. */
        void onEntityUpdated(HaEntityCatalog.EntityUpdate update);
    }

    private final Listener listener;
    private final OkHttpClient http;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "ha-websocket-reconnect");
                thread.setDaemon(true);
                return thread;
            });
    private final HaSnapshotSynchronizer synchronizer = new HaSnapshotSynchronizer();

    private volatile ConnectionState state = ConnectionState.STOPPED;
    private volatile HaEntityCatalog catalog = HaEntityCatalog.empty();
    private volatile Config config;

    private boolean running;
    private boolean disposed;
    private long generation;
    private int reconnectAttempt;
    private int nextCommandId;
    private int subscriptionId;
    private int getStatesId;
    private WebSocket socket;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> phaseTimeout;

    public HaWebSocketConnector(Listener listener) {
        this(new OkHttpClient(), listener);
    }

    /** Injectable client overload for integration tests and applications with shared TLS policy. */
    public HaWebSocketConnector(OkHttpClient baseClient, Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        http = Objects.requireNonNull(baseClient, "baseClient").newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /** Starts immediately. Calling it with a different config performs an atomic reconfigure. */
    public synchronized void start(Config newConfig) {
        Objects.requireNonNull(newConfig, "config");
        if (disposed) throw new IllegalStateException("Home Assistant connector is closed");
        if (running && newConfig.equals(config)) return;
        running = true;
        config = newConfig;
        reconnectAttempt = 0;
        replaceSessionLocked();
    }

    /** Reconnects a running connector or stores the config for the next start. */
    public synchronized void reconfigure(Config newConfig) {
        Objects.requireNonNull(newConfig, "config");
        if (disposed) throw new IllegalStateException("Home Assistant connector is closed");
        if (newConfig.equals(config)) return;
        config = newConfig;
        reconnectAttempt = 0;
        if (running) replaceSessionLocked();
    }

    /** Stops retries and the active socket. The same instance may be started again later. */
    public synchronized void stop() {
        running = false;
        generation++;
        cancelReconnectLocked();
        cancelPhaseTimeoutLocked();
        synchronizer.reset();
        closeSocketLocked();
        transitionLocked(ConnectionState.STOPPED, "stopped");
    }

    /** Final lifecycle boundary. Unlike {@link #stop()}, this instance cannot be restarted. */
    public synchronized void close() {
        if (disposed) return;
        if (running || socket != null || state != ConnectionState.STOPPED) stop();
        disposed = true;
        scheduler.shutdownNow();
        // Release the potentially large entity graph and the bearer token as soon as the owning
        // foreground service is destroyed. OkHttp may be injected/shared, so its pools stay owned
        // by the caller; the already-closed WebSocket no longer retains this listener.
        catalog = HaEntityCatalog.empty();
        config = null;
    }

    public ConnectionState connectionState() { return state; }

    public boolean isOnline() { return state == ConnectionState.ONLINE; }

    public HaEntityCatalog catalog() { return catalog; }

    public Config config() { return config; }

    /** Requests a fresh authoritative get_states snapshot without duplicating the subscription. */
    public synchronized void refreshSnapshot() {
        if (!running || state != ConnectionState.ONLINE || socket == null) {
            throw new IllegalStateException("Home Assistant is not online");
        }
        long session = generation;
        synchronizer.beginSnapshot();
        getStatesId = nextCommandId++;
        transitionLocked(ConnectionState.SYNCING, "refreshing current states");
        if (sendLocked(HaWebSocketProtocol.buildGetStates(getStatesId))) {
            armPhaseTimeoutLocked(session, "Home Assistant get_states timed out");
        }
    }

    public static int reconnectDelaySeconds(int failedAttempt) {
        int index = Math.max(0, Math.min(failedAttempt, RECONNECT_SECONDS.length - 1));
        return RECONNECT_SECONDS[index];
    }

    private void replaceSessionLocked() {
        generation++;
        cancelReconnectLocked();
        cancelPhaseTimeoutLocked();
        synchronizer.reset();
        closeSocketLocked();
        connectLocked();
    }

    private void connectLocked() {
        if (!running || config == null) return;
        long session = ++generation;
        nextCommandId = 1;
        subscriptionId = 0;
        getStatesId = 0;
        transitionLocked(ConnectionState.CONNECTING, "connecting");
        Request request = new Request.Builder().url(config.webSocketUrl()).build();
        socket = http.newWebSocket(request, new SessionListener(session));
        armPhaseTimeoutLocked(session, "Home Assistant connection timed out");
    }

    private synchronized void opened(long session, WebSocket webSocket) {
        if (!isCurrentLocked(session, webSocket)) return;
        transitionLocked(ConnectionState.AUTHENTICATING, "waiting for authentication");
    }

    private synchronized void message(long session, WebSocket webSocket, String text) {
        if (!isCurrentLocked(session, webSocket)) return;
        final JSONObject frame;
        try {
            frame = new JSONObject(text);
        } catch (JSONException error) {
            failSessionLocked("invalid JSON frame");
            return;
        }

        String type = HaWebSocketProtocol.type(frame);
        switch (type) {
            case HaWebSocketProtocol.TYPE_AUTH_REQUIRED:
                sendLocked(HaWebSocketProtocol.buildAuth(config.accessToken));
                return;
            case HaWebSocketProtocol.TYPE_AUTH_OK:
                beginSynchronizationLocked(session);
                return;
            case HaWebSocketProtocol.TYPE_AUTH_INVALID:
                transitionLocked(ConnectionState.AUTH_FAILED,
                        safeDetail(frame.optString("message", "authentication rejected")));
                failSessionLocked("authentication rejected");
                return;
            case HaWebSocketProtocol.TYPE_RESULT:
                handleResultLocked(session, frame);
                return;
            case HaWebSocketProtocol.TYPE_EVENT:
                handleEventLocked(frame);
                return;
            default:
                // Home Assistant may add message types; unknown frames do not invalidate session.
        }
    }

    private void beginSynchronizationLocked(long session) {
        if (subscriptionId != 0 || state == ConnectionState.ONLINE) return;
        synchronizer.beginSnapshot();
        subscriptionId = nextCommandId++;
        transitionLocked(ConnectionState.SYNCING, "subscribing to state changes");
        if (sendLocked(HaWebSocketProtocol.buildStateChangedSubscription(subscriptionId))) {
            armPhaseTimeoutLocked(session, "Home Assistant synchronization timed out");
        }
    }

    private void handleResultLocked(long session, JSONObject frame) {
        int id = frame.optInt("id", -1);
        if (id == subscriptionId) {
            if (getStatesId != 0) return;
            if (!HaWebSocketProtocol.isSuccessfulResult(frame, subscriptionId)) {
                failSessionLocked(HaWebSocketProtocol.resultError(frame));
                return;
            }
            getStatesId = nextCommandId++;
            transitionLocked(ConnectionState.SYNCING, "loading current states");
            if (sendLocked(HaWebSocketProtocol.buildGetStates(getStatesId))) {
                armPhaseTimeoutLocked(session, "Home Assistant get_states timed out");
            }
            return;
        }
        if (id != getStatesId || getStatesId == 0) return;
        JSONArray states = HaWebSocketProtocol.resultStates(frame, getStatesId);
        if (states == null) {
            failSessionLocked(HaWebSocketProtocol.resultError(frame));
            return;
        }
        final HaSnapshotSynchronizer.Completion completion;
        try {
            completion = synchronizer.completeSnapshot(states);
        } catch (RuntimeException error) {
            failSessionLocked("invalid get_states snapshot");
            return;
        }
        catalog = completion.catalog();
        reconnectAttempt = 0;
        cancelPhaseTimeoutLocked();
        // ONLINE is impossible before a successful, parsed get_states response.
        transitionLocked(ConnectionState.ONLINE,
                catalog.size() + " entities synchronized");
        notifySnapshot(catalog);
    }

    private void handleEventLocked(JSONObject frame) {
        HaWebSocketProtocol.StateChange change;
        try {
            change = HaWebSocketProtocol.parseStateChange(frame);
        } catch (RuntimeException ignored) {
            return;
        }
        if (change == null) return;
        if (synchronizer.buffer(change)) return;
        if (state != ConnectionState.ONLINE) return;
        HaEntityCatalog.Update update = catalog.apply(change);
        if (!update.changed()) return;
        catalog = update.catalog();
        notifyEntityUpdate(update.entityUpdate());
    }

    private boolean sendLocked(JSONObject frame) {
        WebSocket active = socket;
        if (active == null || !active.send(frame.toString())) {
            failSessionLocked("could not send Home Assistant command");
            return false;
        }
        return true;
    }

    private synchronized void disconnected(long session, WebSocket webSocket, String detail) {
        if (!isCurrentLocked(session, webSocket)) return;
        socket = null;
        generation++;
        synchronizer.reset();
        cancelPhaseTimeoutLocked();
        scheduleReconnectLocked(detail);
    }

    private void failSessionLocked(String detail) {
        generation++;
        cancelPhaseTimeoutLocked();
        synchronizer.reset();
        WebSocket active = socket;
        socket = null;
        if (active != null) active.cancel();
        scheduleReconnectLocked(detail);
    }

    private void scheduleReconnectLocked(String detail) {
        if (!running) {
            transitionLocked(ConnectionState.STOPPED, "stopped");
            return;
        }
        if (reconnectFuture != null && !reconnectFuture.isDone()) return;
        int delay = reconnectDelaySeconds(reconnectAttempt++);
        long expectedGeneration = generation;
        transitionLocked(ConnectionState.RECONNECT_WAIT,
                safeDetail(detail) + "; retry in " + delay + "s");
        reconnectFuture = scheduler.schedule(() -> {
            synchronized (HaWebSocketConnector.this) {
                if (!running || generation != expectedGeneration) return;
                reconnectFuture = null;
                connectLocked();
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void armPhaseTimeoutLocked(long session, String detail) {
        cancelPhaseTimeoutLocked();
        phaseTimeout = scheduler.schedule(() -> {
            synchronized (HaWebSocketConnector.this) {
                if (running && generation == session && state != ConnectionState.ONLINE) {
                    failSessionLocked(detail);
                }
            }
        }, PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isCurrentLocked(long session, WebSocket webSocket) {
        return running && generation == session && socket == webSocket;
    }

    private void cancelReconnectLocked() {
        if (reconnectFuture != null) reconnectFuture.cancel(false);
        reconnectFuture = null;
    }

    private void cancelPhaseTimeoutLocked() {
        if (phaseTimeout != null) phaseTimeout.cancel(false);
        phaseTimeout = null;
    }

    private void closeSocketLocked() {
        WebSocket active = socket;
        socket = null;
        if (active != null) active.close(1000, "client stop");
    }

    private void transitionLocked(ConnectionState next, String detail) {
        state = next;
        try {
            listener.onConnectionStateChanged(next, safeDetail(detail));
        } catch (RuntimeException ignored) {
            // An application callback must not terminate transport recovery.
        }
    }

    private void notifySnapshot(HaEntityCatalog snapshot) {
        try {
            listener.onSnapshot(snapshot);
        } catch (RuntimeException ignored) {
            // The connector remains online even if one UI refresh fails.
        }
    }

    private void notifyEntityUpdate(HaEntityCatalog.EntityUpdate update) {
        try {
            listener.onEntityUpdated(update);
        } catch (RuntimeException ignored) {
            // The immutable catalog still holds the new state for the next render.
        }
    }

    private String safeDetail(String raw) {
        String value = raw == null ? "" : raw;
        Config current = config;
        if (current != null && !current.accessToken.isEmpty()) {
            value = value.replace(current.accessToken, "<redacted>");
        }
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private final class SessionListener extends WebSocketListener {
        private final long session;

        SessionListener(long session) { this.session = session; }

        @Override public void onOpen(WebSocket webSocket, Response response) {
            opened(session, webSocket);
        }

        @Override public void onMessage(WebSocket webSocket, String text) {
            message(session, webSocket, text);
        }

        @Override public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override public void onClosed(WebSocket webSocket, int code, String reason) {
            disconnected(session, webSocket,
                    "closed " + code + (reason.isEmpty() ? "" : ": " + reason));
        }

        @Override public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
            String detail = failure.getMessage();
            disconnected(session, webSocket, detail == null
                    ? failure.getClass().getSimpleName() : detail);
        }
    }

    /** Complete connection identity. Its string representation always redacts the token. */
    public static final class Config {
        private final String baseUrl;
        private final String accessToken;
        private final String webSocketUrl;

        public Config(String baseUrl, String accessToken) {
            String normalizedBase = normalizeBaseUrl(baseUrl);
            String token = accessToken == null ? "" : accessToken.trim();
            if (token.isEmpty()) throw new IllegalArgumentException("Access token is empty");
            this.baseUrl = normalizedBase;
            this.accessToken = token;
            this.webSocketUrl = deriveWebSocketUrl(normalizedBase);
        }

        public String baseUrl() { return baseUrl; }

        public String webSocketUrl() { return webSocketUrl; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Config)) return false;
            Config config = (Config) other;
            return baseUrl.equals(config.baseUrl) && accessToken.equals(config.accessToken);
        }

        @Override public int hashCode() { return Objects.hash(baseUrl, accessToken); }

        @Override public String toString() {
            return "Config{baseUrl=" + baseUrl + ", accessToken=<redacted>}";
        }

        public static String deriveWebSocketUrl(String baseUrl) {
            String normalized = normalizeBaseUrl(baseUrl);
            try {
                URI source = new URI(normalized);
                String scheme;
                switch (source.getScheme().toLowerCase()) {
                    case "http":
                    case "ws": scheme = "ws"; break;
                    case "https":
                    case "wss": scheme = "wss"; break;
                    default: throw new IllegalArgumentException(
                            "Home Assistant URL must use http, https, ws, or wss");
                }
                if (source.getHost() == null || source.getHost().isEmpty()) {
                    throw new IllegalArgumentException("Home Assistant URL has no host");
                }
                if (source.getUserInfo() != null) {
                    throw new IllegalArgumentException("Credentials are not allowed in the URL");
                }
                String path = source.getRawPath();
                if (path == null || "/".equals(path)) path = "";
                while (path.endsWith("/") && !path.isEmpty()) {
                    path = path.substring(0, path.length() - 1);
                }
                if (path.endsWith("/api")) path += "/websocket";
                else if (!path.endsWith("/api/websocket")) path += "/api/websocket";
                return new URI(scheme, null, source.getHost(), source.getPort(), path,
                        null, null).toString();
            } catch (URISyntaxException error) {
                throw new IllegalArgumentException("Invalid Home Assistant URL", error);
            }
        }

        private static String normalizeBaseUrl(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) throw new IllegalArgumentException("Home Assistant URL is empty");
            if (!value.contains("://")) value = "http://" + value;
            try {
                URI uri = new URI(value);
                if (uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException(
                            "Home Assistant base URL must not contain query or fragment");
                }
                while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
                return value;
            } catch (URISyntaxException error) {
                throw new IllegalArgumentException("Invalid Home Assistant URL", error);
            }
        }
    }
}
