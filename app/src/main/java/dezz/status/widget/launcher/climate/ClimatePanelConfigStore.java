/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import dezz.status.widget.Preferences;

/** Versioned and defensive persistence for the climate panel's live settings. */
public final class ClimatePanelConfigStore {
    public static final int SCHEMA_VERSION = 1;
    private final Preferences preferences;

    public ClimatePanelConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public ClimatePanelConfig load() {
        ClimatePanelConfig value = new ClimatePanelConfig();
        String raw = preferences.launcherClimateConfigJson.get();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return value;
            value.backgroundColor = root.optString("backgroundColor", value.backgroundColor);
            value.backgroundAlpha = root.optInt("backgroundAlpha", value.backgroundAlpha);
            value.cornerRadiusPx = root.optInt("cornerRadiusPx", value.cornerRadiusPx);
            value.accentColor = root.optString("accentColor", value.accentColor);
            value.inactiveColor = root.optString("inactiveColor", value.inactiveColor);
            value.textColor = root.optString("textColor", value.textColor);
            value.scalePercent = root.optInt("scalePercent", value.scalePercent);
            value.showTitle = root.optBoolean("showTitle", value.showTitle);
            value.useVehicleStateColors = root.optBoolean("vehicleColors",
                    value.useVehicleStateColors);
            JSONObject elements = root.optJSONObject("elements");
            if (elements != null) {
                for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
                    value.setElementEnabled(element.id,
                            elements.optBoolean(element.id, value.isElementEnabled(element.id)));
                }
            }
        } catch (JSONException ignored) {
            // An imported or interrupted write must not make HOME unusable.
        }
        value.normalize();
        return value;
    }

    public void save(@NonNull ClimatePanelConfig source) {
        ClimatePanelConfig value = source.copy();
        value.normalize();
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("backgroundColor", value.backgroundColor);
            root.put("backgroundAlpha", value.backgroundAlpha);
            root.put("cornerRadiusPx", value.cornerRadiusPx);
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
            preferences.launcherClimateConfigJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    public void reset() {
        preferences.launcherClimateConfigJson.set("");
    }
}
