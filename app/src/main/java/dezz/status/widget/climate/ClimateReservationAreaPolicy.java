/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

/** Pure verification policy for an OEM system-bar reservation. */
final class ClimateReservationAreaPolicy {
    /** WindowManager policies can round a bar by a few physical pixels. */
    private static final int SIZE_TOLERANCE_PX = 4;

    private ClimateReservationAreaPolicy() {}

    static boolean isReserved(int edge, int extentPx,
                              int beforeWidth, int beforeHeight,
                              int afterWidth, int afterHeight) {
        if (extentPx <= 0 || beforeWidth <= 0 || beforeHeight <= 0
                || afterWidth <= 0 || afterHeight <= 0) {
            return false;
        }
        int widthReduction = beforeWidth - afterWidth;
        int heightReduction = beforeHeight - afterHeight;
        if (edge == ClimateReservationWindowPolicy.EDGE_TOP
                || edge == ClimateReservationWindowPolicy.EDGE_BOTTOM) {
            return closeTo(heightReduction, extentPx)
                    && closeTo(widthReduction, 0);
        }
        return closeTo(widthReduction, extentPx)
                && closeTo(heightReduction, 0);
    }

    private static boolean closeTo(int actual, int expected) {
        return Math.abs((long) actual - expected) <= SIZE_TOLERANCE_PX;
    }
}
