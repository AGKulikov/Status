/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Logcat-only startup timeline for calibrating the ECARX boot lanes on the physical head unit.
 * It deliberately performs no file I/O and keeps no unbounded event history.
 */
public final class StartupPerformanceTrace {
    private static final String TAG = "StatusStartup";
    private static final AtomicLong SESSION = new AtomicLong();
    private static volatile long processStartElapsed = SystemClock.elapsedRealtime();

    private StartupPerformanceTrace() {}

    public static long beginProcess(@NonNull String process) {
        processStartElapsed = SystemClock.elapsedRealtime();
        long session = SESSION.incrementAndGet();
        Log.i(TAG, "session=" + session + " marker=process_create process=" + process
                + " uptime_ms=" + processStartElapsed + " since_process_ms=0");
        return session;
    }

    public static void mark(@NonNull String marker) {
        long now = SystemClock.elapsedRealtime();
        Log.i(TAG, "session=" + SESSION.get() + " marker=" + marker
                + " uptime_ms=" + now
                + " since_process_ms=" + Math.max(0L, now - processStartElapsed));
    }

    public static void mark(@NonNull String marker, long valueMillis) {
        long now = SystemClock.elapsedRealtime();
        Log.i(TAG, "session=" + SESSION.get() + " marker=" + marker
                + " value_ms=" + valueMillis + " uptime_ms=" + now
                + " since_process_ms=" + Math.max(0L, now - processStartElapsed));
    }
}
