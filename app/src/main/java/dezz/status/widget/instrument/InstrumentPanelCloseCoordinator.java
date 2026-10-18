/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** MAIN owns admission; workers must check the supplied cancellation predicate before writes. */
final class InstrumentPanelCloseCoordinator {
    static final long DEADLINE_MS = 10_000L;
    static final int MAX_OWNER_READS = 5;
    static final long OWNER_RETRY_MS = 150L;
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
    private static final class Request { int ownerReads; }
    private volatile Request pending;

    InstrumentPanelCloseCoordinator(Host host, InstrumentPanelLaunchCoordinator.Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    void cancel() { pending = null; }

    void close() {
        Request request = new Request();
        pending = request;
        BooleanSupplier current = () -> pending == request;
        host.finishPanel();
        scheduler.after(DEADLINE_MS, () -> finish(request, "close-timeout; DIM state unconfirmed"));
        host.revokeToken(current, () -> {
            if (!current.getAsBoolean()) return;
            readOwner(request);
        });
    }

    private void readOwner(Request request) {
        if (pending != request) return;
        BooleanSupplier current = () -> pending == request;
        int attempt = ++request.ownerReads;
        host.readOwner(current, owner -> {
            if (!current.getAsBoolean()) return;
            // WindowManager removes a finishing task asynchronously. A transitional or
            // unreadable snapshot is never FREE, but must not permanently abandon OFF.
            if (owner == Owner.UNKNOWN && attempt < MAX_OWNER_READS) {
                long delay = OWNER_RETRY_MS << (attempt - 1);
                host.trace("close owner unsettled attempt=" + attempt + " retry_ms=" + delay);
                scheduler.after(delay, () -> readOwner(request));
                return;
            }
            if (owner != Owner.FREE) {
                finish(request, "close preserved DIM owner=" + owner + " reads=" + attempt);
                return;
            }
            host.restoreDim(current, verified -> {
                if (current.getAsBoolean()) finish(request,
                        "close DIM stock readback=" + verified);
            });
        });
    }

    private void finish(Request request, String result) {
        if (pending != request) return;
        pending = null;
        host.trace(result);
    }
}
