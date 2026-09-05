/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;

/**
 * Versioned collection of independent driver Favorites panels.
 *
 * <p>The collection and every panel's shortcut document are additive and have no artificial
 * count limit. The fixed default ID reuses HA1084's single Favorites document losslessly.</p>
 */
public final class DriverFavoritesPanelStore {
    public static final int SCHEMA_VERSION = 1;

    private final Preferences preferences;

    public DriverFavoritesPanelStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public List<DriverFavoritesPanelConfig> load() {
        String raw = preferences.driverFavoritesPanelsJson.get();
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.singletonList(defaultPanel());
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray items = root.optJSONArray("items");
            if (root.optInt("version", 0) != SCHEMA_VERSION || items == null) {
                return Collections.singletonList(defaultPanel());
            }
            List<DriverFavoritesPanelConfig> result = new ArrayList<>();
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                DriverFavoritesPanelConfig value = decode(item);
                if (value != null && find(result, value.id) == null) result.add(value);
            }
            return result.isEmpty()
                    ? Collections.singletonList(defaultPanel())
                    : Collections.unmodifiableList(result);
        } catch (JSONException ignored) {
            return Collections.singletonList(defaultPanel());
        }
    }

    @NonNull
    public DriverFavoritesPanelConfig create(@Nullable String title) {
        List<DriverFavoritesPanelConfig> values = mutable();
        DriverFavoritesPanelConfig value = sanitize(new DriverFavoritesPanelConfig());
        String requested = title == null ? "" : title.trim();
        value.title = requested.isEmpty() ? "Избранное " + (values.size() + 1) : requested;
        values.add(value);
        save(values);
        return value.copy();
    }

    public void upsert(@NonNull DriverFavoritesPanelConfig source) {
        DriverFavoritesPanelConfig value = sanitize(source.copy());
        List<DriverFavoritesPanelConfig> values = mutable();
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).id.equals(value.id)) {
                values.set(index, value);
                save(values);
                return;
            }
        }
        values.add(value);
        save(values);
    }

    /**
     * Removes only collection membership. The panel's shortcut document is deliberately retained
     * so an accidental deletion remains recoverable from settings backup/import.
     */
    public void remove(@NonNull String id) {
        List<DriverFavoritesPanelConfig> values = mutable();
        values.removeIf(value -> value.id.equals(id));
        if (values.isEmpty()) values.add(defaultPanel());
        save(values);
    }

    @Nullable
    public DriverFavoritesPanelConfig find(@NonNull String id) {
        return find(load(), id);
    }

    @NonNull
    public DriverFavoritesPanelConfig resolve(@Nullable String id) {
        String requested = id == null || id.trim().isEmpty()
                ? DriverFavoritesPanelConfig.DEFAULT_ID : id.trim();
        DriverFavoritesPanelConfig value = find(requested);
        if (value != null) return value;
        List<DriverFavoritesPanelConfig> values = load();
        return values.get(0).copy();
    }

    public void save(@NonNull List<DriverFavoritesPanelConfig> source) {
        try {
            JSONArray items = new JSONArray();
            List<String> ids = new ArrayList<>();
            for (DriverFavoritesPanelConfig raw : source) {
                DriverFavoritesPanelConfig value = sanitize(raw.copy());
                if (ids.contains(value.id)) continue;
                ids.add(value.id);
                items.put(new JSONObject()
                        .put("id", value.id)
                        .put("title", value.title)
                        .put("columns", value.columns)
                        .put("visibleRows", value.visibleRows)
                        .put("cellSizePx", value.cellSizePx)
                        .put("gapPx", value.gapPx)
                        .put("borderEnabled", value.borderEnabled)
                        .put("borderWidthPx", value.borderWidthPx)
                        .put("borderColor", value.borderColor)
                        .put("autoCloseSeconds", value.autoCloseSeconds));
            }
            if (items.length() == 0) {
                DriverFavoritesPanelConfig fallback = defaultPanel();
                items.put(new JSONObject()
                        .put("id", fallback.id)
                        .put("title", fallback.title)
                        .put("columns", fallback.columns)
                        .put("visibleRows", fallback.visibleRows)
                        .put("cellSizePx", fallback.cellSizePx)
                        .put("gapPx", fallback.gapPx)
                        .put("borderEnabled", fallback.borderEnabled)
                        .put("borderWidthPx", fallback.borderWidthPx)
                        .put("borderColor", fallback.borderColor)
                        .put("autoCloseSeconds", fallback.autoCloseSeconds));
            }
            preferences.driverFavoritesPanelsJson.set(new JSONObject()
                    .put("version", SCHEMA_VERSION)
                    .put("items", items).toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    private List<DriverFavoritesPanelConfig> mutable() {
        List<DriverFavoritesPanelConfig> result = new ArrayList<>();
        for (DriverFavoritesPanelConfig value : load()) result.add(value.copy());
        return result;
    }

    @Nullable
    private static DriverFavoritesPanelConfig decode(@Nullable JSONObject item) {
        if (item == null) return null;
        try {
            DriverFavoritesPanelConfig value = new DriverFavoritesPanelConfig();
            value.id = AutomationContract.requireSafeId(item.optString("id", ""));
            value.title = item.optString("title", "Избранное");
            value.columns = item.optInt("columns", value.columns);
            value.visibleRows = item.optInt("visibleRows", value.visibleRows);
            value.cellSizePx = item.optInt("cellSizePx", value.cellSizePx);
            value.gapPx = item.optInt("gapPx", value.gapPx);
            value.borderEnabled = item.optBoolean("borderEnabled", value.borderEnabled);
            value.borderWidthPx = item.optInt("borderWidthPx", value.borderWidthPx);
            value.borderColor = item.optString("borderColor", value.borderColor);
            value.autoCloseSeconds = item.optInt(
                    "autoCloseSeconds", value.autoCloseSeconds);
            return sanitize(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static DriverFavoritesPanelConfig find(
            @NonNull List<DriverFavoritesPanelConfig> values, @NonNull String id) {
        for (DriverFavoritesPanelConfig value : values) {
            if (value.id.equals(id)) return value.copy();
        }
        return null;
    }

    @NonNull
    private static DriverFavoritesPanelConfig defaultPanel() {
        DriverFavoritesPanelConfig value = new DriverFavoritesPanelConfig();
        value.id = DriverFavoritesPanelConfig.DEFAULT_ID;
        value.title = "Избранное";
        return value;
    }

    @NonNull
    private static DriverFavoritesPanelConfig sanitize(
            @NonNull DriverFavoritesPanelConfig value) {
        value.id = AutomationContract.requireSafeId(value.id);
        value.title = value.title == null || value.title.trim().isEmpty()
                ? "Избранное" : value.title.trim();
        value.columns = clamp(value.columns, DriverFavoritesPanelConfig.MIN_COLUMNS,
                DriverFavoritesPanelConfig.MAX_COLUMNS);
        value.visibleRows = clamp(value.visibleRows,
                DriverFavoritesPanelConfig.MIN_VISIBLE_ROWS,
                DriverFavoritesPanelConfig.MAX_VISIBLE_ROWS);
        value.cellSizePx = clamp(value.cellSizePx,
                DriverFavoritesPanelConfig.MIN_CELL_SIZE_PX,
                DriverFavoritesPanelConfig.MAX_CELL_SIZE_PX);
        value.gapPx = clamp(value.gapPx, 0, DriverFavoritesPanelConfig.MAX_GAP_PX);
        value.borderWidthPx = clamp(value.borderWidthPx, 0,
                DriverFavoritesPanelConfig.MAX_BORDER_WIDTH_PX);
        value.borderColor = value.borderColor == null || value.borderColor.trim().isEmpty()
                ? "#55FFFFFF" : value.borderColor.trim();
        if (value.autoCloseSeconds <= DriverFavoritesPanelConfig.AUTO_CLOSE_DISABLED_SECONDS) {
            value.autoCloseSeconds = DriverFavoritesPanelConfig.AUTO_CLOSE_DISABLED_SECONDS;
        } else {
            value.autoCloseSeconds = clamp(value.autoCloseSeconds,
                    DriverFavoritesPanelConfig.MIN_AUTO_CLOSE_SECONDS,
                    DriverFavoritesPanelConfig.MAX_AUTO_CLOSE_SECONDS);
        }
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
