/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import dezz.status.widget.StartupWorkCoordinator;

/** Restarts the fallback only when the user left its persisted opt-in switch enabled. */
public final class HudModeFallbackBootReceiver extends BroadcastReceiver {
    private static final String ACTION_DELAYED_START =
            "ru.natro.statuswidget.action.START_HUD_FALLBACK_AFTER_BOOT";
    private static final int REQUEST_DELAYED_START = 0x4846;
    private static final String ACTION_QUICKBOOT_POWERON =
            "android.intent.action.QUICKBOOT_POWERON";
    private static final String PREFS = "hud_fallback_startup_lane";
    private static final String KEY_NOT_BEFORE = "not_before_elapsed";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DELAYED_START.equals(action)) {
            long now = SystemClock.elapsedRealtime();
            long notBefore = state(context).getLong(KEY_NOT_BEFORE, 0L);
            long remaining = notBefore - now;
            if (remaining > 0L && remaining <= 120_000L) {
                schedule(context, remaining);
                return;
            }
            HudModeFallbackService.startSaved(context, "staged-boot");
            return;
        }
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !ACTION_QUICKBOOT_POWERON.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        // The fallback opens a second ECARX car proxy. Never race it against SystemServer, the
        // main CarIntegration owner and HUD/Climate surface restoration at locked boot.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        long delay = StartupWorkCoordinator.hudFallbackDelayMillis();
        state(context).edit().putLong(KEY_NOT_BEFORE,
                SystemClock.elapsedRealtime() + delay).commit();
        schedule(context, delay);
    }

    private static void schedule(Context context, long delay) {
        Intent delayed = new Intent(context, HudModeFallbackBootReceiver.class)
                .setAction(ACTION_DELAYED_START);
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST_DELAYED_START,
                delayed, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + Math.max(1L, delay), pending);
        } catch (RuntimeException ignored) {
            // A later normal service/settings entry will still reconcile the persisted opt-in.
        }
    }

    private static SharedPreferences state(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
