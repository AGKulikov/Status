/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Serializes automatic startup work so ECARX/SystemServer can finish its own boot first.
 *
 * <p>All deadlines use elapsed realtime. Each phase carries a persisted generation and its own
 * not-before boundary, so a queued alarm from an older BOOT/QuickBoot event cannot pull newer work
 * forward or mark it complete. LOCKED_BOOT only parks work; USER_UNLOCKED resumes it without a
 * wakeup polling loop.</p>
 */
public final class StartupWorkCoordinator {
    static final String ACTION_RUN_PHASE =
            "dezz.status.widget.action.RUN_STARTUP_PHASE";
    static final String EXTRA_PHASE = "startup_phase";
    static final String EXTRA_GENERATION = "startup_generation";
    static final int PHASE_INTEGRATION_HOST = 1;
    static final int PHASE_CLIMATE = 2;
    static final int PHASE_MEDIA_PLAN = 3;

    private static final String TAG = "StartupCoordinator";
    private static final String PREFS = "startup_work_state";
    private static final String KEY_QUIET_UNTIL_ELAPSED = "quiet_until_elapsed";
    private static final String KEY_LAUNCHER_PANELS_NOT_BEFORE_ELAPSED =
            "launcher_panels_not_before_elapsed";
    private static final String KEY_LAUNCHER_RUNTIME_NOT_BEFORE_ELAPSED =
            "launcher_runtime_not_before_elapsed";
    private static final String KEY_AUTOMATIC_RECONCILE_NOT_BEFORE_ELAPSED =
            "automatic_reconcile_not_before_elapsed";
    private static final String KEY_UNLOCK_REFRESH_PENDING = "unlock_refresh_pending";
    private static final String KEY_UNLOCK_REFRESH_GENERATION =
            "unlock_refresh_generation";
    private static final String KEY_SURFACE_RECONCILE_PENDING = "surface_reconcile_pending";
    private static final String KEY_SURFACE_RECONCILE_GENERATION =
            "surface_reconcile_generation";
    private static final String KEY_GENERATION_COUNTER = "generation_counter";
    private static final String KEY_LAST_BOOT_COUNT = "last_boot_count";
    private static final String KEY_STARTUP_INCOMPLETE = "startup_incomplete";
    private static final String KEY_STARTUP_BARRIER_EXPIRES_ELAPSED =
            "startup_barrier_expires_elapsed";
    private static final String KEY_HOST_PHASE_PENDING = "host_phase_pending";
    private static final String KEY_HOST_PHASE_GENERATION = "host_phase_generation";
    private static final String KEY_HOST_NOT_BEFORE_ELAPSED = "host_not_before_elapsed";
    private static final String KEY_CLIMATE_PHASE_PENDING = "climate_phase_pending";
    private static final String KEY_CLIMATE_PHASE_GENERATION = "climate_phase_generation";
    private static final String KEY_CLIMATE_NOT_BEFORE_ELAPSED = "climate_not_before_elapsed";
    private static final String KEY_MEDIA_PHASE_PENDING = "media_phase_pending";
    private static final String KEY_MEDIA_PHASE_GENERATION = "media_phase_generation";
    private static final String KEY_MEDIA_NOT_BEFORE_ELAPSED = "media_not_before_elapsed";
    private static final int REQUEST_HOST = 0x5351;
    private static final int REQUEST_CLIMATE = 0x5352;
    private static final int REQUEST_MEDIA = 0x5353;

    private StartupWorkCoordinator() {}

    /**
     * Establishes a quiet boundary even when SystemServer launches HOME before BOOT_COMPLETED.
     * BOOT_COUNT handles a late cold HOME start; the uptime floor covers providers/activities that
     * race the boot broadcasts. This only touches one tiny device-protected record.
     */
    static void primeEarlyBootQuiet(@NonNull Context context) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        long now = SystemClock.elapsedRealtime();
        int bootCount = currentBootCount(app);
        int recordedBootCount = state.getInt(KEY_LAST_BOOT_COUNT, Integer.MIN_VALUE);
        boolean newBoot = StartupLoadPolicy.isNewBootGeneration(bootCount, recordedBootCount);
        long earlyBootQuiet = StartupLoadPolicy.earlyBootQuietMillis(now);
        boolean existingStartupBarrier = startupBarrierActive(state, now);
        long quiet = Math.max(earlyBootQuiet,
                newBoot ? StartupLoadPolicy.MAIN_PROCESS_SETTLE_MS : 0L);
        boolean fullBootLane = newBoot || earlyBootQuiet > 0L || existingStartupBarrier;
        if (quiet <= 0L && !existingStartupBarrier) {
            quiet = StartupLoadPolicy.MAIN_PROCESS_SETTLE_MS;
        }
        if (quiet <= 0L) return;
        long requestedUntil = now + quiet;
        // elapsedRealtime restarts at a kernel reboot. Never interpret the previous kernel's
        // small absolute deadlines as future work in the new BOOT_COUNT generation.
        long currentUntil = newBoot ? 0L : validFutureDeadline(
                state, KEY_QUIET_UNTIL_ELAPSED, now, StartupLoadPolicy.MAX_VALID_QUIET_MS);
        long quietUntil = Math.max(currentUntil, requestedUntil);
        SharedPreferences.Editor edit = state.edit();
        if (newBoot) {
            clearElapsedGenerationState(edit);
            edit.putInt(KEY_LAST_BOOT_COUNT, bootCount)
                    .putBoolean(KEY_STARTUP_INCOMPLETE, true)
                    .putLong(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED,
                            now + StartupLoadPolicy.MAX_VALID_QUIET_MS);
        }
        if (fullBootLane) {
            writeSharedLaneDeadlines(edit, quietUntil);
        } else {
            writeProcessSettleDeadlines(edit, quietUntil);
        }
        edit.commit();
        Log.i(TAG, "Early HOME/process start joined boot quiet lane for " + quiet + " ms"
                + (newBoot ? " (new BOOT_COUNT)" : " (early uptime)"));
    }

    public static void scheduleForLifecycle(@NonNull Context context,
                                            @Nullable String action) {
        Context app = applicationContext(context);
        StartupLoadPolicy.Trigger trigger = classify(action);
        if (trigger == StartupLoadPolicy.Trigger.OTHER) return;

        long now = SystemClock.elapsedRealtime();
        SharedPreferences state = state(app);
        int bootCount = currentBootCount(app);
        int recordedBootCount = state.getInt(KEY_LAST_BOOT_COUNT, Integer.MIN_VALUE);
        boolean newBoot = StartupLoadPolicy.isNewBootGeneration(bootCount, recordedBootCount);
        long currentUntil = validFutureDeadline(state, KEY_QUIET_UNTIL_ELAPSED, now,
                StartupLoadPolicy.MAX_VALID_QUIET_MS);
        if (newBoot) currentUntil = 0L;
        boolean coalescingActiveBootLane = !newBoot
                && startupBarrierActive(state, now)
                && StartupLoadPolicy.isBootLifecycle(trigger);
        long quietUntil = coalescingActiveBootLane ? Math.max(now, currentUntil)
                : Math.max(currentUntil,
                now + StartupLoadPolicy.quietWindowMillis(trigger, now));
        long generation = nextGeneration(state);
        boolean retainedCredentialRefresh = state.getBoolean(
                KEY_UNLOCK_REFRESH_PENDING, false);
        boolean retainedClimate = state.getBoolean(KEY_CLIMATE_PHASE_PENDING, false);
        boolean retainedMedia = state.getBoolean(KEY_MEDIA_PHASE_PENDING, false);
        boolean retainedHost = state.getBoolean(KEY_HOST_PHASE_PENDING, false);
        boolean retainedSurfaceReconcile = state.getBoolean(
                KEY_SURFACE_RECONCILE_PENDING, false);
        boolean scheduleHost = StartupLoadPolicy.schedulesIntegrationHost(trigger);
        boolean scheduleClimate = StartupLoadPolicy.schedulesClimate(trigger)
                || StartupLoadPolicy.opensCredentialGate(trigger) && retainedClimate;
        boolean scheduleMedia = StartupLoadPolicy.schedulesMediaPlan(trigger)
                || StartupLoadPolicy.opensCredentialGate(trigger) && retainedMedia;

        SharedPreferences.Editor edit = state.edit();
        if (newBoot) clearElapsedGenerationState(edit);
        edit.putLong(KEY_GENERATION_COUNTER, generation);
        if (bootCount >= 0) edit.putInt(KEY_LAST_BOOT_COUNT, bootCount);
        if (trigger == StartupLoadPolicy.Trigger.LOCKED_BOOT
                || trigger == StartupLoadPolicy.Trigger.BOOT_COMPLETED
                || trigger == StartupLoadPolicy.Trigger.QUICK_BOOT) {
            edit.putBoolean(KEY_STARTUP_INCOMPLETE, true)
                    .putLong(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED,
                            now + StartupLoadPolicy.MAX_VALID_QUIET_MS);
        }
        writeSharedLaneDeadlines(edit, quietUntil);
        if (scheduleHost && (StartupLoadPolicy.opensCredentialGate(trigger)
                || retainedCredentialRefresh)) {
            edit.putBoolean(KEY_UNLOCK_REFRESH_PENDING, true)
                    .putLong(KEY_UNLOCK_REFRESH_GENERATION, generation);
        }
        boolean requestsSurfaceReconcile = trigger == StartupLoadPolicy.Trigger.BOOT_COMPLETED
                || trigger == StartupLoadPolicy.Trigger.QUICK_BOOT
                || trigger == StartupLoadPolicy.Trigger.PACKAGE_REPLACED;
        if (scheduleHost && (requestsSurfaceReconcile || retainedSurfaceReconcile)) {
            edit.putBoolean(KEY_SURFACE_RECONCILE_PENDING, true)
                    .putLong(KEY_SURFACE_RECONCILE_GENERATION, generation);
        }
        long hostNotBefore = coalescingActiveBootLane && retainedHost
                ? retainedPhaseNotBefore(state, KEY_HOST_NOT_BEFORE_ELAPSED, now)
                : quietUntil;
        long climateNotBefore = coalescingActiveBootLane && retainedClimate
                ? retainedPhaseNotBefore(state, KEY_CLIMATE_NOT_BEFORE_ELAPSED, now)
                : quietUntil + StartupLoadPolicy.CLIMATE_AFTER_HOST_MS;
        boolean retainedMediaOnly = retainedMedia
                && (!StartupLoadPolicy.schedulesMediaPlan(trigger)
                || coalescingActiveBootLane);
        long retainedMediaNotBefore = retainedMediaOnly
                ? retainedPhaseNotBefore(state, KEY_MEDIA_NOT_BEFORE_ELAPSED, now) : 0L;
        long mediaNotBefore = retainedMediaNotBefore > 0L
                ? retainedMediaNotBefore : Math.max(quietUntil,
                now + StartupLoadPolicy.MEDIA_AUTO_RESUME_MIN_MS);
        if (scheduleHost) writePhase(edit, PHASE_INTEGRATION_HOST, generation, hostNotBefore);
        if (scheduleClimate) writePhase(edit, PHASE_CLIMATE, generation, climateNotBefore);
        if (scheduleMedia) writePhase(edit, PHASE_MEDIA_PLAN, generation, mediaNotBefore);
        edit.commit();

        if (scheduleHost) {
            schedulePhaseAtElapsed(app, PHASE_INTEGRATION_HOST, generation, hostNotBefore);
        }
        if (scheduleClimate) {
            schedulePhaseAtElapsed(app, PHASE_CLIMATE, generation, climateNotBefore);
        }
        if (scheduleMedia) {
            schedulePhaseAtElapsed(app, PHASE_MEDIA_PLAN, generation, mediaNotBefore);
        }
        Log.i(TAG, "Coalesced " + trigger + " generation=" + generation
                + "; automatic runtime quiet for " + Math.max(0L, quietUntil - now) + " ms");
    }

    public static long hudFallbackDelayMillis() {
        return StartupLoadPolicy.HUD_FALLBACK_DELAY_MS;
    }

    public static long mediaAutoResumeMinimumDelayMillis() {
        return StartupLoadPolicy.MEDIA_AUTO_RESUME_MIN_MS;
    }

    static long remainingQuietMillis(@NonNull Context context) {
        return remainingDeadline(context, KEY_QUIET_UNTIL_ELAPSED,
                StartupLoadPolicy.MAX_VALID_QUIET_MS, !AppProcessPolicy.isHudProcess());
    }

    static long launcherPanelDelayMillis(@NonNull Context context, long normalDelayMillis) {
        return Math.max(Math.max(normalDelayMillis, remainingDeadline(context,
                        KEY_LAUNCHER_PANELS_NOT_BEFORE_ELAPSED,
                        StartupLoadPolicy.MAX_VALID_STARTUP_LANE_MS, true)),
                remainingHostHandoffMillis(context));
    }

    static long launcherRuntimeDelayMillis(@NonNull Context context) {
        return Math.max(remainingDeadline(context, KEY_LAUNCHER_RUNTIME_NOT_BEFORE_ELAPSED,
                        StartupLoadPolicy.MAX_VALID_STARTUP_LANE_MS, true),
                remainingHostHandoffMillis(context));
    }

    /**
     * Exact in-process handoff for a visible HOME. AlarmManager remains the durable owner when HOME
     * is hidden or the process dies, while this deadline prevents an inexact alarm from leaving a
     * foreground launcher blank for several extra seconds.
     *
     * @return milliseconds until the current host phase is due, or {@code -1} when none is pending.
     */
    static long pendingIntegrationHostDelayMillis(@NonNull Context context) {
        SharedPreferences state = state(applicationContext(context));
        if (!state.getBoolean(KEY_HOST_PHASE_PENDING, false)) return -1L;
        long now = SystemClock.elapsedRealtime();
        return Math.max(
                StartupLoadPolicy.remainingQuietMillis(now,
                        state.getLong(KEY_QUIET_UNTIL_ELAPSED, 0L)),
                StartupLoadPolicy.remainingStartupLaneMillis(now,
                        state.getLong(KEY_HOST_NOT_BEFORE_ELAPSED, 0L)));
    }

    /** Dispatches only an exact, unlocked, already-due host generation. */
    static boolean dispatchPendingIntegrationHostIfDue(@NonNull Context context) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (!state.getBoolean(KEY_HOST_PHASE_PENDING, false) || !isUserUnlocked(app)) {
            return false;
        }
        long generation = state.getLong(KEY_HOST_PHASE_GENERATION, Long.MIN_VALUE);
        if (generation == Long.MIN_VALUE || pendingIntegrationHostDelayMillis(app) != 0L) {
            return false;
        }
        Intent phase = new Intent(app, BootReceiver.class)
                .setAction(ACTION_RUN_PHASE)
                .putExtra(EXTRA_PHASE, PHASE_INTEGRATION_HOST)
                .putExtra(EXTRA_GENERATION, generation);
        try {
            app.sendBroadcast(phase);
            Log.i(TAG, "Visible HOME dispatched due host generation=" + generation);
            return true;
        } catch (RuntimeException failure) {
            // The durable AlarmManager copy is still pending and remains the fallback owner.
            Log.w(TAG, "Visible HOME could not dispatch the due host phase", failure);
            return false;
        }
    }

    /** Automatic Settings/runtime reconciliation must not jump ahead of the surface lanes. */
    static long automaticReconcileDelayMillis(@NonNull Context context) {
        return Math.max(remainingQuietMillis(context), remainingDeadline(context,
                KEY_AUTOMATIC_RECONCILE_NOT_BEFORE_ELAPSED,
                StartupLoadPolicy.MAX_VALID_STARTUP_LANE_MS, true));
    }

    public static void ensureIntegrationHostScheduled(@NonNull Context context) {
        Context app = applicationContext(context);
        long now = SystemClock.elapsedRealtime();
        long notBefore = now + remainingQuietMillis(app);
        ensurePhaseScheduled(app, PHASE_INTEGRATION_HOST, notBefore);
    }

    /** Durable no-draw HOME fallback; never starts a foreground service in the outgoing frame. */
    static void ensureIntegrationHostScheduledAfter(@NonNull Context context, long delayMillis) {
        Context app = applicationContext(context);
        long now = SystemClock.elapsedRealtime();
        long requestedDelay = Math.max(remainingQuietMillis(app), Math.max(1L, delayMillis));
        ensurePhaseScheduled(app, PHASE_INTEGRATION_HOST, now + requestedDelay);
    }

    public static void ensureClimateScheduled(@NonNull Context context) {
        Context app = applicationContext(context);
        long now = SystemClock.elapsedRealtime();
        long laneDelay = automaticReconcileDelayMillis(app);
        ensurePhaseScheduled(app, PHASE_CLIMATE, now + Math.max(1L, laneDelay));
    }

    static boolean isPhaseIntent(@Nullable Intent intent) {
        return intent != null && ACTION_RUN_PHASE.equals(intent.getAction());
    }

    /** Returns true for a stale/parked/deferred phase; only an exact current generation may run. */
    static boolean deferPhaseIfNeeded(@NonNull Context context, int phase, long generation) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (!state.getBoolean(pendingKey(phase), false)
                || state.getLong(generationKey(phase), Long.MIN_VALUE) != generation) {
            Log.i(TAG, "Ignoring stale startup phase=" + phase + " generation=" + generation);
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        long notBefore = state.getLong(notBeforeKey(phase), 0L);
        long delay = Math.max(
                StartupLoadPolicy.remainingStartupLaneMillis(now, notBefore),
                StartupLoadPolicy.remainingQuietMillis(now,
                        state.getLong(KEY_QUIET_UNTIL_ELAPSED, 0L)));
        if (delay > 0L) {
            schedulePhase(app, phase, generation, delay);
            return true;
        }
        if (!isUserUnlocked(app)) {
            Log.i(TAG, "Startup phase " + phase + " parked until USER_UNLOCKED");
            return true;
        }
        return false;
    }

    static boolean hasCredentialRefreshPending(@NonNull Context context, long generation) {
        SharedPreferences state = state(applicationContext(context));
        return state.getBoolean(KEY_UNLOCK_REFRESH_PENDING, false)
                && state.getLong(KEY_UNLOCK_REFRESH_GENERATION, Long.MIN_VALUE) == generation;
    }

    static boolean hasSurfaceReconcilePending(@NonNull Context context, long generation) {
        SharedPreferences state = state(applicationContext(context));
        return state.getBoolean(KEY_SURFACE_RECONCILE_PENDING, false)
                && state.getLong(KEY_SURFACE_RECONCILE_GENERATION, Long.MIN_VALUE)
                == generation;
    }

    /** Clears only requests that the exact host generation actually accepted. */
    static void acknowledgeHostRequests(@NonNull Context context, long generation,
                                        boolean credentialRefresh,
                                        boolean surfaceReconcile) {
        SharedPreferences state = state(applicationContext(context));
        SharedPreferences.Editor edit = state.edit();
        boolean changed = false;
        if (credentialRefresh
                && state.getBoolean(KEY_UNLOCK_REFRESH_PENDING, false)
                && state.getLong(KEY_UNLOCK_REFRESH_GENERATION, Long.MIN_VALUE) == generation) {
            edit.remove(KEY_UNLOCK_REFRESH_PENDING)
                    .remove(KEY_UNLOCK_REFRESH_GENERATION);
            changed = true;
        }
        if (surfaceReconcile
                && state.getBoolean(KEY_SURFACE_RECONCILE_PENDING, false)
                && state.getLong(KEY_SURFACE_RECONCILE_GENERATION, Long.MIN_VALUE) == generation) {
            edit.remove(KEY_SURFACE_RECONCILE_PENDING)
                    .remove(KEY_SURFACE_RECONCILE_GENERATION);
            changed = true;
        }
        if (changed) edit.commit();
    }

    /** Blocks full Application migrations/vendor setup until the exact delayed host phase. */
    static boolean isStartupInitializationBlocked(@NonNull Context context) {
        SharedPreferences state = state(applicationContext(context));
        long now = SystemClock.elapsedRealtime();
        boolean active = startupBarrierActive(state, now);
        if (!active && !AppProcessPolicy.isHudProcess()
                && state.getBoolean(KEY_STARTUP_INCOMPLETE, false)) {
            state.edit().putBoolean(KEY_STARTUP_INCOMPLETE, false)
                    .remove(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED).apply();
        }
        return active;
    }

    /** Null-intent START_STICKY recovery may not bypass an active boot/process quiet barrier. */
    public static boolean shouldDeferAutomaticStickyRestart(@NonNull Context context) {
        return remainingQuietMillis(context) > 0L
                || isStartupInitializationBlocked(context);
    }

    static long startupInitializationDelayMillis(@NonNull Context context) {
        SharedPreferences state = state(applicationContext(context));
        long now = SystemClock.elapsedRealtime();
        long barrier = startupBarrierActive(state, now)
                ? StartupLoadPolicy.remainingQuietMillis(now,
                state.getLong(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED, 0L)) : 0L;
        return Math.max(remainingQuietMillis(context), barrier);
    }

    /** Opens the barrier only for the current host generation after all delay/unlock checks. */
    static void openInitializationBarrierForHost(@NonNull Context context, long generation) {
        SharedPreferences state = state(applicationContext(context));
        if (state.getBoolean(KEY_HOST_PHASE_PENDING, false)
                && state.getLong(KEY_HOST_PHASE_GENERATION, Long.MIN_VALUE) == generation) {
            state.edit().putBoolean(KEY_STARTUP_INCOMPLETE, false)
                    .remove(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED).commit();
        }
    }

    static int phase(@NonNull Intent intent) {
        return intent.getIntExtra(EXTRA_PHASE, -1);
    }

    static long generation(@NonNull Intent intent) {
        return intent.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE);
    }

    static void markPhaseCompleted(@NonNull Context context, int phase, long generation) {
        SharedPreferences state = state(applicationContext(context));
        if (state.getLong(generationKey(phase), Long.MIN_VALUE) != generation) return;
        SharedPreferences.Editor edit = state.edit()
                .putBoolean(pendingKey(phase), false)
                .remove(notBeforeKey(phase));
        if (phase == PHASE_INTEGRATION_HOST) {
            edit.putBoolean(KEY_STARTUP_INCOMPLETE, false)
                    .remove(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED);
        }
        edit.commit();
    }

    /** Keeps an exact failed phase pending and retries it without minting a new generation. */
    static void retryPhase(@NonNull Context context, int phase, long generation,
                           long delayMillis) {
        SharedPreferences state = state(applicationContext(context));
        if (!state.getBoolean(pendingKey(phase), false)
                || state.getLong(generationKey(phase), Long.MIN_VALUE) != generation) {
            return;
        }
        schedulePhase(applicationContext(context), phase, generation,
                Math.max(1_000L, delayMillis));
    }

    private static void ensurePhaseScheduled(@NonNull Context app, int phase,
                                             long requestedNotBefore) {
        SharedPreferences state = state(app);
        boolean pending = state.getBoolean(pendingKey(phase), false);
        long generation = pending
                ? state.getLong(generationKey(phase), Long.MIN_VALUE)
                : nextGeneration(state);
        if (generation == Long.MIN_VALUE) generation = nextGeneration(state);
        long existing = pending ? state.getLong(notBeforeKey(phase), 0L) : 0L;
        long notBefore = Math.max(existing, requestedNotBefore);
        SharedPreferences.Editor edit = state.edit();
        if (!pending) edit.putLong(KEY_GENERATION_COUNTER, generation);
        writePhase(edit, phase, generation, notBefore);
        edit.commit();
        schedulePhaseAtElapsed(app, phase, generation, notBefore);
    }

    private static void writeSharedLaneDeadlines(@NonNull SharedPreferences.Editor edit,
                                                 long quietUntil) {
        edit.putLong(KEY_QUIET_UNTIL_ELAPSED, quietUntil)
                .putLong(KEY_LAUNCHER_PANELS_NOT_BEFORE_ELAPSED,
                        quietUntil + StartupLoadPolicy.LAUNCHER_PANELS_AFTER_HOST_MS)
                .putLong(KEY_LAUNCHER_RUNTIME_NOT_BEFORE_ELAPSED,
                        quietUntil + StartupLoadPolicy.LAUNCHER_RUNTIME_AFTER_HOST_MS)
                .putLong(KEY_AUTOMATIC_RECONCILE_NOT_BEFORE_ELAPSED,
                        quietUntil + StartupLoadPolicy.CLIMATE_AFTER_HOST_MS);
    }

    /** A normal post-boot process restart gets a short settle, not the full cold-boot UX delay. */
    private static void writeProcessSettleDeadlines(@NonNull SharedPreferences.Editor edit,
                                                    long quietUntil) {
        edit.putLong(KEY_QUIET_UNTIL_ELAPSED, quietUntil)
                .putLong(KEY_LAUNCHER_PANELS_NOT_BEFORE_ELAPSED, quietUntil)
                .putLong(KEY_LAUNCHER_RUNTIME_NOT_BEFORE_ELAPSED,
                        quietUntil + StartupLoadPolicy.PROCESS_SETTLE_RUNTIME_AFTER_MS)
                .putLong(KEY_AUTOMATIC_RECONCILE_NOT_BEFORE_ELAPSED,
                        quietUntil + StartupLoadPolicy.PROCESS_SETTLE_RUNTIME_AFTER_MS);
    }

    /**
     * AlarmManager is inexact on Android 9. Keep dependent launcher work behind the current host
     * handoff for a bounded five-second grace, then fail open so a missing alarm cannot blank HOME.
     */
    private static long remainingHostHandoffMillis(@NonNull Context context) {
        SharedPreferences state = state(applicationContext(context));
        if (!state.getBoolean(KEY_HOST_PHASE_PENDING, false)) return 0L;
        long generation = state.getLong(KEY_HOST_PHASE_GENERATION, Long.MIN_VALUE);
        boolean pendingCredentialRefresh = state.getBoolean(KEY_UNLOCK_REFRESH_PENDING, false)
                && state.getLong(KEY_UNLOCK_REFRESH_GENERATION, Long.MIN_VALUE) == generation;
        boolean pendingSurfaceReconcile = state.getBoolean(KEY_SURFACE_RECONCILE_PENDING, false)
                && state.getLong(KEY_SURFACE_RECONCILE_GENERATION, Long.MIN_VALUE) == generation;
        if (WidgetService.isRunning()
                && !pendingCredentialRefresh && !pendingSurfaceReconcile) return 0L;
        long now = SystemClock.elapsedRealtime();
        long notBefore = state.getLong(KEY_HOST_NOT_BEFORE_ELAPSED, 0L);
        if (notBefore <= 0L || now > notBefore + StartupLoadPolicy.HOST_HANDOFF_GRACE_MS) {
            return 0L;
        }
        long untilHost = notBefore - now;
        return untilHost > 0L ? untilHost : 250L;
    }

    private static void writePhase(@NonNull SharedPreferences.Editor edit, int phase,
                                   long generation, long notBefore) {
        edit.putBoolean(pendingKey(phase), true)
                .putLong(generationKey(phase), generation)
                .putLong(notBeforeKey(phase), notBefore);
    }

    private static void schedulePhaseAtElapsed(@NonNull Context app, int phase,
                                               long generation, long triggerElapsedMillis) {
        schedulePhase(app, phase, generation,
                Math.max(1L, triggerElapsedMillis - SystemClock.elapsedRealtime()));
    }

    private static void schedulePhase(@NonNull Context app, int phase, long generation,
                                      long delayMillis) {
        Intent intent = new Intent(app, BootReceiver.class)
                .setAction(ACTION_RUN_PHASE)
                .putExtra(EXTRA_PHASE, phase)
                .putExtra(EXTRA_GENERATION, generation);
        int requestCode = phase == PHASE_CLIMATE ? REQUEST_CLIMATE
                : phase == PHASE_MEDIA_PLAN ? REQUEST_MEDIA : REQUEST_HOST;
        PendingIntent pending = PendingIntent.getBroadcast(app, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) {
            Log.w(TAG, "AlarmManager unavailable for phase " + phase);
            return;
        }
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + Math.max(1L, delayMillis), pending);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not schedule startup phase " + phase, failure);
        }
    }

    private static long remainingDeadline(@NonNull Context context, @NonNull String key,
                                          long maximum, boolean clearExpired) {
        SharedPreferences state = state(applicationContext(context));
        long now = SystemClock.elapsedRealtime();
        long until = state.getLong(key, 0L);
        long remaining = validRemaining(now, until, maximum);
        if (clearExpired && !AppProcessPolicy.isHudProcess()
                && remaining == 0L && until != 0L) {
            state.edit().remove(key).apply();
        }
        return remaining;
    }

    private static long validFutureDeadline(@NonNull SharedPreferences state,
                                            @NonNull String key, long now, long maximum) {
        long until = state.getLong(key, 0L);
        return validRemaining(now, until, maximum) > 0L ? until : 0L;
    }

    /** Retains a pending lane without letting an expired/corrupt absolute value delay it again. */
    private static long retainedPhaseNotBefore(@NonNull SharedPreferences state,
                                               @NonNull String key, long now) {
        long future = validFutureDeadline(state, key, now,
                StartupLoadPolicy.MAX_VALID_STARTUP_LANE_MS);
        return future > 0L ? future : now;
    }

    private static long validRemaining(long now, long until, long maximum) {
        long remaining = until - now;
        return remaining > 0L && remaining <= maximum ? remaining : 0L;
    }

    private static long nextGeneration(@NonNull SharedPreferences state) {
        long current = state.getLong(KEY_GENERATION_COUNTER, 0L);
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }

    private static boolean startupBarrierActive(@NonNull SharedPreferences state, long now) {
        if (!state.getBoolean(KEY_STARTUP_INCOMPLETE, false)) return false;
        long expires = state.getLong(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED, 0L);
        return StartupLoadPolicy.remainingQuietMillis(now, expires) > 0L;
    }

    private static void clearElapsedGenerationState(
            @NonNull SharedPreferences.Editor edit) {
        edit.remove(KEY_QUIET_UNTIL_ELAPSED)
                .remove(KEY_LAUNCHER_PANELS_NOT_BEFORE_ELAPSED)
                .remove(KEY_LAUNCHER_RUNTIME_NOT_BEFORE_ELAPSED)
                .remove(KEY_AUTOMATIC_RECONCILE_NOT_BEFORE_ELAPSED)
                .remove(KEY_HOST_PHASE_PENDING)
                .remove(KEY_HOST_PHASE_GENERATION)
                .remove(KEY_HOST_NOT_BEFORE_ELAPSED)
                .remove(KEY_CLIMATE_PHASE_PENDING)
                .remove(KEY_CLIMATE_PHASE_GENERATION)
                .remove(KEY_CLIMATE_NOT_BEFORE_ELAPSED)
                .remove(KEY_MEDIA_PHASE_PENDING)
                .remove(KEY_MEDIA_PHASE_GENERATION)
                .remove(KEY_MEDIA_NOT_BEFORE_ELAPSED)
                .remove(KEY_SURFACE_RECONCILE_PENDING)
                .remove(KEY_SURFACE_RECONCILE_GENERATION)
                .remove(KEY_UNLOCK_REFRESH_PENDING)
                .remove(KEY_UNLOCK_REFRESH_GENERATION);
    }

    @NonNull private static String pendingKey(int phase) {
        if (phase == PHASE_INTEGRATION_HOST) return KEY_HOST_PHASE_PENDING;
        if (phase == PHASE_CLIMATE) return KEY_CLIMATE_PHASE_PENDING;
        if (phase == PHASE_MEDIA_PLAN) return KEY_MEDIA_PHASE_PENDING;
        return "invalid_phase_pending";
    }

    @NonNull private static String generationKey(int phase) {
        if (phase == PHASE_INTEGRATION_HOST) return KEY_HOST_PHASE_GENERATION;
        if (phase == PHASE_CLIMATE) return KEY_CLIMATE_PHASE_GENERATION;
        if (phase == PHASE_MEDIA_PLAN) return KEY_MEDIA_PHASE_GENERATION;
        return "invalid_phase_generation";
    }

    @NonNull private static String notBeforeKey(int phase) {
        if (phase == PHASE_INTEGRATION_HOST) return KEY_HOST_NOT_BEFORE_ELAPSED;
        if (phase == PHASE_CLIMATE) return KEY_CLIMATE_NOT_BEFORE_ELAPSED;
        if (phase == PHASE_MEDIA_PLAN) return KEY_MEDIA_NOT_BEFORE_ELAPSED;
        return "invalid_phase_not_before";
    }

    static boolean isUserUnlocked(@NonNull Context context) {
        try {
            UserManager users = context.getSystemService(UserManager.class);
            return users != null && users.isUserUnlocked();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @NonNull
    private static StartupLoadPolicy.Trigger classify(@Nullable String action) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return StartupLoadPolicy.Trigger.LOCKED_BOOT;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return StartupLoadPolicy.Trigger.BOOT_COMPLETED;
        }
        if ("android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return StartupLoadPolicy.Trigger.QUICK_BOOT;
        }
        if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            return StartupLoadPolicy.Trigger.USER_UNLOCKED;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return StartupLoadPolicy.Trigger.PACKAGE_REPLACED;
        }
        return StartupLoadPolicy.Trigger.OTHER;
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
                .getSharedPreferences(PREFS, AppProcessPolicy.preferenceMode());
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
