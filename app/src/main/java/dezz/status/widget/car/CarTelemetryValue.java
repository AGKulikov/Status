/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One typed numeric sample produced by a vehicle integration.
 *
 * <p>The metric id is the durable identity used by settings. It deliberately does not contain a
 * vendor signal number: a flavor may change its SDK adapter without invalidating the user's
 * bindings. Units are descriptive only; conversion is configured explicitly by
 * {@link CarSprutBinding#scale} and {@link CarSprutBinding#offset}.</p>
 */
public final class CarTelemetryValue {
    private static final Pattern METRIC_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_UNIT_CHARS = 64;

    @NonNull public final String metricId;
    public final double value;
    public final long observedAtMillis;
    @NonNull public final String unit;

    public CarTelemetryValue(@NonNull String metricId, double value, long observedAtMillis,
                             String unit) {
        this.metricId = requireMetricId(metricId);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Vehicle telemetry must be finite");
        }
        if (observedAtMillis < 0L) {
            throw new IllegalArgumentException("observedAtMillis must not be negative");
        }
        this.value = value;
        this.observedAtMillis = observedAtMillis;
        this.unit = bounded(unit, MAX_UNIT_CHARS, "unit");
    }

    /**
     * Vehicle SDKs expose these signals as {@code float}. Preserve the shortest decimal value
     * the SDK actually reported (for example {@code 20.4}) instead of widening its binary bits
     * to {@code 20.399999618530273}; otherwise an exact Sprut {@code minStep=0.1} check would
     * reject an otherwise valid sample.
     */
    public CarTelemetryValue(@NonNull String metricId, float value, long observedAtMillis,
                             String unit) {
        this(metricId, decimalFloat(value), observedAtMillis, unit);
    }

    public CarTelemetryValue(@NonNull String metricId, double value, long observedAtMillis) {
        this(metricId, value, observedAtMillis, "");
    }

    public CarTelemetryValue(@NonNull String metricId, float value, long observedAtMillis) {
        this(metricId, value, observedAtMillis, "");
    }

    private static double decimalFloat(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Vehicle telemetry must be finite");
        }
        return Double.parseDouble(Float.toString(value));
    }

    @NonNull
    static String requireMetricId(String raw) {
        String value = Objects.requireNonNull(raw, "metricId").trim();
        if (!METRIC_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid metric id: " + raw);
        }
        return value;
    }

    @NonNull
    private static String bounded(String raw, int maxChars, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maxChars || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CarTelemetryValue)) return false;
        CarTelemetryValue value = (CarTelemetryValue) other;
        return Double.compare(this.value, value.value) == 0
                && observedAtMillis == value.observedAtMillis
                && metricId.equals(value.metricId)
                && unit.equals(value.unit);
    }

    @Override public int hashCode() {
        return Objects.hash(metricId, value, observedAtMillis, unit);
    }
}
