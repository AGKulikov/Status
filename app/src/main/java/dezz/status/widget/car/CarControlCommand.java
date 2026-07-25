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
    /**
     * Optional ordered subset for {@link Operation#CYCLE}. Empty preserves the descriptor's
     * complete runtime-supported domain. A command always carries its own immutable copy so a
     * settings edit cannot change an in-flight vehicle request.
     */
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
        List<Double> sanitized = new ArrayList<>();
        for (Double selected : cycleValues) {
            if (selected == null || !Double.isFinite(selected)) continue;
            boolean duplicate = false;
            for (Double existing : sanitized) {
                if (Math.abs(existing - selected) < .01d) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) sanitized.add(selected);
        }
        this.cycleValues = Collections.unmodifiableList(sanitized);
    }
}
