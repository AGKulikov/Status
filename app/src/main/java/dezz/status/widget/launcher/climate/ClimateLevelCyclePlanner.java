/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.car.CarControlDescriptor;

/** Pure, deterministic manual-level cycle used by the five configurable climate buttons. */
public final class ClimateLevelCyclePlanner {
    private static final double EPSILON = 0.000_001d;

    private ClimateLevelCyclePlanner() {
    }

    /**
     * Returns the next value in {@code off → levels → off}. Auto is deliberately not mixed
     * into the manual 1/2/3 cycle: pressing a manual control while it is in Auto first turns that
     * function off. This mirrors the safe part of mSaver's ECARX handling and makes the following
     * press start exactly at the direction selected by the user.
     *
     * @param direction positive for the configured direction, negative for the opposite direction
     */
    @Nullable
    public static Double nextTarget(@NonNull List<CarControlDescriptor.Option> source,
                                    double current,
                                    @NonNull ClimatePanelConfig.LevelCycleOrder order,
                                    int direction) {
        if (source.isEmpty() || direction == 0) return null;

        CarControlDescriptor.Option off = null;
        CarControlDescriptor.Option auto = null;
        ArrayList<NumberedOption> numbered = new ArrayList<>();
        ArrayList<CarControlDescriptor.Option> fallbackLevels = new ArrayList<>();
        for (CarControlDescriptor.Option option : source) {
            String label = option.label == null ? "" : option.label.trim();
            String normalized = label.toLowerCase(Locale.ROOT);
            if (isOff(normalized, option.value)) {
                if (off == null) off = option;
                continue;
            }
            if (normalized.contains("auto") || normalized.contains("авто")) {
                if (auto == null) auto = option;
                continue;
            }
            Integer level = parsePositiveInteger(label);
            if (level != null) numbered.add(new NumberedOption(option, level));
            else fallbackLevels.add(option);
        }

        numbered.sort(Comparator.comparingInt(value -> value.level));
        fallbackLevels.sort(Comparator.comparingDouble(value -> value.value));
        ArrayList<CarControlDescriptor.Option> levels = new ArrayList<>();
        for (NumberedOption value : numbered) levels.add(value.option);
        levels.addAll(fallbackLevels);
        if (order == ClimatePanelConfig.LevelCycleOrder.DESCENDING) {
            java.util.Collections.reverse(levels);
        }

        ArrayList<CarControlDescriptor.Option> sequence = new ArrayList<>();
        if (off != null) sequence.add(off);
        sequence.addAll(levels);
        if (sequence.isEmpty()) return null;

        // Auto is outside the user's manual order. A first press exits it safely; a second press
        // starts 1→2→3 or 3→2→1 from the confirmed OFF value.
        if (auto != null && same(auto.value, current)) {
            return off == null ? sequence.get(0).value : off.value;
        }

        int currentIndex = findValue(sequence, current);
        if (currentIndex < 0) {
            // Unknown vendor extension: converge to OFF rather than guessing a heat level.
            return off == null ? sequence.get(0).value : off.value;
        }
        int step = direction > 0 ? 1 : -1;
        int next = (currentIndex + step + sequence.size()) % sequence.size();
        return sequence.get(next).value;
    }

    /**
     * Plans a +/- step without mixing manual and AUTO value domains. This distinction matters for
     * the Geely fan: manual levels 0..9 and AUTO intensity profiles are backed by two different
     * AdaptAPI functions even though the panel presents them as one logical control.
     *
     * <p>When {@code includeAuto} is false, an AUTO observation converges to OFF exactly like the
     * manual heater/ventilation cycle above. When true, +/- walks only the available AUTO profiles
     * while the climate is in AUTO; manual mode still walks only manual levels.</p>
     */
    @Nullable
    public static Double nextStepperTarget(
            @NonNull List<CarControlDescriptor.Option> source,
            double current, int direction, boolean includeAuto) {
        if (source.isEmpty() || direction == 0) return null;

        CarControlDescriptor.Option off = null;
        ArrayList<CarControlDescriptor.Option> manual = new ArrayList<>();
        ArrayList<CarControlDescriptor.Option> automatic = new ArrayList<>();
        for (CarControlDescriptor.Option option : source) {
            String label = option.label == null ? "" : option.label.trim();
            String normalized = label.toLowerCase(Locale.ROOT);
            if (isOff(normalized, option.value)) {
                if (off == null) off = option;
                manual.add(option);
            } else if (normalized.contains("auto") || normalized.contains("авто")) {
                automatic.add(option);
            } else {
                manual.add(option);
            }
        }
        manual.sort(Comparator.comparingDouble(value -> value.value));

        int automaticIndex = findValue(automatic, current);
        if (automaticIndex >= 0) {
            if (!includeAuto) return off == null ? firstValue(manual) : off.value;
            int step = direction > 0 ? 1 : -1;
            int next = (automaticIndex + step + automatic.size()) % automatic.size();
            return automatic.get(next).value;
        }

        int manualIndex = findValue(manual, current);
        if (manualIndex < 0) {
            return off == null ? firstValue(manual) : off.value;
        }
        int next = Math.max(0, Math.min(manual.size() - 1,
                manualIndex + (direction > 0 ? 1 : -1)));
        return manual.get(next).value;
    }

    @Nullable
    private static Double firstValue(@NonNull List<CarControlDescriptor.Option> values) {
        return values.isEmpty() ? null : values.get(0).value;
    }

    private static int findValue(@NonNull List<CarControlDescriptor.Option> values,
                                 double target) {
        for (int index = 0; index < values.size(); index++) {
            if (same(values.get(index).value, target)) return index;
        }
        return -1;
    }

    private static boolean isOff(@NonNull String label, double value) {
        return label.contains("off") || label.contains("выкл") || same(value, 0d);
    }

    @Nullable
    private static Integer parsePositiveInteger(@NonNull String label) {
        try {
            int value = Integer.parseInt(label);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean same(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right)
                && Math.abs(left - right) <= EPSILON;
    }

    private static final class NumberedOption {
        @NonNull final CarControlDescriptor.Option option;
        final int level;

        NumberedOption(@NonNull CarControlDescriptor.Option option, int level) {
            this.option = option;
            this.level = level;
        }
    }
}
