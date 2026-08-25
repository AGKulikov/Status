/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;

/**
 * Gives the OEM Bluetooth stack a short cleanup window after an in-place APK replacement.
 *
 * <p>The old process can be killed while its GATT client registration is still owned by the
 * system Bluetooth process. Starting the replacement process immediately can then register a new
 * client privately while Android withholds every callback. Only the phone transport is delayed;
 * Natro's visible surface and other boot work remain immediately available.</p>
 */
public final class PackageReplaceBleRecoveryGate {
    static final long QUIET_MS = 8_000L;
    private static final long MAX_VALID_MARK_AGE_MS = 60_000L;
    private static final String PREFS = "phone_package_replace_recovery";
    private static final String KEY_MARK_ELAPSED = "markElapsed";
    private static volatile long inProcessMarkElapsed = Long.MIN_VALUE;

    private PackageReplaceBleRecoveryGate() {
    }

    /** Called directly at the {@code MY_PACKAGE_REPLACED} receiver boundary. */
    public static void mark(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();
        inProcessMarkElapsed = now;
        state(context).edit().putLong(KEY_MARK_ELAPSED, now).apply();
        PhoneConnectionJournal.append("controller",
                "package-replace BLE cleanup window armed for " + QUIET_MS + " ms");
    }

    /** Returns only a bounded same-kernel delay; stale marks can never freeze startup. */
    public static long remainingQuietMillis(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();
        long persisted = state(context).getLong(KEY_MARK_ELAPSED, Long.MIN_VALUE);
        return Math.max(remainingQuietMillis(persisted, now),
                remainingQuietMillis(inProcessMarkElapsed, now));
    }

    static long remainingQuietMillis(long markedAtElapsed, long nowElapsed) {
        if (markedAtElapsed == Long.MIN_VALUE || nowElapsed < markedAtElapsed) return 0L;
        long age = nowElapsed - markedAtElapsed;
        if (age > MAX_VALID_MARK_AGE_MS || age >= QUIET_MS) return 0L;
        return QUIET_MS - age;
    }

    @NonNull
    private static SharedPreferences state(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        return app.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
