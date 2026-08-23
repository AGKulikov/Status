/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.Preferences;
import dezz.status.widget.StartupWorkCoordinator;
import dezz.status.widget.phone.PhoneConnectionJournal;

/**
 * Captures the pre-boot media target and schedules a bounded, idempotent MEDIA_PLAY retry.
 *
 * <p>The target is frozen as soon as the boot broadcast arrives. This matters because an OEM
 * player may expose a temporary PAUSED session while Android is still starting; that new callback
 * must not overwrite the "was playing before shutdown" decision for the current boot.</p>
 */
public final class MediaAutoResumeController {
    public static final String ACTION_RESUME =
            "dezz.status.widget.action.RESUME_LAST_MEDIA_AFTER_BOOT";
    public static final String EXTRA_BOOT_TOKEN = "boot_token";
    public static final String EXTRA_ATTEMPT = "attempt";

    private static final String TAG = "MediaAutoResume";
    private static final String PREFS = "launcher_media_auto_resume_state";
    private static final String KEY_CAPTURE_TOKEN = "captureToken";
    private static final String KEY_CAPTURE_ELAPSED = "captureElapsed";
    private static final String KEY_PLAN_ANCHOR_ELAPSED = "planAnchorElapsed";
    private static final String KEY_CAPTURE_ACTION = "captureAction";
    private static final String KEY_CAPTURE_BOOT_COUNT = "captureBootCount";
    private static final String KEY_CAPTURE_HISTORY_PACKAGE = "captureHistoryPackage";
    private static final String KEY_CAPTURE_HISTORY_WAS_PLAYING = "captureHistoryWasPlaying";
    private static final String KEY_BOOT_TOKEN = "bootToken";
    private static final String KEY_TARGET_PACKAGE = "targetPackage";
    private static final String KEY_TARGET_ELAPSED = "targetElapsed";
    private static final String KEY_NEXT_ATTEMPT = "nextAttempt";
    private static final String KEY_OBSERVATION_KICK_ELAPSED = "observationKickElapsed";
    private static final String KEY_COMPLETED = "completed";
    private static final int REQUEST_CODE = 0x4D41;
    private static final int MAX_ATTEMPTS = 8;
    private static final long[] RETRY_DELAYS_MS = {
            150L, 250L, 400L, 700L, 1_000L, 1_500L, 2_000L
    };
    /**
     * AlarmManager remains the durable process-death fallback. This timer is the hot path: it is
     * not serialized behind WidgetService/startup work and therefore fires at the user-selected
     * boot boundary even while the launcher is still constructing its UI.
     */
    private static final ScheduledExecutorService EXACT_TIMER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(@NonNull Runnable runnable) {
                    Thread thread = new Thread(runnable, "natro-media-resume");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY + 1);
                    return thread;
                }
            });
    private static final Object EXECUTION_LOCK = new Object();
    private static final Object TIMER_LOCK = new Object();
    private static ScheduledFuture<?> inProcessTimer;

    private MediaAutoResumeController() {}

    /**
     * Freezes only the tiny device-protected playback edge at the lifecycle boundary. No launcher
     * Preferences migration, PackageManager query or media command is allowed here. LOCKED_BOOT
     * and BOOT_COMPLETED for one kernel boot share the first snapshot. Repeated ECARX QuickBoot
     * broadcasts inside one continuous startup burst also share it and cannot reset the timer.
     */
    public static long captureBootHistorySnapshot(@NonNull Context context,
                                                  @NonNull String action) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        long now = SystemClock.elapsedRealtime();
        int bootCount = currentBootCount(app);
        long previousElapsed = state.getLong(KEY_CAPTURE_ELAPSED, Long.MIN_VALUE);
        int previousBootCount = state.getInt(KEY_CAPTURE_BOOT_COUNT, Integer.MIN_VALUE);
        String previousAction = state.getString(KEY_CAPTURE_ACTION, "");
        long delta = previousElapsed == Long.MIN_VALUE ? Long.MAX_VALUE : now - previousElapsed;
        boolean differentKnownBootCount = bootCount >= 0 && previousBootCount >= 0
                && bootCount != previousBootCount;
        long previousToken = state.getLong(KEY_CAPTURE_TOKEN, 0L);
        if (previousToken != 0L && MediaAutoResumeLifecyclePolicy.shouldCoalesce(
                previousAction, action, differentKnownBootCount, delta)) {
            boolean moveAnchor = MediaAutoResumeLifecyclePolicy.shouldMovePlanAnchor(
                    previousAction, action);
            SharedPreferences.Editor duplicate = state.edit()
                    .putLong(KEY_CAPTURE_ELAPSED, now)
                    .putString(KEY_CAPTURE_ACTION, action);
            if (moveAnchor) duplicate.putLong(KEY_PLAN_ANCHOR_ELAPSED, now);
            duplicate.commit();
            PhoneConnectionJournal.append("media-auto-resume",
                    "повтор события объединён action=" + action
                            + ", token=" + previousToken
                            + ", anchorMoved=" + moveAnchor);
            return previousToken;
        }

        MediaPlaybackHistoryStore.Snapshot history = MediaPlaybackHistoryStore.read(app);
        long captureToken = previousToken == Long.MAX_VALUE ? 1L : previousToken + 1L;
        state.edit()
                .putLong(KEY_CAPTURE_TOKEN, captureToken)
                .putLong(KEY_CAPTURE_ELAPSED, now)
                .putLong(KEY_PLAN_ANCHOR_ELAPSED, now)
                .putString(KEY_CAPTURE_ACTION, action)
                .putInt(KEY_CAPTURE_BOOT_COUNT, bootCount)
                .putString(KEY_CAPTURE_HISTORY_PACKAGE, history.packageName)
                .putBoolean(KEY_CAPTURE_HISTORY_WAS_PLAYING, history.wasPlaying)
                .putLong(KEY_OBSERVATION_KICK_ELAPSED, Long.MIN_VALUE)
                .putBoolean(KEY_COMPLETED, false)
                .commit();
        // A queued retry from the previous standard/QuickBoot lifecycle must never cross the new
        // capture boundary. The token check is the second, callback-time barrier.
        cancelInProcessTimer();
        cancelAlarm(app);
        Log.i(TAG, "Frozen media history for lifecycle token=" + captureToken
                + " package=" + history.packageName + " playing=" + history.wasPlaying);
        PhoneConnectionJournal.append("media-auto-resume",
                "снимок загрузки token=" + captureToken
                        + ", playing=" + history.wasPlaying);
        return captureToken;
    }

    /** Enters the dedicated exact-timer lane before unrelated startup work can queue ahead of it. */
    public static void armAtReceiverBoundaryAsync(@NonNull Context context,
                                                  @NonNull String action) {
        if (!MediaAutoResumeLifecyclePolicy.isLifecycleAction(action)) return;
        Context exactApp = applicationContext(context);
        EXACT_TIMER.execute(() -> {
            try {
                captureBootHistorySnapshot(exactApp, action);
                if (MediaAutoResumeLifecyclePolicy.isUsableBoundary(action)) {
                    scheduleAfterBoot(exactApp);
                }
            } catch (RuntimeException failure) {
                Log.e(TAG, "Could not arm media resume at lifecycle boundary action="
                        + action, failure);
            }
        });
    }

    /** Called at the receiver boundary; the delayed media phase may idempotently re-arm it. */
    public static void scheduleAfterBoot(@NonNull Context context) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        long captureToken = state.getLong(KEY_CAPTURE_TOKEN, 0L);
        if (captureToken == 0L) {
            captureToken = captureBootHistorySnapshot(app, Intent.ACTION_BOOT_COMPLETED);
            state = state(app);
        }
        if (state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE) == captureToken) {
            if (!state.getBoolean(KEY_COMPLETED, false)) {
                long targetElapsed = state.getLong(KEY_TARGET_ELAPSED, 0L);
                int nextAttempt = state.getInt(KEY_NEXT_ATTEMPT, 0);
                if (targetElapsed > 0L) {
                    schedule(app, captureToken, nextAttempt,
                            Math.max(0L, targetElapsed - SystemClock.elapsedRealtime()));
                }
            }
            return;
        }
        Preferences preferences = new Preferences(app);
        if (!preferences.launcherMediaAutoResumeEnabled.get()) {
            cancel(app);
            return;
        }
        String frozenHistoryPackage = state.getString(KEY_CAPTURE_HISTORY_PACKAGE, "");
        boolean frozenHistoryWasPlaying = state.getBoolean(
                KEY_CAPTURE_HISTORY_WAS_PLAYING, false);
        boolean fixedPlayer = preferences.launcherMediaFixedPlayerEnabled.get();
        String fixedPackage = preferences.launcherMediaFixedPlayerPackage.get();
        if (!MediaPlaybackTargetPolicy.shouldAutoResume(fixedPlayer, fixedPackage,
                frozenHistoryPackage, frozenHistoryWasPlaying)) {
            Log.i(TAG, "Auto-resume skipped: there is no eligible target player");
            cancel(app);
            return;
        }
        String target = MediaPlaybackTargetPolicy.resolve(fixedPlayer, fixedPackage,
                frozenHistoryPackage);
        state.edit()
                .putLong(KEY_BOOT_TOKEN, captureToken)
                .putString(KEY_TARGET_PACKAGE, target)
                .putBoolean(KEY_COMPLETED, false)
                .commit();
        int delaySeconds = clamp(preferences.launcherMediaAutoResumeDelaySeconds.get(), 0, 60);
        long planAnchorElapsed = state.getLong(
                KEY_PLAN_ANCHOR_ELAPSED, SystemClock.elapsedRealtime());
        long targetElapsed = planAnchorElapsed + Math.max(delaySeconds * 1_000L,
                StartupWorkCoordinator.mediaAutoResumeMinimumDelayMillis());
        long delayMillis = Math.max(0L, targetElapsed - SystemClock.elapsedRealtime());
        schedule(app, captureToken, 0, delayMillis);
        Log.i(TAG, "Scheduled auto-resume for " + target
                + " after " + (delayMillis / 1_000L) + " s");
        PhoneConnectionJournal.append("media-auto-resume",
                "точный план token=" + captureToken + ", delayMs=" + delayMillis);
    }

    static void execute(@NonNull Context context, long bootToken, int attempt) {
        synchronized (EXECUTION_LOCK) {
            executeSerialized(context, bootToken, attempt);
        }
    }

    private static void executeSerialized(@NonNull Context context, long bootToken, int attempt) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (state.getBoolean(KEY_COMPLETED, false)
                || state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE) != bootToken
                || state.getLong(KEY_CAPTURE_TOKEN, Long.MIN_VALUE) != bootToken
                || state.getInt(KEY_NEXT_ATTEMPT, -1) != attempt) {
            return;
        }
        Preferences preferences = new Preferences(app);
        if (!preferences.launcherMediaAutoResumeEnabled.get()) {
            complete(app);
            return;
        }
        String target = state.getString(KEY_TARGET_PACKAGE, "").trim();
        if (target.isEmpty()) {
            complete(app);
            return;
        }

        long planned = state.getLong(KEY_TARGET_ELAPSED, SystemClock.elapsedRealtime());
        long lateness = Math.max(0L, SystemClock.elapsedRealtime() - planned);
        MediaResumeCommand.Result result = MediaResumeCommand.play(app, target);
        PhoneConnectionJournal.append("media-auto-resume",
                "попытка=" + (attempt + 1) + ", result=" + result
                        + ", latenessMs=" + lateness);
        if (result == MediaResumeCommand.Result.ALREADY_PLAYING) {
            Log.i(TAG, "Playback is already active; no duplicate command sent");
            complete(app);
            return;
        }

        int nextAttempt = attempt + 1;
        if (nextAttempt < MAX_ATTEMPTS) {
            long retryDelay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
            schedule(app, bootToken, nextAttempt, retryDelay);
            Log.i(TAG, "Media resume attempt " + (attempt + 1) + " for " + target
                    + " returned " + result + "; retry scheduled");
            return;
        }
        Log.i(TAG, "Media resume attempts finished for " + target + ": " + result);
        complete(app);
    }

    private static void schedule(@NonNull Context app, long bootToken, int attempt,
                                 long delayMillis) {
        long triggerElapsed = SystemClock.elapsedRealtime() + Math.max(0L, delayMillis);
        state(app).edit()
                .putLong(KEY_TARGET_ELAPSED, triggerElapsed)
                .putInt(KEY_NEXT_ATTEMPT, attempt)
                .commit();
        scheduleInProcess(app, bootToken, attempt, delayMillis);
        Intent intent = new Intent(app, MediaAutoResumeReceiver.class)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_BOOT_TOKEN, bootToken)
                .putExtra(EXTRA_ATTEMPT, attempt);
        PendingIntent pending = PendingIntent.getBroadcast(app, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) {
            Log.w(TAG, "AlarmManager unavailable");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            } else {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            }
        } catch (SecurityException exactAlarmDenied) {
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            } catch (RuntimeException fallbackFailure) {
                Log.w(TAG, "Could not schedule media auto-resume fallback", fallbackFailure);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not schedule media auto-resume", failure);
        }
    }

    private static void scheduleInProcess(@NonNull Context app, long bootToken, int attempt,
                                          long delayMillis) {
        synchronized (TIMER_LOCK) {
            if (inProcessTimer != null) inProcessTimer.cancel(false);
            Context exactApp = applicationContext(app);
            inProcessTimer = EXACT_TIMER.schedule(
                    () -> execute(exactApp, bootToken, attempt),
                    Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        }
    }

    /** Wakes a due plan as soon as the target player exposes its MediaSession after boot. */
    public static void onPlaybackObservation(@NonNull Context context,
                                             @NonNull String packageName,
                                             boolean playing) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (state.getBoolean(KEY_COMPLETED, true)) return;
        String target = state.getString(KEY_TARGET_PACKAGE, "").trim();
        if (!target.equals(packageName.trim())) return;
        if (playing) {
            PhoneConnectionJournal.append("media-auto-resume",
                    "целевая MediaSession уже воспроизводит; план завершён");
            complete(app);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long targetElapsed = state.getLong(KEY_TARGET_ELAPSED, Long.MAX_VALUE);
        long lastKick = state.getLong(KEY_OBSERVATION_KICK_ELAPSED, Long.MIN_VALUE);
        if (now < targetElapsed || (lastKick != Long.MIN_VALUE && now - lastKick < 1_000L)) {
            return;
        }
        long bootToken = state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE);
        if (bootToken == Long.MIN_VALUE
                || state.getLong(KEY_CAPTURE_TOKEN, Long.MIN_VALUE) != bootToken) return;
        state.edit().putLong(KEY_OBSERVATION_KICK_ELAPSED, now).apply();
        schedule(app, bootToken, state.getInt(KEY_NEXT_ATTEMPT, 0), 100L);
        PhoneConnectionJournal.append("media-auto-resume",
                "MediaSession готова; просроченная попытка ускорена");
    }

    private static void complete(@NonNull Context app) {
        state(app).edit().putBoolean(KEY_COMPLETED, true).apply();
        cancelInProcessTimer();
        cancelAlarm(app);
    }

    private static void cancel(@NonNull Context app) {
        state(app).edit().putBoolean(KEY_COMPLETED, true).apply();
        cancelInProcessTimer();
        cancelAlarm(app);
    }

    private static void cancelInProcessTimer() {
        synchronized (TIMER_LOCK) {
            if (inProcessTimer != null) inProcessTimer.cancel(false);
            inProcessTimer = null;
        }
    }

    private static void cancelAlarm(@NonNull Context app) {
        Intent intent = new Intent(app, MediaAutoResumeReceiver.class).setAction(ACTION_RESUME);
        PendingIntent pending = PendingIntent.getBroadcast(app, REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms != null) {
            try {
                alarms.cancel(pending);
            } catch (RuntimeException ignored) {}
        }
        pending.cancel();
    }

    private static int currentBootCount(@NonNull Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException ignored) {
            return -1;
        }
    }

    @NonNull
    private static SharedPreferences state(@NonNull Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
