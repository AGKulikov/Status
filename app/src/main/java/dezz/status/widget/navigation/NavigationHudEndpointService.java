/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import dezz.status.widget.Preferences;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.launcher.NavigationDataRepository;

/**
 * Explicit-only Natro endpoint to which the patched Navigator process connects.
 *
 * <p>The endpoint accepts versioned snapshots/route geometry, sends the independent map profiles
 * and leases Natro's real HUD and instrument-cluster Surfaces to Navigator. Each Surface is
 * producer-owned: no screenshot, ImageReader or per-frame bitmap crosses this bridge.</p>
 */
public final class NavigationHudEndpointService extends Service {
    private static final String TAG = "NavigationHudEndpoint";
    private static final String ACTION_BOOTSTRAP_OPTIONAL_HUD_SPEED =
            "ru.natro.statuswidget.navigation.BOOTSTRAP_OPTIONAL_HUD_SPEED";
    private static final String ACTION_KEEP_CLUSTER_ENDPOINT =
            "ru.natro.statuswidget.navigation.KEEP_CLUSTER_ENDPOINT";
    /** Last wake checkpoint is at 120 seconds; leave time for its Binder response. */
    private static final long OPTIONAL_HUD_SPEED_BOOTSTRAP_MS = 135_000L;
    /** Coalesces live editor drags so MapKit rebuilds only for the settled viewport. */
    private static final long SURFACE_RESIZE_SETTLE_MS = 80L;
    static final int MAX_CONFIGURATION_CHARS = 384 * 1024;
    @NonNull private static final Object SURFACE_LOCK = new Object();
    @Nullable private static volatile NavigationHudEndpointService instance;
    @Nullable private static SurfaceLease publishedSurface;
    @Nullable private static SurfaceLease publishedClusterSurface;
    @Nullable private static volatile String publishedConfigurationJson;
    private static long nextSurfaceGeneration;
    private static final long HOST_CAPABILITIES =
            NavigationBridgeContract.CAP_NATRO_CONFIGURATION_HOST
                    | NavigationBridgeContract.CAP_NATRO_NAVIGATION_STATE_SINK
                    | NavigationBridgeContract.CAP_NATRO_HUD_SURFACE_PROVIDER
                    | NavigationBridgeContract.CAP_NATRO_CLUSTER_SURFACE_PROVIDER
                    | NavigationBridgeContract.CAP_NATRO_EXTERNAL_CAMERA_SOURCE;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper(), this::onMessage);
    @NonNull private final Messenger endpoint = new Messenger(handler);
    @NonNull private final Object parserQueueLock = new Object();
    @Nullable private HandlerThread parserThread;
    @Nullable private Handler parser;
    @Nullable private Client client;
    @Nullable private HudSpeedCameraBridgeClient hudSpeedCameraBridge;
    @NonNull private String latestExternalCamerasJson = "";
    @Nullable private PendingPayload pendingSnapshot;
    @Nullable private PendingPayload pendingRouteGeometry;
    private boolean snapshotDrainPosted;
    private boolean routeGeometryDrainPosted;
    private int optionalHudSpeedBootstrapStartId;
    private int clusterEndpointStartId;
    @NonNull private final Runnable snapshotDrain = this::drainLatestSnapshot;
    @NonNull private final Runnable routeGeometryDrain = this::drainLatestRouteGeometry;
    @NonNull private final Runnable sendLatestHudSurface = this::sendPublishedSurface;
    @NonNull private final Runnable sendLatestClusterSurface =
            this::sendPublishedClusterSurface;
    @NonNull private final Runnable finishOptionalHudSpeedBootstrap = () -> {
        int startId = optionalHudSpeedBootstrapStartId;
        optionalHudSpeedBootstrapStartId = 0;
        if (startId != 0) stopSelfResult(startId);
    };

    public interface InstrumentLaunchCallback {
        /** Delivered on Natro's main thread after the request entered Navigator's Binder queue. */
        void onPrepared(boolean prepared);
    }

    /**
     * Gives the optional HUD Speed bridge a bounded startup window after Natro is foreground.
     * Failure to start this helper is intentionally non-fatal; Navigator and Yandex cameras do
     * not depend on the external source.
     */
    public static void startOptionalHudSpeedBootstrap(@NonNull Context context) {
        Context app = context.getApplicationContext();
        Context target = app == null ? context : app;
        Intent command = new Intent(target, NavigationHudEndpointService.class)
                .setAction(ACTION_BOOTSTRAP_OPTIONAL_HUD_SPEED);
        try {
            target.startService(command);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Optional HUD Speed bootstrap was unavailable", unavailable);
        }
    }

    /**
     * Starts the Binder endpoint before a cold instrument-panel TextureView publishes its lease.
     * Opening Settings must never be the event that accidentally creates the map bridge.
     */
    public static void ensureClusterEndpointStarted(@NonNull Context context) {
        Context app = context.getApplicationContext();
        Context target = app == null ? context : app;
        Intent command = new Intent(target, NavigationHudEndpointService.class)
                .setAction(ACTION_KEEP_CLUSTER_ENDPOINT);
        try {
            target.startService(command);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not start cold cluster-map endpoint", unavailable);
        }
    }

    /**
     * Arms the already-authenticated Navigator process as the external DIM launcher. This mirrors
     * MConfig's important process boundary: Navigator keeps the delayed launch after Natro resets
     * its own package task, so ECARX does not ask to move the current Natro task to the DIM.
     */
    public static void prepareInstrumentPanelLaunch(
            int displayId,
            @NonNull String launchToken,
            long delayMillis,
            @NonNull InstrumentLaunchCallback callback) {
        NavigationHudEndpointService current = instance;
        if (current == null) {
            new Handler(Looper.getMainLooper()).post(() -> callback.onPrepared(false));
            return;
        }
        current.handler.post(() -> {
            Client connected = current.client;
            boolean supported = connected != null
                    && (connected.capabilities
                    & NavigationBridgeContract.CAP_EXTERNAL_INSTRUMENT_LAUNCHER) != 0L;
            if (!supported || launchToken.length() < 16 || launchToken.length() > 128) {
                callback.onPrepared(false);
                return;
            }
            Bundle data = new Bundle();
            data.putString(NavigationBridgeContract.KEY_SESSION_ID, connected.sessionId);
            data.putInt(NavigationBridgeContract.KEY_INSTRUMENT_DISPLAY_ID,
                    Math.max(0, Math.min(15, displayId)));
            data.putLong(NavigationBridgeContract.KEY_INSTRUMENT_LAUNCH_DELAY_MS,
                    Math.max(250L, Math.min(3_000L, delayMillis)));
            data.putString(NavigationBridgeContract.KEY_INSTRUMENT_LAUNCH_TOKEN, launchToken);
            boolean sent = send(connected.messenger,
                    NavigationBridgeContract.MSG_PREPARE_INSTRUMENT_PANEL_LAUNCH, data);
            if (sent) {
                DiagnosticJournal.info("instrument-panel",
                        "external Navigator launch armed for display " + displayId);
            }
            callback.onPrepared(sent);
        });
    }

    /** Called by the HUD TextureView in this same Natro process. Ownership stays with it. */
    public static long publishHudSurface(@NonNull Surface surface,
                                         int width, int height, int dpi) {
        if (!surface.isValid() || width <= 0 || height <= 0) return -1L;
        int safeDpi = Math.max(1, dpi);
        final SurfaceLease next;
        final boolean resizedExistingSurface;
        synchronized (SURFACE_LOCK) {
            SurfaceLease current = publishedSurface;
            if (current != null && current.surface == surface) {
                if (current.width == width && current.height == height
                        && current.dpi == safeDpi) return current.generation;
                resizedExistingSurface = true;
            } else {
                resizedExistingSurface = false;
            }
            // OffscreenMapWindow has immutable creation dimensions. A new generation instructs
            // Navigator to rebuild that viewport while TextureView retains its last composed
            // frame; mutating metadata alone permanently stretches the old map raster.
            next = new SurfaceLease(surface, width, height, safeDpi,
                    ++nextSurfaceGeneration);
            publishedSurface = next;
        }
        NavigationHudEndpointService current = instance;
        if (current != null) {
            current.handler.removeCallbacks(current.sendLatestHudSurface);
            current.handler.postDelayed(current.sendLatestHudSurface,
                    resizedExistingSurface ? SURFACE_RESIZE_SETTLE_MS : 0L);
        }
        return next.generation;
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
        if (current != null) {
            current.handler.removeCallbacks(current.sendLatestHudSurface);
            current.handler.post(() -> current.sendSurfaceDetach(generation));
        }
    }

    /** Called by the instrument-panel TextureView; this lease is independent from the HUD. */
    public static long publishClusterSurface(@NonNull Surface surface,
                                             int width, int height, int dpi) {
        if (!surface.isValid() || width <= 0 || height <= 0) return -1L;
        int safeDpi = Math.max(1, dpi);
        final SurfaceLease next;
        final boolean resizedExistingSurface;
        synchronized (SURFACE_LOCK) {
            SurfaceLease current = publishedClusterSurface;
            if (current != null && current.surface == surface) {
                if (current.width == width && current.height == height
                        && current.dpi == safeDpi) return current.generation;
                resizedExistingSurface = true;
            } else {
                resizedExistingSurface = false;
            }
            next = new SurfaceLease(surface, width, height, safeDpi,
                    ++nextSurfaceGeneration);
            publishedClusterSurface = next;
        }
        NavigationHudEndpointService current = instance;
        if (current != null) {
            current.handler.removeCallbacks(current.sendLatestClusterSurface);
            current.handler.postDelayed(current.sendLatestClusterSurface,
                    resizedExistingSurface ? SURFACE_RESIZE_SETTLE_MS : 0L);
        }
        return next.generation;
    }

    /** Revokes exactly the instrument-panel lease before its TextureView releases the Surface. */
    public static void revokeClusterSurface(@NonNull Surface surface) {
        final long generation;
        synchronized (SURFACE_LOCK) {
            if (publishedClusterSurface == null
                    || publishedClusterSurface.surface != surface) return;
            generation = publishedClusterSurface.generation;
            publishedClusterSurface = null;
        }
        NavigationHudEndpointService current = instance;
        if (current != null) {
            current.handler.removeCallbacks(current.sendLatestClusterSurface);
            current.handler.post(() -> {
                current.sendClusterSurfaceDetach(generation);
                current.stopClusterEndpointIfIdle();
            });
        }
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
        HandlerThread thread = new HandlerThread(
                "navigation-bridge-parser", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        parserThread = thread;
        parser = new Handler(thread.getLooper());
        instance = this;
        hudSpeedCameraBridge = new HudSpeedCameraBridgeClient(this, raw -> handler.post(() -> {
            latestExternalCamerasJson = raw;
            sendExternalCameras();
        }));
        hudSpeedCameraBridge.start();
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
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null
                && ACTION_BOOTSTRAP_OPTIONAL_HUD_SPEED.equals(intent.getAction())) {
            optionalHudSpeedBootstrapStartId = startId;
            handler.removeCallbacks(finishOptionalHudSpeedBootstrap);
            handler.postDelayed(finishOptionalHudSpeedBootstrap,
                    OPTIONAL_HUD_SPEED_BOOTSTRAP_MS);
        } else if (intent != null && ACTION_KEEP_CLUSTER_ENDPOINT.equals(intent.getAction())) {
            clusterEndpointStartId = startId;
        } else {
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnectCurrentClient();
        return false;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(finishOptionalHudSpeedBootstrap);
        optionalHudSpeedBootstrapStartId = 0;
        clusterEndpointStartId = 0;
        if (instance == this) instance = null;
        disconnectCurrentClient();
        HudSpeedCameraBridgeClient cameraBridge = hudSpeedCameraBridge;
        hudSpeedCameraBridge = null;
        if (cameraBridge != null) cameraBridge.stop();
        HandlerThread thread = parserThread;
        synchronized (parserQueueLock) {
            pendingSnapshot = null;
            pendingRouteGeometry = null;
            snapshotDrainPosted = false;
            routeGeometryDrainPosted = false;
        }
        parser = null;
        parserThread = null;
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private void stopClusterEndpointIfIdle() {
        synchronized (SURFACE_LOCK) {
            if (publishedClusterSurface != null) return;
        }
        int startId = clusterEndpointStartId;
        clusterEndpointStartId = 0;
        if (startId != 0) stopSelfResult(startId);
    }

    private boolean onMessage(@NonNull Message message) {
        final int sendingUid = message.sendingUid;
        if (message.what == NavigationBridgeContract.MSG_HELLO) {
            // PackageManager/signature checks cross Binder and are intentionally paid only when a
            // session is established. Every later message is bound to the already authenticated
            // UID, random session id and death-linked Messenger below.
            if (!NavigationBridgeCallerVerifier.isTrustedNavigator(this, sendingUid)) {
                rejectUntrustedUid(sendingUid);
                return true;
            }
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
                    enqueueSnapshot(current, message.getData().getString(
                            NavigationBridgeContract.KEY_SNAPSHOT_JSON, ""));
                    break;
                case NavigationBridgeContract.MSG_ROUTE_GEOMETRY:
                    enqueueRouteGeometry(current, message.getData().getString(
                            NavigationBridgeContract.KEY_ROUTE_GEOMETRY_JSON, ""));
                    break;
                case NavigationBridgeContract.MSG_HUD_SURFACE_LOST:
                    String surfaceLoss = "Navigator reported HUD surface loss, generation="
                            + message.getData().getLong(
                                    NavigationBridgeContract.KEY_SURFACE_GENERATION, -1L)
                            + ", detail=" + message.getData().getString(
                            NavigationBridgeContract.KEY_ERROR_DETAIL, "");
                    Log.i(TAG, surfaceLoss);
                    DiagnosticJournal.warn("hud-map", surfaceLoss);
                    break;
                case NavigationBridgeContract.MSG_CLUSTER_SURFACE_LOST:
                    String clusterSurfaceLoss =
                            "Navigator reported instrument-cluster surface loss, generation="
                                    + message.getData().getLong(
                                    NavigationBridgeContract.KEY_SURFACE_GENERATION, -1L)
                                    + ", detail=" + message.getData().getString(
                                    NavigationBridgeContract.KEY_ERROR_DETAIL, "");
                    Log.i(TAG, clusterSurfaceLoss);
                    DiagnosticJournal.warn("cluster-map", clusterSurfaceLoss);
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

    private static void rejectUntrustedUid(int sendingUid) {
        Log.w(TAG, "Rejected bridge message from uid=" + sendingUid);
        DiagnosticJournal.warn("navigation-bridge",
                "rejected message: Navigator package/signature mismatch; uid=" + sendingUid);
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
        sendPublishedClusterSurface();
        sendExternalCameras();
        Log.i(TAG, "Authenticated Navigator bridge session started");
        DiagnosticJournal.info("navigation-bridge",
                "authenticated Navigator session started; capabilities="
                        + Long.toHexString(client.capabilities));
    }

    private void enqueueSnapshot(@NonNull Client current, @NonNull String raw) {
        Handler worker = parser;
        if (worker == null) return;
        synchronized (parserQueueLock) {
            pendingSnapshot = new PendingPayload(current, raw);
            if (snapshotDrainPosted) return;
            snapshotDrainPosted = true;
        }
        if (!worker.post(snapshotDrain)) {
            synchronized (parserQueueLock) {
                snapshotDrainPosted = false;
                pendingSnapshot = null;
            }
        }
    }

    /** Parse at most the newest waiting snapshot, then yield so route work cannot starve. */
    private void drainLatestSnapshot() {
        PendingPayload payload;
        synchronized (parserQueueLock) {
            payload = pendingSnapshot;
            pendingSnapshot = null;
        }
        if (payload != null) acceptSnapshot(payload.client, payload.raw);
        repostSnapshotDrainIfNeeded();
    }

    private void repostSnapshotDrainIfNeeded() {
        Handler worker = parser;
        synchronized (parserQueueLock) {
            if (pendingSnapshot == null || worker == null) {
                snapshotDrainPosted = false;
                return;
            }
            if (worker.post(snapshotDrain)) return;
            snapshotDrainPosted = false;
            pendingSnapshot = null;
        }
    }

    private void acceptSnapshot(@NonNull Client current, @NonNull String raw) {
        final NavigationSnapshotV2 next;
        try {
            next = NavigationSnapshotV2.fromJson(raw);
        } catch (IllegalArgumentException invalid) {
            replyError(current.messenger, "INVALID_PAYLOAD", invalid.getMessage());
            return;
        }
        NavigationSnapshotV2 previous = NavigationBridgeStateStore.snapshot();
        if (!NavigationBridgeStateStore.publishSnapshot(current.sessionId, next)) {
            replyError(current.messenger, "STALE_SNAPSHOT", "Sequence did not advance");
        } else {
            if (!next.routeActive && (previous == null || previous.routeActive)) {
                // One transition write prevents notification-era turns, lanes and lights from
                // resurfacing if this authoritative direct session later disappears.
                NavigationDataRepository.clear(this);
            }
            if (next.sequence == 1L) {
                DiagnosticJournal.info("navigation-bridge",
                        "first navigation snapshot received; routeEpoch=" + next.routeEpoch);
            }
        }
    }

    private void enqueueRouteGeometry(@NonNull Client current, @NonNull String raw) {
        Handler worker = parser;
        if (worker == null) return;
        synchronized (parserQueueLock) {
            pendingRouteGeometry = new PendingPayload(current, raw);
            if (routeGeometryDrainPosted) return;
            routeGeometryDrainPosted = true;
        }
        if (!worker.post(routeGeometryDrain)) {
            synchronized (parserQueueLock) {
                routeGeometryDrainPosted = false;
                pendingRouteGeometry = null;
            }
        }
    }

    /** Route replacement and congestion refresh are state, not an event log: keep only latest. */
    private void drainLatestRouteGeometry() {
        PendingPayload payload;
        synchronized (parserQueueLock) {
            payload = pendingRouteGeometry;
            pendingRouteGeometry = null;
        }
        if (payload != null) acceptRouteGeometry(payload.client, payload.raw);
        repostRouteGeometryDrainIfNeeded();
    }

    private void repostRouteGeometryDrainIfNeeded() {
        Handler worker = parser;
        synchronized (parserQueueLock) {
            if (pendingRouteGeometry == null || worker == null) {
                routeGeometryDrainPosted = false;
                return;
            }
            if (worker.post(routeGeometryDrain)) return;
            routeGeometryDrainPosted = false;
            pendingRouteGeometry = null;
        }
    }

    private void acceptRouteGeometry(@NonNull Client current, @NonNull String raw) {
        final NavigationRouteGeometryV2 next;
        try {
            next = NavigationRouteGeometryV2.fromJson(raw);
        } catch (IllegalArgumentException invalid) {
            replyError(current.messenger, "INVALID_PAYLOAD", invalid.getMessage());
            return;
        }
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

    private void sendPublishedClusterSurface() {
        SurfaceLease lease;
        synchronized (SURFACE_LOCK) {
            lease = publishedClusterSurface;
        }
        if (lease != null) sendClusterSurface(lease);
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
        final int width;
        final int height;
        final int dpi;
        synchronized (SURFACE_LOCK) {
            if (publishedSurface != lease) return;
            width = lease.width;
            height = lease.height;
            dpi = lease.dpi;
        }
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putParcelable(NavigationBridgeContract.KEY_SURFACE, lease.surface);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_WIDTH, width);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_HEIGHT, height);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_DPI, dpi);
        data.putLong(NavigationBridgeContract.KEY_SURFACE_GENERATION, lease.generation);
        send(current.messenger, NavigationBridgeContract.MSG_ATTACH_HUD_SURFACE, data);
        DiagnosticJournal.info("hud-map",
                "HUD surface lease sent to Navigator; generation=" + lease.generation
                        + ", size=" + width + "x" + height);
    }

    private void sendSurfaceDetach(long generation) {
        Client current = client;
        if (current == null || !supportsDirectHudMap(current)) return;
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putLong(NavigationBridgeContract.KEY_SURFACE_GENERATION, generation);
        send(current.messenger, NavigationBridgeContract.MSG_DETACH_HUD_SURFACE, data);
    }

    private void sendClusterSurface(@NonNull SurfaceLease lease) {
        Client current = client;
        if (current == null || !supportsDirectClusterMap(current)
                || !lease.surface.isValid()) return;
        final int width;
        final int height;
        final int dpi;
        synchronized (SURFACE_LOCK) {
            if (publishedClusterSurface != lease) return;
            width = lease.width;
            height = lease.height;
            dpi = lease.dpi;
        }
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putParcelable(NavigationBridgeContract.KEY_SURFACE, lease.surface);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_WIDTH, width);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_HEIGHT, height);
        data.putInt(NavigationBridgeContract.KEY_SURFACE_DPI, dpi);
        data.putLong(NavigationBridgeContract.KEY_SURFACE_GENERATION, lease.generation);
        send(current.messenger, NavigationBridgeContract.MSG_ATTACH_CLUSTER_SURFACE, data);
        DiagnosticJournal.info("cluster-map",
                "instrument-cluster surface lease sent to Navigator; generation="
                        + lease.generation + ", size=" + width + "x" + height);
    }

    private void sendClusterSurfaceDetach(long generation) {
        Client current = client;
        if (current == null || !supportsDirectClusterMap(current)) return;
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putLong(NavigationBridgeContract.KEY_SURFACE_GENERATION, generation);
        send(current.messenger, NavigationBridgeContract.MSG_DETACH_CLUSTER_SURFACE, data);
    }

    private void sendExternalCameras() {
        Client current = client;
        String raw = latestExternalCamerasJson;
        if (current == null || raw.isEmpty()
                || (current.capabilities
                & NavigationBridgeContract.CAP_EXTERNAL_CAMERA_OVERLAY) == 0L) return;
        Bundle data = new Bundle();
        data.putString(NavigationBridgeContract.KEY_SESSION_ID, current.sessionId);
        data.putString(NavigationBridgeContract.KEY_EXTERNAL_CAMERAS_JSON, raw);
        send(current.messenger, NavigationBridgeContract.MSG_EXTERNAL_CAMERAS, data);
    }

    private static boolean supportsDirectHudMap(@NonNull Client value) {
        long required = NavigationBridgeContract.CAP_HUD_INDEPENDENT_MAP_WINDOW
                | NavigationBridgeContract.CAP_HUD_DIRECT_SURFACE;
        return (value.capabilities & required) == required;
    }

    private static boolean supportsDirectClusterMap(@NonNull Client value) {
        long required = NavigationBridgeContract.CAP_CLUSTER_INDEPENDENT_MAP_WINDOW
                | NavigationBridgeContract.CAP_CLUSTER_DIRECT_SURFACE;
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
        NavigationSnapshotV2 direct = NavigationBridgeStateStore.snapshot();
        try {
            current.remote.unlinkToDeath(current.deathRecipient, 0);
        } catch (RuntimeException ignored) {}
        NavigationBridgeStateStore.endSession(current.sessionId);
        if (direct != null) {
            // Disconnect is also an explicit end-of-authority event. Clear once here so legacy
            // caches cannot revive the last direct route while Navigator is no longer connected.
            NavigationDataRepository.clear(this);
        }
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

    private static boolean send(@NonNull Messenger target, int what, @NonNull Bundle data) {
        Message response = Message.obtain(null, what);
        response.setData(data);
        try {
            target.send(response);
            return true;
        } catch (RemoteException ignored) {
            return false;
        }
    }

    /** Immutable hand-off from the Messenger main Looper to the bounded parser queue. */
    private static final class PendingPayload {
        @NonNull final Client client;
        @NonNull final String raw;

        PendingPayload(@NonNull Client client, @NonNull String raw) {
            this.client = client;
            this.raw = raw;
        }
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
        // Geometry is immutable per generation. A resize must create a new generation because
        // MapKit fixes an OffscreenMapWindow's viewport at construction time.
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
