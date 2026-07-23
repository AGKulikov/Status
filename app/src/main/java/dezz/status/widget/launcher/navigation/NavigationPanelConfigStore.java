/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.navigation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.panels.PanelElementConfigStore;

/** Versioned persistence and one-time migration for the navigation-panel grid. */
public final class NavigationPanelConfigStore {
    public static final int SCHEMA_VERSION = 1;

    private final Preferences preferences;
    private final PanelElementConfigStore legacyStore;

    public NavigationPanelConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
        legacyStore = new PanelElementConfigStore(preferences);
    }

    @NonNull
    public NavigationPanelConfig load() {
        String raw = preferences.launcherNavigationConfigJson.get();
        if (raw == null || raw.trim().isEmpty()) {
            NavigationPanelConfig migrated = new NavigationPanelConfig();
            migrated.applyLegacy(legacyStore.load(LauncherLayoutStore.NAVIGATION));
            save(migrated);
            return migrated;
        }
        return decode(raw);
    }

    public void save(@NonNull NavigationPanelConfig source) {
        preferences.launcherNavigationConfigJson.set(encode(source).toString());
    }

    public void reset() {
        preferences.launcherNavigationConfigJson.set(
                encode(new NavigationPanelConfig()).toString());
    }

    @NonNull
    static NavigationPanelConfig decode(@Nullable String raw) {
        NavigationPanelConfig value = new NavigationPanelConfig();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return value;
            value.gridColumns = root.optInt("gridColumns", value.gridColumns);
            value.gridRows = root.optInt("gridRows", value.gridRows);
            value.normalize();
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null) return value;
            Set<String> restored = new HashSet<>();
            for (int index = 0; index < elements.length(); index++) {
                JSONObject item = elements.optJSONObject(index);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (NavigationPanelConfig.spec(id) == null) continue;
                restored.add(id);
                NavigationPanelConfig.Element element = value.element(id);
                element.enabled = item.optBoolean("enabled", element.enabled);
                element.scalePercent = item.optInt("scalePercent", element.scalePercent);
                element.column = item.optInt("column", element.column);
                element.row = item.optInt("row", element.row);
                element.columnSpan = item.optInt("columnSpan", element.columnSpan);
                element.rowSpan = item.optInt("rowSpan", element.rowSpan);
            }
            value.appendMissingDisabled(restored);
        } catch (JSONException ignored) {
            return new NavigationPanelConfig();
        }
        value.normalize();
        return value;
    }

    @NonNull
    static JSONObject encode(@NonNull NavigationPanelConfig source) {
        NavigationPanelConfig value = source.copy();
        value.normalize();
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("gridColumns", value.gridColumns);
            root.put("gridRows", value.gridRows);
            JSONArray elements = new JSONArray();
            for (NavigationPanelConfig.Element element : value.elements()) {
                JSONObject item = new JSONObject();
                item.put("id", element.id);
                item.put("enabled", element.enabled);
                item.put("scalePercent", element.scalePercent);
                item.put("column", element.column);
                item.put("row", element.row);
                item.put("columnSpan", element.columnSpan);
                item.put("rowSpan", element.rowSpan);
                elements.put(item);
            }
            root.put("elements", elements);
            return root;
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }
}
