/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** A one-shot continuation of the exact player that was playing before this Alice request. */
final class AliceMusicResume {
    interface Scheduler { void later(long delayMs, Runnable task); }
    private final Scheduler scheduler;
    private final Runnable resume;
    private boolean started, ended;
    private volatile boolean cancelled, done;
    AliceMusicResume(Scheduler scheduler, Runnable resume) {
        this.scheduler = scheduler;
        this.resume = resume; // null means no music was playing: never manufacture a PLAY.
    }
    boolean active() { return !done; }
    boolean mayResume() { return !cancelled; }
    void cancel() { cancelled = true; done = true; }
    void event(int result) {
        if (done || ended) return;
        if (result == 33804) started = true;
        else if (result == 33806) cancel();
        else if (result == 33805 && started) {
            ended = true;
            scheduler.later(2000, () -> {
                if (cancelled || done) return;
                done = true;
                if (resume != null) resume.run();
            });
        }
    }
}
