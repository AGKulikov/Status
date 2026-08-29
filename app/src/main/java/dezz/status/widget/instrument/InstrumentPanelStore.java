/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/** Durable store for the instrument panel; one atomic JSON write drives live projection. */
public final class InstrumentPanelStore {
    public static final String PREFS = "instrument_panel";
    public static final String KEY_CONFIG = "config_json";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String ACTION_CONFIG_CHANGED =
            "ru.natro.statuswidget.internal.INSTRUMENT_CONFIG_CHANGED";
    public static final String ACTION_CLOSE =
            "ru.natro.statuswidget.internal.INSTRUMENT_CLOSE";

    @NonNull private final SharedPreferences preferences;

    public InstrumentPanelStore(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        // BootReceiver restores the DIM surface at the lifecycle boundary, including ECARX
        // QuickBoot. Keep the enable/autostart gate and layout readable before credential storage
        // is unlocked so opening Settings is never the event that makes the panel appear.
        Context storage = app.createDeviceProtectedStorageContext();
        preferences = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public boolean isAutostart() {
        return preferences.getBoolean(KEY_AUTOSTART, true);
    }

    public void setAutostart(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTOSTART, enabled).apply();
    }

    @NonNull
    public InstrumentPanelConfig load() {
        String raw = preferences.getString(KEY_CONFIG, "");
        if (raw == null || raw.trim().isEmpty()) return InstrumentPanelConfig.defaults();
        try {
            return InstrumentPanelConfig.fromJson(new JSONObject(raw));
        } catch (JSONException | RuntimeException ignored) {
            return InstrumentPanelConfig.defaults();
        }
    }

    public void save(@NonNull InstrumentPanelConfig config) {
        config.normalize();
        try {
            preferences.edit().putString(KEY_CONFIG, config.toJson().toString()).apply();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
