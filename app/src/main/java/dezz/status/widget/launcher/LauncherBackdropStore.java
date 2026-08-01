/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.Preferences;

/**
 * Persistent, unlimited decorative layers for HOME.
 *
 * <p>Backdrops are not widgets: they have their own geometry and appearance and are always inserted
 * below every live launcher element. The only limits here are defensive JSON and screen clamps.</p>
 */
public final class LauncherBackdropStore {
    public static final int SCHEMA_VERSION = 1;
    private static final int MIN_WIDTH = 36;
    private static final int MIN_HEIGHT = 28;
    private static final int MAX_JSON_CHARS = 1_048_576;

    public static final class Backdrop {
        @NonNull public String id = "";
        @NonNull public String name = "Подложка";
        public int x;
        public int y;
        public int width = 520;
        public int height = 240;
        @NonNull public String fillColor = "#FF121923";
        public int fillOpacityPercent = 72;
        public int cornerRadiusPx = 28;
        @NonNull public String borderColor = "#FFFFFFFF";
        public int borderOpacityPercent;
        public int borderWidthPx;
        @NonNull public String shadowColor = "#FF000000";
        public int shadowOpacityPercent = 38;
        public int shadowRadiusPx = 22;
        public int shadowOffsetXPx;
        public int shadowOffsetYPx = 7;

        @NonNull
        public Backdrop copy() {
            Backdrop value = new Backdrop();
            value.id = id;
            value.name = name;
            value.x = x;
            value.y = y;
            value.width = width;
            value.height = height;
            value.fillColor = fillColor;
            value.fillOpacityPercent = fillOpacityPercent;
            value.cornerRadiusPx = cornerRadiusPx;
            value.borderColor = borderColor;
            value.borderOpacityPercent = borderOpacityPercent;
            value.borderWidthPx = borderWidthPx;
            value.shadowColor = shadowColor;
            value.shadowOpacityPercent = shadowOpacityPercent;
            value.shadowRadiusPx = shadowRadiusPx;
            value.shadowOffsetXPx = shadowOffsetXPx;
            value.shadowOffsetYPx = shadowOffsetYPx;
            return value;
        }
    }

    private final Preferences preferences;
    private final Map<String, Backdrop> backdrops = new LinkedHashMap<>();
    private int screenWidth = 1;
    private int screenHeight = 1;

    public LauncherBackdropStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public void load(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        backdrops.clear();
        String raw = preferences.launcherBackdropsJson.get();
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_JSON_CHARS) return;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return;
            JSONArray items = root.optJSONArray("items");
            if (items == null) return;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                Backdrop parsed = decode(item);
                if (parsed.id.isEmpty() || backdrops.containsKey(parsed.id)) continue;
                backdrops.put(parsed.id, normalize(parsed));
            }
        } catch (JSONException ignored) {
            backdrops.clear();
        }
    }

    @NonNull
    public List<Backdrop> all() {
        ArrayList<Backdrop> result = new ArrayList<>(backdrops.size());
        for (Backdrop backdrop : backdrops.values()) result.add(backdrop.copy());
        return result;
    }

    @Nullable
    public Backdrop get(@NonNull String id) {
        Backdrop value = backdrops.get(id);
        return value == null ? null : value.copy();
    }

    @NonNull
    public Backdrop create() {
        int ordinal = 1;
        String id;
        do {
            id = "launcher_backdrop_" + ordinal++;
        } while (backdrops.containsKey(id));
        Backdrop value = new Backdrop();
        value.id = id;
        value.name = "Подложка " + (ordinal - 1);
        value.width = Math.min(520, screenWidth);
        value.height = Math.min(240, screenHeight);
        int offset = ((ordinal - 2) * 28) % Math.max(1, Math.min(screenWidth, screenHeight));
        value.x = Math.max(0, Math.min((screenWidth - value.width) / 2 + offset,
                screenWidth - value.width));
        value.y = Math.max(0, Math.min((screenHeight - value.height) / 2 + offset,
                screenHeight - value.height));
        value = normalize(value);
        backdrops.put(value.id, value);
        save();
        return value.copy();
    }

    public void put(@NonNull Backdrop source) {
        Backdrop value = normalize(source);
        if (value.id.isEmpty()) return;
        backdrops.put(value.id, value);
        save();
    }

    public void remove(@NonNull String id) {
        if (backdrops.remove(id) != null) save();
    }

    @NonNull
    private Backdrop normalize(@NonNull Backdrop source) {
        Backdrop value = source.copy();
        value.id = clean(value.id, 120);
        value.name = clean(value.name, 120);
        if (value.name.isEmpty()) value.name = "Подложка";
        int minWidth = Math.min(MIN_WIDTH, screenWidth);
        int minHeight = Math.min(MIN_HEIGHT, screenHeight);
        value.width = clamp(value.width, minWidth, screenWidth);
        value.height = clamp(value.height, minHeight, screenHeight);
        value.x = clamp(value.x, 0, Math.max(0, screenWidth - value.width));
        value.y = clamp(value.y, 0, Math.max(0, screenHeight - value.height));
        value.fillColor = color(value.fillColor, "#FF121923");
        value.fillOpacityPercent = clamp(value.fillOpacityPercent, 0, 100);
        value.cornerRadiusPx = clamp(value.cornerRadiusPx, 0, 500);
        value.borderColor = color(value.borderColor, "#FFFFFFFF");
        value.borderOpacityPercent = clamp(value.borderOpacityPercent, 0, 100);
        value.borderWidthPx = clamp(value.borderWidthPx, 0, 100);
        value.shadowColor = color(value.shadowColor, "#FF000000");
        value.shadowOpacityPercent = clamp(value.shadowOpacityPercent, 0, 100);
        value.shadowRadiusPx = clamp(value.shadowRadiusPx, 0, 300);
        value.shadowOffsetXPx = clamp(value.shadowOffsetXPx, -300, 300);
        value.shadowOffsetYPx = clamp(value.shadowOffsetYPx, -300, 300);
        return value;
    }

    private void save() {
        try {
            JSONObject root = new JSONObject().put("version", SCHEMA_VERSION);
            JSONArray items = new JSONArray();
            for (Backdrop value : backdrops.values()) items.put(encode(value));
            root.put("items", items);
            preferences.launcherBackdropsJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    private static JSONObject encode(@NonNull Backdrop value) throws JSONException {
        return new JSONObject()
                .put("id", value.id).put("name", value.name)
                .put("x", value.x).put("y", value.y)
                .put("width", value.width).put("height", value.height)
                .put("fillColor", value.fillColor)
                .put("fillOpacityPercent", value.fillOpacityPercent)
                .put("cornerRadiusPx", value.cornerRadiusPx)
                .put("borderColor", value.borderColor)
                .put("borderOpacityPercent", value.borderOpacityPercent)
                .put("borderWidthPx", value.borderWidthPx)
                .put("shadowColor", value.shadowColor)
                .put("shadowOpacityPercent", value.shadowOpacityPercent)
                .put("shadowRadiusPx", value.shadowRadiusPx)
                .put("shadowOffsetXPx", value.shadowOffsetXPx)
                .put("shadowOffsetYPx", value.shadowOffsetYPx);
    }

    @NonNull
    private static Backdrop decode(@NonNull JSONObject source) {
        Backdrop value = new Backdrop();
        value.id = source.optString("id", "");
        value.name = source.optString("name", "Подложка");
        value.x = source.optInt("x", 0);
        value.y = source.optInt("y", 0);
        value.width = source.optInt("width", 520);
        value.height = source.optInt("height", 240);
        value.fillColor = source.optString("fillColor", "#FF121923");
        value.fillOpacityPercent = source.optInt("fillOpacityPercent", 72);
        value.cornerRadiusPx = source.optInt("cornerRadiusPx", 28);
        value.borderColor = source.optString("borderColor", "#FFFFFFFF");
        value.borderOpacityPercent = source.optInt("borderOpacityPercent", 0);
        value.borderWidthPx = source.optInt("borderWidthPx", 0);
        value.shadowColor = source.optString("shadowColor", "#FF000000");
        value.shadowOpacityPercent = source.optInt("shadowOpacityPercent", 38);
        value.shadowRadiusPx = source.optInt("shadowRadiusPx", 22);
        value.shadowOffsetXPx = source.optInt("shadowOffsetXPx", 0);
        value.shadowOffsetYPx = source.optInt("shadowOffsetYPx", 7);
        return value;
    }

    @NonNull
    private static String color(@Nullable String raw, @NonNull String fallback) {
        String value = clean(raw, 32);
        return value.isEmpty() ? fallback : value;
    }

    @NonNull
    private static String clean(@Nullable String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maximum || value.indexOf('\u0000') >= 0) return "";
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
