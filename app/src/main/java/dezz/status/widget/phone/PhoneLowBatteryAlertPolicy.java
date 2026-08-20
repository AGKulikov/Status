/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Exact-integer, two-stage low-battery threshold policy with recovery hysteresis. */
public final class PhoneLowBatteryAlertPolicy {
    private static final int MIN_THRESHOLD = 1;
    private static final int MAX_THRESHOLD = 100;
    private static final int RECOVERY_HYSTERESIS_PERCENT = 2;

    private PhoneLowBatteryAlertPolicy() {
    }

    public static int boundedThreshold(int threshold) {
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, threshold));
    }

    /** The second warning must be strictly lower so one descending charge can fire both stages. */
    public static boolean validOrderedThresholds(int first, int second) {
        int upper = boundedThreshold(first);
        int lower = boundedThreshold(second);
        return first == upper && second == lower && lower < upper;
    }

    /**
     * Triggers once when a fresh exact integer is at or below the configured threshold. The latch resets only
     * after recovery by two percentage points, preventing repeated alerts around a noisy boundary.
     */
    @NonNull
    public static Result evaluate(boolean enabled, int threshold, boolean latched,
                                  @Nullable Integer batteryLevel) {
        if (!enabled) return new Result(false, false);
        if (batteryLevel == null || batteryLevel < 0 || batteryLevel > 100) {
            return new Result(latched, false);
        }
        int bounded = boundedThreshold(threshold);
        if (batteryLevel <= bounded) {
            return latched ? new Result(true, false) : new Result(true, true);
        }
        if (batteryLevel >= Math.min(100, bounded + RECOVERY_HYSTERESIS_PERCENT)) {
            return new Result(false, false);
        }
        return new Result(latched, false);
    }

    public static final class Result {
        public final boolean latched;
        public final boolean trigger;

        private Result(boolean latched, boolean trigger) {
            this.latched = latched;
            this.trigger = trigger;
        }
    }
}
