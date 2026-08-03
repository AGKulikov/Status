/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

/**
 * Stable fan-scale envelope shared by the nine-step manual and five-step AUTO modes.
 *
 * <p>The outer rectangle never moves. Logical divisions are packed with the same small gap and
 * their width grows when AUTO has only five divisions, avoiding the visually broken empty slots
 * that used to appear between bars.</p>
 */
public final class ClimateFanScaleGeometry {
    public static final int PHYSICAL_SLOTS = ClimateFanIndicatorPolicy.MANUAL_SEGMENTS;

    private ClimateFanScaleGeometry() {
    }

    /** Logical divisions are contiguous in both modes; the containing envelope remains fixed. */
    public static int physicalSlot(int logicalIndex, int logicalTotal) {
        int total = Math.max(1, Math.min(PHYSICAL_SLOTS, logicalTotal));
        return Math.max(0, Math.min(total - 1, logicalIndex));
    }

    public static float segmentWidth(float availableWidth, float gap, int logicalTotal) {
        int total = Math.max(1, Math.min(PHYSICAL_SLOTS, logicalTotal));
        float safeWidth = Math.max(1f, availableWidth);
        float safeGap = Math.max(0f, gap);
        return Math.max(.75f, (safeWidth - safeGap * (total - 1)) / total);
    }
}
