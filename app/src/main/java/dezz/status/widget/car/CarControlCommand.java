/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A validated, vendor-neutral command selected by the visual HOME editor. */
public final class CarControlCommand {
    public enum Operation { TOGGLE, CYCLE, SET, ACTIVATE }

    @NonNull public final String controlId;
    @NonNull public final Operation operation;
    public final double value;
    /** Optional ordered subset used only by CYCLE; empty means all runtime-supported values. */
    @NonNull public final List<Double> cycleValues;

    public CarControlCommand(@NonNull String controlId, @NonNull Operation operation,
                             double value) {
        this(controlId, operation, value, Collections.emptyList());
    }

    public CarControlCommand(@NonNull String controlId, @NonNull Operation operation,
                             double value, @NonNull List<Double> cycleValues) {
        this.controlId = controlId;
        this.operation = operation;
        this.value = value;
        ArrayList<Double> selected = new ArrayList<>();
        for (Double candidate : cycleValues) {
            if (candidate == null || !Double.isFinite(candidate)
                    || selected.contains(candidate)) continue;
            selected.add(candidate);
        }
        this.cycleValues = Collections.unmodifiableList(selected);
    }
}
