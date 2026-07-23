/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

/** Exact conversion between the percent UI and Android's discrete STREAM_MUSIC steps. */
public final class MediaVolumeMath {
    private MediaVolumeMath() {}

    public static int stepForPercent(int percent, int maximumStep) {
        if (maximumStep <= 0) return 0;
        int safePercent = Math.max(0, Math.min(100, percent));
        return Math.max(0, Math.min(maximumStep,
                Math.round(safePercent * maximumStep / 100f)));
    }

    public static int percentForStep(int step, int maximumStep) {
        if (maximumStep <= 0) return 0;
        int safeStep = Math.max(0, Math.min(maximumStep, step));
        return Math.max(0, Math.min(100,
                Math.round(safeStep * 100f / maximumStep)));
    }
}
