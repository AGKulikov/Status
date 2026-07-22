/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Versioned and defensive persistence for the climate panel's live settings. */
public final class ClimatePanelConfigStore {
    public static final int SCHEMA_VERSION = 3;
    private final Preferences preferences;

    public ClimatePanelConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public ClimatePanelConfig load() {
        String raw = preferences.launcherClimateConfigJson.get();
        ClimatePanelConfig value = decode(raw);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(raw);
                if (root.optInt("version", 0) < SCHEMA_VERSION
                        && (root.has("elements") || root.has("enabledElements"))) {
                    preferences.launcherClimateConfigJson.set(encode(value).toString());
                }
            } catch (JSONException ignored) {
            }
        }
        return value;
    }

    @NonNull
    static ClimatePanelConfig decode(@Nullable String raw) {
        ClimatePanelConfig value = new ClimatePanelConfig();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject root = new JSONObject(raw);
            int version = root.optInt("version", 0);
            if (version > SCHEMA_VERSION) return value;
            value.backgroundColor = root.optString("backgroundColor", value.backgroundColor);
            value.backgroundAlpha = root.optInt("backgroundAlpha", value.backgroundAlpha);
            value.cornerRadiusPx = root.optInt("cornerRadiusPx", value.cornerRadiusPx);
            value.tileCornerRadiusPx = root.optInt("tileCornerRadiusPx",
                    value.tileCornerRadiusPx);
            value.tileSpacingPx = root.optInt("tileSpacingPx", value.tileSpacingPx);
            value.activeTileAlpha = root.optInt("activeTileAlpha", value.activeTileAlpha);
            value.inactiveTileAlpha = root.optInt("inactiveTileAlpha", value.inactiveTileAlpha);
            value.accentColor = root.optString("accentColor", value.accentColor);
            value.inactiveColor = root.optString("inactiveColor", value.inactiveColor);
            value.textColor = root.optString("textColor", value.textColor);
            value.scalePercent = root.optInt("scalePercent", value.scalePercent);
            value.showTitle = root.optBoolean("showTitle", value.showTitle);
            value.useVehicleStateColors = root.optBoolean("vehicleColors",
                    value.useVehicleStateColors);
            JSONObject elements = root.optJSONObject("elements");
            if (elements != null && (elements.length() > 0
                    || !root.has("enabledElements"))) {
                for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
                    value.setElementEnabled(element.id,
                            elements.optBoolean(element.id, value.isElementEnabled(element.id)));
                }
            } else {
                migrateLegacyEnabledElements(root, value);
            }
            JSONObject scales = root.optJSONObject("elementScales");
            if (scales != null) {
                for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
                    value.setElementScalePercent(element.id,
                            scales.optInt(element.id, value.elementScalePercent(element.id)));
                }
            }
            JSONObject widths = root.optJSONObject("elementWidths");
            JSONObject heights = root.optJSONObject("elementHeights");
            for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
                // v2 had one size slider. Preserve its visible result while splitting size into
                // physical width/height and icon/text scale in the CarPlay editor.
                int legacySize = value.elementScalePercent(element.id);
                value.setElementWidthPercent(element.id, widths == null
                        ? legacySize : widths.optInt(element.id, legacySize));
                value.setElementHeightPercent(element.id, heights == null
                        ? legacySize : heights.optInt(element.id, legacySize));
            }
            JSONArray order = root.optJSONArray("elementOrder");
            if (order != null) {
                List<String> ids = new ArrayList<>();
                for (int index = 0; index < order.length(); index++) {
                    String id = order.optString(index, "").trim();
                    if (!id.isEmpty()) ids.add(id);
                }
                value.setElementOrder(ids);
            }
        } catch (JSONException ignored) {
            // An imported or interrupted write must not make HOME unusable.
        }
        value.normalize();
        return value;
    }

    public void save(@NonNull ClimatePanelConfig source) {
        try {
            preferences.launcherClimateConfigJson.set(encode(source).toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    static JSONObject encode(@NonNull ClimatePanelConfig source) throws JSONException {
        ClimatePanelConfig value = source.copy();
        value.normalize();
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("backgroundColor", value.backgroundColor);
        root.put("backgroundAlpha", value.backgroundAlpha);
        root.put("cornerRadiusPx", value.cornerRadiusPx);
        root.put("tileCornerRadiusPx", value.tileCornerRadiusPx);
        root.put("tileSpacingPx", value.tileSpacingPx);
        root.put("activeTileAlpha", value.activeTileAlpha);
        root.put("inactiveTileAlpha", value.inactiveTileAlpha);
        root.put("accentColor", value.accentColor);
        root.put("inactiveColor", value.inactiveColor);
        root.put("textColor", value.textColor);
        root.put("scalePercent", value.scalePercent);
        root.put("showTitle", value.showTitle);
        root.put("vehicleColors", value.useVehicleStateColors);
        JSONObject elements = new JSONObject();
        for (Map.Entry<String, Boolean> entry : value.elementSelections().entrySet()) {
            elements.put(entry.getKey(), entry.getValue());
        }
        root.put("elements", elements);
        JSONObject scales = new JSONObject();
        for (Map.Entry<String, Integer> entry : value.elementScales().entrySet()) {
            scales.put(entry.getKey(), entry.getValue());
        }
        root.put("elementScales", scales);
        JSONObject widths = new JSONObject();
        for (Map.Entry<String, Integer> entry : value.elementWidths().entrySet()) {
            widths.put(entry.getKey(), entry.getValue());
        }
        root.put("elementWidths", widths);
        JSONObject heights = new JSONObject();
        for (Map.Entry<String, Integer> entry : value.elementHeights().entrySet()) {
            heights.put(entry.getKey(), entry.getValue());
        }
        root.put("elementHeights", heights);
        JSONArray order = new JSONArray();
        for (String id : value.elementOrder()) order.put(id);
        root.put("elementOrder", order);
        return root;
    }

    public void reset() {
        preferences.launcherClimateConfigJson.set("");
    }

    /**
     * Early prototypes stored only a collection named enabledElements. Accept its array,
     * comma-separated and object forms so no existing user's choices are lost.
     */
    private static void migrateLegacyEnabledElements(@NonNull JSONObject root,
                                                     @NonNull ClimatePanelConfig value) {
        Object legacy = root.opt("enabledElements");
        if (legacy == null || legacy == JSONObject.NULL) return;
        Set<String> enabled = new HashSet<>();
        if (legacy instanceof JSONArray) {
            JSONArray array = (JSONArray) legacy;
            for (int index = 0; index < array.length(); index++) {
                String id = array.optString(index, "").trim();
                if (!id.isEmpty()) enabled.add(id);
            }
        } else if (legacy instanceof JSONObject) {
            JSONObject object = (JSONObject) legacy;
            for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
                if (object.optBoolean(element.id, false)) enabled.add(element.id);
            }
        } else {
            for (String id : String.valueOf(legacy).split(",")) {
                if (!id.trim().isEmpty()) enabled.add(id.trim());
            }
        }
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            value.setElementEnabled(element.id, enabled.contains(element.id));
        }
    }
}
