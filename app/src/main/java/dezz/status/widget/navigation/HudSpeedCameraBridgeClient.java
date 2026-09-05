/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Signature-pinned, read-only client for the optional HUD Speed live-camera bridge. */
final class HudSpeedCameraBridgeClient {
    interface Listener {
        void onHudSpeedCameraFrame(@NonNull String normalizedJson);
    }

    private static final String TAG = "HudSpeedCameraBridge";
    private static final String HUD_SPEED_PACKAGE = "air.StrelkaHUDFREE";
    private static final String HUD_SPEED_SERVICE =
            "air.StrelkaSD.bridge.HudSpeedCameraBridgeService";
    private static final String BIND_ACTION = "ru.natro.hudspeed.camera.BIND_V1";
    /** Certificate of the supplied HUD_Speed_v76_L_v13_FineControls APK. */
    private static final String HUD_SPEED_CERT_SHA256 =
            "7963b6e8d32253b84a4af2f25462adfe22ccc6399ad30d31db1d90a0294d6481";
    private static final int MSG_REQUEST_CAMERAS = 1;
    private static final int MSG_CAMERA_FRAME = 2;
    private static final int PROTOCOL_VERSION = 1;
    private static final String KEY_PROTOCOL_VERSION = "protocol_version";
    private static final String KEY_CLIENT_PACKAGE = "client_package";
    private static final String KEY_CAMERAS_JSON = "cameras_json";
    private static final String KEY_ENSURE_RUNTIME = "ensure_runtime";
    private static final String KEY_RUNTIME_RUNNING = "runtime_running";
    private static final String KEY_WAKE_ATTEMPT = "wake_attempt";
    private static final int MAX_RAW_CHARS = 96 * 1024;
    private static final int MAX_CAMERAS = 64;
    private static final long POLL_MS = 1_000L;
    private static final long RETRY_MS = 10_000L;
    /** Startup checkpoints; an already-running HUD Speed consumes the checkpoint without a wake. */
    private static final long[] RUNTIME_WAKE_OFFSETS_MS = {
            0L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L
    };
    private static final Set<String> ALLOWED_TAGS = new HashSet<>(Arrays.asList(
            "SPEED_CONTROL", "LANE_CONTROL", "ROAD_MARKING_CONTROL",
            "TRAFFIC_CONTROL", "CROSS_ROAD_CONTROL", "NO_STOPPING_CONTROL",
            "MOBILE_CONTROL", "AVERAGE_SPEED_CONTROL", "ALL_RULES_CONTROL",
            "STOP_SIGN_CONTROL", "SHOULDER_CONTROL", "PEDESTRIAN_CONTROL",
            "TRUCK_CONTROL"));

    @NonNull private final Context context;
    @NonNull private final Listener listener;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final Messenger callbacks = new Messenger(
            new Handler(Looper.getMainLooper(), this::onMessage));
    private Messenger remote;
    private boolean bound;
    private boolean binding;
    private boolean started;
    private long runtimeWakeStartedElapsedMs = -1L;
    private int runtimeWakeOffsetIndex;
    private boolean hudRuntimeRunning;

    @NonNull private final Runnable poll = this::requestFrame;
    @NonNull private final Runnable retry = this::bind;
    @NonNull private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            if (!isTrustedHudSpeedPackage()) {
                safeUnbind();
                publishEmpty();
                return;
            }
            bound = true;
            remote = new Messenger(service);
            if (runtimeWakeStartedElapsedMs < 0L) {
                runtimeWakeStartedElapsedMs = SystemClock.elapsedRealtime();
            }
            requestFrame();
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

    HudSpeedCameraBridgeClient(@NonNull Context context, @NonNull Listener listener) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        bind();
    }

    void stop() {
        started = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(retry);
        safeUnbind();
        runtimeWakeStartedElapsedMs = -1L;
        runtimeWakeOffsetIndex = 0;
        hudRuntimeRunning = false;
        publishEmpty();
    }

    private void bind() {
        if (!started || bound || binding) return;
        main.removeCallbacks(retry);
        if (!isTrustedHudSpeedPackage()) {
            publishEmpty();
            main.postDelayed(retry, RETRY_MS);
            return;
        }
        binding = true;
        Intent intent = new Intent(BIND_ACTION).setComponent(
                new ComponentName(HUD_SPEED_PACKAGE, HUD_SPEED_SERVICE));
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                main.postDelayed(retry, RETRY_MS);
            }
        } catch (RuntimeException unavailable) {
            binding = false;
            main.postDelayed(retry, RETRY_MS);
        }
    }

    private void requestFrame() {
        main.removeCallbacks(poll);
        Messenger target = remote;
        if (!started || !bound || target == null) return;
        int wakeAttempt = dueRuntimeWakeAttempt();
        Message request = Message.obtain(null, MSG_REQUEST_CAMERAS);
        request.replyTo = callbacks;
        Bundle data = new Bundle();
        data.putInt(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION);
        data.putString(KEY_CLIENT_PACKAGE, context.getPackageName());
        data.putBoolean(KEY_ENSURE_RUNTIME, wakeAttempt > 0);
        if (wakeAttempt > 0) data.putInt(KEY_WAKE_ATTEMPT, wakeAttempt);
        request.setData(data);
        try {
            target.send(request);
            if (wakeAttempt > 0) {
                runtimeWakeOffsetIndex = wakeAttempt;
                Log.i(TAG, "Requested optional HUD Speed runtime wake checkpoint "
                        + wakeAttempt + '/' + RUNTIME_WAKE_OFFSETS_MS.length);
            }
            main.postDelayed(poll, POLL_MS);
        } catch (RemoteException dead) {
            disconnectAndRetry();
        }
    }

    /** Returns the latest due checkpoint, coalescing missed checkpoints after a reconnect. */
    private int dueRuntimeWakeAttempt() {
        if (runtimeWakeStartedElapsedMs < 0L
                || runtimeWakeOffsetIndex >= RUNTIME_WAKE_OFFSETS_MS.length) return 0;
        long elapsed = Math.max(0L,
                SystemClock.elapsedRealtime() - runtimeWakeStartedElapsedMs);
        int dueCount = runtimeWakeOffsetIndex;
        while (dueCount < RUNTIME_WAKE_OFFSETS_MS.length
                && elapsed >= RUNTIME_WAKE_OFFSETS_MS[dueCount]) {
            dueCount++;
        }
        if (dueCount == runtimeWakeOffsetIndex) return 0;
        if (hudRuntimeRunning) {
            // This checkpoint verified that no force-start was needed.
            runtimeWakeOffsetIndex = dueCount;
            return 0;
        }
        return dueCount;
    }

    private boolean onMessage(@NonNull Message message) {
        if (message.what != MSG_CAMERA_FRAME || !isTrustedHudSpeedUid(message.sendingUid)) {
            return true;
        }
        Bundle data = message.getData();
        hudRuntimeRunning = data.getBoolean(KEY_RUNTIME_RUNNING, false);
        String raw = data.getString(KEY_CAMERAS_JSON, "");
        String normalized = normalizeFrame(raw);
        if (normalized != null) listener.onHudSpeedCameraFrame(normalized);
        return true;
    }

    private String normalizeFrame(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_RAW_CHARS
                || raw.indexOf('\u0000') >= 0) return null;
        try {
            JSONObject source = new JSONObject(raw);
            if (source.optInt("schema", -1) != 1) return null;
            JSONArray input = source.optJSONArray("cameras");
            JSONArray output = new JSONArray();
            if (input != null) {
                for (int index = 0; index < input.length() && output.length() < MAX_CAMERAS;
                     index++) {
                    JSONObject camera = normalizeCamera(input.optJSONObject(index));
                    if (camera != null) output.put(camera);
                }
            }
            return new JSONObject()
                    .put("schema", 1)
                    .put("sampleElapsedMs", SystemClock.elapsedRealtime())
                    .put("cameras", output)
                    .toString();
        } catch (JSONException invalid) {
            return null;
        }
    }

    private static JSONObject normalizeCamera(JSONObject source) throws JSONException {
        if (source == null) return null;
        String id = source.optString("id", "");
        if (id.isEmpty() || id.length() > 96) return null;
        double latitude = source.optDouble("latitude", Double.NaN);
        double longitude = source.optDouble("longitude", Double.NaN);
        if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d
                || !Double.isFinite(longitude) || longitude < -180d
                || longitude > 180d) return null;
        JSONObject result = new JSONObject()
                .put("source", "HUD_SPEED")
                .put("id", id)
                .put("latitude", latitude)
                .put("longitude", longitude);
        int typeId = source.optInt("typeId", Integer.MIN_VALUE);
        if (typeId >= 0 && typeId <= 10_000) result.put("typeId", typeId);
        String typeLabel = source.optString("typeLabel", "");
        if (!typeLabel.isEmpty() && typeLabel.length() <= 64) {
            result.put("typeLabel", typeLabel);
        }
        int speed = source.optInt("speedLimit", -1);
        if (speed > 0 && speed <= 400) {
            result.put("speedLimit", speed);
            String unit = source.optString("speedUnit", "KPH");
            result.put("speedUnit", "MPH".equals(unit) ? "MPH" : "KPH");
        }
        JSONArray tags = new JSONArray();
        JSONArray inputTags = source.optJSONArray("controlTags");
        if (inputTags != null) {
            for (int index = 0; index < inputTags.length() && tags.length() < 8; index++) {
                String tag = inputTags.optString(index, "");
                if (ALLOWED_TAGS.contains(tag) && !contains(tags, tag)) tags.put(tag);
            }
        }
        result.put("controlTags", tags);
        JSONArray directions = new JSONArray();
        JSONArray inputDirections = source.optJSONArray("directions");
        if (inputDirections != null) {
            for (int index = 0; index < inputDirections.length()
                    && directions.length() < 2; index++) {
                double direction = inputDirections.optDouble(index, Double.NaN);
                if (!Double.isFinite(direction)) continue;
                double normalized = direction % 360d;
                directions.put(normalized < 0d ? normalized + 360d : normalized);
            }
        }
        result.put("directions", directions);
        return result;
    }

    private static boolean contains(JSONArray array, String value) {
        for (int index = 0; index < array.length(); index++) {
            if (value.equals(array.optString(index))) return true;
        }
        return false;
    }

    private void disconnectAndRetry() {
        main.removeCallbacks(poll);
        hudRuntimeRunning = false;
        safeUnbind();
        publishEmpty();
        if (started) main.postDelayed(retry, RETRY_MS);
    }

    private void safeUnbind() {
        remote = null;
        boolean hadBinding = bound || binding;
        bound = false;
        binding = false;
        if (hadBinding) {
            try { context.unbindService(connection); }
            catch (RuntimeException ignored) {}
        }
    }

    private void publishEmpty() {
        try {
            listener.onHudSpeedCameraFrame(new JSONObject()
                    .put("schema", 1)
                    .put("sampleElapsedMs", SystemClock.elapsedRealtime())
                    .put("cameras", new JSONArray())
                    .toString());
        } catch (JSONException impossible) {
            Log.w(TAG, "Could not create empty camera frame", impossible);
        }
    }

    private boolean isTrustedHudSpeedUid(int uid) {
        if (uid <= 0) return false;
        String[] names;
        try {
            names = context.getPackageManager().getPackagesForUid(uid);
        } catch (RuntimeException failure) {
            return false;
        }
        if (!NavigationBridgeCallerVerifier.containsExactPackage(names, HUD_SPEED_PACKAGE)) {
            return false;
        }
        return isTrustedHudSpeedPackage();
    }

    private boolean isTrustedHudSpeedPackage() {
        PackageManager packages = context.getPackageManager();
        try {
            // A locally patched HUD Speed may be signed with Natro's stable release key. Accept
            // that exact same signer as well as the signer of the supplied original APK.
            if (packages.checkSignatures(context.getPackageName(), HUD_SPEED_PACKAGE)
                    == PackageManager.SIGNATURE_MATCH) return true;
            PackageInfo info = packages.getPackageInfo(
                    HUD_SPEED_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signatures = info.signingInfo == null ? null
                    : info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            if (signatures == null) return false;
            for (Signature signature : signatures) {
                if (HUD_SPEED_CERT_SHA256.equals(sha256(signature.toByteArray()))) return true;
            }
        } catch (Exception unavailable) {
            return false;
        }
        return false;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
