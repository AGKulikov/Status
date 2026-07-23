/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Connector-neutral description of one vehicle value that can be displayed or exported.
 *
 * <p>{@code staleAfterMillis == 0} means that the value is stateful and remains authoritative
 * until the vehicle reports another value (for example gear or ignition state). A non-zero value
 * is a hint to presentation layers that a continuously sampled reading should be marked stale
 * after that amount of time without an update.</p>
 */
public final class CarTelemetryDescriptor {
    @NonNull public final String id;
    @NonNull public final String label;
    @NonNull public final String unit;
    public final boolean streaming;
    public final long staleAfterMillis;

    public CarTelemetryDescriptor(@NonNull String id, @NonNull String label,
                                  @NonNull String unit, boolean streaming,
                                  long staleAfterMillis) {
        this.id = requireText(id, "id");
        this.label = requireText(label, "label");
        this.unit = Objects.requireNonNull(unit, "unit");
        if (staleAfterMillis < 0L) {
            throw new IllegalArgumentException("staleAfterMillis must be non-negative");
        }
        this.streaming = streaming;
        this.staleAfterMillis = staleAfterMillis;
    }

    @NonNull
    private static String requireText(@NonNull String value, @NonNull String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return trimmed;
    }
}
