/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded, single-drain queue keeping diagnostic file writes off the caller thread. */
final class JournalWriteQueue {
    interface Scheduler {
        void execute(@NonNull Runnable task);
    }

    interface Sink {
        void write(@NonNull List<Operation> operations);
    }

    static final class Operation {
        final boolean resetBefore;
        @NonNull final String line;

        Operation(boolean resetBefore, @NonNull String line) {
            this.resetBefore = resetBefore;
            this.line = line;
        }
    }

    private final int maximumPending;
    @NonNull private final Scheduler scheduler;
    @NonNull private final Sink sink;
    @NonNull private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    private boolean drainScheduled;

    JournalWriteQueue(int maximumPending, @NonNull Scheduler scheduler,
                      @NonNull Sink sink) {
        this.maximumPending = Math.max(1, maximumPending);
        this.scheduler = scheduler;
        this.sink = sink;
    }

    void append(@NonNull String line) {
        boolean schedule;
        synchronized (this) {
            boolean dropped = false;
            while (pending.size() >= maximumPending) {
                pending.removeFirst();
                dropped = true;
            }
            // If file writes fall behind, the retained batch must replace the on-disk prefix;
            // otherwise dropped in-memory operations would leave an unbounded file.
            boolean reset = dropped && pending.isEmpty();
            if (dropped && !pending.isEmpty()) {
                Operation first = pending.removeFirst();
                pending.addFirst(new Operation(true, first.line));
            }
            pending.addLast(new Operation(reset, line));
            schedule = markDrainScheduledLocked();
        }
        if (schedule) scheduleDrain();
    }

    void resetAndAppend(@NonNull String line) {
        boolean schedule;
        synchronized (this) {
            pending.clear();
            pending.addLast(new Operation(true, line));
            schedule = markDrainScheduledLocked();
        }
        if (schedule) scheduleDrain();
    }

    private boolean markDrainScheduledLocked() {
        if (drainScheduled) return false;
        drainScheduled = true;
        return true;
    }

    private void scheduleDrain() {
        try {
            scheduler.execute(this::drain);
        } catch (RuntimeException ignored) {
            synchronized (this) {
                drainScheduled = false;
            }
        }
    }

    private void drain() {
        while (true) {
            List<Operation> batch;
            synchronized (this) {
                if (pending.isEmpty()) {
                    drainScheduled = false;
                    return;
                }
                batch = new ArrayList<>(pending);
                pending.clear();
            }
            try {
                sink.write(batch);
            } catch (RuntimeException ignored) {
                // The in-memory journal remains authoritative for the running process.
            }
        }
    }
}
