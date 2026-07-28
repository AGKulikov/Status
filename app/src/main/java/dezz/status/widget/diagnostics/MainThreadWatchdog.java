/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.diagnostics;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Records a bounded thread dump when the application's main looper stops responding. */
public final class MainThreadWatchdog {
    private static final long HEARTBEAT_MS = 2_000L;
    private static final long HANG_THRESHOLD_MS = 8_000L;
    private static final long REPORT_COOLDOWN_MS = 30_000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicLong HEARTBEAT = new AtomicLong();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MainThreadWatchdog() {
    }

    public static void setEnabled(boolean enabled) {
        if (!enabled) {
            RUNNING.set(false);
            GENERATION.incrementAndGet();
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        long generation = GENERATION.incrementAndGet();
        HEARTBEAT.set(SystemClock.elapsedRealtime());
        Thread worker = new Thread(() -> loop(generation), "status-main-watchdog");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loop(long generation) {
        long lastReport = 0L;
        while (RUNNING.get() && GENERATION.get() == generation) {
            MAIN.post(() -> HEARTBEAT.set(SystemClock.elapsedRealtime()));
            try {
                Thread.sleep(HEARTBEAT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                if (GENERATION.get() == generation) RUNNING.set(false);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long blocked = now - HEARTBEAT.get();
            if (blocked < HANG_THRESHOLD_MS || now - lastReport < REPORT_COOLDOWN_MS) continue;
            lastReport = now;
            DiagnosticJournal.warn("watchdog",
                    "main thread unresponsive for " + blocked + " ms\n" + threadDump());
        }
    }

    @NonNull
    private static String threadDump() {
        StringBuilder result = new StringBuilder();
        int threadCount = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            if (threadCount++ >= 24 || result.length() >= 14_000) break;
            Thread thread = entry.getKey();
            result.append("THREAD ").append(thread.getName())
                    .append(" state=").append(thread.getState()).append('\n');
            StackTraceElement[] stack = entry.getValue();
            for (int index = 0; index < stack.length && index < 48; index++) {
                result.append("  at ").append(stack[index]).append('\n');
            }
        }
        return result.toString();
    }
}
