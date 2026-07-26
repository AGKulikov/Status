package ru.natro.ancstest;

/**
 * Small Android-independent reconnect policy. One instance belongs to one running service.
 */
final class ReconnectBackoff {
    private static final long[] DELAYS_MS = {
            1_000L,
            2_000L,
            5_000L,
            10_000L,
            30_000L,
            60_000L
    };

    private int attempt;

    long nextDelayMs() {
        int index = Math.min(attempt, DELAYS_MS.length - 1);
        if (attempt < DELAYS_MS.length - 1) attempt++;
        return DELAYS_MS[index];
    }

    void reset() {
        attempt = 0;
    }

    int attempt() {
        return attempt;
    }
}
