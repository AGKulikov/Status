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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.Output;
import dezz.status.widget.scenario.Result;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;

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
    private final ConnectorValueRegistry values;
    private final HaBrickConfigStore mainConfigs;
    private final PopupItemConfigStore popupConfigs;
    private final StateListener listener;
    @Nullable private MqttClient client;
    @Nullable private PowerManager.WakeLock wakeLock;
    @Nullable private WifiManager.WifiLock wifiLock;
    private String signature = "";
    private String statePrefix = "";
    private String commandPrefix = "";
    /** Runtime states seen in this process; demoted together when the MQTT session drops. */
    private final Set<String> observedStateKeys = new LinkedHashSet<>();

    public MqttController(@NonNull Context context, @NonNull Preferences prefs,
                          @NonNull AutomationStateStore states,
                          @NonNull ConnectorValueRegistry values,
                          @NonNull StateListener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.states = states;
        this.values = values;
        mainConfigs = new HaBrickConfigStore(prefs);
        popupConfigs = new PopupItemConfigStore(prefs);
        this.listener = listener;
    }

    /** Idempotently applies the latest settings; changed credentials restart only MQTT. */
    public synchronized void reconfigure() {
        String nextSignature = settingsSignature();
        if (Objects.equals(signature, nextSignature) && client != null) {
            // Brick configuration is intentionally not part of the transport signature. Re-map
            // the latest retained/raw value immediately after a source or display-rule edit;
            // waiting for the next MQTT publish could otherwise leave an old status indefinitely.
            applyAllBindingsFromRegistry();
            return;
        }
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

    /** Re-evaluates only display rule sets that explicitly read another connector. */
    public synchronized void reapplyCrossSourceBindings() {
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            if (item.displayRules == null || item.displayRules.usesOwnSource()) continue;
            reapplyMain(item);
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            if (item.displayRules == null || item.displayRules.usesOwnSource()) continue;
            reapplyPopup(item);
        }
    }

    /** Publishes the binding's exact payload to an explicit topic or legacy command id. */
    public synchronized void publishAction(@NonNull ActionBinding binding)
            throws java.io.IOException {
        MqttClient current = client;
        if (current == null || !lastConnected) throw new java.io.IOException("MQTT is not connected");
        MqttActionRequest request = MqttActionRequest.from(commandPrefix, binding);
        current.publish(request.topic, request.payload, false);
    }

    /** Backward-compatible programmatic API for callers that still provide a legacy action id. */
    public synchronized void publishAction(String actionId, @NonNull JSONObject payload)
            throws java.io.IOException {
        publishAction(ActionBinding.legacy(actionId, payload.toString()));
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
            synchronized (this) {
                observedStateKeys.add(scope + "|" + id);
            }
            if (bytes.length == 0) {
                clearValue(topic, scope, id);
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
            if (payload.optBoolean("clear", false)) {
                clearValue(topic, scope, id);
                return;
            }
            states.apply(scope, id, payload);
            publishRawValue(topic, scope, id, payload);
            applyBindingsForResource(topic, scope, id);
            listener.onStateChanged(scope, id);
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "Ignored invalid MQTT message on " + topic, e);
        }
    }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        lastConnected = connected;
        lastConnectionDetail = detail == null ? "" : detail;
        if (!connected) {
            values.markConnectorStale(ConnectorType.MQTT,
                    SourceBinding.DEFAULT_CONNECTOR_ID);
            markObservedStatesStale();
        }
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
        values.markConnectorStale(ConnectorType.MQTT, SourceBinding.DEFAULT_CONNECTOR_ID);
        markObservedStatesStale();
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

    private void publishRawValue(String topic, String scope, String id, JSONObject payload) {
        Object raw;
        if (payload.has("value")) raw = nullIfJsonNull(payload.opt("value"));
        else if (payload.has("state")) raw = nullIfJsonNull(payload.opt("state"));
        else if (payload.has("text")) raw = nullIfJsonNull(payload.opt("text"));
        else if (payload.has("visible")) raw = payload.optBoolean("visible");
        else raw = payload.toString();
        boolean available = !payload.has("available")
                || AutomationContract.parseBoolean(payload.opt("available"));
        boolean fresh = !payload.has("fresh")
                || AutomationContract.parseBoolean(payload.opt("fresh"));
        Map<String, Object> attributes = jsonObjectToMap(payload.optJSONObject("attributes"));
        long updatedAt = payload.optLong("updated_at", System.currentTimeMillis());
        String valueType = payload.optString("type",
                raw == null ? "" : raw.getClass().getSimpleName());
        String unit = payload.optString("unit", "");
        for (String resourceId : resourceAliases(topic, scope, id)) {
            values.upsert(new ConnectorValue(ConnectorType.MQTT,
                    SourceBinding.DEFAULT_CONNECTOR_ID, resourceId, raw, fresh, available,
                    true, payload.optBoolean("writable", false), valueType, unit, attributes,
                    updatedAt));
        }
    }

    private void clearValue(String topic, String scope, String id) throws JSONException {
        for (String resourceId : resourceAliases(topic, scope, id)) {
            values.remove(ConnectorType.MQTT, SourceBinding.DEFAULT_CONNECTOR_ID, resourceId);
        }
        JSONObject clear = new JSONObject();
        clear.put("clear", true);
        states.apply(scope, id, clear);
        clearBoundTargets(topic, scope, id, clear);
        listener.onStateChanged(scope, id);
    }

    private Set<String> resourceAliases(String topic, String scope, String id) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(scope + "/" + id);
        aliases.add(topic);
        // Legacy brick configs historically store only automationId. Keep that address working
        // while new configs use the unambiguous scope/id or full broker topic.
        if (AutomationContract.SCOPE_MAIN.equals(scope)
                || AutomationContract.SCOPE_POPUP.equals(scope)) {
            aliases.add(id);
        }
        return aliases;
    }

    private void applyBindingsForResource(String topic, String scope, String id) {
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            if (!matches(item.sourceBinding, topic, scope, id)) continue;
            applyMapped(AutomationContract.SCOPE_MAIN, item.id, item.sourceBinding,
                    item.displayRules, item.actionBinding != null && item.actionBinding.isBound());
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            if (!matches(item.sourceBinding, topic, scope, id)) continue;
            applyMapped(AutomationContract.SCOPE_POPUP, item.automationId, item.sourceBinding,
                    item.displayRules, item.actionBinding != null && item.actionBinding.isBound());
        }
    }

    /** Reprojects every configured MQTT target from the in-process retained-value registry. */
    private void applyAllBindingsFromRegistry() {
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            reapplyMain(item);
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            reapplyPopup(item);
        }
    }

    private void reapplyMain(HaBrickConfig item) {
        if (!isMqttFamily(item.sourceBinding)) return;
        if (!isMqtt(item.sourceBinding) || values.get(ConnectorType.MQTT,
                item.sourceBinding.connectorId, item.sourceBinding.resourceId) == null) {
            markTargetStale(AutomationContract.SCOPE_MAIN, item.id);
            return;
        }
        applyMapped(AutomationContract.SCOPE_MAIN, item.id, item.sourceBinding,
                item.displayRules, item.actionBinding != null && item.actionBinding.isBound());
    }

    private void reapplyPopup(PopupItemConfig item) {
        if (!isMqttFamily(item.sourceBinding)) return;
        if (!isMqtt(item.sourceBinding) || values.get(ConnectorType.MQTT,
                item.sourceBinding.connectorId, item.sourceBinding.resourceId) == null) {
            markTargetStale(AutomationContract.SCOPE_POPUP, item.automationId);
            return;
        }
        applyMapped(AutomationContract.SCOPE_POPUP, item.automationId, item.sourceBinding,
                item.displayRules, item.actionBinding != null && item.actionBinding.isBound());
    }

    private void clearBoundTargets(String topic, String scope, String id, JSONObject clear)
            throws JSONException {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            if (matches(item.sourceBinding, topic, scope, id)) {
                targets.add(AutomationContract.SCOPE_MAIN + "|" + item.id);
            }
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            if (matches(item.sourceBinding, topic, scope, id)) {
                targets.add(AutomationContract.SCOPE_POPUP + "|" + item.automationId);
            }
        }
        for (String target : targets) {
            int separator = target.indexOf('|');
            String targetScope = target.substring(0, separator);
            String targetId = target.substring(separator + 1);
            states.apply(targetScope, targetId, clear);
            listener.onStateChanged(targetScope, targetId);
        }
    }

    private void applyMapped(String scope, String id, SourceBinding binding,
                             @Nullable RuleSet configuredRules, boolean hasAction) {
        try {
            Input input = values.resolve(binding);
            RuleSet rules = configuredRules == null ? ScenarioPresets.raw() : configuredRules;
            Result result = rules.evaluate(input, values);
            Output output = result.output;
            String text = result.renderedText == null ? display(input.rawValue)
                    : result.renderedText;
            if (!binding.unitSuffix.isEmpty() && !text.endsWith(binding.unitSuffix)) {
                text += " " + binding.unitSuffix;
            }
            JSONObject patch = new JSONObject();
            patch.put("text", text);
            patch.put("color", output.textColor == null ? JSONObject.NULL : output.textColor);
            patch.put("icon", output.icon == null ? JSONObject.NULL : output.icon);
            patch.put("background_color", output.backgroundColor == null
                    ? JSONObject.NULL : output.backgroundColor);
            patch.put("visible", output.visible == null || output.visible);
            patch.put("action_enabled", output.actionEnabled == null
                    ? hasAction : hasAction && output.actionEnabled);
            patch.put("fresh", input.fresh);
            patch.put("source", ConnectorType.MQTT.jsonName());
            patch.put("updated_at", System.currentTimeMillis());
            states.apply(scope, id, patch);
            synchronized (this) {
                observedStateKeys.add(scope + "|" + id);
            }
            listener.onStateChanged(scope, id);
        } catch (JSONException | IllegalArgumentException error) {
            Log.w(TAG, "Could not apply MQTT display rules for " + binding.resourceId, error);
        }
    }

    private boolean matches(@Nullable SourceBinding binding, String topic, String scope,
                            String id) {
        if (binding == null || !binding.isBound() || binding.connectorType != ConnectorType.MQTT
                || !SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId)) {
            return false;
        }
        String resource = binding.resourceId;
        return resource.equals(scope + "/" + id) || resource.equals(topic)
                || ((AutomationContract.SCOPE_MAIN.equals(scope)
                || AutomationContract.SCOPE_POPUP.equals(scope)) && resource.equals(id));
    }

    private static boolean isMqtt(@Nullable SourceBinding binding) {
        return isMqttFamily(binding) && binding.isBound()
                && SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId);
    }

    private static boolean isMqttFamily(@Nullable SourceBinding binding) {
        return binding != null && binding.connectorType == ConnectorType.MQTT;
    }

    private void markTargetStale(String scope, String id) {
        states.markStale(scope, id);
        listener.onStateChanged(scope, id);
    }

    private void markObservedStatesStale() {
        List<String> keys;
        synchronized (this) {
            keys = new ArrayList<>(observedStateKeys);
        }
        for (String key : keys) {
            int separator = key.indexOf('|');
            if (separator <= 0 || separator == key.length() - 1) continue;
            String scope = key.substring(0, separator);
            String id = key.substring(separator + 1);
            states.markStale(scope, id);
            listener.onStateChanged(scope, id);
        }
    }

    @Nullable
    private static Object nullIfJsonNull(@Nullable Object value) {
        return value == JSONObject.NULL ? null : value;
    }

    @NonNull
    private static Map<String, Object> jsonObjectToMap(@Nullable JSONObject object) {
        if (object == null) return Collections.emptyMap();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext() && result.size() < 128) {
            String key = keys.next();
            result.put(key, jsonValue(object.opt(key)));
        }
        return result;
    }

    @Nullable
    private static Object jsonValue(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return jsonObjectToMap((JSONObject) value);
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) value;
            List<Object> result = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                result.add(jsonValue(array.opt(index)));
            }
            return result;
        }
        return value;
    }

    private static String display(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {}
        }
        return String.valueOf(value);
    }
}
