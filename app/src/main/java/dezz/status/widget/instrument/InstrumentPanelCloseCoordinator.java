/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** MAIN owns admission; workers must check the supplied cancellation predicate before writes. */
final class InstrumentPanelCloseCoordinator {
    static final long DEADLINE_MS = 10_000L;
    enum Owner { FREE, OTHER, UNKNOWN }
    interface Host {
        void finishPanel();
        void revokeToken(BooleanSupplier current, Runnable done);
        void readOwner(BooleanSupplier current, Consumer<Owner> done);
        void restoreDim(BooleanSupplier current, Consumer<Boolean> done);
        void trace(String message);
    }
    private final Host host;
    private final InstrumentPanelLaunchCoordinator.Scheduler scheduler;
    private volatile Object pending;

    InstrumentPanelCloseCoordinator(Host host, InstrumentPanelLaunchCoordinator.Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    void cancel() { pending = null; }

    void close() {
        Object request = new Object();
        pending = request;
        BooleanSupplier current = () -> pending == request;
        host.finishPanel();
        scheduler.after(DEADLINE_MS, () -> finish(request, "close-timeout; DIM state unconfirmed"));
        host.revokeToken(current, () -> {
            if (!current.getAsBoolean()) return;
            host.readOwner(current, owner -> {
                if (!current.getAsBoolean()) return;
                if (owner != Owner.FREE) {
                    finish(request, "close preserved DIM owner=" + owner);
                    return;
                }
                host.restoreDim(current, verified -> {
                    if (current.getAsBoolean()) finish(request,
                            "close DIM stock readback=" + verified);
                });
            });
        });
    }

    private void finish(Object request, String result) {
        if (pending != request) return;
        pending = null;
        host.trace(result);
    }
}
