/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;

/** Bridges MQTT topics to the same reducer/cache used by explicit Broadcast updates. */
public final class MqttController implements MqttClient.Listener {
    private static final String TAG = "MqttController";
    private static volatile boolean lastConnected;
    private static volatile String lastConnectionDetail = "not started";

    public interface StateListener {
        void onStateChanged(String scope, String id);
        void onConnectionChanged(boolean connected, String detail);
    }

    private final Context context;
    private final Preferences prefs;
    private final AutomationStateStore states;
    private final StateListener listener;
    @Nullable private MqttClient client;
    @Nullable private PowerManager.WakeLock wakeLock;
    @Nullable private WifiManager.WifiLock wifiLock;
    private String signature = "";
    private String statePrefix = "";
    private String commandPrefix = "";

    public MqttController(@NonNull Context context, @NonNull Preferences prefs,
                          @NonNull AutomationStateStore states,
                          @NonNull StateListener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.states = states;
        this.listener = listener;
    }

    /** Idempotently applies the latest settings; changed credentials restart only MQTT. */
    public synchronized void reconfigure() {
        String nextSignature = settingsSignature();
        if (Objects.equals(signature, nextSignature) && client != null) return;
        stopLocked();
        signature = nextSignature;
        if (!prefs.mqttEnabled.get() || prefs.mqttHost.get().trim().isEmpty()) return;
        try {
            String base = trimSlashes(prefs.mqttBaseTopic.get());
            if (base.length() > 256 || base.indexOf('#') >= 0 || base.indexOf('+') >= 0
                    || base.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Invalid MQTT base topic");
            }
            if (prefs.mqttHost.get().trim().length() > 255
                    || prefs.mqttHost.get().contains("://")) {
                throw new IllegalArgumentException("MQTT host must not contain a URL scheme");
            }
            String device = AutomationContract.requireSafeId(prefs.mqttDeviceId.get());
            String root = base + "/" + device;
            statePrefix = root + "/state/";
            commandPrefix = root + "/command/";
            String configuredClientId = prefs.mqttClientId.get().trim();
            String clientId = configuredClientId.isEmpty() ? "status-widget-ha-" + device
                    : AutomationContract.requireSafeId(configuredClientId);
            int port = prefs.mqttPort.get();
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid MQTT port");
            MqttClient.Config config = new MqttClient.Config(
                    prefs.mqttHost.get().trim(), port, prefs.mqttTls.get(),
                    prefs.mqttUsername.get(), prefs.mqttPassword.get(), clientId,
                    statePrefix + "#", root + "/availability",
                    prefs.mqttKeepAliveSeconds.get(), prefs.mqttQos.get());
            client = new MqttClient(config, this);
            if (prefs.mqttKeepAwake.get()) acquireLocks();
            client.start();
        } catch (RuntimeException e) {
            client = null;
            lastConnected = false;
            lastConnectionDetail = "configuration error: " + e.getMessage();
            listener.onConnectionChanged(false, lastConnectionDetail);
            Log.w(TAG, "MQTT configuration rejected", e);
        }
    }

    public synchronized void stop() {
        signature = "";
        stopLocked();
    }

    public static boolean isConnected() { return lastConnected; }
    public static String connectionDetail() { return lastConnectionDetail; }

    public synchronized void publishAction(String actionId, @NonNull JSONObject payload)
            throws java.io.IOException {
        MqttClient current = client;
        if (current == null || !lastConnected) throw new java.io.IOException("MQTT is not connected");
        current.publish(commandPrefix + AutomationContract.requireSafeId(actionId),
                payload.toString(), false);
    }

    @Override
    public void onMessage(@NonNull String topic, @NonNull byte[] bytes, boolean retained) {
        if (!topic.startsWith(statePrefix)) return;
        if (bytes.length > AutomationContract.MAX_PAYLOAD_CHARS) {
            Log.w(TAG, "Ignored oversized MQTT payload on " + topic);
            return;
        }
        String remainder = topic.substring(statePrefix.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) return;
        String scope = remainder.substring(0, slash);
        String id = remainder.substring(slash + 1);
        if (AutomationContract.SCOPE_POPUP.equals(scope) && "_overlay".equals(id)) {
            scope = AutomationContract.SCOPE_OVERLAY;
            id = "popup";
        }
        try {
            scope = AutomationContract.normalizeScope(scope);
            id = AutomationContract.requireSafeId(id);
            if (bytes.length == 0) {
                JSONObject clear = new JSONObject();
                clear.put("clear", true);
                states.apply(scope, id, clear);
                listener.onStateChanged(scope, id);
                return;
            }
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            JSONObject payload;
            if (text.startsWith("{")) {
                payload = new JSONObject(text);
            } else {
                payload = new JSONObject();
                if (AutomationContract.SCOPE_BUILTIN.equals(scope)
                        || AutomationContract.SCOPE_OVERLAY.equals(scope)) {
                    payload.put("visible", AutomationContract.parseBoolean(text));
                } else {
                    payload.put("text", text);
                }
            }
            states.apply(scope, id, payload);
            listener.onStateChanged(scope, id);
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "Ignored invalid MQTT message on " + topic, e);
        }
    }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        lastConnected = connected;
        lastConnectionDetail = detail == null ? "" : detail;
        listener.onConnectionChanged(connected, detail);
    }

    private String settingsSignature() {
        return prefs.mqttEnabled.get() + "|" + prefs.mqttHost.get() + "|" + prefs.mqttPort.get()
                + "|" + prefs.mqttTls.get() + "|" + prefs.mqttUsername.get() + "|"
                + prefs.mqttPassword.get() + "|" + prefs.mqttDeviceId.get() + "|"
                + prefs.mqttClientId.get() + "|" + prefs.mqttBaseTopic.get() + "|"
                + prefs.mqttQos.get() + "|" + prefs.mqttKeepAliveSeconds.get() + "|"
                + prefs.mqttKeepAwake.get();
    }

    private synchronized void stopLocked() {
        if (client != null) client.stop();
        client = null;
        lastConnected = false;
        lastConnectionDetail = prefs.mqttEnabled.get() ? "disconnected" : "disabled";
        releaseLocks();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StatusWidgetHA:mqtt");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "StatusWidgetHA:mqtt");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
    }

    private static String trimSlashes(String value) {
        String result = value == null ? "" : value.trim();
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? "statuswidget/v1" : result;
    }
}
