/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.car;

import androidx.annotation.NonNull;

/** A validated, vendor-neutral command selected by the visual HOME editor. */
public final class CarControlCommand {
    public enum Operation { TOGGLE, CYCLE, SET, ACTIVATE }

    @NonNull public final String controlId;
    @NonNull public final Operation operation;
    public final double value;

    public CarControlCommand(@NonNull String controlId, @NonNull Operation operation,
                             double value) {
        this.controlId = controlId;
        this.operation = operation;
        this.value = value;
    }
}
