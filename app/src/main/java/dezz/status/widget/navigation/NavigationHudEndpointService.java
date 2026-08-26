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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import dezz.status.widget.Preferences;

/**
 * Explicit-only Natro endpoint to which the patched Navigator process connects.
 *
 * <p>This first executable slice accepts versioned snapshots/route geometry and sends the two-map
 * configuration back to Navigator. It intentionally does not advertise a HUD Surface capability
 * until the compositor owns a real lifecycle-safe Surface lease.</p>
 */
public final class NavigationHudEndpointService extends Service {
    private static final String TAG = "NavigationHudEndpoint";
    private static final long HOST_CAPABILITIES =
            NavigationBridgeContract.CAP_NATRO_CONFIGURATION_HOST
                    | NavigationBridgeContract.CAP_NATRO_NAVIGATION_STATE_SINK;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper(), this::onMessage);
    @NonNull private final Messenger endpoint = new Messenger(handler);
    @Nullable private Client client;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null
                || !NavigationBridgeContract.NATRO_BIND_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Rejected bind without the exact v2 action");
            return null;
        }
        return endpoint.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnectCurrentClient();
        return false;
    }

    @Override
    public void onDestroy() {
        disconnectCurrentClient();
        super.onDestroy();
    }

    private boolean onMessage(@NonNull Message message) {
        final int sendingUid = message.sendingUid;
        if (!NavigationBridgeCallerVerifier.isTrustedNavigator(this, sendingUid)) {
            Log.w(TAG, "Rejected bridge message from uid=" + sendingUid);
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
                    // A future surface provider uses the generation to discard exactly one lease.
                    Log.i(TAG, "Navigator reported HUD surface loss, generation="
                            + message.getData().getLong(
                                    NavigationBridgeContract.KEY_SURFACE_GENERATION, -1L));
                    break;
                case NavigationBridgeContract.MSG_HEARTBEAT:
                    replyCapabilities(current.messenger);
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
        Log.i(TAG, "Authenticated Navigator bridge session started");
    }

    private void acceptSnapshot(@NonNull Client current, @NonNull Message message) {
        String raw = message.getData().getString(
                NavigationBridgeContract.KEY_SNAPSHOT_JSON, "");
        NavigationSnapshotV2 next = NavigationSnapshotV2.fromJson(raw);
        if (!NavigationBridgeStateStore.publishSnapshot(current.sessionId, next)) {
            replyError(current.messenger, "STALE_SNAPSHOT", "Sequence did not advance");
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
        String raw = new Preferences(this).navigationIntegrationConfigJson.get();
        NavigationIntegrationConfig config;
        try {
            config = raw.isEmpty()
                    ? new NavigationIntegrationConfig()
                    : NavigationIntegrationConfig.fromJson(raw);
        } catch (IllegalArgumentException invalidStoredValue) {
            config = new NavigationIntegrationConfig();
        }
        try {
            Bundle data = new Bundle();
            data.putString(NavigationBridgeContract.KEY_SESSION_ID, session);
            data.putString(NavigationBridgeContract.KEY_CONFIGURATION_JSON,
                    config.toJson().toString());
            send(target, NavigationBridgeContract.MSG_APPLY_CONFIGURATION, data);
        } catch (JSONException failure) {
            replyError(target, "CONFIGURATION_ERROR", failure.getMessage());
        }
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
}
