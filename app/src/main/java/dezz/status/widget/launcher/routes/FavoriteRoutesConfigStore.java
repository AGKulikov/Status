/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Defensive, order-preserving JSON persistence for an unlimited number of routes. */
public final class FavoriteRoutesConfigStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ITEMS = 1000;
    @NonNull private final Preferences preferences;

    public FavoriteRoutesConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public List<FavoriteRouteConfig> load() {
        ArrayList<FavoriteRouteConfig> result = new ArrayList<>();
        String raw = preferences.launcherFavoriteRoutesJson.get();
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) > SCHEMA_VERSION) return result;
            JSONArray items = root.optJSONArray("items");
            if (items == null) return result;
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < Math.min(items.length(), MAX_ITEMS); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                FavoriteRouteConfig value = decode(item);
                if (value == null) continue;
                if (!ids.add(value.id)) value.id = java.util.UUID.randomUUID().toString();
                ids.add(value.id);
                result.add(value);
            }
        } catch (JSONException ignored) {
            // A damaged import must not crash HOME. The user can add routes again in the editor.
        }
        return result;
    }

    public void save(@NonNull List<FavoriteRouteConfig> source) {
        JSONArray items = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (FavoriteRouteConfig original : source) {
            if (original == null || items.length() >= MAX_ITEMS) continue;
            FavoriteRouteConfig value = original.copy();
            if (!ids.add(value.id)) value.id = java.util.UUID.randomUUID().toString();
            ids.add(value.id);
            items.put(encode(value));
        }
        try {
            preferences.launcherFavoriteRoutesJson.set(new JSONObject()
                    .put("version", SCHEMA_VERSION)
                    .put("items", items).toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull public FavoriteRouteConfig defaultNew() { return new FavoriteRouteConfig(); }

    public boolean hasEnabled() {
        for (FavoriteRouteConfig value : load()) if (value.enabled) return true;
        return false;
    }

    public void add(@NonNull FavoriteRouteConfig value) {
        List<FavoriteRouteConfig> items = load();
        items.add(value.copy());
        save(items);
    }

    public boolean delete(@NonNull String id) {
        List<FavoriteRouteConfig> items = load();
        boolean changed = items.removeIf(value -> value.id.equals(id));
        if (changed) save(items);
        return changed;
    }

    public boolean move(@NonNull String id, int delta) {
        List<FavoriteRouteConfig> items = load();
        int from = -1;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id.equals(id)) { from = index; break; }
        }
        if (from < 0 || delta == 0) return false;
        int to = Math.max(0, Math.min(items.size() - 1, from + (delta < 0 ? -1 : 1)));
        if (from == to) return false;
        FavoriteRouteConfig value = items.remove(from);
        items.add(to, value);
        save(items);
        return true;
    }

    private static FavoriteRouteConfig decode(@NonNull JSONObject item) {
        try {
            FavoriteRouteConfig value = new FavoriteRouteConfig();
            value.id = item.optString("id", value.id);
            value.title = item.optString("title", value.title);
            value.address = item.optString("address", value.address);
            value.coordinates = item.optString("coordinates", value.coordinates);
            try {
                value.product = FavoriteRouteConfig.Product.valueOf(
                        item.optString("product", value.product.name()));
            } catch (IllegalArgumentException ignored) {
                value.product = FavoriteRouteConfig.Product.NAVIGATOR;
            }
            value.floating = item.optBoolean("floating", value.floating);
            value.icon = item.optString("icon", value.icon);
            value.enabled = item.optBoolean("enabled", value.enabled);
            value.iconSizePx = item.optInt("iconSizePx", value.iconSizePx);
            value.labelSizeSp = item.optInt("labelSizeSp", value.labelSizeSp);
            value.backgroundColor = item.optString("backgroundColor", value.backgroundColor);
            value.textColor = item.optString("textColor", value.textColor);
            value.iconColor = item.optString("iconColor", value.iconColor);
            value.normalize();
            return value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    private static JSONObject encode(@NonNull FavoriteRouteConfig value) {
        try {
            return new JSONObject()
                    .put("id", value.id)
                    .put("title", value.title)
                    .put("address", value.address)
                    .put("coordinates", value.coordinates)
                    .put("product", value.product.name())
                    .put("floating", value.floating)
                    .put("icon", value.icon)
                    .put("enabled", value.enabled)
                    .put("iconSizePx", value.iconSizePx)
                    .put("labelSizeSp", value.labelSizeSp)
                    .put("backgroundColor", value.backgroundColor)
                    .put("textColor", value.textColor)
                    .put("iconColor", value.iconColor);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }
}
