/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import dezz.status.widget.Preferences;
import dezz.status.widget.integration.SourceBinding;

/** Versioned persistence for the independent HOME information panel. */
public final class InformationPanelConfigStore {
    public static final int SCHEMA_VERSION = 1;
    private final Preferences preferences;

    public InformationPanelConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public InformationPanelConfig load() {
        return decode(preferences.launcherInformationConfigJson.get());
    }

    public void save(@NonNull InformationPanelConfig source) {
        try {
            preferences.launcherInformationConfigJson.set(encode(source).toString());
        } catch (JSONException ignored) {
            // SourceBinding and normalized primitive fields are always JSON-safe.
        }
    }

    public void reset() {
        preferences.launcherInformationConfigJson.set("");
    }

    @NonNull
    static InformationPanelConfig decode(@Nullable String raw) {
        InformationPanelConfig result = new InformationPanelConfig();
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return result;
            result.backgroundColor = root.optString("backgroundColor", result.backgroundColor);
            result.backgroundAlpha = root.optInt("backgroundAlpha", result.backgroundAlpha);
            result.cornerRadiusPx = root.optInt("cornerRadiusPx", result.cornerRadiusPx);
            result.contentPaddingPx = root.optInt("contentPaddingPx", result.contentPaddingPx);
            result.gapPx = root.optInt("gapPx", result.gapPx);
            result.columns = root.optInt("columns", result.columns);
            result.rows = root.optInt("rows", result.rows);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject encoded = items.optJSONObject(index);
                    if (encoded == null) continue;
                    InformationPanelConfig.SourceKind kind;
                    try {
                        kind = InformationPanelConfig.SourceKind.valueOf(
                                encoded.optString("sourceKind", ""));
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    SourceBinding binding = null;
                    JSONObject bindingJson = encoded.optJSONObject("binding");
                    if (bindingJson != null) {
                        try { binding = SourceBinding.fromJson(bindingJson); }
                        catch (IllegalArgumentException ignored) { binding = null; }
                    }
                    InformationPanelConfig.Item item =
                            InformationPanelConfig.Item.restored(
                                    encoded.optString("id", ""),
                                    kind,
                                    encoded.optString("sourceId", ""),
                                    binding,
                                    encoded.optString("sourceLabel", ""),
                                    encoded.optString("sourceUnit", ""),
                                    encoded.optString("sourceTypeHint", ""));
                    item.labelOverride = encoded.optString("labelOverride", "");
                    item.iconKey = encoded.optString("iconKey", "auto");
                    item.iconColor = encoded.optString("iconColor", "#FFFFFF");
                    item.valueColor = encoded.optString("valueColor", "#FFFFFF");
                    item.labelColor = encoded.optString("labelColor", "#AEB9C8");
                    try {
                        item.visibility = InformationPanelConfig.Visibility.valueOf(
                                encoded.optString("visibility", "ALWAYS"));
                    } catch (IllegalArgumentException ignored) {
                        item.visibility = InformationPanelConfig.Visibility.ALWAYS;
                    }
                    item.enabled = encoded.optBoolean("enabled", true);
                    item.showIcon = encoded.optBoolean("showIcon", true);
                    item.showLabel = encoded.optBoolean("showLabel", true);
                    item.column = encoded.optInt("column", 0);
                    item.row = encoded.optInt("row", 0);
                    item.columnSpan = encoded.optInt("columnSpan", 1);
                    item.rowSpan = encoded.optInt("rowSpan", 1);
                    item.scalePercent = encoded.optInt("scalePercent", 100);
                    item.decimals = encoded.optInt("decimals", item.decimals);
                    item.unitOverride = encoded.optString("unitOverride", "");
                    result.mutableItems().add(item);
                }
            }
        } catch (JSONException ignored) {
            return new InformationPanelConfig();
        }
        result.normalize();
        return result;
    }

    @NonNull
    static JSONObject encode(@NonNull InformationPanelConfig source) throws JSONException {
        InformationPanelConfig value = source.copy();
        value.normalize();
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("backgroundColor", value.backgroundColor);
        root.put("backgroundAlpha", value.backgroundAlpha);
        root.put("cornerRadiusPx", value.cornerRadiusPx);
        root.put("contentPaddingPx", value.contentPaddingPx);
        root.put("gapPx", value.gapPx);
        root.put("columns", value.columns);
        root.put("rows", value.rows);
        JSONArray items = new JSONArray();
        for (InformationPanelConfig.Item item : value.mutableItems()) {
            JSONObject encoded = new JSONObject();
            encoded.put("id", item.id);
            encoded.put("sourceKind", item.sourceKind.name());
            encoded.put("sourceId", item.sourceId);
            if (item.binding != null) encoded.put("binding", item.binding.toJson());
            encoded.put("sourceLabel", item.sourceLabel);
            encoded.put("sourceUnit", item.sourceUnit);
            encoded.put("sourceTypeHint", item.sourceTypeHint);
            encoded.put("labelOverride", item.labelOverride);
            encoded.put("iconKey", item.iconKey);
            encoded.put("iconColor", item.iconColor);
            encoded.put("valueColor", item.valueColor);
            encoded.put("labelColor", item.labelColor);
            encoded.put("visibility", item.visibility.name());
            encoded.put("enabled", item.enabled);
            encoded.put("showIcon", item.showIcon);
            encoded.put("showLabel", item.showLabel);
            encoded.put("column", item.column);
            encoded.put("row", item.row);
            encoded.put("columnSpan", item.columnSpan);
            encoded.put("rowSpan", item.rowSpan);
            encoded.put("scalePercent", item.scalePercent);
            encoded.put("decimals", item.decimals);
            encoded.put("unitOverride", item.unitOverride);
            items.put(encoded);
        }
        root.put("items", items);
        return root;
    }
}
