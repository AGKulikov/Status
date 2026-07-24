/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Latest confirmed state of a vehicle function, normalized for presentation. */
public final class CarControlState {
    @NonNull public final String controlId;
    public final boolean available;
    public final boolean known;
    public final double value;
    @NonNull public final String valueLabel;
    public final boolean active;
    public final int level;
    @Nullable public final String suggestedColor;
    public final long observedAtMillis;

    public CarControlState(@NonNull String controlId, boolean available, boolean known,
                           double value, @NonNull String valueLabel, boolean active,
                           int level, @Nullable String suggestedColor,
                           long observedAtMillis) {
        this.controlId = controlId;
        this.available = available;
        this.known = known;
        this.value = value;
        this.valueLabel = valueLabel;
        this.active = active;
        this.level = level;
        this.suggestedColor = suggestedColor;
        this.observedAtMillis = observedAtMillis;
    }
}
