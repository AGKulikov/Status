/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Versioned persistence for the visual media-panel editor. */
public final class MediaPanelConfigStore {
    public static final int SCHEMA_VERSION = 1;
    private final Preferences preferences;

    public MediaPanelConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public MediaPanelConfig load() {
        MediaPanelConfig value = new MediaPanelConfig();
        String raw = preferences.launcherMediaConfigJson.get();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return value;
            value.backgroundColor = root.optString("backgroundColor", value.backgroundColor);
            value.backgroundAlpha = root.optInt("backgroundAlpha", value.backgroundAlpha);
            value.cornerRadiusPx = root.optInt("cornerRadiusPx", value.cornerRadiusPx);
            value.spacingPx = root.optInt("spacingPx", value.spacingPx);
            value.contentPaddingPx = root.optInt("contentPaddingPx", value.contentPaddingPx);
            value.titleColor = root.optString("titleColor", value.titleColor);
            value.secondaryColor = root.optString("secondaryColor", value.secondaryColor);
            value.controlColor = root.optString("controlColor", value.controlColor);
            JSONArray elements = root.optJSONArray("elements");
            if (elements != null) {
                Set<String> restored = new HashSet<>();
                int maximumOrder = -1;
                for (int index = 0; index < elements.length(); index++) {
                    JSONObject item = elements.optJSONObject(index);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (MediaPanelConfig.spec(id) == null) continue;
                    restored.add(id);
                    value.setEnabled(id, item.optBoolean("enabled", value.element(id).enabled));
                    value.setScale(id, item.optInt("scalePercent",
                            value.element(id).scalePercent));
                    // Applying moves in array order also makes hand-edited/imported JSON robust
                    // against duplicate or sparse order numbers.
                    value.element(id).order = item.optInt("order", index);
                    maximumOrder = Math.max(maximumOrder, value.element(id).order);
                }
                // New built-ins are appended to an existing user's layout instead of being
                // inserted between carefully ordered controls after an application update.
                value.appendMissingDisabled(restored, maximumOrder);
            }
        } catch (JSONException ignored) {
            // Keep safe defaults after an interrupted write or incompatible import.
        }
        value.normalize();
        return value;
    }

    public void save(@NonNull MediaPanelConfig source) {
        MediaPanelConfig value = source.copy();
        value.normalize();
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("backgroundColor", value.backgroundColor);
            root.put("backgroundAlpha", value.backgroundAlpha);
            root.put("cornerRadiusPx", value.cornerRadiusPx);
            root.put("spacingPx", value.spacingPx);
            root.put("contentPaddingPx", value.contentPaddingPx);
            root.put("titleColor", value.titleColor);
            root.put("secondaryColor", value.secondaryColor);
            root.put("controlColor", value.controlColor);
            JSONArray elements = new JSONArray();
            for (MediaPanelConfig.Element element : value.orderedElements()) {
                JSONObject item = new JSONObject();
                item.put("id", element.id);
                item.put("enabled", element.enabled);
                item.put("order", element.order);
                item.put("scalePercent", element.scalePercent);
                elements.put(item);
            }
            root.put("elements", elements);
            preferences.launcherMediaConfigJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    public void reset() {
        preferences.launcherMediaConfigJson.set("");
    }
}
