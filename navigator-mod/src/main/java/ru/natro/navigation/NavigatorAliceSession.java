/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Follows the native Alice dialog, including while the Activity is stopped/backgrounded. */
final class NavigatorAliceSession {
    interface Backend {
        long now();
        int state(); // -1 unavailable, 0 closed, 1 open; never Activity visibility/audio silence
        void later(long delay, Runnable callback);
        void event(int result);
    }
    static final int STARTED = 33804, FINISHED = 33805, ABORTED = 33806;
    private final Backend backend;
    private final long startDeadline, expiry;
    private boolean started, finished;
    NavigatorAliceSession(Backend backend) {
        this.backend = backend;
        startDeadline = backend.now() + 10000;
        expiry = backend.now() + 30 * 60 * 1000;
    }
    boolean active() { return !finished; }
    void poll() {
        if (finished) return;
        int state = backend.state();
        if (state < 0 || backend.now() >= expiry || (!started && backend.now() >= startDeadline)) {
            abort(); return;
        }
        if (state > 0 && !started) { started = true; backend.event(STARTED); }
        if (state == 0 && started) { finished = true; backend.event(FINISHED); return; }
        backend.later(100, this::poll);
    }
    void abort() {
        if (finished) return;
        finished = true;
        backend.event(ABORTED); // Loss/timeout is not evidence that Alice finished.
    }
}
