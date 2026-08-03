/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Pure policy for retaining a same-boot broadcast when the publisher has no heartbeat. */
final class MediaBroadcastFreshness {
    static final long UNKNOWN_TTL_MS = 5L * 60L * 1_000L;
    static final long KNOWN_TTL_MS = 2L * 60L * 60L * 1_000L;
    static final long TRACK_END_GRACE_MS = 15L * 60L * 1_000L;
    static final long MAX_TTL_MS = 24L * 60L * 60L * 1_000L;

    private MediaBroadcastFreshness() {}

    static long ttl(boolean known, boolean playing, long durationMs, long positionMs) {
        if (!known) return UNKNOWN_TTL_MS;
        long result = KNOWN_TTL_MS;
        if (playing && durationMs > 0L) {
            long remaining = Math.max(0L, durationMs - Math.max(0L, positionMs));
            long untilExpectedEnd = remaining > Long.MAX_VALUE - TRACK_END_GRACE_MS
                    ? Long.MAX_VALUE : remaining + TRACK_END_GRACE_MS;
            result = Math.max(result, untilExpectedEnd);
        }
        return Math.min(result, MAX_TTL_MS);
    }

    static boolean expired(long nowElapsedMs, long receivedElapsedMs, long ttlMs) {
        long age = Math.max(0L, nowElapsedMs - Math.max(0L, receivedElapsedMs));
        return age > Math.max(0L, ttlMs);
    }

    static long remaining(long nowElapsedMs, long receivedElapsedMs, long ttlMs) {
        long safeTtl = Math.max(0L, ttlMs);
        long age = Math.max(0L, nowElapsedMs - Math.max(0L, receivedElapsedMs));
        if (age > safeTtl) return 0L;
        return safeTtl - age + 1L;
    }
}
