/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.diagnostics;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Records a bounded thread dump when the application's main looper stops responding. */
public final class MainThreadWatchdog {
    private static final long HEARTBEAT_MS = 500L;
    private static final long HANG_THRESHOLD_MS = 2_000L;
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
        HEARTBEAT.set(SystemClock.uptimeMillis());
        Thread worker = new Thread(() -> loop(generation), "status-main-watchdog");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loop(long generation) {
        long lastReport = -REPORT_COOLDOWN_MS;
        long reportedHeartbeat = -1L;
        AtomicBoolean pending = new AtomicBoolean();
        while (RUNNING.get() && GENERATION.get() == generation) {
            if (pending.compareAndSet(false, true)) {
                if (!MAIN.post(() -> {
                    if (GENERATION.get() == generation) HEARTBEAT.set(SystemClock.uptimeMillis());
                    pending.set(false);
                })) pending.set(false);
            }
            try {
                Thread.sleep(HEARTBEAT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                if (GENERATION.get() == generation) RUNNING.set(false);
                return;
            }
            if (!RUNNING.get() || GENERATION.get() != generation) return;
            long now = SystemClock.uptimeMillis();
            long heartbeat = HEARTBEAT.get();
            if (reportedHeartbeat >= 0L && heartbeat > reportedHeartbeat) {
                DiagnosticJournal.info("watchdog", "main thread recovered; stalled_for_ms="
                        + (heartbeat - reportedHeartbeat) + ", recovered_uptime=" + heartbeat);
                reportedHeartbeat = -1L;
            }
            long blocked = now - heartbeat;
            if (blocked < HANG_THRESHOLD_MS || now - lastReport < REPORT_COOLDOWN_MS) continue;
            lastReport = now;
            reportedHeartbeat = heartbeat;
            DiagnosticJournal.warn("watchdog",
                    "main thread unresponsive for " + blocked + " ms\n" + threadDump());
        }
    }

    @NonNull
    private static String threadDump() {
        return ThreadDumpFormatter.format(Looper.getMainLooper().getThread(),
                Thread.getAllStackTraces());
    }
}
