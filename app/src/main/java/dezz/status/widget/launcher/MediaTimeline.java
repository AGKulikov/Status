/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Pure timeline helpers kept separate so edge cases are covered by local unit tests. */
public final class MediaTimeline {
    private MediaTimeline() {}

    public static long clampPosition(long positionMs, long durationMs) {
        long position = Math.max(0L, positionMs);
        return durationMs > 0L ? Math.min(position, durationMs) : position;
    }

    public static int progress(long positionMs, long durationMs, int maximum) {
        if (durationMs <= 0L || maximum <= 0) return 0;
        double ratio = (double) clampPosition(positionMs, durationMs) / (double) durationMs;
        return (int) Math.round(Math.max(0d, Math.min(1d, ratio)) * maximum);
    }

    @NonNull
    public static String format(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1_000L;
        long seconds = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        if (totalMinutes < 60L) {
            return String.format(Locale.ROOT, "%d:%02d", totalMinutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d", totalMinutes / 60L,
                totalMinutes % 60L, seconds);
    }
}
