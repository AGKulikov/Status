/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.Preferences;

/** Persists the exact pixel rectangle of every independent HOME panel. */
public final class LauncherLayoutStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String APPS = "apps";
    public static final String MEDIA = "media";
    public static final String CLOCK = "clock";
    public static final String NAVIGATION = "navigation";
    public static final String ACTIONS = "actions";
    public static final String CLIMATE = "climate";
    public static final String FAVORITE_ROUTES = "favorite_routes";

    public static final class Geometry {
        public int x;
        public int y;
        public int width;
        public int height;

        public Geometry(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @NonNull
        Geometry copy() {
            return new Geometry(x, y, width, height);
        }
    }

    private final Preferences preferences;
    private final Map<String, Geometry> geometry = new LinkedHashMap<>();
    private int screenWidth;
    private int screenHeight;

    public LauncherLayoutStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public void load(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        geometry.clear();
        geometry.putAll(defaults(screenWidth, screenHeight));

        String raw = preferences.launcherLayoutJson.get();
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return;
            JSONObject elements = root.optJSONObject("elements");
            if (elements == null) return;
            for (String id : geometry.keySet()) {
                JSONObject value = elements.optJSONObject(id);
                if (value == null) continue;
                Geometry fallback = geometry.get(id);
                Geometry parsed = new Geometry(
                        value.optInt("x", fallback.x),
                        value.optInt("y", fallback.y),
                        value.optInt("width", fallback.width),
                        value.optInt("height", fallback.height));
                geometry.put(id, clamp(parsed));
            }
        } catch (JSONException ignored) {
            // A corrupt/imported HOME layout must never make the launcher unusable. Defaults stay.
        }
    }

    @NonNull
    public Geometry get(@NonNull String id) {
        Geometry value = geometry.get(id);
        if (value == null) {
            value = new Geometry(0, 0, Math.min(400, screenWidth), Math.min(200, screenHeight));
            geometry.put(id, value);
        }
        return value.copy();
    }

    public void put(@NonNull String id, @NonNull Geometry value) {
        geometry.put(id, clamp(value));
        save();
    }

    public void reset() {
        geometry.clear();
        geometry.putAll(defaults(screenWidth, screenHeight));
        preferences.launcherLayoutJson.set("");
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            JSONObject elements = new JSONObject();
            for (Map.Entry<String, Geometry> entry : geometry.entrySet()) {
                Geometry g = entry.getValue();
                JSONObject value = new JSONObject();
                value.put("x", g.x);
                value.put("y", g.y);
                value.put("width", g.width);
                value.put("height", g.height);
                elements.put(entry.getKey(), value);
            }
            root.put("elements", elements);
            preferences.launcherLayoutJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    private Geometry clamp(@NonNull Geometry source) {
        int minWidth = Math.min(160, screenWidth);
        int minHeight = Math.min(96, screenHeight);
        int width = Math.max(minWidth, Math.min(source.width, screenWidth));
        int height = Math.max(minHeight, Math.min(source.height, screenHeight));
        int x = Math.max(0, Math.min(source.x, Math.max(0, screenWidth - width)));
        int y = Math.max(0, Math.min(source.y, Math.max(0, screenHeight - height)));
        return new Geometry(x, y, width, height);
    }

    @NonNull
    private static Map<String, Geometry> defaults(int width, int height) {
        LinkedHashMap<String, Geometry> result = new LinkedHashMap<>();

        int appsW = Math.min(380, Math.max(240, width / 5));
        int appsH = Math.min(410, Math.max(250, height - 210));
        result.put(APPS, new Geometry(24, Math.min(64, height / 12), appsW, appsH));

        int mediaW = Math.min(820, Math.max(420, width / 2));
        int mediaH = Math.min(300, Math.max(190, height * 2 / 5));
        result.put(MEDIA, new Geometry(Math.max(0, (width - mediaW) / 2),
                Math.max(0, height - mediaH - 36), mediaW, mediaH));

        int clockW = Math.min(300, Math.max(200, width / 6));
        int clockH = Math.min(150, Math.max(110, height / 5));
        result.put(CLOCK, new Geometry(Math.max(0, width - clockW - 28), 36, clockW, clockH));

        int navW = Math.min(520, Math.max(320, width / 3));
        int navH = Math.min(130, Math.max(96, height / 6));
        result.put(NAVIGATION, new Geometry(36, Math.max(0, height - navH - 24), navW, navH));

        int actionsW = Math.min(340, Math.max(240, width / 5));
        int actionsH = Math.min(280, Math.max(180, height / 3));
        result.put(ACTIONS, new Geometry(Math.max(0, width - actionsW - 28),
                Math.max(0, (height - actionsH) / 2), actionsW, actionsH));

        int climateW = Math.min(920, Math.max(620, width / 2));
        int climateH = Math.min(370, Math.max(290, height / 2));
        result.put(CLIMATE, new Geometry(Math.max(0, (width - climateW) / 2),
                Math.min(32, Math.max(0, height - climateH)), climateW, climateH));

        int routesW = Math.min(520, Math.max(320, width / 4));
        int routesH = Math.min(230, Math.max(150, height / 4));
        result.put(FAVORITE_ROUTES, new Geometry(
                Math.max(0, width - routesW - clockW - 52),
                Math.min(36, Math.max(0, height - routesH)),
                routesW, routesH));
        return result;
    }
}
