/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Versioned overlay catalog with transparent migration from the original single popup. */
public final class PopupOverlayConfigStore {
    private static final Object LOCK = new Object();
    private final Preferences prefs;

    public PopupOverlayConfigStore(@NonNull Preferences prefs) {
        this.prefs = prefs;
    }

    @NonNull
    public synchronized List<PopupOverlayConfig> load() {
        synchronized (LOCK) {
            return loadLocked();
        }
    }

    @NonNull
    private List<PopupOverlayConfig> loadLocked() {
        ArrayList<PopupOverlayConfig> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String raw = prefs.popupOverlaysJson.get();
        boolean needsMigration = raw == null || raw.trim().isEmpty();
        try {
            JSONArray array = new JSONArray(needsMigration ? "[]" : raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) continue;
                try {
                    PopupOverlayConfig config = PopupOverlayConfig.fromJson(object, index);
                    if (ids.add(config.id)) result.add(config);
                } catch (IllegalArgumentException ignored) {
                    // One corrupt/imported overlay must not hide every unrelated window.
                }
            }
        } catch (JSONException ignored) {
            // Project the legacy keys below.
        }
        if (needsMigration) {
            result.add(PopupOverlayConfig.fromLegacy(prefs));
            try {
                saveLocked(result);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        result.sort(Comparator.comparingInt(config -> config.order));
        return result;
    }

    @Nullable
    public synchronized PopupOverlayConfig find(@NonNull String id) {
        for (PopupOverlayConfig config : load()) if (config.id.equals(id)) return config;
        return null;
    }

    public synchronized void save(@NonNull List<PopupOverlayConfig> configs) throws JSONException {
        synchronized (LOCK) {
            // The settings screen can stay open while the WindowManager controller writes a new
            // drag position. Its in-memory config then contains stale x/y; never let a checkbox,
            // slider, rename or reorder operation move the window back to those old coordinates.
            preserveStoredPositions(configs, loadLocked());
            saveLocked(configs);
        }
    }

    /** Package-private for the regression test; position changes must go through savePosition. */
    static void preserveStoredPositions(@NonNull List<PopupOverlayConfig> pending,
                                        @NonNull List<PopupOverlayConfig> stored) {
        for (PopupOverlayConfig target : pending) {
            if (target == null) continue;
            for (PopupOverlayConfig current : stored) {
                if (current == null || !target.id.equals(current.id)) continue;
                target.x = current.x;
                target.y = current.y;
                break;
            }
        }
    }

    private void saveLocked(@NonNull List<PopupOverlayConfig> configs) throws JSONException {
        JSONArray array = new JSONArray();
        Set<String> ids = new HashSet<>();
        int order = 0;
        for (PopupOverlayConfig config : configs) {
            if (config == null || !ids.add(config.id)) continue;
            config.order = order++;
            array.put(config.toJson());
        }
        prefs.savePopupOverlaysJson(array.toString());
    }

    /** Called from touch handling; updates only one window position and preserves every peer. */
    public synchronized void savePosition(@NonNull String id, int x, int y) {
        synchronized (LOCK) {
            List<PopupOverlayConfig> configs = loadLocked();
            for (PopupOverlayConfig config : configs) {
                if (!config.id.equals(id)) continue;
                config.x = Math.max(-10000, Math.min(10000, x));
                config.y = Math.max(-10000, Math.min(10000, y));
                try {
                    saveLocked(configs);
                } catch (JSONException impossible) {
                    throw new IllegalStateException(impossible);
                }
                return;
            }
        }
    }
}
