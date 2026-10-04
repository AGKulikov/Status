/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Bounded FIFO with room reserved for failures/boundaries. Never runs writes on a producer. */
final class DiagnosticWriteQueue {
    interface Scheduler { void execute(Runnable task); }
    private static final class Pending {
        final boolean critical;
        final Runnable task;
        Pending(boolean critical, Runnable task) { this.critical = critical; this.task = task; }
    }
    private final int limit, ordinaryLimit;
    private final Scheduler scheduler;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private boolean scheduled;
    private int ordinary;
    private long accepted, completed, ordinaryDropped, criticalDropped, cleared, scheduleErrors, writeErrors;
    DiagnosticWriteQueue(int limit, int reserved, Scheduler scheduler) {
        this.limit = Math.max(2, limit);
        ordinaryLimit = this.limit - Math.min(this.limit - 1, Math.max(1, reserved));
        this.scheduler = scheduler;
    }
    boolean submit(boolean critical, Runnable task) {
        boolean launch;
        synchronized (this) {
            if (!critical && (ordinary >= ordinaryLimit || pending.size() >= limit)) {
                ordinaryDropped++; return false;
            }
            if (pending.size() >= limit) {
                boolean removed = false;
                for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                    if (!it.next().critical) { it.remove(); ordinary--; ordinaryDropped++; removed = true; break; }
                }
                if (!removed) { pending.removeFirst(); criticalDropped++; }
            }
            pending.addLast(new Pending(critical, task));
            if (!critical) ordinary++;
            accepted++;
            launch = !scheduled; scheduled = true;
        }
        if (launch) {
            try { scheduler.execute(this::drain); }
            catch (RuntimeException failed) { synchronized (this) { scheduled = false; scheduleErrors++; } }
        }
        return true;
    }
    private void drain() {
        while (true) {
            Pending next;
            synchronized (this) {
                next = pending.pollFirst();
                if (next == null) { scheduled = false; return; }
                if (!next.critical) ordinary--;
            }
            try { next.task.run(); }
            catch (RuntimeException failed) { synchronized (this) { writeErrors++; } }
            finally { synchronized (this) { completed++; } }
        }
    }
    synchronized void clear() { cleared += pending.size(); pending.clear(); ordinary = 0; }
    synchronized long dropped() { return ordinaryDropped + criticalDropped; }
    synchronized String state() {
        return "queued=" + pending.size() + ", accepted=" + accepted + ", drained=" + completed
                + ", ordinary_dropped=" + ordinaryDropped + ", critical_dropped=" + criticalDropped
                + ", cleared=" + cleared + ", schedule_errors=" + scheduleErrors + ", writer_errors=" + writeErrors;
    }
}
