/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Shared presentation policy for the confirmed fan state used by every climate surface.
 *
 * <p>{@code climate.fan} is already routed by the Geely integration to the correct vendor
 * function for the confirmed climate mode. Manual speed therefore arrives as levels 0..9,
 * while AUTO arrives as one of the vehicle's named intensity profiles. Consumers must not
 * read or infer a second fan mode independently.</p>
 */
public final class ClimateFanIndicatorPolicy {
    public static final int MANUAL_SEGMENTS = 9;
    public static final int AUTO_SEGMENTS = 5;

    public static final class Indicator {
        public final boolean automatic;
        public final int activeSegments;
        public final int totalSegments;

        Indicator(boolean automatic, int activeSegments, int totalSegments) {
            this.automatic = automatic;
            this.activeSegments = activeSegments;
            this.totalSegments = totalSegments;
        }
    }

    private ClimateFanIndicatorPolicy() {
    }

    @NonNull
    public static Indicator fromConfirmedState(@Nullable String valueLabel, int level) {
        if (!isAutomaticLabel(valueLabel)) {
            return new Indicator(false, clamp(level, 0, MANUAL_SEGMENTS), MANUAL_SEGMENTS);
        }
        return new Indicator(true, automaticSegments(valueLabel, level), AUTO_SEGMENTS);
    }

    public static boolean isAutomaticLabel(@Nullable String valueLabel) {
        if (valueLabel == null) return false;
        String normalized = valueLabel.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("auto") || normalized.contains("авто");
    }

    private static int automaticSegments(@Nullable String valueLabel, int profileIndex) {
        String normalized = valueLabel == null
                ? "" : valueLabel.trim().toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "тихо", "тише", "silent", "quiet", "quieter")) return 1;
        if (containsAny(normalized, "обычно", "normal")) return 3;
        if (containsAny(normalized, "интенсив", "выше", "high", "higher")) return 5;

        // Unknown labels retain the three-profile 1/3/5 visual convention. Known two-profile
        // labels are handled above, so their upper profile still lights all five segments.
        return clamp(profileIndex * 2 + 1, 1, AUTO_SEGMENTS);
    }

    private static boolean containsAny(@NonNull String value, @NonNull String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
