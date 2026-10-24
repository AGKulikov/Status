/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import java.util.function.IntConsumer;

/** One bounded command: probe first, launch only on a complete absence proof, execute once. */
final class NavigatorAliceRequest {
    static final int READY = 33801, ACCEPTED = 33802;
    interface Backend {
        long now();
        void send(boolean probe, IntConsumer reply);
        void inspect(IntConsumer reply);
        boolean launch();
        void later(long delayMs, Runnable callback);
        void failure(String reason);
    }
    private final Backend backend;
    private final long deadline;
    private boolean finished;
    NavigatorAliceRequest(Backend backend, long deadline) { this.backend = backend; this.deadline = deadline; }
    boolean active() { return !finished && backend.now() < deadline; }
    void cancel() { finished = true; }
    void expire() { if (!finished) fail("timeout"); }
    void start() {
        backend.later(Math.max(0, deadline - backend.now()), () -> {
            if (!finished) fail("timeout");
        });
        probe(false);
    }
    private void probe(boolean launched) {
        if (!active()) return;
        backend.send(true, result -> {
            if (!active()) return;
            if (result == READY) {
                backend.send(false, reply -> {
                    if (!active()) return;
                    if (reply == ACCEPTED) finished = true;
                    else fail("not_accepted");
                });
            } else if (launched) {
                backend.later(250, () -> probe(true));
            } else {
                backend.inspect(state -> {
                    if (!active()) return;
                    if (state == 0) {
                        if (backend.launch()) probe(true);
                        else fail("launch_failed");
                    } else {
                        // Running/unknown is never permission to move an existing task.
                        fail(state > 0 ? "receiver_unavailable" : "state_unknown");
                    }
                });
            }
        });
    }
    private void fail(String reason) {
        if (finished) return;
        finished = true;
        backend.failure(reason);
    }
}
