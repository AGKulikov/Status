/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extracts the explicit 1/2/3 manual domain used by Geely heat and ventilation controls. */
public final class CarThreeLevelCyclePolicy {
    private CarThreeLevelCyclePolicy() {}

    /** Empty means this runtime control is not the three-level kind. OFF and AUTO are excluded. */
    @NonNull
    public static List<Double> orderedValues(@NonNull CarControlDescriptor control,
                                             boolean descending) {
        Map<Integer, Double> levels = new HashMap<>();
        for (CarControlDescriptor.Option option : control.options) {
            String label = option.label == null ? "" : option.label.trim();
            if ("1".equals(label)) levels.put(1, option.value);
            else if ("2".equals(label)) levels.put(2, option.value);
            else if ("3".equals(label)) levels.put(3, option.value);
        }
        if (levels.size() != 3) return Collections.emptyList();
        ArrayList<Double> result = new ArrayList<>(3);
        if (descending) {
            result.add(levels.get(3));
            result.add(levels.get(2));
            result.add(levels.get(1));
        } else {
            result.add(levels.get(1));
            result.add(levels.get(2));
            result.add(levels.get(3));
        }
        return Collections.unmodifiableList(result);
    }
}
