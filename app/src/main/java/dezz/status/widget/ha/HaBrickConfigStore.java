/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha;

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

/** Validates and serializes an unlimited ordered collection of main-row HA bricks. */
public final class HaBrickConfigStore {
    private final Preferences prefs;

    public HaBrickConfigStore(@NonNull Preferences prefs) {
        this.prefs = prefs;
    }

    @NonNull
    public List<HaBrickConfig> loadMain() {
        ArrayList<HaBrickConfig> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONArray array = new JSONArray(prefs.haMainBricksJson.get());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                try {
                    HaBrickConfig config = HaBrickConfig.fromJson(object, i);
                    if (ids.add(config.id)) result.add(config);
                } catch (IllegalArgumentException ignored) {
                    // One malformed user item must not take down the entire overlay.
                }
            }
        } catch (JSONException ignored) {
            // Invalid imported JSON is treated as an empty collection; saveMain repairs it.
        }
        result.sort(Comparator.comparingInt(c -> c.order));
        return result;
    }

    public void saveMain(@NonNull List<HaBrickConfig> configs) throws JSONException {
        JSONArray array = new JSONArray();
        Set<String> ids = new HashSet<>();
        int order = 0;
        for (HaBrickConfig config : configs) {
            if (config == null || !ids.add(config.id)) continue;
            config.order = order++;
            array.put(config.toJson());
        }
        prefs.haMainBricksJson.set(array.toString());
    }
}
