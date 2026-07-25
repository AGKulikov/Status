/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

/** Stable physical slots shared by the nine-step manual and five-step AUTO fan scales. */
public final class ClimateFanScaleGeometry {
    public static final int PHYSICAL_SLOTS = ClimateFanIndicatorPolicy.MANUAL_SEGMENTS;

    private ClimateFanScaleGeometry() {
    }

    /**
     * Maps a logical division onto the fixed nine-position scale. AUTO therefore occupies slots
     * 0, 2, 4, 6 and 8, retaining the same outer edges, bar width and gaps as manual mode.
     */
    public static int physicalSlot(int logicalIndex, int logicalTotal) {
        int total = Math.max(1, Math.min(PHYSICAL_SLOTS, logicalTotal));
        int index = Math.max(0, Math.min(total - 1, logicalIndex));
        if (total == 1) return PHYSICAL_SLOTS / 2;
        return Math.round(index * (PHYSICAL_SLOTS - 1f) / (total - 1f));
    }
}
