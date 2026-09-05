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
 * Coalesces automatic startup work without imposing an artificial boot delay.
 *
 * <p>Each phase carries a persisted generation, so a queued fallback alarm from an older
 * BOOT/QuickBoot event cannot mark newer work complete. Due work is broadcast immediately and its
 * AlarmManager copy is only a durable fallback. LOCKED_BOOT parks only on the real credential gate;
 * USER_UNLOCKED resumes without a wakeup polling loop.</p>
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
    /** Immediate broadcast is primary; this alarm only covers process death/delivery failure. */
    private static final long PHASE_DELIVERY_FALLBACK_MS = 1_000L;

    private StartupWorkCoordinator() {}

    /** Clears persisted delays written by older builds without doing any heavyweight startup work. */
    static void clearLegacyStartupDeferrals(@NonNull Context context) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        int bootCount = currentBootCount(app);
        int recordedBootCount = state.getInt(KEY_LAST_BOOT_COUNT, Integer.MIN_VALUE);
        SharedPreferences.Editor edit = state.edit();
        if (StartupLoadPolicy.isNewBootGeneration(bootCount, recordedBootCount)) {
            // AlarmManager drops elapsed-realtime alarms at reboot. Drop their persisted owners too,
            // before an early HOME process can dispatch a generation from the previous kernel.
            clearElapsedGenerationState(edit);
        }
        clearArtificialDeferralState(edit);
        if (bootCount >= 0) edit.putInt(KEY_LAST_BOOT_COUNT, bootCount);
        edit.apply();
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
        long generation = nextGeneration(state);
        boolean retainedCredentialRefresh = state.getBoolean(
                KEY_UNLOCK_REFRESH_PENDING, false);
        boolean retainedClimate = state.getBoolean(KEY_CLIMATE_PHASE_PENDING, false);
        boolean retainedMedia = state.getBoolean(KEY_MEDIA_PHASE_PENDING, false);
        boolean retainedSurfaceReconcile = state.getBoolean(
                KEY_SURFACE_RECONCILE_PENDING, false);
        boolean scheduleHost = StartupLoadPolicy.schedulesIntegrationHost(trigger);
        boolean scheduleClimate = StartupLoadPolicy.schedulesClimate(trigger)
                || StartupLoadPolicy.opensCredentialGate(trigger) && retainedClimate;
        boolean scheduleMedia = StartupLoadPolicy.schedulesMediaPlan(trigger)
                || StartupLoadPolicy.opensCredentialGate(trigger) && retainedMedia;

        SharedPreferences.Editor edit = state.edit();
        if (newBoot) clearElapsedGenerationState(edit);
        clearArtificialDeferralState(edit);
        edit.putLong(KEY_GENERATION_COUNTER, generation);
        if (bootCount >= 0) edit.putInt(KEY_LAST_BOOT_COUNT, bootCount);
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
        if (scheduleHost) writePhase(edit, PHASE_INTEGRATION_HOST, generation, now);
        if (scheduleClimate) writePhase(edit, PHASE_CLIMATE, generation, now);
        if (scheduleMedia) writePhase(edit, PHASE_MEDIA_PLAN, generation, now);
        edit.commit();

        if (scheduleHost) {
            dispatchPhaseNowWithFallback(app, PHASE_INTEGRATION_HOST, generation);
        }
        if (scheduleClimate) {
            dispatchPhaseNowWithFallback(app, PHASE_CLIMATE, generation);
        }
        if (scheduleMedia) {
            dispatchPhaseNowWithFallback(app, PHASE_MEDIA_PLAN, generation);
        }
        Log.i(TAG, "Dispatched " + trigger + " generation=" + generation
                + " without an artificial startup delay");
    }

    public static long hudFallbackDelayMillis() {
        return 0L;
    }

    public static long mediaAutoResumeMinimumDelayMillis() {
        return 0L;
    }

    static long remainingQuietMillis(@NonNull Context context) {
        return 0L;
    }

    /** Startup timing never parks automatic runtime; real permission/readiness gates still apply. */
    static boolean shouldParkAutomaticRuntime(@NonNull Context context) {
        return false;
    }

    /**
     * Exact in-process handoff for a visible HOME. Every pending generation is due immediately;
     * AlarmManager remains the durable owner when HOME is hidden or the process dies.
     *
     * @return {@code 0} when a host phase is pending, or {@code -1} when none is pending.
     */
    static long pendingIntegrationHostDelayMillis(@NonNull Context context) {
        SharedPreferences state = state(applicationContext(context));
        if (!state.getBoolean(KEY_HOST_PHASE_PENDING, false)) return -1L;
        return 0L;
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

    public static void ensureIntegrationHostScheduled(@NonNull Context context) {
        Context app = applicationContext(context);
        ensurePhaseScheduled(app, PHASE_INTEGRATION_HOST);
    }

    /** Legacy no-draw API; HA1216 admits the durable host request immediately. */
    static void ensureIntegrationHostScheduledAfter(@NonNull Context context,
                                                    long ignoredDelayMillis) {
        Context app = applicationContext(context);
        ensurePhaseScheduled(app, PHASE_INTEGRATION_HOST);
    }

    public static void ensureClimateScheduled(@NonNull Context context) {
        Context app = applicationContext(context);
        ensurePhaseScheduled(app, PHASE_CLIMATE);
    }

    static boolean isPhaseIntent(@Nullable Intent intent) {
        return intent != null && ACTION_RUN_PHASE.equals(intent.getAction());
    }

    /** Returns true for a stale or credential-locked phase; timing never defers due work. */
    static boolean deferPhaseIfNeeded(@NonNull Context context, int phase, long generation) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (!state.getBoolean(pendingKey(phase), false)
                || state.getLong(generationKey(phase), Long.MIN_VALUE) != generation) {
            Log.i(TAG, "Ignoring stale startup phase=" + phase + " generation=" + generation);
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

    /** START_STICKY recovery is immediate; service-local readiness checks remain authoritative. */
    public static boolean shouldDeferAutomaticStickyRestart(@NonNull Context context) {
        return false;
    }

    /** Clears a legacy barrier only for the exact current host generation. */
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
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (state.getLong(generationKey(phase), Long.MIN_VALUE) != generation) return;
        SharedPreferences.Editor edit = state.edit()
                .putBoolean(pendingKey(phase), false)
                .remove(notBeforeKey(phase));
        if (phase == PHASE_INTEGRATION_HOST) {
            edit.putBoolean(KEY_STARTUP_INCOMPLETE, false)
                    .remove(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED);
        }
        edit.commit();
        cancelPhaseFallback(app, phase);
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

    private static void ensurePhaseScheduled(@NonNull Context app, int phase) {
        SharedPreferences state = state(app);
        boolean pending = state.getBoolean(pendingKey(phase), false);
        long generation = pending
                ? state.getLong(generationKey(phase), Long.MIN_VALUE)
                : nextGeneration(state);
        if (generation == Long.MIN_VALUE) generation = nextGeneration(state);
        long now = SystemClock.elapsedRealtime();
        SharedPreferences.Editor edit = state.edit();
        if (!pending) edit.putLong(KEY_GENERATION_COUNTER, generation);
        clearArtificialDeferralState(edit);
        writePhase(edit, phase, generation, now);
        edit.commit();
        dispatchPhaseNowWithFallback(app, phase, generation);
    }

    private static void clearArtificialDeferralState(
            @NonNull SharedPreferences.Editor edit) {
        edit.remove(KEY_QUIET_UNTIL_ELAPSED)
                .remove(KEY_LAUNCHER_PANELS_NOT_BEFORE_ELAPSED)
                .remove(KEY_LAUNCHER_RUNTIME_NOT_BEFORE_ELAPSED)
                .remove(KEY_AUTOMATIC_RECONCILE_NOT_BEFORE_ELAPSED)
                .remove(KEY_STARTUP_INCOMPLETE)
                .remove(KEY_STARTUP_BARRIER_EXPIRES_ELAPSED);
    }

    private static void writePhase(@NonNull SharedPreferences.Editor edit, int phase,
                                   long generation, long notBefore) {
        edit.putBoolean(pendingKey(phase), true)
                .putLong(generationKey(phase), generation)
                .putLong(notBeforeKey(phase), notBefore);
    }

    private static void dispatchPhaseNowWithFallback(@NonNull Context app, int phase,
                                                     long generation) {
        if (!isUserUnlocked(app)) {
            Log.i(TAG, "Startup phase " + phase + " waiting for USER_UNLOCKED");
            return;
        }
        schedulePhase(app, phase, generation, PHASE_DELIVERY_FALLBACK_MS);
        try {
            app.sendBroadcast(phaseIntent(app, phase, generation));
        } catch (RuntimeException failure) {
            // The generation-fenced AlarmManager copy remains the delivery fallback.
            Log.w(TAG, "Could not immediately dispatch startup phase " + phase, failure);
        }
    }

    private static void schedulePhase(@NonNull Context app, int phase, long generation,
                                      long delayMillis) {
        PendingIntent pending = PendingIntent.getBroadcast(app, requestCode(phase),
                phaseIntent(app, phase, generation),
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

    @NonNull
    private static Intent phaseIntent(@NonNull Context app, int phase, long generation) {
        return new Intent(app, BootReceiver.class)
                .setAction(ACTION_RUN_PHASE)
                .putExtra(EXTRA_PHASE, phase)
                .putExtra(EXTRA_GENERATION, generation);
    }

    private static int requestCode(int phase) {
        return phase == PHASE_CLIMATE ? REQUEST_CLIMATE
                : phase == PHASE_MEDIA_PLAN ? REQUEST_MEDIA : REQUEST_HOST;
    }

    private static void cancelPhaseFallback(@NonNull Context app, int phase) {
        PendingIntent pending = PendingIntent.getBroadcast(app, requestCode(phase),
                phaseIntent(app, phase, 0L),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        try {
            if (alarms != null) alarms.cancel(pending);
            pending.cancel();
        } catch (RuntimeException failure) {
            // Completion is already generation-fenced; a late fallback will be rejected as stale.
            Log.w(TAG, "Could not cancel completed startup fallback phase " + phase, failure);
        }
    }

    private static long nextGeneration(@NonNull SharedPreferences state) {
        long current = state.getLong(KEY_GENERATION_COUNTER, 0L);
        return current == Long.MAX_VALUE ? 1L : current + 1L;
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
