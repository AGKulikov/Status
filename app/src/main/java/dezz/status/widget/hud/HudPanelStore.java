/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import org.json.JSONException;

import dezz.status.widget.Preferences;

/** Crash-tolerant preference adapter for the versioned HUD document. */
public final class HudPanelStore {
    @NonNull private final Preferences preferences;

    public HudPanelStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public HudPanelConfig load() {
        try {
            return HudPanelConfig.fromJson(preferences.hudPanelConfigJson.get());
        } catch (RuntimeException invalid) {
            return HudPanelConfig.defaults();
        }
    }

    public void save(@NonNull HudPanelConfig config) {
        try {
            String json = config.toJson().toString();
            if (json.length() > HudPanelConfig.MAX_JSON_CHARS) {
                throw new IllegalArgumentException("HUD configuration is too large");
            }
            preferences.hudPanelConfigJson.set(json);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
