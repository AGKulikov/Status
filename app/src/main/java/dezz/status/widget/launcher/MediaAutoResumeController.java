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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import dezz.status.widget.Preferences;

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
    private static final String KEY_BOOT_TOKEN = "bootToken";
    private static final String KEY_TARGET_PACKAGE = "targetPackage";
    private static final String KEY_COMPLETED = "completed";
    private static final int REQUEST_CODE = 0x4D41;
    private static final int MAX_ATTEMPTS = 6;
    private static final long[] RETRY_DELAYS_MS = {
            2_000L, 5_000L, 10_000L, 15_000L, 20_000L
    };

    private MediaAutoResumeController() {}

    /** Called only for real boot actions, never for an application update. */
    public static void scheduleAfterBoot(@NonNull Context context) {
        Context app = applicationContext(context);
        Preferences preferences = new Preferences(app);
        if (!preferences.launcherMediaAutoResumeEnabled.get()) {
            cancel(app);
            return;
        }
        MediaPlaybackHistoryStore.Snapshot history = MediaPlaybackHistoryStore.read(app);
        boolean fixedPlayer = preferences.launcherMediaFixedPlayerEnabled.get();
        String fixedPackage = preferences.launcherMediaFixedPlayerPackage.get();
        if (!MediaPlaybackTargetPolicy.shouldAutoResume(fixedPlayer, fixedPackage,
                history.packageName, history.wasPlaying)) {
            Log.i(TAG, "Auto-resume skipped: there is no eligible target player");
            cancel(app);
            return;
        }
        String target = MediaPlaybackTargetPolicy.resolve(fixedPlayer, fixedPackage,
                history.packageName);

        long bootToken = currentBootToken(app);
        SharedPreferences state = state(app);
        if (state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE) == bootToken) {
            // LOCKED_BOOT_COMPLETED and BOOT_COMPLETED commonly arrive for the same boot.
            return;
        }
        state.edit()
                .putLong(KEY_BOOT_TOKEN, bootToken)
                .putString(KEY_TARGET_PACKAGE, target)
                .putBoolean(KEY_COMPLETED, false)
                .commit();
        int delaySeconds = clamp(preferences.launcherMediaAutoResumeDelaySeconds.get(), 0, 60);
        schedule(app, bootToken, 0, delaySeconds * 1_000L);
        Log.i(TAG, "Scheduled auto-resume for " + target
                + " after " + delaySeconds + " s");
    }

    static void execute(@NonNull Context context, long bootToken, int attempt) {
        Context app = applicationContext(context);
        SharedPreferences state = state(app);
        if (state.getBoolean(KEY_COMPLETED, false)
                || state.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE) != bootToken
                || currentBootToken(app) != bootToken) {
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

        MediaResumeCommand.Result result = MediaResumeCommand.play(app, target);
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
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + Math.max(0L, delayMillis), pending);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not schedule media auto-resume", failure);
        }
    }

    private static void complete(@NonNull Context app) {
        state(app).edit().putBoolean(KEY_COMPLETED, true).apply();
        cancelAlarm(app);
    }

    private static void cancel(@NonNull Context app) {
        state(app).edit().putBoolean(KEY_COMPLETED, true).apply();
        cancelAlarm(app);
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

    private static long currentBootToken(@NonNull Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | SecurityException ignored) {
            // Millisecond boot epoch is stable for one boot, including LOCKED/normal boot events.
            return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L;
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
