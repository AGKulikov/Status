/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Durable store for the instrument panel; one atomic JSON write drives live projection. */
public final class InstrumentPanelStore {
    public static final String PREFS = "instrument_panel";
    public static final String KEY_CONFIG = "config_json";
    /** Active profile plus one complete document per approved variant. */
    public static final String KEY_ACTIVE_PRESET = "active_preset_id";
    private static final String KEY_PRESET_PREFIX = "preset_config_json_";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String ACTION_CONFIG_CHANGED =
            "ru.natro.statuswidget.internal.INSTRUMENT_CONFIG_CHANGED";
    public static final String ACTION_CLOSE =
            "ru.natro.statuswidget.internal.INSTRUMENT_CLOSE";
    public static final String EXTRA_LAUNCH_TOKEN = "instrument_launch_token";
    private static final String KEY_LAUNCH_TOKEN = "launch_token";
    private static final String KEY_LAUNCH_TOKEN_EXPIRES_AT = "launch_token_expires_at";
    private static final long LAUNCH_TOKEN_LIFETIME_MS = 15_000L;

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
        String activeId = preferences.getString(KEY_ACTIVE_PRESET, "");
        if (activeId != null && !activeId.trim().isEmpty()) {
            InstrumentPanelPreset active = InstrumentPanelPreset.fromId(activeId);
            InstrumentPanelConfig profile = readConfig(profileKey(active));
            if (profile != null && active.id.equals(profile.presetId)) return profile;
        }
        // KEY_CONFIG is the old single-document location. Reading it here gives existing users a
        // lossless first migration; the next save assigns that document to its own variant.
        InstrumentPanelConfig legacy = readConfig(KEY_CONFIG);
        return legacy == null ? InstrumentPanelConfig.defaults() : legacy;
    }

    public void save(@NonNull InstrumentPanelConfig config) {
        config.normalize();
        try {
            String raw = config.toJson().toString();
            InstrumentPanelPreset preset = InstrumentPanelPreset.fromId(config.presetId);
            preferences.edit()
                    .putString(KEY_CONFIG, raw)
                    .putString(KEY_ACTIVE_PRESET, preset.id)
                    .putString(profileKey(preset), raw)
                    .apply();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Saves the current variant and restores the target variant's independent module document.
     * Only the physical ECARX display remains global; layout, visibility, sizes and background
     * belong to each of the five variants and survive arbitrary switching between them.
     */
    @NonNull
    public InstrumentPanelConfig switchPreset(@NonNull InstrumentPanelPreset target,
                                              @NonNull InstrumentPanelConfig current) {
        save(current);
        InstrumentPanelConfig next = readConfig(profileKey(target));
        if (next == null || !target.id.equals(next.presetId)) next = target.create();
        next.displayId = current.displayId;
        next.normalize();
        save(next);
        return next;
    }

    @NonNull
    private static String profileKey(@NonNull InstrumentPanelPreset preset) {
        return KEY_PRESET_PREFIX + preset.id;
    }

    private InstrumentPanelConfig readConfig(@NonNull String key) {
        String raw = preferences.getString(key, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return InstrumentPanelConfig.fromJson(new JSONObject(raw));
        } catch (JSONException | RuntimeException ignored) {
            return null;
        }
    }

    /** One durable capability authorizes exactly one fresh exported DIM Activity start. */
    @NonNull
    public String issueLaunchToken() {
        String token = UUID.randomUUID().toString();
        boolean stored = preferences.edit()
                .putString(KEY_LAUNCH_TOKEN, token)
                .putLong(KEY_LAUNCH_TOKEN_EXPIRES_AT,
                        System.currentTimeMillis() + LAUNCH_TOKEN_LIFETIME_MS)
                .commit();
        if (!stored) throw new IllegalStateException("Could not persist instrument launch token");
        return token;
    }

    /** Validates and consumes the capability before any driver-display content is created. */
    public synchronized boolean consumeLaunchToken(String candidate) {
        if (candidate == null || candidate.length() < 16 || candidate.length() > 128) return false;
        String expected = preferences.getString(KEY_LAUNCH_TOKEN, "");
        long expiresAt = preferences.getLong(KEY_LAUNCH_TOKEN_EXPIRES_AT, 0L);
        boolean valid = candidate.equals(expected) && System.currentTimeMillis() <= expiresAt;
        if (valid || (expiresAt > 0L && System.currentTimeMillis() > expiresAt)) {
            if (!preferences.edit()
                    .remove(KEY_LAUNCH_TOKEN)
                    .remove(KEY_LAUNCH_TOKEN_EXPIRES_AT)
                    .commit()) return false;
        }
        return valid;
    }
}
