/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/**
 * Tiny thread-safe coalescer for UI refreshes backed by expensive disk/package-manager reads.
 * At most one job runs; any number of requests while it runs collapse into one trailing job.
 */
public final class SingleFlightRefresh {
    private boolean inFlight;
    private boolean pending;

    /** Returns true when the caller owns a new job and should submit it. */
    public synchronized boolean request() {
        if (inFlight) {
            pending = true;
            return false;
        }
        inFlight = true;
        return true;
    }

    /** Returns true when one coalesced trailing job should be submitted immediately. */
    public synchronized boolean complete() {
        if (pending) {
            pending = false;
            inFlight = true;
            return true;
        }
        inFlight = false;
        return false;
    }

    public synchronized void cancel() {
        inFlight = false;
        pending = false;
    }
}
