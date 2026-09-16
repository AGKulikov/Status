/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** One running task and at most one pending desired state, including cancellation. */
final class LatestTaskQueue {
    private final Executor executor;
    private Job pending;
    private boolean running;

    LatestTaskQueue(Executor executor) { this.executor = executor; }

    synchronized CompletableFuture<Void> submit(Runnable task) {
        Job next = new Job(task);
        Job displaced = pending;
        if (displaced != null) next.done.whenComplete((value, failure) -> {
            if (failure == null) displaced.done.complete(null);
            else displaced.done.completeExceptionally(failure);
        });
        pending = next;
        if (!running) {
            running = true;
            executor.execute(this::drain);
        }
        return next.done;
    }

    private void drain() {
        while (true) {
            Job job;
            synchronized (this) {
                job = pending;
                pending = null;
                if (job == null) { running = false; return; }
            }
            try { job.task.run(); job.done.complete(null); }
            catch (RuntimeException failure) { job.done.completeExceptionally(failure); }
        }
    }

    private static final class Job {
        final Runnable task;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Job(Runnable task) { this.task = task; }
    }
}
