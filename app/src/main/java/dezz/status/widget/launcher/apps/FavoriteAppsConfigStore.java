/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.apps;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import dezz.status.widget.Preferences;

/**
 * Ordered favourites backed by the legacy package list plus a separate, versioned appearance
 * document. Keeping the old list authoritative makes this store safe to adopt incrementally in
 * {@code LauncherActivity} without moving or losing an existing HOME setup.
 */
public final class FavoriteAppsConfigStore {
    public static final int SCHEMA_VERSION = 1;
    /** Optional app-scoped broadcast emitted by the visual editor after every saved change. */
    public static final String ACTION_CHANGED =
            "dezz.status.widget.action.HOME_FAVORITE_APPS_CHANGED";

    // LauncherActivity treats an empty legacy string as "create defaults". A deliberately invalid
    // package keeps an intentional empty selection stable until LauncherActivity adopts this store.
    private static final String EMPTY_SENTINEL = "__status_widget_no_favorite_apps__";
    private static final Object LOCK = new Object();

    private final Preferences preferences;

    public FavoriteAppsConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    /** Returns selected packages in display order with a defensive copy of each appearance. */
    @NonNull
    public List<FavoriteAppConfig> load() {
        synchronized (LOCK) {
            Map<String, FavoriteAppConfig> styles = readStyles();
            List<FavoriteAppConfig> result = new ArrayList<>();
            for (String packageName : readOrder()) {
                FavoriteAppConfig style = styles.get(packageName);
                result.add(style == null
                        ? new FavoriteAppConfig(packageName) : style.copy());
            }
            return result;
        }
    }

    /** Returns saved appearance even when the app is currently not selected. */
    @NonNull
    public FavoriteAppConfig appearance(@NonNull String packageName) {
        synchronized (LOCK) {
            String normalized = validPackage(packageName);
            FavoriteAppConfig value = readStyles().get(normalized);
            return value == null ? new FavoriteAppConfig(normalized) : value.copy();
        }
    }

    public boolean contains(@NonNull String packageName) {
        synchronized (LOCK) {
            return readOrder().contains(validPackage(packageName));
        }
    }

    /** Adds an app at the end. Its previously saved appearance is restored automatically. */
    public boolean add(@NonNull String packageName) {
        synchronized (LOCK) {
            String normalized = validPackage(packageName);
            List<String> order = readOrder();
            if (order.contains(normalized)) return false;
            order.add(normalized);
            writeOrder(order);
            return true;
        }
    }

    /** Removes only the selection; appearance remains available if the app is added again. */
    public boolean remove(@NonNull String packageName) {
        synchronized (LOCK) {
            List<String> order = readOrder();
            if (!order.remove(validPackage(packageName))) return false;
            writeOrder(order);
            return true;
        }
    }

    public boolean move(@NonNull String packageName, int delta) {
        synchronized (LOCK) {
            if (delta == 0) return false;
            List<String> order = readOrder();
            int from = order.indexOf(validPackage(packageName));
            if (from < 0) return false;
            int to = Math.max(0, Math.min(order.size() - 1, from + (delta < 0 ? -1 : 1)));
            if (from == to) return false;
            String value = order.remove(from);
            order.add(to, value);
            writeOrder(order);
            return true;
        }
    }

    /** Saves appearance immediately without changing selection or order. */
    public void updateAppearance(@NonNull FavoriteAppConfig source) {
        synchronized (LOCK) {
            FavoriteAppConfig value = source.copy();
            String packageName = validPackage(value.packageName);
            Map<String, FavoriteAppConfig> styles = readStyles();
            styles.put(packageName, value);
            writeStyles(styles);
        }
    }

    @NonNull
    private List<String> readOrder() {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String raw = preferences.launcherFavoritePackages.get();
        if (raw != null) {
            for (String token : raw.split(",")) {
                String value = token.trim();
                if (value.isEmpty() || EMPTY_SENTINEL.equals(value)) continue;
                // Android package names cannot contain commas. Ignore corrupt separators and the
                // private sentinel instead of allowing an unusable row into the editor.
                if (isPlausiblePackage(value)) unique.add(value);
            }
        }
        return new ArrayList<>(unique);
    }

    private void writeOrder(@NonNull List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (isPlausiblePackage(normalized)) unique.add(normalized);
        }
        preferences.launcherFavoritePackages.set(unique.isEmpty()
                ? EMPTY_SENTINEL : String.join(",", unique));
    }

    @NonNull
    private Map<String, FavoriteAppConfig> readStyles() {
        LinkedHashMap<String, FavoriteAppConfig> result = new LinkedHashMap<>();
        String raw = preferences.launcherFavoriteAppsAppearanceJson.get();
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return result;
            JSONArray items = root.optJSONArray("items");
            if (items == null) return result;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                String packageName = item.optString("packageName", "").trim();
                if (!isPlausiblePackage(packageName)) continue;
                FavoriteAppConfig value = new FavoriteAppConfig(packageName);
                value.iconSizePx = item.optInt("iconSizePx", value.iconSizePx);
                value.labelSizeSp = item.optInt("labelSizeSp", value.labelSizeSp);
                value.showLabel = item.optBoolean("showLabel", value.showLabel);
                value.normalize();
                result.put(packageName, value);
            }
        } catch (JSONException ignored) {
            // A damaged import must not hide existing favourites. Defaults remain usable.
        }
        return result;
    }

    private void writeStyles(@NonNull Map<String, FavoriteAppConfig> values) {
        try {
            JSONObject root = new JSONObject().put("version", SCHEMA_VERSION);
            JSONArray items = new JSONArray();
            for (FavoriteAppConfig source : values.values()) {
                FavoriteAppConfig value = source.copy();
                if (!isPlausiblePackage(value.packageName)) continue;
                items.put(new JSONObject()
                        .put("packageName", value.packageName)
                        .put("iconSizePx", value.iconSizePx)
                        .put("labelSizeSp", value.labelSizeSp)
                        .put("showLabel", value.showLabel));
            }
            root.put("items", items);
            preferences.launcherFavoriteAppsAppearanceJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    private static String validPackage(@NonNull String value) {
        String normalized = FavoriteAppConfig.normalizePackage(value);
        if (!isPlausiblePackage(normalized)) {
            throw new IllegalArgumentException("Invalid application package");
        }
        return normalized;
    }

    private static boolean isPlausiblePackage(String value) {
        return value != null && !EMPTY_SENTINEL.equals(value) && value.length() <= 255
                && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    }
}
