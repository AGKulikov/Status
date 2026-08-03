/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Device-protected opt-in state for the conservative CB33278 fallback. */
final class HudModeFallbackStore {
    static final int NO_MODE = Integer.MIN_VALUE;

    private static final String PREFS = "status_widget_hud_mode_fallback_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TARGET = "target";
    private static final String KEY_STATUS = "status";
    private static final String KEY_TRIGGER = "trigger";
    private static final String KEY_STATUS_AT = "status_at";

    private HudModeFallbackStore() {
    }

    static Config read(Context context) {
        SharedPreferences preferences = prefs(context);
        return new Config(preferences.getBoolean(KEY_ENABLED, false),
                preferences.getInt(KEY_TARGET, NO_MODE));
    }

    static void enable(Context context, int target) {
        requireMode(target);
        if (!prefs(context).edit()
                .putInt(KEY_TARGET, target)
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_STATUS, "Включён; ожидание ECARX")
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .commit()) {
            throw new IllegalStateException("не удалось сохранить режим HUD");
        }
    }

    /** OFF is committed before the service is asked to stop, closing the queued-write race. */
    static void disable(Context context) {
        if (!prefs(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .putString(KEY_STATUS, "Автоповтор выключен; записей больше не будет")
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .commit()) {
            throw new IllegalStateException("не удалось выключить автоповтор HUD");
        }
    }

    static void record(Context context, String trigger, String status) {
        prefs(context).edit()
                .putString(KEY_TRIGGER, trigger == null ? "" : trigger)
                .putString(KEY_STATUS, status == null ? "" : status)
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .apply();
    }

    static int requireMode(int mode) {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("режим HUD должен быть 0…3");
        }
        return mode;
    }

    private static SharedPreferences prefs(Context context) {
        Context application = context.getApplicationContext();
        Context base = application == null ? context : application;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Context device = base.createDeviceProtectedStorageContext();
            if (device != null) base = device;
        }
        return base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Config {
        final boolean enabled;
        final int target;

        Config(boolean enabled, int target) {
            this.enabled = enabled;
            this.target = target;
        }

        boolean isValid() {
            return !enabled || (target >= 0 && target <= 3);
        }
    }
}
