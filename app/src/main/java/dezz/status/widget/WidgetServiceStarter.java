/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

/** Idempotent bootstrap shared by HOME, boot and package-update recovery paths. */
public final class WidgetServiceStarter {
    private static final String TAG = "WidgetServiceStarter";
    private static final long[] RETRY_DELAYS_MS = {2_000L, 5_000L, 15_000L};
    static final String ACTION_RETRY =
            "dezz.status.widget.action.RETRY_WIDGET_SERVICE_START";
    static final String EXTRA_RETRY_ATTEMPT = "retry_attempt";
    private static final int RETRY_REQUEST_CODE = 0x5749;

    private WidgetServiceStarter() {}

    /**
     * Starts the status overlay only when the user left it enabled and Android currently exposes
     * all required permissions. A temporarily unavailable locked-boot AppOp is deliberately not
     * persisted as an opt-out: USER_UNLOCKED or the next HOME start will retry safely.
     */
    public static boolean startIfNeeded(@NonNull Context context) {
        return attemptStart(applicationContext(context), -1);
    }

    /**
     * Boot/package recovery with a small bounded retry for transient OEM FGS rejections. The
     * retry is an explicit AlarmManager broadcast rather than an in-process Handler, so Android
     * can terminate the short-lived boot-receiver process without losing the recovery attempt.
     */
    public static boolean startIfNeededWithRetry(@NonNull Context context) {
        Context app = applicationContext(context);
        cancelPendingRetry(app);
        return attemptStart(app, 0);
    }

    static boolean retryFromAlarm(@NonNull Context context, int retryAttempt) {
        Context app = applicationContext(context);
        if (retryAttempt < 1 || retryAttempt > RETRY_DELAYS_MS.length) {
            Log.w(TAG, "Ignored invalid status overlay retry " + retryAttempt);
            cancelPendingRetry(app);
            return false;
        }
        return attemptStart(app, retryAttempt);
    }

    private static boolean attemptStart(@NonNull Context app, int retryAttempt) {
        try {
            if (WidgetService.isRunning()) {
                cancelPendingRetry(app);
                return true;
            }
            Preferences preferences = new Preferences(app);
            if (!preferences.widgetEnabled.get()) {
                cancelPendingRetry(app);
                return false;
            }
            if (!Permissions.allPermissionsGranted(app)) {
                Log.w(TAG, "Status overlay remains enabled; waiting for permissions/unlock");
                cancelPendingRetry(app);
                return false;
            }
            app.startForegroundService(new Intent(app, WidgetService.class));
            cancelPendingRetry(app);
            return true;
        } catch (RuntimeException failure) {
            // OEM builds can reject a foreground-service start briefly while the display/user is
            // still becoming ready. Keep the preference intact so a later lifecycle event retries.
            Log.e(TAG, "Could not start status overlay yet", failure);
            scheduleRetry(app, retryAttempt);
            return false;
        }
    }

    private static void scheduleRetry(@NonNull Context app, int retryAttempt) {
        // Plain HOME calls use -1 and deliberately do not create a background alarm.
        if (retryAttempt < 0) return;
        if (retryAttempt >= RETRY_DELAYS_MS.length) {
            Log.e(TAG, "Status overlay start retry limit reached");
            cancelPendingRetry(app);
            return;
        }
        int nextAttempt = retryAttempt + 1;
        Intent retry = new Intent(app, BootReceiver.class)
                .setAction(ACTION_RETRY)
                .putExtra(EXTRA_RETRY_ATTEMPT, nextAttempt);
        PendingIntent pending = PendingIntent.getBroadcast(app, RETRY_REQUEST_CODE, retry,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) {
            Log.e(TAG, "AlarmManager unavailable; status overlay retry was not scheduled");
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + RETRY_DELAYS_MS[retryAttempt];
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            Log.i(TAG, "Scheduled status overlay retry " + nextAttempt);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not schedule status overlay retry", failure);
        }
    }

    private static void cancelPendingRetry(@NonNull Context app) {
        Intent retry = new Intent(app, BootReceiver.class).setAction(ACTION_RETRY);
        PendingIntent pending = PendingIntent.getBroadcast(app, RETRY_REQUEST_CODE, retry,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        try {
            AlarmManager alarms = app.getSystemService(AlarmManager.class);
            if (alarms != null) alarms.cancel(pending);
            pending.cancel();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not cancel status overlay retry", failure);
        }
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
