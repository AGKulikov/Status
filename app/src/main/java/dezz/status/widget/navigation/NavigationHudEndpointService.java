/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import dezz.status.widget.Preferences;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Explicit-only Natro endpoint to which the patched Navigator process connects.
 *
 * <p>The endpoint accepts versioned snapshots/route geometry, sends the two-map configuration and
 * leases Natro's real HUD Surface to Navigator. The Surface is producer-owned: no screenshot,
 * ImageReader or per-frame bitmap crosses this bridge.</p>
 */
public final class NavigationHudEndpointService extends Service {
    private static final String TAG = "NavigationHudEndpoint";
    static final int MAX_CONFIGURATION_CHARS = 384 * 1024;
    @NonNull private static final Object SURFACE_LOCK = new Object();
    @Nullable private static volatile NavigationHudEndpointService instance;
    @Nullable private static SurfaceLease publishedSurface;
    @Nullable private static volatile String publishedConfigurationJson;
    private static long nextSurfaceGeneration;
    private static final long HOST_CAPABILITIES =
            NavigationBridgeContract.CAP_NATRO_CONFIGURATION_HOST
                    | NavigationBridgeContract.CAP_NATRO_NAVIGATION_STATE_SINK
                    | NavigationBridgeContract.CAP_NATRO_HUD_SURFACE_PROVIDER;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper(), this::onMessage);
    @NonNull private final Messenger endpoint = new Messenger(handler);
    @Nullable private Client client;

    /** Called by the HUD TextureView in this same Natro process. Ownership stays with it. */
    public static void publishHudSurface(@NonNull Surface surface,
                                         int width, int height, int dpi) {
        if (!surface.isValid() || width <= 0 || height <= 0) return;
        final SurfaceLease next;
        synchronized (SURFACE_LOCK) {
            next = new SurfaceLease(surface, width, height, Math.max(1, dpi),
                    ++nextSurfaceGeneration);
            publishedSurface = next;
        }
        NavigationHudEndpointService current = instance;
        if (current != null) current.handler.post(() -> current.sendSurface(next));
    }

    /** Revokes exactly the lease backed by this TextureView Surface before its owner releases it. */
    public static void revokeHudSurface(@NonNull Surface surface) {
        final long generation;
        synchronized (SURFACE_LOCK) {
            if (publishedSurface == null || publishedSurface.surface != surface) return;
            generation = publishedSurface.generation;
            publishedSurface = null;
        }
        NavigationHudEndpointService current = instance;
        if (current != null) current.handler.post(() -> current.sendSurfaceDetach(generation));
    }

    /** Re-sends independently edited map profiles to the authenticated Navigator client. */
    public static void notifyConfigurationChanged() {
        NavigationHudEndpointService current = instance;
        if (current == null) return;
        current.handler.post(() -> {
            Client connected = current.client;
            if (connected != null) {
                current.sendConfiguration(connected.messenger, connected.sessionId);
            }
        });
    }

    /** Explicit settings wake used by the UI after it persists either MapProfile. */
    public static void requestConfigurationRefresh(@NonNull android.content.Context context) {
        Intent command = new Intent(context, NavigationConfigurationRelayService.class)
                .setAction(NavigationConfigurationRelayService.ACTION_REFRESH_CONFIGURATION);
        String raw = new Preferences(context).navigationIntegrationConfigJson.get();
        if (raw != null && raw.length() <= MAX_CONFIGURATION_CHARS
                && raw.indexOf('\u0000') < 0) {
            command.putExtra(NavigationConfigurationRelayService.EXTRA_CONFIGURATION_JSON, raw);
        }
        try {
            context.startService(command);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not wake navigation configuration endpoint", failure);
        }
    }

    /** Called only by the non-exported relay in this :hud process. */
    static void acceptRelayedConfiguration(@Nullable String raw) {
        if (raw == null || raw.length() > MAX_CONFIGURATION_CHARS
                || raw.indexOf('\u0000') >= 0) return;
        try {
            NavigationIntegrationConfig decoded = raw.trim().isEmpty()
                    ? new NavigationIntegrationConfig()
                    : NavigationIntegrationConfig.fromJson(raw);
            publishedConfigurationJson = decoded.toJson().toString();
        } catch (IllegalArgumentException | JSONException invalidDocument) {
            Log.w(TAG, "Rejected invalid navigation configuration refresh", invalidDocument);
            return;
        }
        NavigationHudEndpointService current = instance;
        if (current != null) current.handler.post(() -> {
            Client connected = current.client;
            if (connected != null) {
                current.sendConfiguration(connected.messenger, connected.sessionId);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DiagnosticJournal.info("navigation-bridge", "HUD endpoint service created in main Natro process");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null
                || !NavigationBridgeContract.NATRO_BIND_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Rejected bind without the exact v2 action");
            DiagnosticJournal.warn("navigation-bridge", "rejected bind: wrong action");
            return null;
        }
        DiagnosticJournal.info("navigation-bridge", "Navigator requested endpoint bind");
        return endpoint.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnectCurrentClient();
        return false;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        disconnectCurrentClient();
        super.onDestroy();
    }

    private boolean onMessage(@NonNull Message message) {
        final int sendingUid = message.sendingUid;
        if (!NavigationBridgeCallerVerifier.isTrustedNavigator(this, sendingUid)) {
            Log.w(TAG, "Rejected bridge message from uid=" + sendingUid);
            DiagnosticJournal.warn("navigation-bridge",
                    "rejected message: Navigator package/signature mismatch; uid=" + sendingUid);
            return true;
        }
        if (message.what == NavigationBridgeContract.MSG_HELLO) {
            acceptHello(message, sendingUid);
            return true;
        }

        Client current = client;
        if (current == null || current.uid != sendingUid
                || !current.sessionId.equals(sessionFrom(message))) {
            replyError(message.replyTo, "SESSION_REQUIRED", "Send an authenticated HELLO first");
            return true;
        }
        current.lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime();
        try {
            switch (message.what) {
                case NavigationBridgeContract.MSG_NAVIGATION_SNAPSHOT:
                    acceptSnapshot(current, message);
                    break;
                case NavigationBridgeContract.MSG_ROUTE_GEOMETRY:
                    acceptRouteGeometry(current, message);
                    break;
                case NavigationBridgeContract.MSG_HUD_SURFACE_LOST:
                    Log.i(TAG, "Navigator reported HUD surface loss, generation="
                            + message.getData().getLong(
                                    NavigationBridgeContract.KEY_SURFACE_GENERATION, -1L)
                            + ", detail=" + message.getData().getString(
                            NavigationBridgeContract.KEY_ERROR_DETAIL, ""));
                    break;
                case NavigationBridgeContract.MSG_HEARTBEAT:
                    replyCapabilities(current.messenger);
                    break;
                case NavigationBridgeContract.MSG_DIAGNOSTIC:
                    DiagnosticJournal.info("navigation-runtime",
                            message.getData().getString(
                                    NavigationBridgeContract.KEY_ERROR_DETAIL, "empty event"));
                    break;
                default:
                    replyError(current.messenger, "UNKNOWN_MESSAGE",
                            "Unsupported message " + message.what);
                    break;
            }
        } catch (IllegalArgumentException failure) {
            replyError(current.messenger, "INVALID_PAYLOAD", failure.getMessage());
        }
        return true;
    }

    private void acceptHello(@NonNull Message message, int sendingUid) {
        Bundle data = message.getData();
        int protocol = data.getInt(NavigationBridgeContract.KEY_PROTOCOL_VERSION, -1);
        String packageName = data.getString(NavigationBridgeContract.KEY_CLIENT_PACKAGE, "");
        String session = NavigationBridgeStateStore.boundedSession(
                data.getString(NavigationBridgeContract.KEY_SESSION_ID, ""));
        Messenger reply = message.replyTo;
        if (protocol != NavigationBridgeContract.PROTOCOL_VERSION
                || !NavigationBridgeContract.NAVIGATOR_PACKAGE.equals(packageName)
                || session.isEmpty() || reply == null) {
            replyError(reply, "INVALID_HELLO", "Protocol, package, session or replyTo mismatch");
            return;
        }

        disconnectCurrentClient();
        IBinder remote = reply.getBinder();
        IBinder.DeathRecipient death = () -> handler.post(() -> disconnectIf(remote));
        try {
            remote.linkToDeath(death, 0);
        } catch (RemoteException dead) {
            return;
        }
        client = new Client(
                sendingUid,
                session,
                data.getLong(NavigationBridgeContract.KEY_CAPABILITIES, 0L),
                reply,
                remote,
                death);
        NavigationBridgeStateStore.beginSession(session);
        replyCapabilities(reply);
        sendConfiguration(reply, session);
        requestNavigationState(client);
        sendPublishedSurface();
        Log.i(TAG, "Authenticated Navigator bridge session started");
        DiagnosticJournal.info("navigation-bridge",
                "authenticated Navigator session started; capabilities="
                        + Long.toHexString(client.capabilities));
    }

    private void acceptSnapshot(@NonNull Client current, @NonNull Message message) {
        String raw = message.getData().getString(
                NavigationBridgeContract.KEY_SNAPSHOT_JSON, "");
        NavigationSnapshotV2 next = NavigationSnapshotV2.fromJson(raw);
        if (!NavigationBridgeStateStore.publishSnapshot(current.sessionId, next)) {
            replyError(current.messenger, "STALE_SNAPSHOT", "Sequence did not advance");
        } else if (next.sequence == 1L) {
            DiagnosticJournal.info("navigation-bridge",
                    "first navigation snapshot received; routeEpoch=" + next.routeEpoch);
        }
    }

    private void acceptRouteGeometry(@NonNull Client current, @NonNull Message message) {
        String raw = message.getData().getString(
                NavigationBridgeContract.KEY_ROUTE_GEOMETRY_JSON, "");
        NavigationRouteGeometryV2 next = NavigationRouteGeometryV2.fromJson(raw);
        if (!NavigationBridgeStateStore.publishRouteGeometry(current.sessionId, next)) {
            replyError(current.messenger, "STALE_ROUTE", "Session or route epoch mismatch");
        }
    }

    private void replyCapabilities(@NonNull Messenger target) {
        Bundle data = new Bundle();
        data.putInt(NavigationBridgeContract.KEY_PROTOCOL_VERSION,
                NavigationBridgeContract.PROTOCOL_VERSION);
        data.putLong(NavigationBridgeContract.KEY_CAPABILITIES, HOST_CAPABILITIES);
        send(target, NavigationBridgeContract.MSG_CAPABILITIES, data);
    }

    private void sendConfiguration(@NonNull Messenger target, @NonNull String session) {
        String raw = publishedConfigurationJson;
        if (raw == null) raw = new Preferences(this).navigationIntegrationConfigJson.get();
        NavigationIntegrationConfig config;
        try {
            config = raw.isEmpty()
                    ? new NavigationIntegrationConfig()
                    : NavigationIntegrationConfig.fromJson(raw);
        } catch (IllegalArgumentException invalidStoredValue) {
            config = new NavigationIntegrationConfig();
        }
        try {
            String normalized = config.toJson().toString();
            publishedConfigurationJson = normalized;
            Bundle data = new Bundle();
            data.putString(NavigationBridgeContract.KEY_SESSION_ID, session);
            data.putString(NavigationBridgeContract.KEY_CONFIGURATION_JSON,
                    normalized);
            send(target, NavigationBridgeContract.MSG_APPLY_CONFIGURATION, data);
        } catch (JSONException failure) {
            replyError(target, "CONFIGURATION_ERROR", failure.getMessage());
        }
    }

    private void sendPublishedSurface() {
        SurfaceLease lease;
        synchronized (SURFACE_LOCK) {
            lease = publishedSurface;
        }
        if (lease != null) sendSurface(lease);
    }

    private void requestNavigationState(@NonNull Client current) {
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        if ((current.capabilities & NavigationBridgeContract.CAP_NAVIGATION_SNAPSHOT) != 0L) {
            send(current.messenger, NavigationBridgeContract.MSG_REQUEST_SNAPSHOT, data);
        }
        if ((current.capabilities & NavigationBridgeContract.CAP_ROUTE_GEOMETRY) != 0L) {
            Bundle routeData = new Bundle(data);
            send(current.messenger, NavigationBridgeContract.MSG_REQUEST_ROUTE_GEOMETRY,
                    routeData);
        }
    }

    private void sendSurface(@NonNull SurfaceLease lease) {
        Client current = client;
        if (current == null || !supportsDirectHudMap(current) || !lease.surface.isValid()) return;
        synchronized (SURFACE_LOCK) {
            if (publishedSurface != lease) return;
        }
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putParcelable(NavigationBridgeContract.KEY_SURFACE, lease.surface);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_WIDTH, lease.width);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_HEIGHT, lease.height);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_DPI, lease.dpi);
        data.putLong(NavigationBridgeContract.KEY_SURFACE_GENERATION, lease.generation);
        send(current.messenger, NavigationBridgeContract.MSG_ATTACH_HUD_SURFACE, data);
    }

    private void sendSurfaceDetach(long generation) {
        Client current = client;
        if (current == null || !supportsDirectHudMap(current)) return;
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putLong(NavigationBridgeContract.KEY_SURFACE_GENERATION, generation);
        send(current.messenger, NavigationBridgeContract.MSG_DETACH_HUD_SURFACE, data);
    }

    private static boolean supportsDirectHudMap(@NonNull Client value) {
        long required = NavigationBridgeContract.CAP_HUD_INDEPENDENT_MAP_WINDOW
                | NavigationBridgeContract.CAP_HUD_DIRECT_SURFACE;
        return (value.capabilities & required) == required;
    }

    private void disconnectIf(@NonNull IBinder remote) {
        Client current = client;
        if (current != null && current.remote == remote) disconnectCurrentClient();
    }

    private void disconnectCurrentClient() {
        Client current = client;
        client = null;
        if (current == null) return;
        try {
            current.remote.unlinkToDeath(current.deathRecipient, 0);
        } catch (RuntimeException ignored) {}
        NavigationBridgeStateStore.endSession(current.sessionId);
        Log.i(TAG, "Navigator bridge session ended");
        DiagnosticJournal.warn("navigation-bridge", "Navigator bridge session ended");
    }

    @NonNull
    private static String sessionFrom(@NonNull Message message) {
        return NavigationBridgeStateStore.boundedSession(message.getData().getString(
                NavigationBridgeContract.KEY_SESSION_ID, ""));
    }

    private static void replyError(@Nullable Messenger target, @NonNull String code,
                                   @Nullable String detail) {
        if (target == null) return;
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_ERROR_CODE, code);
        data.putString(NavigationBridgeContract.KEY_ERROR_DETAIL,
                detail == null ? "" : detail);
        send(target, NavigationBridgeContract.MSG_ERROR, data);
    }

    private static void send(@NonNull Messenger target, int what, @NonNull Bundle data) {
        Message response = Message.obtain(null, what);
        response.setData(data);
        try {
            target.send(response);
        } catch (RemoteException ignored) {}
    }

    private static final class Client {
        final int uid;
        @NonNull final String sessionId;
        final long capabilities;
        @NonNull final Messenger messenger;
        @NonNull final IBinder remote;
        @NonNull final IBinder.DeathRecipient deathRecipient;
        long lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime();

        Client(int uid, @NonNull String sessionId, long capabilities,
               @NonNull Messenger messenger, @NonNull IBinder remote,
               @NonNull IBinder.DeathRecipient deathRecipient) {
            this.uid = uid;
            this.sessionId = sessionId;
            this.capabilities = capabilities;
            this.messenger = messenger;
            this.remote = remote;
            this.deathRecipient = deathRecipient;
        }
    }

    private static final class SurfaceLease {
        @NonNull final Surface surface;
        final int width;
        final int height;
        final int dpi;
        final long generation;

        SurfaceLease(@NonNull Surface surface, int width, int height,
                     int dpi, long generation) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.generation = generation;
        }
    }
}
