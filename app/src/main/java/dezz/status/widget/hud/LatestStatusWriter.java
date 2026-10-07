/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import java.util.concurrent.Executor;

/** One in-flight write and one replaceable snapshot; callers never wait for disk. */
final class LatestStatusWriter {
    private final Executor executor;
    private Runnable pending;
    private boolean running;

    LatestStatusWriter(Executor executor) { this.executor = executor; }

    synchronized void submit(Runnable write) {
        pending = write;
        if (running) return;
        running = true;
        try { executor.execute(this::drain); }
        catch (RuntimeException rejected) { running = false; pending = null; throw rejected; }
    }

    private void drain() {
        for (;;) {
            Runnable next;
            synchronized (this) {
                next = pending;
                pending = null;
                if (next == null) { running = false; return; }
            }
            // Storage owns error reporting. Keep the lane usable after an unexpected failure.
            try { next.run(); } catch (RuntimeException ignored) {}
        }
    }
}
