/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

/** Shared physical geometry for iPhone/CarPlay-inspired phone indicators. */
public final class PhoneIndicatorVisualPolicy {
    private PhoneIndicatorVisualPolicy() {
    }

    /** Gap between the cellular bars and their first textual value. */
    public static int cellularIconTextGapPx(int iconSizePx) {
        return Math.max(6, Math.round(Math.max(1, iconSizePx) * .16f));
    }

    /** Smaller separator between network type and operator when both are visible. */
    public static int cellularTextGapPx(int iconSizePx) {
        return Math.max(3, Math.round(Math.max(1, iconSizePx) * .08f));
    }
}
