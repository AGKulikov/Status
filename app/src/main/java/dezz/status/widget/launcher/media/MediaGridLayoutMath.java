/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

/** Pure integer grid calculations shared by the HOME renderer and unit tests. */
public final class MediaGridLayoutMath {
    private MediaGridLayoutMath() {}

    public static int clampSpan(int span, int count) {
        if (count <= 0) return 1;
        return Math.max(1, Math.min(count, span));
    }

    public static int clampStart(int start, int span, int count) {
        int safeCount = Math.max(1, count);
        int safeSpan = clampSpan(span, safeCount);
        return Math.max(0, Math.min(safeCount - safeSpan, start));
    }

    /** Start of a cell, excluding outer padding but including inter-cell spacing. */
    public static int startPx(int availablePx, int spacingPx, int count, int cell) {
        int safeCount = Math.max(1, count);
        int usable = Math.max(0, availablePx - Math.max(0, spacingPx) * (safeCount - 1));
        int safeCell = Math.max(0, Math.min(safeCount, cell));
        return Math.round(usable * safeCell / (float) safeCount)
                + Math.max(0, spacingPx) * safeCell;
    }

    public static int spanPx(int availablePx, int spacingPx, int count, int start, int span) {
        int safeSpan = clampSpan(span, Math.max(1, count));
        int safeStart = clampStart(start, safeSpan, Math.max(1, count));
        int left = startPx(availablePx, spacingPx, count, safeStart);
        int right = startPx(availablePx, spacingPx, count, safeStart + safeSpan);
        return Math.max(1, right - left - Math.max(0, spacingPx));
    }

    /** Nearest valid cell for a dragged element's leading edge. */
    public static int startForPx(int pixel, int availablePx, int spacingPx,
                                 int count, int span) {
        int safeCount = Math.max(1, count);
        int safeSpan = clampSpan(span, safeCount);
        int maximum = safeCount - safeSpan;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int cell = 0; cell <= maximum; cell++) {
            int distance = Math.abs(pixel - startPx(availablePx, spacingPx, safeCount, cell));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cell;
            }
        }
        return best;
    }

    /** Nearest valid span when an element's bottom/right resize handle is dragged. */
    public static int spanForEndPx(int pixel, int availablePx, int spacingPx,
                                   int count, int start) {
        int safeCount = Math.max(1, count);
        int safeStart = Math.max(0, Math.min(safeCount - 1, start));
        int bestSpan = 1;
        int bestDistance = Integer.MAX_VALUE;
        for (int end = safeStart + 1; end <= safeCount; end++) {
            int edge = startPx(availablePx, spacingPx, safeCount, end)
                    - Math.max(0, spacingPx);
            int distance = Math.abs(pixel - edge);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSpan = end - safeStart;
            }
        }
        return bestSpan;
    }
}
