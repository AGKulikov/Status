/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.performance;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small named, daemon executors for settings work that must never block the UI thread. */
public final class PerformanceExecutors {
    private PerformanceExecutors() {
    }

    @NonNull
    public static ExecutorService serial(@NonNull String threadName) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {
                }
                task.run();
            }, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }
}
