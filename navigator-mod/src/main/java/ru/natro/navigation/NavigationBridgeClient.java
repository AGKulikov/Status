/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;

import java.util.UUID;

/** Explicit, signature-pinned Navigator client for the Natro-owned HUD endpoint. */
final class NavigationBridgeClient {
    private static final int PROTOCOL_VERSION = 2;
    private static final String NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi";
    private static final String NATRO_PACKAGE = "ru.natro.statuswidget";
    private static final String NATRO_SERVICE =
            "dezz.status.widget.navigation.NavigationHudEndpointService";
    private static final String BIND_ACTION = "ru.natro.navigation.bridge.BIND_V2";
    private static final Uri CONFIGURATION_PROVIDER_URI = Uri.parse(
            "content://ru.natro.statuswidget.navigation.configuration");
    private static final String CONFIGURATION_PROVIDER_METHOD =
            "get_navigation_configuration_v2";

    private static final int MSG_HELLO = 1;
    private static final int MSG_CAPABILITIES = 2;
    private static final int MSG_APPLY_CONFIGURATION = 3;
    private static final int MSG_ATTACH_HUD_SURFACE = 4;
    private static final int MSG_DETACH_HUD_SURFACE = 5;
    private static final int MSG_REQUEST_SNAPSHOT = 6;
    private static final int MSG_NAVIGATION_SNAPSHOT = 7;
    private static final int MSG_REQUEST_ROUTE_GEOMETRY = 8;
    private static final int MSG_ROUTE_GEOMETRY = 9;
    private static final int MSG_SET_MAIN_WINDOW_MODE = 11;
    private static final int MSG_HUD_SURFACE_LOST = 12;
    private static final int MSG_HEARTBEAT = 13;
    private static final int MSG_DIAGNOSTIC = 14;

    private static final long CAP_NAVIGATION_SNAPSHOT = 1L;
    private static final long CAP_ROUTE_GEOMETRY = 1L << 1;
    private static final long CAP_LANES = 1L << 2;
    private static final long CAP_MAIN_FLOATING_WINDOW = 1L << 5;
    private static final long CAP_HUD_INDEPENDENT_MAP_WINDOW = 1L << 6;
    private static final long CAP_HUD_DIRECT_SURFACE = 1L << 7;
    private static final long CAP_NAVIGATOR_WINDOW_BUTTON = 1L << 8;
    private static final long CAP_LEGACY_WINDOW_INTENTS = 1L << 9;

    private static final String KEY_PROTOCOL_VERSION = "protocol_version";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_CLIENT_PACKAGE = "client_package";
    private static final String KEY_CAPABILITIES = "capabilities";
    private static final String KEY_CONFIGURATION_JSON = "configuration_json";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";
    private static final String KEY_ROUTE_GEOMETRY_JSON = "route_geometry_json";
    private static final String KEY_SURFACE = "surface";
    private static final String KEY_SURFACE_WIDTH = "surface_width";
    private static final String KEY_SURFACE_HEIGHT = "surface_height";
    private static final String KEY_SURFACE_GENERATION = "surface_generation";
    private static final String KEY_ERROR_DETAIL = "error_detail";
    private static final String KEY_WINDOW_MODE = "window_mode";

    private static final long MIN_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 30_000L;
    private static final int MAX_CONFIGURATION_CHARS = 384 * 1024;
    private static NavigationBridgeClient instance;

    private final Context context;
    private final Handler main;
    private final Messenger callbacks;
    private final MainMapController mainMapController;
    private final HudMapRenderer hudMapRenderer;
    private final NavigatorStatePublisher statePublisher;
    private final String sessionId = UUID.randomUUID().toString();
    private Messenger remote;
    private boolean binding;
    private boolean bound;
    private long retryMs = MIN_RETRY_MS;
    private long lastConnectedElapsedMs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            bound = true;
            remote = new Messenger(service);
            retryMs = MIN_RETRY_MS;
            lastConnectedElapsedMs = SystemClock.elapsedRealtime();
            sendHello();
            sendDiagnostic("Navigator hook connected to Natro endpoint");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            disconnectAndRetry();
        }

        @Override public void onBindingDied(ComponentName name) {
            disconnectAndRetry();
        }

        @Override public void onNullBinding(ComponentName name) {
            disconnectAndRetry();
        }
    };

    static synchronized void ensureStarted(Context context) {
        if (instance == null) instance = new NavigationBridgeClient(context);
        instance.start();
    }

    static synchronized void attachActivity(Activity activity) {
        if (activity == null) return;
        ensureStarted(activity.getApplicationContext());
        instance.statePublisher.attach(activity);
    }

    static synchronized void detachActivity(Activity activity) {
        if (instance != null) instance.statePublisher.detach(activity);
    }

    /** Best-effort diagnostics from the host lifecycle hook; it must never throw into Navigator. */
    static synchronized void reportDiagnostic(String detail) {
        if (instance != null) instance.sendDiagnostic(detail);
    }

    /** Reads one current snapshot even when KX11 has not established the Messenger bridge yet. */
    static String readHostedConfiguration(Context source) {
        if (source == null) return null;
        try {
            Bundle result = source.getContentResolver().call(
                    CONFIGURATION_PROVIDER_URI, CONFIGURATION_PROVIDER_METHOD, null, null);
            if (result == null) return null;
            String raw = result.getString(KEY_CONFIGURATION_JSON);
            if (raw == null || raw.length() > MAX_CONFIGURATION_CHARS
                    || raw.indexOf('\u0000') >= 0) return null;
            return raw;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private NavigationBridgeClient(Context context) {
        this.context = context.getApplicationContext();
        main = new Handler(Looper.getMainLooper());
        callbacks = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
        mainMapController = new MainMapController(this.context);
        hudMapRenderer = new HudMapRenderer(this.context, this::sendSurfaceLost);
        statePublisher = new NavigatorStatePublisher(new NavigatorStatePublisher.Sink() {
            @Override public void onPrimaryMap(Object mapWindow, Object map) {
                mainMapController.attach(mapWindow, map);
            }

            @Override public void onPrimaryCamera(NavigatorStatePublisher.CameraState state) {
                mainMapController.updatePrimaryCamera(state);
                // Main-map gestures must never drive the independent HUD camera. The renderer may
                // consume only its first camera as a cold-start fallback; live following comes
                // from canonical Guidance location snapshots below.
                hudMapRenderer.updateInitialCamera(state);
            }

            @Override public void onNavigationState(String snapshotJson, String routeJson,
                                                     Object drivingRoute, long routeEpoch) {
                mainMapController.updateRoute(routeEpoch, drivingRoute);
                hudMapRenderer.updateRoute(routeEpoch, drivingRoute);
                // Install the current DrivingRoute wrapper before applying its snapshot so the
                // HUD trims against the exact RoutePosition that produced this state.
                hudMapRenderer.updateNavigationState(snapshotJson);
                sendState(MSG_NAVIGATION_SNAPSHOT, KEY_SNAPSHOT_JSON, snapshotJson);
                if (routeJson != null) {
                    sendState(MSG_ROUTE_GEOMETRY, KEY_ROUTE_GEOMETRY_JSON, routeJson);
                }
            }

            @Override public void onDiagnostic(String detail) {
                sendDiagnostic(detail);
            }
        });
    }

    private void start() {
        if (binding || bound) return;
        binding = true;
        Intent intent = new Intent(BIND_ACTION)
                .setComponent(new ComponentName(NATRO_PACKAGE, NATRO_SERVICE));
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                scheduleRetry();
            }
        } catch (RuntimeException failure) {
            binding = false;
            scheduleRetry();
        }
    }

    private boolean onMessage(Message message) {
        if (!isTrustedNatro(message.sendingUid)) return true;
        switch (message.what) {
            case MSG_CAPABILITIES:
                // A capability announcement never starts rendering by itself. Only a validated
                // MSG_ATTACH_HUD_SURFACE can create the independent MapWindow.
                break;
            case MSG_APPLY_CONFIGURATION:
                if (sessionMatches(message.getData())) {
                    String raw = message.getData().getString(KEY_CONFIGURATION_JSON, "");
                    NatroEntryPoint.applyConfiguration(raw);
                    mainMapController.applyConfiguration(raw);
                    hudMapRenderer.applyConfiguration(raw);
                }
                break;
            case MSG_ATTACH_HUD_SURFACE:
                if (sessionMatches(message.getData())) attachHudSurface(message.getData());
                break;
            case MSG_DETACH_HUD_SURFACE:
                if (sessionMatches(message.getData())) {
                    hudMapRenderer.detach(message.getData().getLong(
                            KEY_SURFACE_GENERATION, -1L));
                }
                break;
            case MSG_REQUEST_SNAPSHOT:
                if (sessionMatches(message.getData())) statePublisher.requestSnapshot();
                break;
            case MSG_REQUEST_ROUTE_GEOMETRY:
                if (sessionMatches(message.getData())) statePublisher.requestRoute();
                break;
            case MSG_SET_MAIN_WINDOW_MODE:
                if (sessionMatches(message.getData())) {
                    NatroEntryPoint.setWindowMode(message.getData().getInt(KEY_WINDOW_MODE, 2));
                }
                break;
            case MSG_HEARTBEAT:
                sendHello();
                break;
            default:
                break;
        }
        return true;
    }

    private void sendHello() {
        Messenger current = remote;
        if (current == null) return;
        Bundle data = new Bundle();
        data.putInt(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION);
        data.putString(KEY_SESSION_ID, sessionId);
        data.putString(KEY_CLIENT_PACKAGE, NAVIGATOR_PACKAGE);
        data.putLong(KEY_CAPABILITIES,
                CAP_NAVIGATION_SNAPSHOT
                        | CAP_ROUTE_GEOMETRY
                        | CAP_LANES
                        | CAP_MAIN_FLOATING_WINDOW
                        | CAP_HUD_INDEPENDENT_MAP_WINDOW
                        | CAP_HUD_DIRECT_SURFACE
                        | CAP_NAVIGATOR_WINDOW_BUTTON
                        | CAP_LEGACY_WINDOW_INTENTS);
        Message hello = Message.obtain(null, MSG_HELLO);
        hello.replyTo = callbacks;
        hello.setData(data);
        try {
            current.send(hello);
        } catch (RemoteException failure) {
            disconnectAndRetry();
        }
    }

    private boolean sessionMatches(Bundle data) {
        return sessionId.equals(data.getString(KEY_SESSION_ID, ""));
    }

    private void attachHudSurface(Bundle data) {
        data.setClassLoader(Surface.class.getClassLoader());
        Surface surface;
        try {
            surface = data.getParcelable(KEY_SURFACE);
        } catch (RuntimeException invalidParcel) {
            surface = null;
        }
        hudMapRenderer.attach(surface,
                data.getInt(KEY_SURFACE_WIDTH, 0),
                data.getInt(KEY_SURFACE_HEIGHT, 0),
                data.getLong(KEY_SURFACE_GENERATION, -1L));
    }

    private void sendSurfaceLost(long generation, String detail) {
        Messenger current = remote;
        if (current == null) return;
        Bundle data = new Bundle();
        data.putString(KEY_SESSION_ID, sessionId);
        data.putLong(KEY_SURFACE_GENERATION, generation);
        data.putString(KEY_ERROR_DETAIL, detail == null ? "" : detail);
        Message lost = Message.obtain(null, MSG_HUD_SURFACE_LOST);
        lost.replyTo = callbacks;
        lost.setData(data);
        try {
            current.send(lost);
        } catch (RemoteException failure) {
            disconnectAndRetry();
        }
    }

    private void sendState(int what, String key, String value) {
        Messenger current = remote;
        if (current == null || value == null || value.isEmpty()) return;
        Bundle data = new Bundle();
        data.putString(KEY_SESSION_ID, sessionId);
        data.putString(key, value);
        Message state = Message.obtain(null, what);
        state.replyTo = callbacks;
        state.setData(data);
        try {
            current.send(state);
        } catch (RemoteException failure) {
            disconnectAndRetry();
        }
    }

    private void sendDiagnostic(String detail) {
        Messenger current = remote;
        if (current == null || detail == null || detail.isEmpty()) return;
        Bundle data = new Bundle();
        data.putString(KEY_SESSION_ID, sessionId);
        data.putString(KEY_ERROR_DETAIL, detail);
        Message diagnostic = Message.obtain(null, MSG_DIAGNOSTIC);
        diagnostic.replyTo = callbacks;
        diagnostic.setData(data);
        try {
            current.send(diagnostic);
        } catch (RemoteException failure) {
            disconnectAndRetry();
        }
    }

    private boolean isTrustedNatro(int sendingUid) {
        if (sendingUid <= 0) return false;
        PackageManager packages = context.getPackageManager();
        String[] names;
        try {
            names = packages.getPackagesForUid(sendingUid);
        } catch (RuntimeException failure) {
            return false;
        }
        boolean exact = false;
        if (names != null) {
            for (String name : names) {
                if (NATRO_PACKAGE.equals(name)) exact = true;
            }
        }
        if (!exact) return false;
        try {
            return packages.checkSignatures(NAVIGATOR_PACKAGE, NATRO_PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void disconnectAndRetry() {
        hudMapRenderer.disconnect();
        remote = null;
        binding = false;
        if (bound) {
            bound = false;
            try { context.unbindService(connection); } catch (RuntimeException ignored) {}
        }
        scheduleRetry();
    }

    private void scheduleRetry() {
        main.removeCallbacks(retry);
        main.postDelayed(retry, retryMs);
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2L);
    }

    private final Runnable retry = this::start;
}
