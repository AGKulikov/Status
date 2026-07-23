/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;

/**
 * Bounded device-protected index of logical MQTT resources actually observed by this app.
 *
 * <p>The cache deliberately stores neither state payloads nor action payloads and never derives
 * a broker topic. It only lets a settings screen list a previously observed {@code scope/id}
 * while {@code WidgetService} is stopped. A command id/topic is still chosen explicitly by the
 * user in the next editor step.</p>
 */
public final class MqttShortcutCatalogStore {
    private static final String TAG = "MqttShortcutCatalog";
    private static final String PREF_SUFFIX = "_mqtt_shortcut_catalog_v1";
    private static final String KEY_PREFIX = "resource|";
    static final int SCHEMA_VERSION = 1;
    static final int MAX_ENTRIES = 512;

    private final SharedPreferences prefs;
    private final String profilePrefix;

    public MqttShortcutCatalogStore(@NonNull Context context,
                                    @NonNull Preferences preferences) {
        Context device = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = device.getSharedPreferences(context.getPackageName() + PREF_SUFFIX,
                Context.MODE_PRIVATE);
        profilePrefix = KEY_PREFIX + profileId(preferences) + "|";
    }

    /** Records one real logical state resource. Runtime aliases and full state topics are ignored. */
    public synchronized void upsert(@NonNull ConnectorValue value) {
        if (value.connectorType != ConnectorType.MQTT
                || !SourceBinding.DEFAULT_CONNECTOR_ID.equals(value.connectorId)
                || !isLogicalResource(value.resourceId)) return;
        String key = profilePrefix + value.resourceId;
        if (!prefs.contains(key)) evictOldestIfFull();
        prefs.edit().putString(key, encode(value).toString()).apply();
    }

    /** Removes an entry only after the matching retained/live MQTT state was explicitly cleared. */
    public synchronized void remove(String resourceId) {
        if (!isLogicalResource(resourceId)) return;
        prefs.edit().remove(profilePrefix + resourceId).apply();
    }

    /** Returns stale metadata-only values; a stopped service can never make cached state current. */
    @NonNull
    public synchronized List<ConnectorValue> snapshot() {
        List<ConnectorValue> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(profilePrefix)
                    || !(entry.getValue() instanceof String)) continue;
            String expectedResource = entry.getKey().substring(profilePrefix.length());
            ConnectorValue value = decode((String) entry.getValue());
            if (value != null && expectedResource.equals(value.resourceId)
                    && isLogicalResource(value.resourceId)) result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private void evictOldestIfFull() {
        int count = 0;
        long oldestTime = Long.MAX_VALUE;
        String oldestKey = null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(profilePrefix)
                    || !(entry.getValue() instanceof String)) continue;
            count++;
            try {
                long updatedAt = new JSONObject((String) entry.getValue())
                        .optLong("updatedAt", 0L);
                if (updatedAt < oldestTime) {
                    oldestTime = updatedAt;
                    oldestKey = entry.getKey();
                }
            } catch (JSONException ignored) {
                oldestKey = entry.getKey();
                oldestTime = Long.MIN_VALUE;
            }
        }
        if (count >= MAX_ENTRIES && oldestKey != null) prefs.edit().remove(oldestKey).apply();
    }

    @NonNull
    static JSONObject encode(@NonNull ConnectorValue value) {
        JSONObject out = new JSONObject();
        try {
            out.put("schema", SCHEMA_VERSION);
            out.put("resourceId", value.resourceId);
            out.put("valueType", value.valueType);
            out.put("unit", value.unit);
            out.put("writable", value.writable);
            out.put("updatedAt", value.updatedAt);
            JSONObject semantic = new JSONObject();
            copySemanticAttribute(value.attributes, semantic, "device_class");
            copySemanticAttribute(value.attributes, semantic, "friendly_name");
            copySemanticAttribute(value.attributes, semantic, "name");
            copySemanticAttribute(value.attributes, semantic, "type");
            out.put("semantic", semantic);
            return out;
        } catch (JSONException impossible) {
            throw new IllegalStateException("Could not encode MQTT catalog entry", impossible);
        }
    }

    @Nullable
    static ConnectorValue decode(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject source = new JSONObject(raw);
            if (source.optInt("schema", 0) != SCHEMA_VERSION) return null;
            String resourceId = source.optString("resourceId", "");
            if (!isLogicalResource(resourceId)) return null;
            Map<String, Object> semantic = jsonObjectToMap(source.optJSONObject("semantic"));
            return new ConnectorValue(ConnectorType.MQTT,
                    SourceBinding.DEFAULT_CONNECTOR_ID, resourceId, null,
                    false, false, false, source.optBoolean("writable", false),
                    source.optString("valueType", ""), source.optString("unit", ""),
                    semantic, source.optLong("updatedAt", 0L));
        } catch (JSONException | IllegalArgumentException error) {
            Log.w(TAG, "Ignored invalid MQTT shortcut catalog entry", error);
            return null;
        }
    }

    static boolean isLogicalResource(@Nullable String resource) {
        if (resource == null) return false;
        int slash = resource.indexOf('/');
        if (slash <= 0 || slash != resource.lastIndexOf('/')
                || slash >= resource.length() - 1) return false;
        try {
            String scope = resource.substring(0, slash);
            String id = resource.substring(slash + 1);
            return scope.equals(AutomationContract.normalizeScope(scope))
                    && id.equals(AutomationContract.requireSafeId(id));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void copySemanticAttribute(Map<String, Object> source, JSONObject target,
                                              String key) throws JSONException {
        Object value = source.get(key);
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isEmpty() && text.length() <= 256 && text.indexOf('\u0000') < 0) {
            target.put(key, text);
        }
    }

    @NonNull
    private static Map<String, Object> jsonObjectToMap(@Nullable JSONObject source) {
        if (source == null || source.length() == 0) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL) result.put(key, String.valueOf(value));
        }
        return result;
    }

    private static String profileId(Preferences preferences) {
        String identity = preferences.mqttHost.get().trim() + '\n'
                + preferences.mqttPort.get() + '\n' + preferences.mqttTls.get() + '\n'
                + preferences.mqttUsername.get().trim() + '\n'
                + preferences.mqttBaseTopic.get().trim() + '\n'
                + preferences.mqttDeviceId.get().trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }
}
