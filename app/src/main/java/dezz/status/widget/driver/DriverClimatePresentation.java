/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;
import java.util.Locale;

/** Pure formatting/mapping policy for the live climate shortcut. */
public final class DriverClimatePresentation {
    private DriverClimatePresentation() {
    }

    @NonNull
    public static String temperature(double value, boolean known) {
        if (!known || !Double.isFinite(value)) return "—";
        return String.format(Locale.ROOT, "%.1f", value);
    }

}
