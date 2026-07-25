/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;

/** Pure formatting/mapping policy for the live climate shortcut. */
public final class DriverClimatePresentation {
    public static final int AIRFLOW_FACE = 1;
    public static final int AIRFLOW_LEGS = 1 << 1;
    public static final int AIRFLOW_WINDSHIELD = 1 << 2;

    private DriverClimatePresentation() {
    }

    @NonNull
    public static String temperature(double value, boolean known) {
        if (!known || !Double.isFinite(value)) return "—";
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * Converts the normalized ECARX option label into independently drawable outlet targets.
     * Combinations are bitwise, so the driver shortcut can stay icon-only without maintaining a
     * second copy of vendor numeric constants in the common (non-Geely) source set.
     */
    public static int airflowTargets(@Nullable String valueLabel) {
        if (valueLabel == null) return 0;
        String normalized = valueLabel.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.contains("auto")
                || normalized.contains("авто") || normalized.contains("выкл")
                || normalized.contains("off")) {
            return 0;
        }
        int result = 0;
        if (normalized.contains("лиц") || normalized.contains("face")) {
            result |= AIRFLOW_FACE;
        }
        if (normalized.contains("ног") || normalized.contains("leg")
                || normalized.contains("feet")) {
            result |= AIRFLOW_LEGS;
        }
        if (normalized.contains("стекл") || normalized.contains("window")
                || normalized.contains("windshield")) {
            result |= AIRFLOW_WINDSHIELD;
        }
        return result;
    }

    /**
     * A fresh climate.auto observation is authoritative. During its brief Binder boot gap the
     * already-normalized AUTO fan label keeps the shortcut from flashing a manual direction.
     */
    public static boolean automatic(boolean autoKnown, boolean autoActive,
                                    @Nullable String fanValueLabel) {
        if (autoKnown) return autoActive;
        if (fanValueLabel == null) return false;
        String normalized = fanValueLabel.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("auto") || normalized.contains("авто");
    }

}
