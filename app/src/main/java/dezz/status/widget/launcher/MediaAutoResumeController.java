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
    private static final String KEY_FIRST_COMMAND_ELAPSED = "firstCommandElapsed";
    private static final String KEY_LAST_COMMAND_ELAPSED = "lastCommandElapsed";
    private static final String KEY_COMPLETED = "completed";
    private static final int REQUEST_CODE = 0x4D41;
    /** mSaver's field-proven bounded policy: one initial PLAY plus four 10-second retries. */
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 10_000L;
    /** Verify the exact receiver result quickly, then escalate without shifting attempt one. */
    private static final long YANDEX_RECEIVER_VERIFY_MS = 2_000L;
    private static final String YANDEX_MUSIC_PACKAGE = "ru.yandex.music";
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
            duplicate.apply();
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=lifecycle_coalesced, action=" + action
                            + ", token=" + previousToken
                            + ", previousAction=" + previousAction
                            + ", sincePreviousMs=" + delta
                            + ", anchorMoved=" + moveAnchor
                            + ", elapsed=" + now);
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
                .putLong(KEY_FIRST_COMMAND_ELAPSED, Long.MIN_VALUE)
                .putLong(KEY_LAST_COMMAND_ELAPSED, Long.MIN_VALUE)
                .putBoolean(KEY_COMPLETED, false)
                .apply();
        // A queued retry from the previous standard/QuickBoot lifecycle must never cross the new
        // capture boundary. The token check is the second, callback-time barrier.
        cancelInProcessTimer();
        cancelAlarm(app);
        Log.i(TAG, "Frozen media history for lifecycle token=" + captureToken
                + " package=" + history.packageName + " playing=" + history.wasPlaying);
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=lifecycle_captured, token=" + captureToken
                        + ", action=" + action
                        + ", bootCount=" + bootCount
                        + ", historyPackage=" + history.packageName
                        + ", playing=" + history.wasPlaying
                        + ", elapsed=" + now);
        return captureToken;
    }

    /**
     * Captures and arms the absolute deadline directly at the BroadcastReceiver boundary.
     *
     * <p>This deliberately does not enqueue behind the command/timer lane. Real KX11 traces
     * showed that PackageManager work from an older media attempt could occupy that lane for
     * 5-6 seconds, so a configured three-second delay did not even begin until much later.</p>
     */
    public static void armAtReceiverBoundary(@NonNull Context context,
                                             @NonNull String action) {
        if (!MediaAutoResumeLifecyclePolicy.isLifecycleAction(action)) return;
        Context exactApp = applicationContext(context);
        long receiverAt = SystemClock.elapsedRealtime();
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=receiver_boundary_enqueue, action=" + action
                        + ", elapsed=" + receiverAt);
        long enteredAt = SystemClock.elapsedRealtime();
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=receiver_boundary_dequeue, action=" + action
                        + ", queueWaitMs=" + Math.max(0L, enteredAt - receiverAt)
                        + ", route=inline, elapsed=" + enteredAt);
        try {
            captureBootHistorySnapshot(exactApp, action);
            if (MediaAutoResumeLifecyclePolicy.isUsableBoundary(action)) {
                scheduleAfterBoot(exactApp);
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not arm media resume at lifecycle boundary action="
                    + action, failure);
        }
    }

    /** Called at the receiver boundary; the delayed media phase may idempotently re-arm it. */
    public static void scheduleAfterBoot(@NonNull Context context) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        long enteredAt = SystemClock.elapsedRealtime();
        long captureToken = state.getLong(KEY_CAPTURE_TOKEN, 0L);
        if (captureToken == 0L) {
            captureToken = captureBootHistorySnapshot(app, Intent.ACTION_BOOT_COMPLETED);
            state = state(app);
        }
        String captureAction = state.getString(KEY_CAPTURE_ACTION, "");
        if (!MediaAutoResumeLifecyclePolicy.isUsableBoundary(captureAction)) {
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=plan_deferred, reason=player_boot_gate, token="
                            + captureToken + ", action=" + captureAction
                            + ", sinceCaptureMs=" + elapsedSince(state,
                            KEY_CAPTURE_ELAPSED, enteredAt));
            return;
        }
        if (state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE) == captureToken) {
            if (!state.getBoolean(KEY_COMPLETED, false)) {
                long targetElapsed = state.getLong(KEY_TARGET_ELAPSED, 0L);
                int nextAttempt = state.getInt(KEY_NEXT_ATTEMPT, 0);
                if (targetElapsed > 0L) {
                    schedule(app, captureToken, nextAttempt,
                            Math.max(0L, targetElapsed - SystemClock.elapsedRealtime()),
                            "existing_plan_rearm");
                }
            }
            return;
        }
        Preferences preferences = new Preferences(app);
        if (!preferences.launcherMediaAutoResumeEnabled.get()) {
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=plan_skipped, reason=disabled, token=" + captureToken
                            + ", sinceCaptureMs=" + elapsedSince(state,
                            KEY_CAPTURE_ELAPSED, enteredAt));
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
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=plan_skipped, reason=no_eligible_target, token="
                            + captureToken + ", fixed=" + fixedPlayer
                            + ", historyPlaying=" + frozenHistoryWasPlaying
                            + ", historyPackage=" + frozenHistoryPackage
                            + ", sinceCaptureMs=" + elapsedSince(state,
                            KEY_CAPTURE_ELAPSED, enteredAt));
            cancel(app);
            return;
        }
        String target = MediaPlaybackTargetPolicy.resolve(fixedPlayer, fixedPackage,
                frozenHistoryPackage);
        state.edit()
                .putLong(KEY_BOOT_TOKEN, captureToken)
                .putString(KEY_TARGET_PACKAGE, target)
                .putBoolean(KEY_COMPLETED, false)
                .apply();
        int delaySeconds = clamp(preferences.launcherMediaAutoResumeDelaySeconds.get(), 0, 60);
        long planAnchorElapsed = state.getLong(
                KEY_PLAN_ANCHOR_ELAPSED, SystemClock.elapsedRealtime());
        long targetElapsed = planAnchorElapsed + Math.max(delaySeconds * 1_000L,
                StartupWorkCoordinator.mediaAutoResumeMinimumDelayMillis());
        long delayMillis = Math.max(0L, targetElapsed - SystemClock.elapsedRealtime());
        schedule(app, captureToken, 0, delayMillis, "initial_plan");
        Log.i(TAG, "Scheduled auto-resume for " + target
                + " after " + (delayMillis / 1_000L) + " s");
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=plan_created, token=" + captureToken
                        + ", target=" + target
                        + ", configuredDelayMs=" + (delaySeconds * 1_000L)
                        + ", minimumDelayMs="
                        + StartupWorkCoordinator.mediaAutoResumeMinimumDelayMillis()
                        + ", anchorAgeMs=" + Math.max(0L, enteredAt - planAnchorElapsed)
                        + ", remainingMs=" + delayMillis
                        + ", targetElapsed=" + targetElapsed);
    }

    static void execute(@NonNull Context context, long bootToken, int attempt) {
        synchronized (EXECUTION_LOCK) {
            executeSerialized(context, bootToken, attempt);
        }
    }

    private static void executeSerialized(@NonNull Context context, long bootToken, int attempt) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        long executeAt = SystemClock.elapsedRealtime();
        boolean completed = state.getBoolean(KEY_COMPLETED, false);
        long plannedBootToken = state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE);
        long captureToken = state.getLong(KEY_CAPTURE_TOKEN, Long.MIN_VALUE);
        int plannedAttempt = state.getInt(KEY_NEXT_ATTEMPT, -1);
        if (completed || plannedBootToken != bootToken || captureToken != bootToken
                || plannedAttempt != attempt) {
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=attempt_rejected, token=" + bootToken
                            + ", attempt=" + (attempt + 1)
                            + ", completed=" + completed
                            + ", plannedToken=" + plannedBootToken
                            + ", captureToken=" + captureToken
                            + ", plannedAttempt=" + (plannedAttempt + 1)
                            + ", elapsed=" + executeAt);
            return;
        }
        Preferences preferences = new Preferences(app);
        if (!preferences.launcherMediaAutoResumeEnabled.get()) {
            complete(app, "disabled_before_dispatch");
            return;
        }
        String target = state.getString(KEY_TARGET_PACKAGE, "").trim();
        if (target.isEmpty()) {
            complete(app, "empty_target_before_dispatch");
            return;
        }

        long planned = state.getLong(KEY_TARGET_ELAPSED, executeAt);
        long lateness = Math.max(0L, executeAt - planned);
        long dispatchStartedAt = SystemClock.elapsedRealtime();
        boolean coldStartEscalation = attempt > 0
                && YANDEX_MUSIC_PACKAGE.equals(target);
        MediaResumeCommand.DispatchTrace trace = MediaResumeCommand.playWithTrace(
                app, target, coldStartEscalation);
        long dispatchFinishedAt = SystemClock.elapsedRealtime();
        long firstCommandAt = state.getLong(KEY_FIRST_COMMAND_ELAPSED, Long.MIN_VALUE);
        SharedPreferences.Editor commandTiming = state.edit()
                .putLong(KEY_LAST_COMMAND_ELAPSED, dispatchFinishedAt);
        if (firstCommandAt == Long.MIN_VALUE) {
            commandTiming.putLong(KEY_FIRST_COMMAND_ELAPSED, dispatchStartedAt);
        }
        commandTiming.apply();
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=command_dispatched, token=" + bootToken
                        + ", attempt=" + (attempt + 1)
                        + ", target=" + target
                        + ", result=" + trace.result
                        + ", plannedLatenessMs=" + lateness
                        + ", sinceCaptureMs=" + elapsedSince(state,
                        KEY_CAPTURE_ELAPSED, dispatchStartedAt)
                        + ", sincePlanAnchorMs=" + elapsedSince(state,
                        KEY_PLAN_ANCHOR_ELAPSED, dispatchStartedAt)
                        + ", dispatchDurationMs="
                        + Math.max(0L, dispatchFinishedAt - dispatchStartedAt)
                        + ", " + trace.detail);
        if (trace.result == MediaResumeCommand.Result.ALREADY_PLAYING) {
            Log.i(TAG, "Playback is already active; no duplicate command sent");
            complete(app, "already_playing_at_dispatch");
            return;
        }

        int nextAttempt = attempt + 1;
        if (nextAttempt < MAX_ATTEMPTS) {
            long retryDelay = attempt == 0 && YANDEX_MUSIC_PACKAGE.equals(target)
                    ? YANDEX_RECEIVER_VERIFY_MS : RETRY_DELAY_MS;
            schedule(app, bootToken, nextAttempt, retryDelay,
                    attempt == 0 && YANDEX_MUSIC_PACKAGE.equals(target)
                            ? "receiver_result_verification" : "command_retry");
            Log.i(TAG, "Media resume attempt " + (attempt + 1) + " for " + target
                    + " returned " + trace.result + "; retry scheduled");
            return;
        }
        Log.i(TAG, "Media resume attempts finished for " + target + ": " + trace.result);
        complete(app, "attempt_limit_" + trace.result);
    }

    private static void schedule(@NonNull Context app, long bootToken, int attempt,
                                 long delayMillis) {
        schedule(app, bootToken, attempt, delayMillis, "unspecified");
    }

    private static void schedule(@NonNull Context app, long bootToken, int attempt,
                                 long delayMillis, @NonNull String source) {
        long scheduledAt = SystemClock.elapsedRealtime();
        long boundedDelay = Math.max(0L, delayMillis);
        long triggerElapsed = scheduledAt + boundedDelay;
        state(app).edit()
                .putLong(KEY_TARGET_ELAPSED, triggerElapsed)
                .putInt(KEY_NEXT_ATTEMPT, attempt)
                .apply();
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
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=timer_scheduled, source=" + source
                            + ", token=" + bootToken + ", attempt=" + (attempt + 1)
                            + ", delayMs=" + boundedDelay
                            + ", triggerElapsed=" + triggerElapsed
                            + ", inProcess=true, alarm=unavailable");
            return;
        }
        String alarmRoute = "exact";
        String alarmError = "none";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !alarms.canScheduleExactAlarms()) {
                alarmRoute = "inexact_allow_idle_no_permission";
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmRoute = "exact_allow_idle";
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            } else {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            }
        } catch (SecurityException exactAlarmDenied) {
            alarmRoute = "inexact_allow_idle_security_fallback";
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed, pending);
            } catch (RuntimeException fallbackFailure) {
                alarmRoute = "failed";
                alarmError = fallbackFailure.getClass().getSimpleName();
                Log.w(TAG, "Could not schedule media auto-resume fallback", fallbackFailure);
            }
        } catch (RuntimeException failure) {
            alarmRoute = "failed";
            alarmError = failure.getClass().getSimpleName();
            Log.w(TAG, "Could not schedule media auto-resume", failure);
        }
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=timer_scheduled, source=" + source
                        + ", token=" + bootToken + ", attempt=" + (attempt + 1)
                        + ", delayMs=" + boundedDelay
                        + ", scheduledElapsed=" + scheduledAt
                        + ", triggerElapsed=" + triggerElapsed
                        + ", inProcess=true, alarm=" + alarmRoute
                        + ", alarmError=" + alarmError);
    }

    private static void scheduleInProcess(@NonNull Context app, long bootToken, int attempt,
                                          long delayMillis) {
        synchronized (TIMER_LOCK) {
            boolean replaced = inProcessTimer != null;
            if (inProcessTimer != null) inProcessTimer.cancel(false);
            Context exactApp = applicationContext(app);
            long scheduledAt = SystemClock.elapsedRealtime();
            long triggerElapsed = scheduledAt + Math.max(0L, delayMillis);
            inProcessTimer = EXACT_TIMER.schedule(
                    () -> {
                        long deliveredAt = SystemClock.elapsedRealtime();
                        PhoneConnectionJournal.append("media-auto-resume",
                                "trace event=in_process_timer_delivery, token=" + bootToken
                                        + ", attempt=" + (attempt + 1)
                                        + ", latenessMs="
                                        + Math.max(0L, deliveredAt - triggerElapsed)
                                        + ", elapsed=" + deliveredAt);
                        execute(exactApp, bootToken, attempt);
                    },
                    Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=in_process_timer_armed, token=" + bootToken
                            + ", attempt=" + (attempt + 1)
                            + ", replaced=" + replaced
                            + ", delayMs=" + Math.max(0L, delayMillis)
                            + ", triggerElapsed=" + triggerElapsed);
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
            long observedAt = SystemClock.elapsedRealtime();
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=playing_observed, target=" + target
                            + ", sinceCaptureMs=" + elapsedSince(state,
                            KEY_CAPTURE_ELAPSED, observedAt)
                            + ", sincePlanAnchorMs=" + elapsedSince(state,
                            KEY_PLAN_ANCHOR_ELAPSED, observedAt)
                            + ", sinceFirstCommandMs=" + elapsedSince(state,
                            KEY_FIRST_COMMAND_ELAPSED, observedAt)
                            + ", sinceLastCommandMs=" + elapsedSince(state,
                            KEY_LAST_COMMAND_ELAPSED, observedAt)
                            + ", elapsed=" + observedAt);
            complete(app, "playing_observed");
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
        schedule(app, bootToken, state.getInt(KEY_NEXT_ATTEMPT, 0), 100L,
                "media_session_ready_kick");
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=session_ready_kick, target=" + target
                        + ", overdueMs=" + Math.max(0L, now - targetElapsed)
                        + ", elapsed=" + now);
    }

    /** Re-dispatches a due PLAY as soon as the head unit reports a usable audio/ACL route. */
    public static void onAudioRouteReady(@NonNull Context context, @NonNull String source) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (state.getBoolean(KEY_COMPLETED, true)) return;
        long now = SystemClock.elapsedRealtime();
        long targetElapsed = state.getLong(KEY_TARGET_ELAPSED, Long.MAX_VALUE);
        if (now < targetElapsed) return;
        long bootToken = state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE);
        if (bootToken == Long.MIN_VALUE
                || state.getLong(KEY_CAPTURE_TOKEN, Long.MIN_VALUE) != bootToken) return;
        int attempt = state.getInt(KEY_NEXT_ATTEMPT, 0);
        if (attempt >= MAX_ATTEMPTS) return;
        schedule(app, bootToken, attempt, 50L, "audio_route_ready");
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=audio_route_kick, source=" + source
                        + ", attempt=" + (attempt + 1)
                        + ", overdueMs=" + Math.max(0L, now - targetElapsed)
                        + ", elapsed=" + now);
    }

    static void recordAlarmDelivery(long bootToken, int attempt) {
        long deliveredAt = SystemClock.elapsedRealtime();
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=alarm_delivery, token=" + bootToken
                        + ", attempt=" + (attempt + 1)
                        + ", elapsed=" + deliveredAt);
    }

    private static void complete(@NonNull Context app, @NonNull String reason) {
        SharedPreferences snapshot = state(app);
        long completedAt = SystemClock.elapsedRealtime();
        PhoneConnectionJournal.append("media-auto-resume",
                "trace event=plan_completed, reason=" + reason
                        + ", token=" + snapshot.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE)
                        + ", attempts=" + snapshot.getInt(KEY_NEXT_ATTEMPT, -1)
                        + ", totalSinceCaptureMs=" + elapsedSince(snapshot,
                        KEY_CAPTURE_ELAPSED, completedAt)
                        + ", elapsed=" + completedAt);
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

    private static long elapsedSince(@NonNull SharedPreferences preferences,
                                     @NonNull String key, long now) {
        long startedAt = preferences.getLong(key, Long.MIN_VALUE);
        if (startedAt == Long.MIN_VALUE || now < startedAt) return -1L;
        return now - startedAt;
    }
}
