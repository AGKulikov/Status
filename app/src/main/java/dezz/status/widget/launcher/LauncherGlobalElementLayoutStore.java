/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.Preferences;

/**
 * Persists independent screen-space rectangles for real HOME elements.
 *
 * <p>Missing entries are intentional: their first rectangle is migrated from the current live
 * panel layout after Android has measured it, preserving existing user arrangements.</p>
 */
public final class LauncherGlobalElementLayoutStore {
    public static final int SCHEMA_VERSION = 1;
    private static final int MIN_WIDTH = 36;
    private static final int MIN_HEIGHT = 28;

    public static final class Geometry {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public Geometry(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private final Preferences preferences;
    private final Map<String, Geometry> geometry = new LinkedHashMap<>();
    private int screenWidth;
    private int screenHeight;

    public LauncherGlobalElementLayoutStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public void load(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        geometry.clear();
        String raw = preferences.launcherGlobalElementsJson.get();
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return;
            JSONObject elements = root.optJSONObject("elements");
            if (elements == null) return;
            java.util.Iterator<String> keys = elements.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject value = elements.optJSONObject(id);
                if (value == null) continue;
                Geometry parsed = new Geometry(
                        value.optInt("x", 0),
                        value.optInt("y", 0),
                        value.optInt("width", MIN_WIDTH),
                        value.optInt("height", MIN_HEIGHT));
                geometry.put(id, clamp(parsed, screenWidth, screenHeight));
            }
        } catch (JSONException ignored) {
            geometry.clear();
        }
    }

    @Nullable
    public Geometry get(@NonNull String id) {
        return geometry.get(id);
    }

    public void put(@NonNull String id, @NonNull Geometry value) {
        geometry.put(id, clamp(value, screenWidth, screenHeight));
        save();
    }

    public void reset() {
        geometry.clear();
        preferences.launcherGlobalElementsJson.set("");
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            JSONObject elements = new JSONObject();
            for (Map.Entry<String, Geometry> entry : geometry.entrySet()) {
                Geometry value = entry.getValue();
                JSONObject encoded = new JSONObject();
                encoded.put("x", value.x);
                encoded.put("y", value.y);
                encoded.put("width", value.width);
                encoded.put("height", value.height);
                elements.put(entry.getKey(), encoded);
            }
            root.put("elements", elements);
            preferences.launcherGlobalElementsJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    static Geometry clamp(@NonNull Geometry source, int rawWidth, int rawHeight) {
        int screenWidth = Math.max(1, rawWidth);
        int screenHeight = Math.max(1, rawHeight);
        int minWidth = Math.min(MIN_WIDTH, screenWidth);
        int minHeight = Math.min(MIN_HEIGHT, screenHeight);
        int width = Math.max(minWidth, Math.min(source.width, screenWidth));
        int height = Math.max(minHeight, Math.min(source.height, screenHeight));
        int x = Math.max(0, Math.min(source.x, Math.max(0, screenWidth - width)));
        int y = Math.max(0, Math.min(source.y, Math.max(0, screenHeight - height)));
        return new Geometry(x, y, width, height);
    }
}
