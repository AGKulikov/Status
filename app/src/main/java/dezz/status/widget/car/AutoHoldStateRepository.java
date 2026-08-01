/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.launcher.vehicle.VehicleDerivedMetrics;

/** Boot-session scoped snapshot written by the exported mHUD-compatible receiver. */
public final class AutoHoldStateRepository {
    public static final String ACTION_EXTERNAL = "plus.monjaro.AUTOHOLD";
    public static final String ACTION_CHANGED =
            "ru.natro.statuswidget.AUTO_HOLD_STATE_CHANGED";
    public static final String EXTRA_STATE = "state";

    private static final String PREFS = "vehicle_external_state";
    private static final String KEY_VALUE = "autoHoldValue";
    private static final String KEY_OBSERVED_AT = "autoHoldObservedAt";
    private static final String KEY_BOOT_COUNT = "autoHoldBootCount";
    private static final String KEY_BOOT_EPOCH = "autoHoldBootEpoch";
    private static final long BOOT_EPOCH_TOLERANCE_MS = 5L * 60L * 1_000L;

    public static final class Snapshot {
        public final boolean available;
        public final boolean value;
        public final long observedAtMillis;

        Snapshot(boolean available, boolean value, long observedAtMillis) {
            this.available = available;
            this.value = value;
            this.observedAtMillis = observedAtMillis;
        }
    }

    private AutoHoldStateRepository() {}

    /** Returns false for a missing or malformed state; malformed broadcasts never clear truth. */
    public static boolean accept(@NonNull Context context, @Nullable Object rawState) {
        Boolean state = VehicleDerivedMetrics.booleanState(rawState);
        if (state == null) return false;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = preferences(context).edit()
                .putBoolean(KEY_VALUE, state)
                .putLong(KEY_OBSERVED_AT, now)
                .putLong(KEY_BOOT_EPOCH, bootEpochMillis());
        int bootCount = bootCount(context);
        if (bootCount >= 0) editor.putInt(KEY_BOOT_COUNT, bootCount);
        else editor.remove(KEY_BOOT_COUNT);
        editor.apply();
        context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
        return true;
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences prefs = preferences(context);
        long observedAt = prefs.getLong(KEY_OBSERVED_AT, 0L);
        if (observedAt <= 0L) return new Snapshot(false, false, 0L);
        int currentBoot = bootCount(context);
        if (currentBoot >= 0 && prefs.contains(KEY_BOOT_COUNT)) {
            if (prefs.getInt(KEY_BOOT_COUNT, -1) != currentBoot) {
                return new Snapshot(false, false, observedAt);
            }
        } else {
            long storedEpoch = prefs.getLong(KEY_BOOT_EPOCH, Long.MIN_VALUE);
            if (storedEpoch == Long.MIN_VALUE
                    || Math.abs(storedEpoch - bootEpochMillis()) > BOOT_EPOCH_TOLERANCE_MS) {
                return new Snapshot(false, false, observedAt);
            }
        }
        return new Snapshot(true, prefs.getBoolean(KEY_VALUE, false), observedAt);
    }

    private static int bootCount(@NonNull Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | SecurityException ignored) {
            return -1;
        }
    }

    private static long bootEpochMillis() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
