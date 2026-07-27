/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Device-protected, opt-in configuration for the conservative method 01 fallback. */
final class HudModeFallbackStore {
    static final int NO_MODE = Integer.MIN_VALUE;

    private static final String PREFS = "hud_mode_fallback_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TARGET = "target";
    private static final String KEY_ROLLBACK = "rollback";
    private static final String KEY_STATUS = "status";
    private static final String KEY_STATUS_AT = "status_at";
    private static final String KEY_TRIGGER = "trigger";
    private static final String KEY_WRITE_AT = "write_at";

    static final class Config {
        final boolean enabled;
        final int target;
        final int rollback;

        Config(boolean enabled, int target, int rollback) {
            this.enabled = enabled;
            this.target = target;
            this.rollback = rollback;
        }

        boolean isValid() {
            return !enabled || (isTargetMode(target)
                    && HudProfileTransferMode.isSdkMode(rollback));
        }
    }

    private HudModeFallbackStore() {
    }

    static boolean isTargetMode(int mode) {
        return HudProfileTransferMode.isSdkMode(mode);
    }

    static Config read(Context context) {
        SharedPreferences prefs = prefs(context);
        return new Config(
                prefs.getBoolean(KEY_ENABLED, false),
                prefs.getInt(KEY_TARGET, NO_MODE),
                prefs.getInt(KEY_ROLLBACK, NO_MODE));
    }

    static void enable(Context context, int target, int rollback) {
        if (!isTargetMode(target)) {
            throw new IllegalArgumentException("target должен быть режимом 0…3");
        }
        int validRollback = HudProfileTransferMode.requireSdkMode(rollback);
        boolean committed = prefs(context).edit()
                .putInt(KEY_TARGET, target)
                .putInt(KEY_ROLLBACK, validRollback)
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_STATUS, "Включён резервный автоповтор; ожидание ECARX")
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .commit();
        if (!committed) {
            throw new IllegalStateException("не удалось сохранить резервный режим");
        }
    }

    /**
     * Commits OFF before the service receives its stop intent. Every write path checks this bit.
     */
    static void disable(Context context) {
        boolean committed = prefs(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .putString(KEY_STATUS, "Резервный автоповтор выключен; записей больше не будет")
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .commit();
        if (!committed) {
            throw new IllegalStateException("не удалось выключить резервный режим");
        }
    }

    static void recordStatus(Context context, String status) {
        prefs(context).edit()
                .putString(KEY_STATUS, status)
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .apply();
    }

    static void recordWrite(Context context, String trigger, String status) {
        prefs(context).edit()
                .putString(KEY_TRIGGER, trigger)
                .putString(KEY_STATUS, status)
                .putLong(KEY_STATUS_AT, System.currentTimeMillis())
                .putLong(KEY_WRITE_AT, System.currentTimeMillis())
                .apply();
    }

    static String describe(Context context) {
        SharedPreferences prefs = prefs(context);
        Config config = read(context);
        String state = config.enabled
                ? "ВКЛ (резерв), target=" + modeLabel(config.target)
                + ", rollback=" + config.rollback
                : "ВЫКЛ";
        String status = prefs.getString(KEY_STATUS, "событий ещё нет");
        String trigger = prefs.getString(KEY_TRIGGER, "");
        long at = prefs.getLong(KEY_STATUS_AT, 0L);
        long writeAt = prefs.getLong(KEY_WRITE_AT, 0L);
        return state
                + "\n  " + status + (at == 0L ? "" : " · " + time(at))
                + (trigger.isEmpty() ? "" : "\n  Триггер: " + trigger)
                + (writeAt == 0L ? "" : "\n  Последняя запись: " + time(writeAt));
    }

    static String modeLabel(int mode) {
        return isTargetMode(mode) ? "SDK " + mode : "не выбран";
    }

    private static String time(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(millis));
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        Context base = app == null ? context : app;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Context device = base.createDeviceProtectedStorageContext();
            if (device != null) base = device;
        }
        return base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
