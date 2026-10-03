/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.drivemode.car;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Pending selection, inspired by monjaro-selector 1.1.0; writes remain confirmed by Natro. */
public final class DriveModeSelection {
    public interface Host {
        void write(int mode, Consumer<Boolean> completed);
        void confirmed(int mode, boolean tapped);
        void rejected();
    }
    private final Host host;
    private final LongSupplier clock;
    private int actual = -1, target = -1, inFlight = -1;
    private long deadline, generation;
    private boolean targetTapped;
    public DriveModeSelection(Host host, LongSupplier clock) { this.host = host; this.clock = clock; }
    public boolean busy() { return inFlight >= 0; }
    public int selected() { return target >= 0 ? target : actual; }
    public void observe(int mode) { if (mode >= 0 && mode != 255) actual = mode; }
    public boolean step(List<Integer> order, int delta, long expiresAt) {
        if (clock.getAsLong() > expiresAt || delta == 0 || delta < -3 || delta > 3
                || order.size() < 2 || selected() < 0) return false;
        int start = order.indexOf(selected());
        if (start < 0) start = delta > 0 ? -1 : order.size();
        select(order.get(Math.floorMod(start + delta, order.size())), expiresAt, false);
        return true;
    }
    public void select(int mode, long expiresAt, boolean tapped) {
        if (mode < 0 || mode == 255 || clock.getAsLong() > expiresAt) return;
        target = mode; deadline = expiresAt; targetTapped = tapped;
        if (!busy()) send();
    }
    private void send() {
        if (target < 0) return;
        if (clock.getAsLong() > deadline || target == actual) {
            boolean tapped = targetTapped;
            target = -1;
            if (actual >= 0) host.confirmed(actual, tapped);
            return;
        }
        final int sent = target;
        final boolean tapped = targetTapped;
        final long owner = ++generation;
        inFlight = sent;
        host.write(sent, ok -> {
            if (owner != generation || inFlight != sent) return;
            inFlight = -1;
            if (!ok) { target = -1; actual = -1; host.rejected(); return; }
            actual = sent;
            if (target == sent) target = -1;
            host.confirmed(sent, tapped);
            // Only the newest still-fresh selection is sent. No automatic retries.
            if (owner == generation && !busy()) send();
        });
    }
    /** Revokes callbacks/pending writes; cannot undo a request already sent to the car. */
    public void cancel() { generation++; inFlight = -1; target = -1; }
}
