/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Process-independent dead-man switch for the foreground integration host.
 *
 * <p>The live service continually moves one alarm into the future, so no broadcast runs during
 * normal operation. If the OEM kills the process, the last alarm remains in AlarmManager and
 * wakes a small receiver within a few seconds.</p>
 */
final class WidgetServiceWatchdog {
    private static final String TAG = "WidgetServiceWatchdog";
    static final String ACTION_CHECK = "dezz.status.widget.action.CHECK_WIDGET_SERVICE";
    static final long DEADLINE_MS = 9_000L;
    static final long BLUETOOTH_WAKE_DELAY_MS = 250L;
    static final long DESTROY_RECOVERY_DELAY_MS = 1_000L;
    private static final int REQUEST_CODE = 0x5757;

    private WidgetServiceWatchdog() {}

    static void arm(@NonNull Context context) {
        arm(context, DEADLINE_MS);
    }

    static void arm(@NonNull Context context, long delayMs) {
        Context app = applicationContext(context);
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) {
            Log.e(TAG, "AlarmManager unavailable; integration watchdog was not armed");
            return;
        }
        PendingIntent pending = pendingIntent(app, PendingIntent.FLAG_UPDATE_CURRENT);
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(1L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            }
        } catch (SecurityException exactAlarmDenied) {
            // Android variants that gate exact alarms still retain a best-effort wakeup alarm.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarms.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
                } else {
                    alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
                }
            } catch (RuntimeException failure) {
                Log.e(TAG, "Could not arm fallback integration watchdog", failure);
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not arm integration watchdog", failure);
        }
    }

    static void cancel(@NonNull Context context) {
        Context app = applicationContext(context);
        PendingIntent pending = pendingIntent(app, PendingIntent.FLAG_NO_CREATE);
        if (pending == null) return;
        try {
            AlarmManager alarms = app.getSystemService(AlarmManager.class);
            if (alarms != null) alarms.cancel(pending);
            pending.cancel();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not cancel integration watchdog", failure);
        }
    }

    private static PendingIntent pendingIntent(@NonNull Context context, int lookupFlag) {
        Intent check = new Intent(context, WidgetServiceWatchdogReceiver.class)
                .setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, check,
                lookupFlag | PendingIntent.FLAG_IMMUTABLE);
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
