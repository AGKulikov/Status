/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.Preferences;

public final class PopupItemConfigStore {
    private final Preferences prefs;
    public PopupItemConfigStore(@NonNull Preferences prefs) { this.prefs = prefs; }

    @NonNull
    public List<PopupItemConfig> load() {
        ArrayList<PopupItemConfig> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> automationIds = new HashSet<>();
        try {
            JSONArray array = new JSONArray(prefs.popupItemsJson.get());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                try {
                    PopupItemConfig config = PopupItemConfig.fromJson(object, i);
                    if (ids.add(config.id) && automationIds.add(config.automationId)) {
                        result.add(config);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (JSONException ignored) {}
        result.sort(Comparator.comparingInt(c -> c.order));
        return result;
    }

    public void save(@NonNull List<PopupItemConfig> configs) throws JSONException {
        JSONArray array = new JSONArray();
        Set<String> ids = new HashSet<>();
        Set<String> automationIds = new HashSet<>();
        int order = 0;
        for (PopupItemConfig config : configs) {
            if (config == null || !ids.add(config.id)) continue;
            if (!automationIds.add(config.automationId)) {
                throw new IllegalArgumentException("Duplicate automation ID: " + config.automationId);
            }
            config.order = order++;
            array.put(config.toJson());
        }
        prefs.popupItemsJson.set(array.toString());
    }
}
