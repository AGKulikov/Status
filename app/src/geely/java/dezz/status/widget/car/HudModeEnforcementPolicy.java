/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import java.util.ArrayDeque;

/**
 * Safety limits for the optional ProfileTransfer mode fallback.
 *
 * <p>Speed is an edge trigger, not a reason to write on every CAN frame. The global budget and
 * circuit breaker also bound writes when several ECARX callbacks arrive together.</p>
 */
final class HudModeEnforcementPolicy {
    static final double SPEED_SCALE_KMH = 0.00391d;
    static final double SPEED_REARM_KMH = 15.0d;
    static final double SPEED_TRIGGER_KMH = 20.0d;
    static final long MIN_WRITE_INTERVAL_MS = 4_000L;
    static final long WRITE_BUDGET_WINDOW_MS = 60_000L;
    static final int MAX_WRITES_PER_WINDOW = 5;
    static final int FAILURES_BEFORE_CIRCUIT_BREAK = 3;
    static final long CIRCUIT_BREAK_MS = 60_000L;

    private final ArrayDeque<Long> writes = new ArrayDeque<>();
    private boolean speedArmed = true;
    private long lastWriteAt = Long.MIN_VALUE;
    private int consecutiveFailures;
    private long circuitOpenUntil;

    boolean onRawSpeed(int rawSpeed) {
        double kmh = Math.max(0, rawSpeed) * SPEED_SCALE_KMH;
        if (kmh < SPEED_REARM_KMH) {
            speedArmed = true;
            return false;
        }
        if (speedArmed && kmh > SPEED_TRIGGER_KMH) {
            speedArmed = false;
            return true;
        }
        return false;
    }

    void resetSpeedLatch() {
        speedArmed = true;
    }

    long delayBeforeWrite(long now) {
        discardExpiredWrites(now);
        long delay = 0L;
        if (lastWriteAt != Long.MIN_VALUE) {
            delay = Math.max(delay, MIN_WRITE_INTERVAL_MS - (now - lastWriteAt));
        }
        delay = Math.max(delay, circuitOpenUntil - now);
        if (writes.size() >= MAX_WRITES_PER_WINDOW) {
            long oldest = writes.peekFirst();
            delay = Math.max(delay, WRITE_BUDGET_WINDOW_MS - (now - oldest));
        }
        return Math.max(0L, delay);
    }

    void recordWriteAttempt(long now) {
        discardExpiredWrites(now);
        writes.addLast(now);
        lastWriteAt = now;
    }

    void recordWriteSuccess() {
        consecutiveFailures = 0;
        circuitOpenUntil = 0L;
    }

    void recordWriteFailure(long now) {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURES_BEFORE_CIRCUIT_BREAK) {
            circuitOpenUntil = Math.max(circuitOpenUntil, now + CIRCUIT_BREAK_MS);
            consecutiveFailures = 0;
        }
    }

    int writesInCurrentWindow(long now) {
        discardExpiredWrites(now);
        return writes.size();
    }

    private void discardExpiredWrites(long now) {
        while (!writes.isEmpty()
                && now - writes.peekFirst() >= WRITE_BUDGET_WINDOW_MS) {
            writes.removeFirst();
        }
    }
}
