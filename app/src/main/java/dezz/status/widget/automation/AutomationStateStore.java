/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Iterator;
import java.util.Map;

/**
 * Device-protected persistent runtime cache. MQTT retained messages and Broadcast updates pass
 * through exactly the same merge operation, so source switching cannot change UI semantics.
 */
public final class AutomationStateStore {
    private static final String PREF_SUFFIX = "_automation_state_v1";
    private static final String KEY_PREFIX = "state|";

    private final SharedPreferences prefs;

    public AutomationStateStore(@NonNull Context context) {
        Context device = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = device.getSharedPreferences(context.getPackageName() + PREF_SUFFIX,
                Context.MODE_PRIVATE);
    }

    @NonNull
    public synchronized AutomationState get(String scope, String id) {
        String raw = prefs.getString(key(scope, id), null);
        if (raw == null) return AutomationState.missing();
        try {
            return AutomationState.fromJson(new JSONObject(raw));
        } catch (JSONException ignored) {
            return AutomationState.missing();
        }
    }

    /** Partial-update merge. A payload with clear=true removes the retained local state. */
    @NonNull
    public synchronized AutomationState apply(String scope, String id, @NonNull JSONObject patch)
            throws JSONException {
        validatePatch(patch);
        String storageKey = key(scope, id);
        if (patch.optBoolean("clear", false)) {
            prefs.edit().remove(storageKey).commit();
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
        if (!patch.has("text")) {
            if (patch.has("state")) merged.put("text", String.valueOf(patch.opt("state")));
            else if (patch.has("value")) merged.put("text", String.valueOf(patch.opt("value")));
        }
        if (!patch.has("updated_at")) merged.put("updated_at", System.currentTimeMillis());
        merged.put("visible", patch.has("visible")
                ? AutomationContract.parseBoolean(patch.opt("visible"))
                : merged.optBoolean("visible", true));
        merged.put("schema", AutomationContract.SCHEMA_VERSION);

        // Synchronous persistence is intentional: a car head unit can cut power immediately after
        // receiving an update and the next boot still needs the last effective state.
        prefs.edit().putString(storageKey, merged.toString()).commit();
        return AutomationState.fromJson(merged);
    }

    public synchronized void clearAll() {
        prefs.edit().clear().commit();
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
                || "enabled".equals(name)
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
        checkLength(patch, "icon", 64);
        checkLength(patch, "request_id", 128);
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
}
