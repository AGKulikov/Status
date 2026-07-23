/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ActionBinding;
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

/** Long-lived direct Home Assistant connector and connector-neutral value adapter. */
public final class HaApiController implements HaWebSocketConnector.Listener {
    private static final String TAG = "HaApiController";
    private static volatile HaApiController activeInstance;
    private static volatile String lastDetail = "not started";

    public interface Listener {
        void onStateChanged(@NonNull String scope, @NonNull String id);
        void onConnectionChanged(@NonNull HaWebSocketConnector.ConnectionState state,
                                 @NonNull String detail);
        void onCatalogChanged(@NonNull HaEntityCatalog catalog);
    }

    private final Context context;
    private final Preferences prefs;
    private final AutomationStateStore states;
    private final ConnectorValueRegistry values;
    private final HaBrickConfigStore mainConfigs;
    private final PopupItemConfigStore popupConfigs;
    private volatile List<HaBrickConfig> configuredMain = Collections.emptyList();
    private volatile List<PopupItemConfig> configuredPopup = Collections.emptyList();
    private final Listener listener;
    private final HaWebSocketConnector connector;
    private final OkHttpClient http = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private PowerManager.WakeLock wakeLock;
    @Nullable private WifiManager.WifiLock wifiLock;
    @Nullable private CompletableFuture<HaEntityCatalog> refreshFuture;
    private String signature = "";
    private boolean started;

    public HaApiController(@NonNull Context context, @NonNull Preferences prefs,
                           @NonNull AutomationStateStore states,
                           @NonNull ConnectorValueRegistry values,
                           @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.states = states;
        this.values = values;
        this.listener = listener;
        mainConfigs = new HaBrickConfigStore(prefs);
        popupConfigs = new PopupItemConfigStore(prefs);
        connector = new HaWebSocketConnector(this);
    }

    @Nullable public static HaApiController active() { return activeInstance; }

    @NonNull public static String connectionDetail() { return lastDetail; }

    @NonNull public HaEntityCatalog catalog() { return connector.catalog(); }

    /** Whether commands may be based on the authoritative snapshot of this session. */
    public boolean isOnline() { return connector.isOnline(); }

    /** Re-evaluates only display rule sets that explicitly read another connector. */
    public void reapplyCrossSourceBindings() {
        HaEntityCatalog current = connector.catalog();
        boolean fresh = connector.isOnline();
        for (HaBrickConfig item : configuredMain) {
            if (item.displayRules == null || item.displayRules.usesOwnSource()) continue;
            applyMain(item, current, fresh, null);
        }
        for (PopupItemConfig item : configuredPopup) {
            if (item.displayRules == null || item.displayRules.usesOwnSource()) continue;
            applyPopup(item, current, fresh, null);
        }
    }

    /** Re-renders popup presentation from the current authoritative HA catalog. No transport
     * restart or network request is performed. */
    public void reapplyPopupBindings() {
        configuredPopup = popupConfigs.load();
        HaEntityCatalog current = connector.catalog();
        boolean fresh = connector.isOnline();
        for (PopupItemConfig item : configuredPopup) {
            applyPopup(item, current, fresh, null);
        }
    }

    /** Main-row counterpart of {@link #reapplyPopupBindings()}. */
    public void reapplyMainBindings() {
        configuredMain = mainConfigs.loadMain();
        HaEntityCatalog current = connector.catalog();
        boolean fresh = connector.isOnline();
        for (HaBrickConfig item : configuredMain) {
            applyMain(item, current, fresh, null);
        }
    }

    public synchronized void reconfigure() {
        configuredMain = mainConfigs.loadMain();
        configuredPopup = popupConfigs.load();
        String next = settingsSignature();
        if (Objects.equals(signature, next) && started) {
            applyAllBindings(connector.catalog(), connector.isOnline());
            return;
        }
        signature = next;
        stopTransport();
        markAllStale();
        if (!isConfigured()) {
            if (activeInstance == this) activeInstance = null;
            lastDetail = prefs.haApiEnabled.get() ? "configuration incomplete" : "disabled";
            listener.onConnectionChanged(HaWebSocketConnector.ConnectionState.STOPPED, lastDetail);
            return;
        }
        try {
            if (prefs.haKeepAwake.get()) acquireLocks();
            activeInstance = this;
            started = true;
            connector.start(new HaWebSocketConnector.Config(prefs.haBaseUrl.get(),
                    prefs.haAccessToken.get()));
        } catch (RuntimeException error) {
            started = false;
            releaseLocks();
            if (activeInstance == this) activeInstance = null;
            lastDetail = "configuration error: " + safeMessage(error);
            listener.onConnectionChanged(HaWebSocketConnector.ConnectionState.STOPPED,
                    lastDetail);
        }
    }

    public synchronized void stop() {
        signature = "";
        stopTransport();
        connector.close();
        main.removeCallbacksAndMessages(null);
        // This REST client is private to the controller. Cancel delayed/in-flight commands and
        // release its threads/sockets when WidgetService is destroyed.
        http.dispatcher().cancelAll();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        markAllStale();
        if (activeInstance == this) activeInstance = null;
    }

    @NonNull
    public synchronized CompletableFuture<HaEntityCatalog> refreshCatalog() {
        if (!connector.isOnline()) return failed(new IOException("Home Assistant is not online"));
        if (refreshFuture != null && !refreshFuture.isDone()) return refreshFuture;
        refreshFuture = new CompletableFuture<>();
        try {
            connector.refreshSnapshot();
        } catch (RuntimeException error) {
            refreshFuture.completeExceptionally(error);
        }
        return refreshFuture;
    }

    /** Executes a safe Home Assistant service call for a bound entity via the direct REST API. */
    @NonNull
    public CompletableFuture<JSONObject> execute(@NonNull ActionBinding binding) {
        if (binding.connectorType != ConnectorType.HOME_ASSISTANT || !binding.isBound()
                || !SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId)) {
            return failed(new IllegalArgumentException("Not a Home Assistant action"));
        }
        if (!ActionBinding.OPERATION_SET.equals(binding.operation)
                && !ActionBinding.OPERATION_TOGGLE.equals(binding.operation)) {
            return failed(new IllegalArgumentException(
                    "Home Assistant supports only SET and TOGGLE actions"));
        }
        if (!connector.isOnline()) return failed(new IOException("Home Assistant is not online"));
        final ServiceCall serviceCall;
        try {
            serviceCall = serviceCall(binding);
        } catch (JSONException | IllegalArgumentException error) {
            return failed(error);
        }
        HaWebSocketConnector.Config activeConfig = connector.config();
        String base = activeConfig == null ? prefs.haBaseUrl.get().trim()
                : activeConfig.baseUrl();
        if (base.startsWith("ws://")) base = "http://" + base.substring(5);
        else if (base.startsWith("wss://")) base = "https://" + base.substring(6);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/api/websocket")) {
            base = base.substring(0, base.length() - "/api/websocket".length());
        }
        if (base.endsWith("/api")) base = base.substring(0, base.length() - 4);
        RequestBody body = RequestBody.create(serviceCall.data.toString(),
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(base + "/api/services/" + serviceCall.domain + "/" + serviceCall.service)
                .header("Authorization", "Bearer " + prefs.haAccessToken.get())
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
                future.completeExceptionally(error);
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    String text = closeable.body() == null ? "" : closeable.body().string();
                    if (!closeable.isSuccessful()) {
                        future.completeExceptionally(new IOException(
                                "Home Assistant HTTP " + closeable.code()));
                        return;
                    }
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("response", text);
                    future.complete(result);
                    // state_changed normally arrives first; a delayed snapshot is a fallback for
                    // integrations that acknowledge a command without emitting an event.
                    main.postDelayed(() -> {
                        try { connector.refreshSnapshot(); }
                        catch (RuntimeException ignored) {}
                    }, 2_000L);
                } catch (Exception error) {
                    future.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    @Override
    public void onConnectionStateChanged(HaWebSocketConnector.ConnectionState state,
                                         String detail) {
        lastDetail = detail == null ? "" : detail;
        if (state != HaWebSocketConnector.ConnectionState.ONLINE) {
            values.markConnectorStale(ConnectorType.HOME_ASSISTANT,
                    SourceBinding.DEFAULT_CONNECTOR_ID);
            markBoundStatesStale();
        }
        if (state == HaWebSocketConnector.ConnectionState.AUTH_FAILED
                || state == HaWebSocketConnector.ConnectionState.RECONNECT_WAIT) {
            CompletableFuture<HaEntityCatalog> pending;
            synchronized (this) {
                pending = refreshFuture;
                refreshFuture = null;
            }
            if (pending != null) {
                pending.completeExceptionally(new IOException(lastDetail));
            }
        }
        listener.onConnectionChanged(state, lastDetail);
    }

    @Override
    public void onSnapshot(HaEntityCatalog catalog) {
        ArrayList<ConnectorValue> snapshot = new ArrayList<>(catalog.size());
        for (HaEntity entity : catalog.values()) snapshot.add(toConnectorValue(entity, true));
        values.replaceSnapshot(ConnectorType.HOME_ASSISTANT,
                SourceBinding.DEFAULT_CONNECTOR_ID, snapshot);
        applyAllBindings(catalog, true);
        listener.onCatalogChanged(catalog);
        CompletableFuture<HaEntityCatalog> pending;
        synchronized (this) {
            pending = refreshFuture;
            refreshFuture = null;
        }
        if (pending != null) pending.complete(catalog);
    }

    @Override
    public void onEntityUpdated(HaEntityCatalog.EntityUpdate update) {
        HaEntity entity = update.current();
        if (entity == null) {
            values.remove(ConnectorType.HOME_ASSISTANT, SourceBinding.DEFAULT_CONNECTOR_ID,
                    update.entityId());
            markEntityStale(update.entityId());
            return;
        }
        values.upsert(toConnectorValue(entity, true));
        applyBindingsForEntity(entity.entityId(), connector.catalog(), true);
    }

    private void applyAllBindings(HaEntityCatalog catalog, boolean fresh) {
        for (HaBrickConfig item : configuredMain) applyMain(item, catalog, fresh, null);
        for (PopupItemConfig item : configuredPopup) applyPopup(item, catalog, fresh, null);
    }

    private void applyBindingsForEntity(String entityId, HaEntityCatalog catalog, boolean fresh) {
        for (HaBrickConfig item : configuredMain) applyMain(item, catalog, fresh, entityId);
        for (PopupItemConfig item : configuredPopup) applyPopup(item, catalog, fresh, entityId);
    }

    private void applyMain(HaBrickConfig item, HaEntityCatalog catalog, boolean fresh,
                           @Nullable String changedEntity) {
        SourceBinding binding = item.sourceBinding;
        if (!isHaFamily(binding)) return;
        if (!isHa(binding)) {
            markTargetStale(AutomationContract.SCOPE_MAIN, item.id);
            return;
        }
        if (changedEntity != null && !changedEntity.equals(binding.resourceId)) return;
        HaEntity entity = catalog.find(binding.resourceId);
        if (entity == null) {
            markTargetStale(AutomationContract.SCOPE_MAIN, item.id);
            return;
        }
        applyMapped(AutomationContract.SCOPE_MAIN, item.id, entity, binding, item.displayRules,
                item.actionBinding != null && item.actionBinding.isBound(), fresh);
    }

    private void applyPopup(PopupItemConfig item, HaEntityCatalog catalog, boolean fresh,
                            @Nullable String changedEntity) {
        SourceBinding binding = item.sourceBinding;
        if (!isHaFamily(binding)) return;
        if (!isHa(binding)) {
            markTargetStale(AutomationContract.SCOPE_POPUP, item.automationId);
            return;
        }
        if (changedEntity != null && !changedEntity.equals(binding.resourceId)) return;
        HaEntity entity = catalog.find(binding.resourceId);
        if (entity == null) {
            markTargetStale(AutomationContract.SCOPE_POPUP, item.automationId);
            return;
        }
        applyMapped(AutomationContract.SCOPE_POPUP, item.automationId, entity, binding,
                item.displayRules, item.actionBinding != null && item.actionBinding.isBound(),
                fresh);
    }

    private void applyMapped(String scope, String id, HaEntity entity, SourceBinding binding,
                             @Nullable RuleSet configuredRules, boolean hasAction,
                             boolean fresh) {
        try {
            Input input = values.resolve(binding);
            RuleSet rules = configuredRules == null ? ScenarioPresets.raw() : configuredRules;
            Result result = rules.evaluate(input, values);
            Output output = result.output;
            String text = result.renderedText == null ? display(input.rawValue)
                    : result.renderedText;
            String suffix = binding.unitSuffix;
            if (suffix.isEmpty() && SourceBinding.PRESENTATION_TEMPERATURE.equals(
                    binding.presentation)) suffix = input.unit;
            if (!suffix.isEmpty() && !text.endsWith(suffix)) text += " " + suffix;
            JSONObject patch = new JSONObject();
            patch.put("text", text);
            patch.put("color", output.textColor == null ? JSONObject.NULL : output.textColor);
            patch.put("icon", output.icon == null ? JSONObject.NULL : output.icon);
            patch.put("background_color", output.backgroundColor == null
                    ? JSONObject.NULL : output.backgroundColor);
            patch.put("visible", output.visible == null || output.visible);
            patch.put("action_enabled", output.actionEnabled == null
                    ? hasAction : hasAction && output.actionEnabled);
            patch.put("fresh", fresh);
            patch.put("source", ConnectorType.HOME_ASSISTANT.jsonName());
            patch.put("updated_at", System.currentTimeMillis());
            states.apply(scope, id, patch);
            listener.onStateChanged(scope, id);
        } catch (JSONException | IllegalArgumentException error) {
            Log.w(TAG, "Could not map Home Assistant entity " + entity.entityId(), error);
        }
    }

    @NonNull
    private ConnectorValue toConnectorValue(HaEntity entity, boolean fresh) {
        String state = entity.state();
        boolean available = !"unavailable".equalsIgnoreCase(state)
                && !"unknown".equalsIgnoreCase(state);
        String unit = string(entity.attribute("unit_of_measurement"));
        String valueType = firstNonBlank(string(entity.attribute("device_class")),
                entity.domain());
        return new ConnectorValue(ConnectorType.HOME_ASSISTANT,
                SourceBinding.DEFAULT_CONNECTOR_ID, entity.entityId(), state, fresh, available,
                true, isWritableDomain(entity.domain()), valueType, unit, entity.attributes(),
                System.currentTimeMillis());
    }

    private void markAllStale() {
        values.markConnectorStale(ConnectorType.HOME_ASSISTANT,
                SourceBinding.DEFAULT_CONNECTOR_ID);
        markBoundStatesStale();
    }

    private void markTargetStale(String scope, String id) {
        states.markStale(scope, id);
        listener.onStateChanged(scope, id);
    }

    private void markBoundStatesStale() {
        for (HaBrickConfig item : configuredMain) {
            if (!isHaFamily(item.sourceBinding)) continue;
            states.markStale(AutomationContract.SCOPE_MAIN, item.id);
            listener.onStateChanged(AutomationContract.SCOPE_MAIN, item.id);
        }
        for (PopupItemConfig item : configuredPopup) {
            if (!isHaFamily(item.sourceBinding)) continue;
            states.markStale(AutomationContract.SCOPE_POPUP, item.automationId);
            listener.onStateChanged(AutomationContract.SCOPE_POPUP, item.automationId);
        }
    }

    private void markEntityStale(String entityId) {
        for (HaBrickConfig item : configuredMain) {
            if (!isHaFamily(item.sourceBinding)
                    || !entityId.equals(item.sourceBinding.resourceId)) {
                continue;
            }
            states.markStale(AutomationContract.SCOPE_MAIN, item.id);
            listener.onStateChanged(AutomationContract.SCOPE_MAIN, item.id);
        }
        for (PopupItemConfig item : configuredPopup) {
            if (!isHaFamily(item.sourceBinding)
                    || !entityId.equals(item.sourceBinding.resourceId)) {
                continue;
            }
            states.markStale(AutomationContract.SCOPE_POPUP, item.automationId);
            listener.onStateChanged(AutomationContract.SCOPE_POPUP, item.automationId);
        }
    }

    private synchronized void stopTransport() {
        if (started) connector.stop();
        started = false;
        releaseLocks();
        if (refreshFuture != null && !refreshFuture.isDone()) {
            refreshFuture.completeExceptionally(new IOException("Home Assistant stopped"));
        }
        refreshFuture = null;
    }

    private boolean isConfigured() {
        return prefs.haApiEnabled.get() && !prefs.haBaseUrl.get().trim().isEmpty()
                && !prefs.haAccessToken.get().trim().isEmpty();
    }

    private String settingsSignature() {
        return prefs.haApiEnabled.get() + "|" + prefs.haBaseUrl.get() + "|"
                + prefs.haAccessToken.get() + "|" + prefs.haKeepAwake.get();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StatusWidgetHA:ha-api");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "StatusWidgetHA:ha-api");
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

    private static boolean isHa(@Nullable SourceBinding binding) {
        return isHaFamily(binding) && binding.isBound()
                && SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId);
    }

    private static boolean isHaFamily(@Nullable SourceBinding binding) {
        return binding != null && binding.connectorType == ConnectorType.HOME_ASSISTANT;
    }

    private static boolean isWritableDomain(String domain) {
        switch (domain) {
            case "button":
            case "cover":
            case "fan":
            case "input_boolean":
            case "input_button":
            case "light":
            case "lock":
            case "scene":
            case "script":
            case "switch":
                return true;
            default: return false;
        }
    }

    @NonNull
    private ServiceCall serviceCall(ActionBinding binding) throws JSONException {
        String entityId = binding.resourceId.trim();
        int separator = entityId.indexOf('.');
        if (separator <= 0 || separator == entityId.length() - 1) {
            throw new IllegalArgumentException("Invalid Home Assistant entity id");
        }
        String domain = entityId.substring(0, separator);
        JSONObject payload = parsePayload(binding.payload);
        String requested = payload.optString("service", "").trim();
        String serviceDomain = payload.optString("domain", domain).trim();
        String service = "";
        if (!requested.isEmpty()) {
            int dot = requested.indexOf('.');
            if (dot > 0 && dot < requested.length() - 1) {
                serviceDomain = requested.substring(0, dot);
                service = requested.substring(dot + 1);
            } else service = requested;
        }
        JSONObject data = payload.optJSONObject("data");
        if (data == null) {
            data = new JSONObject(payload.toString());
            data.remove("service");
            data.remove("domain");
        } else data = new JSONObject(data.toString());
        data.put("entity_id", entityId);

        if (service.isEmpty() && ActionBinding.OPERATION_TOGGLE.equals(binding.operation)) {
            HaEntity entity = connector.catalog().find(entityId);
            String current = entity == null ? "" : entity.state().toLowerCase(Locale.ROOT);
            if ("cover".equals(domain)) {
                service = ("open".equals(current) || "opening".equals(current))
                        ? "close_cover" : "open_cover";
            } else if ("lock".equals(domain)) {
                service = "locked".equals(current) ? "unlock" : "lock";
            } else if ("button".equals(domain) || "input_button".equals(domain)) {
                service = "press";
            }
            else if ("scene".equals(domain) || "script".equals(domain)) service = "turn_on";
            else service = "toggle";
        }
        if (service.isEmpty() && ActionBinding.OPERATION_SET.equals(binding.operation)) {
            Object value = data.has("value") ? data.opt("value") : null;
            if (value != null && value != JSONObject.NULL) data.remove("value");
            Boolean bool = booleanValue(value);
            if (bool != null) service = bool ? "turn_on" : "turn_off";
            else if ("cover".equals(domain) && value != null) {
                String target = String.valueOf(value).toLowerCase(Locale.ROOT);
                if ("open".equals(target)) service = "open_cover";
                else if ("closed".equals(target) || "close".equals(target)) {
                    service = "close_cover";
                }
            }
        }
        if (service.isEmpty()) {
            throw new IllegalArgumentException("Home Assistant action needs a service or value");
        }
        if (!safeServiceName(serviceDomain) || !safeServiceName(service)) {
            throw new IllegalArgumentException("Invalid Home Assistant service name");
        }
        return new ServiceCall(serviceDomain, service, data);
    }

    private static JSONObject parsePayload(String raw) throws JSONException {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "{}".equals(value)) return new JSONObject();
        if (value.startsWith("{")) return new JSONObject(value);
        JSONObject result = new JSONObject();
        Object decoded = new org.json.JSONArray("[" + value + "]").get(0);
        result.put("value", decoded);
        return result;
    }

    @Nullable
    private static Boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0d;
        if (value == null || value == JSONObject.NULL) return null;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "on".equals(text) || "1".equals(text)) return true;
        if ("false".equals(text) || "off".equals(text) || "0".equals(text)) return false;
        return null;
    }

    private static boolean safeServiceName(String value) {
        return value != null && value.matches("[a-z0-9_]+") && value.length() <= 128;
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

    private static String string(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static final class ServiceCall {
        final String domain;
        final String service;
        final JSONObject data;
        ServiceCall(String domain, String service, JSONObject data) {
            this.domain = domain;
            this.service = service;
            this.data = data;
        }
    }
}
