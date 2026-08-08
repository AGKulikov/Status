/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

/** Initializes only the stock BTPhone runtime and selects its floating call UI for custom HOME. */
public final class EcarxBtPhoneBridge {
    static final String ACTION_IS_LAUNCHER = "ECARX_ACTION_IS_LAUNCHER";
    static final String ACTION_PHONE_UI_EVENT = "com.ecarx.btphone";
    static final String ACTION_WAKE_PHONE_RUNTIME =
            "dezz.status.widget.action.INIT_ECARX_BTPHONE_RUNTIME";
    static final String EXTRA_IS_LAUNCHER = "isLauncher";
    static final String PHONE_PACKAGE = "com.ecarx.btphone";
    static final String PHONE_WAKE_RECEIVER =
            "com.ecarx.btphone.control.ControlKeyBroadcastReceiver";

    private static final String TAG = "EcarxBtPhoneBridge";
    private static final long MIN_WAKE_INTERVAL_MS = 2_000L;
    private static final long[] STATE_RETRY_DELAYS_MS = {0L, 250L, 1_000L};
    private static final Object LOCK = new Object();
    private static long lastWakeElapsed;

    private EcarxBtPhoneBridge() { }

    public static void onLauncherVisible(@NonNull Context context) {
        Context app = applicationContext(context);
        wakePhoneMainProcess(app);
        assertFloatingUiWithRetries(app);
    }

    public static void onPhoneUiEvent(@NonNull Context context) {
        Context app = applicationContext(context);
        if (isSelectedHome(app)) assertFloatingUiWithRetries(app);
    }

    static boolean isSelectedHome(@NonNull Context context) {
        try {
            ResolveInfo resolved = context.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && resolved.activityInfo != null
                    && context.getPackageName().equals(resolved.activityInfo.packageName);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void wakePhoneMainProcess(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (lastWakeElapsed != 0L && now - lastWakeElapsed < MIN_WAKE_INTERVAL_MS) return;
            lastWakeElapsed = now;
        }
        try {
            context.sendBroadcast(new Intent(ACTION_WAKE_PHONE_RUNTIME)
                    .setComponent(new ComponentName(PHONE_PACKAGE, PHONE_WAKE_RECEIVER))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            Log.i(TAG, "Requested stock BTPhone main-process initialization");
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not initialize stock BTPhone runtime", error);
        }
    }

    private static void assertFloatingUiWithRetries(@NonNull Context context) {
        Handler handler = new Handler(Looper.getMainLooper());
        for (long delay : STATE_RETRY_DELAYS_MS) {
            if (delay == 0L) sendNonStockLauncherState(context);
            else handler.postDelayed(() -> sendNonStockLauncherState(context), delay);
        }
    }

    private static void sendNonStockLauncherState(@NonNull Context context) {
        try {
            context.sendBroadcast(new Intent(ACTION_IS_LAUNCHER)
                    .setPackage(PHONE_PACKAGE)
                    .putExtra(EXTRA_IS_LAUNCHER, false)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not select the stock floating-call UI", error);
        }
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
