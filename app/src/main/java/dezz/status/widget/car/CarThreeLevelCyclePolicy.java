/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extracts the explicit OFF/1/2/3 domain used by Geely heat and ventilation controls. */
public final class CarThreeLevelCyclePolicy {
    private CarThreeLevelCyclePolicy() {}

    /**
     * Empty means this runtime control is not the three-level kind. AUTO is excluded, but the
     * real vendor OFF option is mandatory so every saved carousel can actually switch the
     * heater/ventilator off.
     */
    @NonNull
    public static List<Double> orderedValues(@NonNull CarControlDescriptor control,
                                             boolean descending) {
        if (control.kind != CarControlDescriptor.Kind.LEVELS) {
            return Collections.emptyList();
        }
        Map<Integer, Double> levels = new HashMap<>();
        Double off = null;
        boolean autoSeen = false;
        for (CarControlDescriptor.Option option : control.options) {
            String label = option.label == null ? "" : option.label.trim();
            if (isOffOption(option)) {
                if (off != null) return Collections.emptyList();
                off = option.value;
            } else if ("1".equals(label)) {
                if (levels.put(1, option.value) != null) return Collections.emptyList();
            } else if ("2".equals(label)) {
                if (levels.put(2, option.value) != null) return Collections.emptyList();
            } else if ("3".equals(label)) {
                if (levels.put(3, option.value) != null) return Collections.emptyList();
            } else if ("auto".equalsIgnoreCase(label)
                    || "авто".equalsIgnoreCase(label)) {
                if (autoSeen) return Collections.emptyList();
                autoSeen = true;
            } else {
                // Fan 0..9, vendor profiles and arbitrary option controls must keep their
                // original generic carousel semantics.
                return Collections.emptyList();
            }
        }
        if (off == null || levels.size() != 3) return Collections.emptyList();
        ArrayList<Double> result = new ArrayList<>(4);
        if (descending) {
            result.add(levels.get(3));
            result.add(levels.get(2));
            result.add(levels.get(1));
            result.add(off);
        } else {
            result.add(off);
            result.add(levels.get(1));
            result.add(levels.get(2));
            result.add(levels.get(3));
        }
        return Collections.unmodifiableList(result);
    }

    /** The vendor value that must remain in a recognized OFF/1/2/3 carousel. */
    @Nullable
    public static Double mandatoryOffValue(@NonNull CarControlDescriptor control) {
        if (orderedValues(control, false).isEmpty()) return null;
        return findOffValue(control);
    }

    /** True only for the mandatory OFF option of a recognized three-level control. */
    public static boolean isMandatoryOffValue(@NonNull CarControlDescriptor control,
                                              double value) {
        Double off = mandatoryOffValue(control);
        return off != null && same(off, value);
    }

    /**
     * Keeps a custom order unchanged when it already contains OFF; otherwise prepends the exact
     * vendor OFF value. Empty means "all runtime values" and remains empty by command contract.
     */
    @NonNull
    public static List<Double> withMandatoryOff(@NonNull CarControlDescriptor control,
                                                @NonNull List<Double> selected) {
        if (selected.isEmpty()) return Collections.emptyList();
        Double off = mandatoryOffValue(control);
        ArrayList<Double> result = new ArrayList<>(selected.size() + 1);
        boolean containsOff = false;
        for (Double value : selected) {
            if (value == null || !Double.isFinite(value)) continue;
            if (!result.contains(value)) result.add(value);
            if (off != null && same(off, value)) containsOff = true;
        }
        if (off != null && !containsOff) result.add(0, off);
        return Collections.unmodifiableList(result);
    }

    @Nullable
    private static Double findOffValue(@NonNull CarControlDescriptor control) {
        for (CarControlDescriptor.Option option : control.options) {
            if (isOffOption(option)) return option.value;
        }
        return null;
    }

    private static boolean isOffOption(@NonNull CarControlDescriptor.Option option) {
        String label = option.label == null ? "" : option.label.trim();
        return "0".equals(label) || "off".equalsIgnoreCase(label)
                || label.toLowerCase(java.util.Locale.ROOT).contains("выкл")
                || Math.abs(option.value) < .000_001d;
    }

    private static boolean same(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right)
                && Math.abs(left - right) < .000_001d;
    }
}
