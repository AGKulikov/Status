/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** A one-shot continuation of the exact player that was playing before this Alice request. */
final class AliceMusicResume {
    interface Scheduler { void later(long delayMs, Runnable task); }
    private final Scheduler scheduler;
    private final Runnable resume;
    private final boolean asynchronous;
    private volatile boolean dispatched;
    private boolean started;
    private volatile boolean ended;
    private volatile long revision;
    private volatile boolean cancelled, done;
    AliceMusicResume(Scheduler scheduler, Runnable resume) {
        this(scheduler, resume, false);
    }
    AliceMusicResume(Scheduler scheduler, Runnable resume, boolean asynchronous) {
        this.asynchronous = asynchronous;
        this.scheduler = scheduler;
        this.resume = resume; // null means no music was playing: never manufacture a PLAY.
    }
    boolean active() { return !done; }
    boolean mayResume() { return !cancelled && ended && dispatched; }
    long revision() { return revision; }
    void complete(long expected, boolean played) {
        if (expected != revision) return;
        if (played) done = true;
        else if (ended) cancel();
    }
    void cancel() { cancelled = true; done = true; }
    void event(int result) {
        if (done) {
            if (result == 33804 || result == 33806) cancel();
            return;
        }
        if (result == 33804) { started = true; ended = false; dispatched = false; revision++; }
        else if (result == 33806) cancel();
        else if (result == 33805 && started && !ended) {
            ended = true;
            long expected = ++revision;
            scheduler.later(2000, () -> {
                if (cancelled || done || expected != revision || !ended) return;
                dispatched = true;
                done = !asynchronous || resume == null;
                if (resume != null) resume.run();
            });
        }
    }
}
