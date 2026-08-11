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
     * Starts the shared integration host when a surface or a headless connector needs it.
     * A temporarily unavailable locked-boot AppOp is deliberately not persisted as an opt-out:
     * USER_UNLOCKED or the next HOME start will retry safely.
     */
    public static boolean startIfNeeded(@NonNull Context context) {
        return attemptStart(applicationContext(context), -1, false);
    }

    /** Automatic HOME/boot entry respects HUD autostart instead of waking a manual-only HUD. */
    public static boolean startIfNeededAutomatically(@NonNull Context context) {
        return attemptStart(applicationContext(context), -1, true);
    }

    /**
     * Boot/package recovery with a small bounded retry for transient OEM FGS rejections. The
     * retry is an explicit AlarmManager broadcast rather than an in-process Handler, so Android
     * can terminate the short-lived boot-receiver process without losing the recovery attempt.
     */
    public static boolean startIfNeededWithRetry(@NonNull Context context) {
        Context app = applicationContext(context);
        cancelPendingRetry(app);
        return attemptStart(app, 0, true);
    }

    static boolean retryFromAlarm(@NonNull Context context, int retryAttempt) {
        Context app = applicationContext(context);
        if (retryAttempt < 1 || retryAttempt > RETRY_DELAYS_MS.length) {
            Log.w(TAG, "Ignored invalid status overlay retry " + retryAttempt);
            cancelPendingRetry(app);
            return false;
        }
        return attemptStart(app, retryAttempt, true);
    }

    private static boolean attemptStart(@NonNull Context app, int retryAttempt,
                                        boolean automaticLifecycle) {
        try {
            if (WidgetService.isRunning()) {
                cancelPendingRetry(app);
                return true;
            }
            // HOME, Settings and the isolated HUD process can all race the boot receiver. Before
            // parsing Preferences or constructing the foreground-service graph, honor the one
            // device-protected quiet boundary and coalesce every caller onto the same alarm.
            if (StartupWorkCoordinator.remainingQuietMillis(app) > 0L
                    || StartupWorkCoordinator.isStartupInitializationBlocked(app)) {
                if (!AppProcessPolicy.isHudProcess()) {
                    StartupWorkCoordinator.ensureIntegrationHostScheduled(app);
                }
                Log.i(TAG, "Integration host deferred until the boot quiet window closes");
                return false;
            }
            if (!StartupWorkCoordinator.isUserUnlocked(app)) {
                if (!AppProcessPolicy.isHudProcess()) {
                    StartupWorkCoordinator.ensureIntegrationHostScheduled(app);
                }
                Log.i(TAG, "Integration host deferred until USER_UNLOCKED");
                return false;
            }
            Preferences preferences = new Preferences(app);
            boolean integrationHostRequired = automaticLifecycle
                    ? requiresAutomaticIntegrationHost(preferences)
                    : requiresIntegrationHost(preferences);
            boolean headlessHostRequired = automaticLifecycle
                    ? requiresAutomaticHeadlessHost(preferences)
                    : requiresHeadlessHost(preferences);
            if (!integrationHostRequired) {
                cancelPendingRetry(app);
                return false;
            }
            if (!Permissions.allPermissionsGranted(app)
                    && !headlessHostRequired) {
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

    static boolean requiresIntegrationHost(@NonNull Preferences preferences) {
        return requiresIntegrationHost(
                preferences.widgetEnabled.get(),
                preferences.driverPanelEnabled.get(),
                preferences.hudPanelEnabled.get(),
                preferences.phoneConnectorEnabled.get(),
                preferences.mqttEnabled.get(),
                preferences.sprutEnabled.get(),
                preferences.haApiEnabled.get());
    }

    static boolean requiresHeadlessHost(@NonNull Preferences preferences) {
        return requiresHeadlessHost(
                preferences.driverPanelEnabled.get(),
                preferences.hudPanelEnabled.get(),
                preferences.phoneConnectorEnabled.get(),
                preferences.mqttEnabled.get(),
                preferences.sprutEnabled.get(),
                preferences.haApiEnabled.get());
    }

    static boolean requiresAutomaticIntegrationHost(@NonNull Preferences preferences) {
        return requiresAutomaticIntegrationHost(
                preferences.widgetEnabled.get(),
                preferences.driverPanelEnabled.get(),
                preferences.hudPanelEnabled.get(),
                preferences.hudPanelAutostart.get(),
                preferences.phoneConnectorEnabled.get(),
                preferences.mqttEnabled.get(),
                preferences.sprutEnabled.get(),
                preferences.haApiEnabled.get());
    }

    static boolean requiresAutomaticHeadlessHost(@NonNull Preferences preferences) {
        return requiresAutomaticHeadlessHost(
                preferences.driverPanelEnabled.get(),
                preferences.hudPanelEnabled.get(),
                preferences.hudPanelAutostart.get(),
                preferences.phoneConnectorEnabled.get(),
                preferences.mqttEnabled.get(),
                preferences.sprutEnabled.get(),
                preferences.haApiEnabled.get());
    }

    static boolean requiresAutomaticIntegrationHost(
            boolean widgetEnabled, boolean driverPanelEnabled,
            boolean hudPanelEnabled, boolean hudPanelAutostart,
            boolean phoneConnectorEnabled, boolean mqttEnabled,
            boolean sprutEnabled, boolean haApiEnabled) {
        return widgetEnabled || requiresAutomaticHeadlessHost(
                driverPanelEnabled, hudPanelEnabled, hudPanelAutostart,
                phoneConnectorEnabled, mqttEnabled, sprutEnabled, haApiEnabled);
    }

    static boolean requiresAutomaticHeadlessHost(
            boolean driverPanelEnabled, boolean hudPanelEnabled,
            boolean hudPanelAutostart, boolean phoneConnectorEnabled,
            boolean mqttEnabled, boolean sprutEnabled, boolean haApiEnabled) {
        return requiresHeadlessHost(driverPanelEnabled,
                hudPanelEnabled && hudPanelAutostart, phoneConnectorEnabled,
                mqttEnabled, sprutEnabled, haApiEnabled);
    }

    static boolean requiresIntegrationHost(boolean widgetEnabled,
                                           boolean driverPanelEnabled,
                                           boolean hudPanelEnabled,
                                           boolean phoneConnectorEnabled) {
        return requiresIntegrationHost(widgetEnabled, driverPanelEnabled, hudPanelEnabled,
                phoneConnectorEnabled, false, false, false);
    }

    static boolean requiresIntegrationHost(boolean widgetEnabled,
                                           boolean driverPanelEnabled,
                                           boolean hudPanelEnabled,
                                           boolean phoneConnectorEnabled,
                                           boolean mqttEnabled,
                                           boolean sprutEnabled,
                                           boolean haApiEnabled) {
        return widgetEnabled || requiresHeadlessHost(
                driverPanelEnabled, hudPanelEnabled, phoneConnectorEnabled,
                mqttEnabled, sprutEnabled, haApiEnabled);
    }

    static boolean requiresHeadlessHost(boolean driverPanelEnabled,
                                        boolean hudPanelEnabled,
                                        boolean phoneConnectorEnabled) {
        return requiresHeadlessHost(driverPanelEnabled, hudPanelEnabled, phoneConnectorEnabled,
                false, false, false);
    }

    static boolean requiresHeadlessHost(boolean driverPanelEnabled,
                                        boolean hudPanelEnabled,
                                        boolean phoneConnectorEnabled,
                                        boolean mqttEnabled,
                                        boolean sprutEnabled,
                                        boolean haApiEnabled) {
        return driverPanelEnabled || hudPanelEnabled || phoneConnectorEnabled
                || mqttEnabled || sprutEnabled || haApiEnabled;
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
