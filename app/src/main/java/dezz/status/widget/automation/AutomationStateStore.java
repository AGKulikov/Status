/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import dezz.status.widget.AppProcessPolicy;

/**
 * Device-protected persistent runtime cache. MQTT retained messages and Broadcast updates pass
 * through exactly the same merge operation, so source switching cannot change UI semantics.
 */
public final class AutomationStateStore {
    private static final String PREF_SUFFIX = "_automation_state_v1";
    private static final String KEY_PREFIX = "state|";
    /** Serializes whole-file demotion with fresh writes from a replacement service instance. */
    private static final Object PERSISTENCE_LOCK = new Object();

    private final SharedPreferences prefs;
    /** True between visual-shell creation and the persisted boot-session freshness barrier. */
    private boolean sessionFreshnessBarrierPending;
    /** Derived local UI layer. Never persisted: it must be recomputed from fresh connector data. */
    private final Map<String, JSONObject> scenarioOverrides = new LinkedHashMap<>();

    public AutomationStateStore(@NonNull Context context) {
        Context device = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = device.getSharedPreferences(context.getPackageName() + PREF_SUFFIX,
                AppProcessPolicy.preferenceMode());
    }

    @NonNull
    public synchronized AutomationState get(String scope, String id) {
        String storageKey = key(scope, id);
        String raw = prefs.getString(storageKey, null);
        AutomationState base;
        if (raw == null) base = AutomationState.missing();
        else {
            try {
                base = AutomationState.fromJson(new JSONObject(raw));
            } catch (JSONException ignored) {
                base = AutomationState.missing();
            }
        }
        if (sessionFreshnessBarrierPending) base = base.asStale();
        return base.withLocalOverrides(scenarioOverrides.get(storageKey));
    }

    /** Makes cached values render stale immediately, before the later full preference rewrite. */
    public synchronized void beginSessionFreshnessBarrier() {
        sessionFreshnessBarrierPending = true;
    }

    /** Visibility with a caller-defined default. Scenario overrides have priority over retained
     * connector state; a missing state no longer forces every automation-only overlay visible. */
    public synchronized boolean effectiveVisibility(String scope, String id,
                                                    boolean defaultValue) {
        String storageKey = key(scope, id);
        JSONObject override = scenarioOverrides.get(storageKey);
        if (override != null && override.has("visible")) {
            return AutomationContract.parseBoolean(override.opt("visible"));
        }
        String raw = prefs.getString(storageKey, null);
        if (raw == null) return defaultValue;
        try {
            JSONObject state = new JSONObject(raw);
            return state.has("visible")
                    ? AutomationContract.parseBoolean(state.opt("visible")) : defaultValue;
        } catch (JSONException ignored) {
            return defaultValue;
        }
    }

    /**
     * Returns only an explicitly supplied visibility decision.
     *
     * <p>This is used by transient driver Favorites panels: missing automation state must preserve
     * what the driver opened manually, while an explicit false must close it.</p>
     */
    @Nullable
    public synchronized Boolean explicitVisibility(String scope, String id) {
        String storageKey = key(scope, id);
        JSONObject override = scenarioOverrides.get(storageKey);
        if (override != null && override.has("visible")) {
            return AutomationContract.parseBoolean(override.opt("visible"));
        }
        String raw = prefs.getString(storageKey, null);
        if (raw == null) return null;
        try {
            JSONObject state = new JSONObject(raw);
            return state.optBoolean("_visible_explicit", false) && state.has("visible")
                    ? AutomationContract.parseBoolean(state.opt("visible")) : null;
        } catch (JSONException ignored) {
            return null;
        }
    }

    /** Interaction with a caller-defined default, using scenario overrides before retained data. */
    public synchronized boolean effectiveActionEnabled(String scope, String id,
                                                       boolean defaultValue) {
        String storageKey = key(scope, id);
        JSONObject override = scenarioOverrides.get(storageKey);
        if (override != null && override.has("action_enabled")) {
            return AutomationContract.parseBoolean(override.opt("action_enabled"));
        }
        String raw = prefs.getString(storageKey, null);
        if (raw == null) return defaultValue;
        try {
            JSONObject state = new JSONObject(raw);
            if (state.has("action_enabled")) {
                return AutomationContract.parseBoolean(state.opt("action_enabled"));
            }
            if (state.has("enabled")) {
                return AutomationContract.parseBoolean(state.opt("enabled"));
            }
            return defaultValue;
        } catch (JSONException ignored) {
            return defaultValue;
        }
    }

    /** Partial-update merge. A payload with clear=true removes the retained local state. */
    @NonNull
    public synchronized AutomationState apply(String scope, String id, @NonNull JSONObject patch)
            throws JSONException {
        synchronized (PERSISTENCE_LOCK) {
            return applyLocked(scope, id, patch);
        }
    }

    /** Ownership-checked write used by bounded startup cleanup from a replaceable worker. */
    public synchronized boolean applyIf(@NonNull BooleanSupplier stillOwner,
                                        String scope, String id,
                                        @NonNull JSONObject patch) throws JSONException {
        synchronized (PERSISTENCE_LOCK) {
            if (!stillOwner.getAsBoolean()) return false;
            applyLocked(scope, id, patch);
            return true;
        }
    }

    @NonNull
    private AutomationState applyLocked(String scope, String id, @NonNull JSONObject patch)
            throws JSONException {
        validatePatch(patch);
        String storageKey = key(scope, id);
        if (patch.optBoolean("clear", false)) {
            prefs.edit().remove(storageKey).apply();
            return AutomationState.missing();
        }

        JSONObject merged;
        String previous = prefs.getString(storageKey, null);
        try {
            merged = previous == null ? new JSONObject() : new JSONObject(previous);
        } catch (JSONException ignored) {
            merged = new JSONObject();
        }

        Iterator<String> keys = patch.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!isStateField(name)) continue;
            Object value = patch.get(name);
            if (value == JSONObject.NULL) merged.remove(name); else merged.put(name, value);
        }
        if (patch.has("enabled") && !patch.has("action_enabled")) {
            merged.put("action_enabled", AutomationContract.parseBoolean(patch.opt("enabled")));
        }
        if (patch.has("visible")) merged.put("_visible_explicit", true);
        if (!patch.has("text")) {
            if (patch.has("state")) merged.put("text", String.valueOf(patch.opt("state")));
            else if (patch.has("value")) merged.put("text", String.valueOf(patch.opt("value")));
        }
        if (!patch.has("updated_at")) merged.put("updated_at", System.currentTimeMillis());
        // Every accepted connector update is a confirmation unless the caller explicitly marks
        // it stale. Cached values are demoted with markStale() during disconnect/restart.
        if (!patch.has("fresh")) merged.put("fresh", true);
        merged.put("visible", patch.has("visible")
                ? AutomationContract.parseBoolean(patch.opt("visible"))
                : merged.optBoolean("visible", true));
        merged.put("schema", AutomationContract.SCHEMA_VERSION);

        // SharedPreferences updates memory synchronously; disk persistence is queued. Blocking a
        // connector callback on a whole-file fsync for every temperature/progress packet caused
        // backlogs on low-end head units. HA/MQTT/Sprut restore an authoritative snapshot after
        // restart, so the tiny last-write power-loss window is preferable to runtime stalls.
        prefs.edit().putString(storageKey, merged.toString()).apply();
        return AutomationState.fromJson(merged);
    }

    public synchronized void clearAll() {
        synchronized (PERSISTENCE_LOCK) {
            prefs.edit().clear().commit();
            sessionFreshnessBarrierPending = false;
            scenarioOverrides.clear();
        }
    }

    /**
     * Atomically replaces every derived scenario override. Keys are canonical
     * {@code scope|id}; values may contain only the local presentation fields accepted below.
     * The layer is deliberately memory-only and therefore empty after every process start.
     */
    public synchronized void replaceScenarioOverrides(@NonNull Map<String, JSONObject> overrides) {
        LinkedHashMap<String, JSONObject> next = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> entry : overrides.entrySet()) {
            String rawKey = entry.getKey() == null ? "" : entry.getKey().trim();
            int separator = rawKey.indexOf('|');
            if (separator <= 0 || separator == rawKey.length() - 1) {
                throw new IllegalArgumentException("Invalid scenario target key");
            }
            String storageKey = key(rawKey.substring(0, separator),
                    rawKey.substring(separator + 1));
            JSONObject source = entry.getValue();
            if (source == null) continue;
            JSONObject filtered = new JSONObject();
            try {
                copyIfPresent(source, filtered, "text");
                copyIfPresent(source, filtered, "color");
                copyIfPresent(source, filtered, "icon");
                copyIfPresent(source, filtered, "background_color");
                copyIfPresent(source, filtered, "visible");
                copyIfPresent(source, filtered, "action_enabled");
                copyIfPresent(source, filtered, "border_color");
                copyIfPresent(source, filtered, "border_width");
                copyIfPresent(source, filtered, "icon_tint");
                copyIfPresent(source, filtered, "icon_background_color");
                copyIfPresent(source, filtered, "icon_outline_color");
                copyIfPresent(source, filtered, "icon_outline_width");
            } catch (JSONException impossible) {
                throw new IllegalArgumentException(impossible);
            }
            if (filtered.length() > 0) next.put(storageKey, filtered);
        }
        scenarioOverrides.clear();
        scenarioOverrides.putAll(next);
    }

    public synchronized void clearScenarioOverrides() {
        scenarioOverrides.clear();
    }

    /** Keeps the last value for display but forces the renderer to use its per-brick stale style. */
    public synchronized void markStale(String scope, String id) {
        synchronized (PERSISTENCE_LOCK) {
            String storageKey = key(scope, id);
            String previous = prefs.getString(storageKey, null);
            if (previous == null) return;
            try {
                JSONObject state = new JSONObject(previous);
                state.put("fresh", false);
                prefs.edit().putString(storageKey, state.toString()).apply();
            } catch (JSONException ignored) {
                prefs.edit().remove(storageKey).apply();
            }
        }
    }

    /** Boot/session barrier: no disk-cached network value is current until its connector syncs. */
    public synchronized void markAllStale() {
        markAllStaleIf(() -> true);
    }

    /**
     * Ownership-aware session barrier. A stopped service may finish parsing after its replacement
     * starts; the shared write lock plus the final owner check prevents that old pass from
     * demoting a value published by the replacement session.
     */
    public synchronized boolean markAllStaleIf(@NonNull BooleanSupplier stillOwner) {
        synchronized (PERSISTENCE_LOCK) {
            if (!stillOwner.getAsBoolean()) return false;
            SharedPreferences.Editor editor = prefs.edit();
            int checked = 0;
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if ((checked++ & 31) == 0 && !stillOwner.getAsBoolean()) return false;
                if (!entry.getKey().startsWith(KEY_PREFIX)
                        || !(entry.getValue() instanceof String)) continue;
                try {
                    JSONObject state = new JSONObject((String) entry.getValue());
                    state.put("fresh", false);
                    editor.putString(entry.getKey(), state.toString());
                } catch (JSONException ignored) {
                    editor.remove(entry.getKey());
                }
            }
            if (!stillOwner.getAsBoolean()) return false;
            // SharedPreferences memory changes synchronously. Disk persistence may remain async;
            // every replacement process establishes the same guarded barrier before connectors.
            editor.apply();
            sessionFreshnessBarrierPending = false;
            return true;
        }
    }

    /** Snapshot for an explicitly addressed local integration helper; never contains config. */
    @NonNull
    public synchronized JSONArray snapshot() {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof String)) continue;
            String remainder = key.substring(KEY_PREFIX.length());
            int separator = remainder.indexOf('|');
            if (separator <= 0 || separator == remainder.length() - 1) continue;
            try {
                JSONObject item = new JSONObject();
                item.put("scope", remainder.substring(0, separator));
                item.put("id", remainder.substring(separator + 1));
                item.put("state", new JSONObject((String) entry.getValue()));
                out.put(item);
            } catch (JSONException ignored) {}
        }
        return out;
    }

    private static boolean isStateField(String name) {
        return "text".equals(name) || "color".equals(name) || "visible".equals(name)
                || "updated_at".equals(name) || "expires_at".equals(name)
                || "value".equals(name) || "icon".equals(name) || "state".equals(name)
                || "background_color".equals(name) || "action_enabled".equals(name)
                || "border_color".equals(name) || "border_width".equals(name)
                || "icon_tint".equals(name) || "icon_background_color".equals(name)
                || "icon_outline_color".equals(name) || "icon_outline_width".equals(name)
                || "enabled".equals(name)
                || "fresh".equals(name) || "source".equals(name)
                || "attributes".equals(name) || "request_id".equals(name);
    }

    private static void validatePatch(JSONObject patch) {
        if (patch.toString().length() > AutomationContract.MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("Automation payload is too large");
        }
        checkLength(patch, "text", 8192);
        checkLength(patch, "state", 8192);
        checkLength(patch, "value", 8192);
        checkLength(patch, "color", 64);
        checkLength(patch, "background_color", 64);
        checkLength(patch, "border_color", 64);
        checkLength(patch, "icon_tint", 64);
        checkLength(patch, "icon_background_color", 64);
        checkLength(patch, "icon_outline_color", 64);
        checkLength(patch, "icon", 64);
        checkLength(patch, "request_id", 128);
        checkLength(patch, "source", 64);
    }

    private static void checkLength(JSONObject patch, String field, int max) {
        if (patch.has(field) && !patch.isNull(field)
                && String.valueOf(patch.opt(field)).length() > max) {
            throw new IllegalArgumentException(field + " is too long");
        }
    }

    private static String key(String scope, String id) {
        return KEY_PREFIX + AutomationContract.normalizeScope(scope) + "|"
                + AutomationContract.requireSafeId(id);
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String field)
            throws JSONException {
        if (!source.has(field)) return;
        Object value = source.get(field);
        if (("visible".equals(field) || "action_enabled".equals(field))) {
            target.put(field, AutomationContract.parseBoolean(value));
            return;
        }
        if (value == JSONObject.NULL) target.put(field, JSONObject.NULL);
        else if ("border_width".equals(field) || "icon_outline_width".equals(field)) {
            int width;
            try { width = Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Scenario width is not a number");
            }
            if (width < 0 || width > 64) {
                throw new IllegalArgumentException("Scenario width is outside 0..64");
            }
            target.put(field, width);
        }
        else {
            String text = String.valueOf(value);
            if (text.length() > 1024 || text.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Scenario override is too long");
            }
            target.put(field, text);
        }
    }
}
