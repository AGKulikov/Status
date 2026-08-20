/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.Preferences;

/** Versioned storage for the two dedicated phone-notification compositions. */
public final class PhoneNotificationLayoutConfigStore {
    private static final Object LOCK = new Object();
    private final Preferences prefs;

    public PhoneNotificationLayoutConfigStore(@NonNull Preferences prefs) {
        this.prefs = prefs;
    }

    @NonNull
    public PhoneNotificationLayoutConfig load(@NonNull String overlayId) {
        synchronized (LOCK) {
            JSONObject source = readAll().get(overlayId);
            return PhoneNotificationLayoutConfig.fromJson(overlayId, source);
        }
    }

    public void save(@NonNull PhoneNotificationLayoutConfig value) {
        synchronized (LOCK) {
            Map<String, JSONObject> all = readAll();
            try {
                all.put(value.overlayId, value.toJson());
                JSONArray array = new JSONArray();
                for (JSONObject item : all.values()) array.put(item);
                prefs.phoneNotificationLayoutsJson.set(array.toString());
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    @NonNull
    private Map<String, JSONObject> readAll() {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        try {
            JSONArray array = new JSONArray(prefs.phoneNotificationLayoutsJson.get());
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                String id = item.optString("overlayId", "");
                if (PhoneNotificationAutomation.isNotificationOverlayId(id)) {
                    result.put(id, item);
                }
            }
        } catch (JSONException ignored) {
            // One malformed import falls back to the safe CarPlay preset.
        }
        return result;
    }
}
