/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Vendor-neutral description of one controllable vehicle function. */
public final class CarControlDescriptor {
    public enum Kind { TOGGLE, LEVELS, OPTIONS, RANGE, ACTION }
    public enum Availability { SUPPORTED, UNSUPPORTED, UNKNOWN }

    public static final class Option {
        public final double value;
        @NonNull public final String label;

        public Option(double value, @NonNull String label) {
            this.value = value;
            this.label = label;
        }

        @Override public String toString() { return label; }
    }

    @NonNull public final String id;
    @NonNull public final String label;
    @NonNull public final String category;
    @NonNull public final String iconKey;
    @NonNull public final Kind kind;
    @NonNull public final Availability availability;
    @NonNull public final List<Option> options;
    public final double minimum;
    public final double maximum;
    public final double step;
    @NonNull public final String unit;
    @NonNull public final String suggestedActiveColor;

    public CarControlDescriptor(@NonNull String id, @NonNull String label,
                                @NonNull String category, @NonNull String iconKey,
                                @NonNull Kind kind, @NonNull Availability availability,
                                @NonNull List<Option> options,
                                double minimum, double maximum, double step,
                                @NonNull String unit,
                                @NonNull String suggestedActiveColor) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.iconKey = iconKey;
        this.kind = kind;
        this.availability = availability;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.unit = unit;
        this.suggestedActiveColor = suggestedActiveColor;
    }

    @NonNull
    public CarControlDescriptor withAvailability(@NonNull Availability value) {
        return new CarControlDescriptor(id, label, category, iconKey, kind, value, options,
                minimum, maximum, step, unit, suggestedActiveColor);
    }

    @Override public String toString() { return label; }
}
