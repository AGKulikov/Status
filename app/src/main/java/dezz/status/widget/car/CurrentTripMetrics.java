/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

/** Stable IDs and KX11 conversion rules for the stock instrument-cluster “Trip 2” signals. */
public final class CurrentTripMetrics {
    public static final String DISTANCE_ID = "Trip.current_distance_km";
    public static final String DURATION_ID = "Trip.current_duration_minutes";
    public static final String AVERAGE_SPEED_ID = "Derived.current_trip_average_speed";
    /** A disconnected ECARX trip callback must not leave yesterday's trip on a live surface. */
    public static final long STALE_AFTER_MILLIS = 10L * 60L * 1_000L;
    public static final long STALE_AFTER_NANOS = STALE_AFTER_MILLIS * 1_000_000L;

    public static final String DISTANCE_LABEL = "Пробег текущей поездки";
    public static final String DURATION_LABEL = "Время текущей поездки";
    public static final String AVERAGE_SPEED_LABEL = "Средняя скорость текущей поездки";

    /** {@code DstTrvld2} is encoded in tenths of a kilometre on the KX11 cluster path. */
    public static float distanceKilometres(int rawTenthsKilometre) {
        if (rawTenthsKilometre < 0 || rawTenthsKilometre > 100_000_000) return Float.NaN;
        return rawTenthsKilometre / 10f;
    }

    /**
     * Converts the stock indicated average-speed signal to km/h. The stable metric ID is retained
     * so existing layouts survive the source correction from the rejected derived PA value.
     */
    public static float averageSpeedKmh(int rawSpeed, int rawUnit) {
        if (rawSpeed < 0 || rawSpeed > 500) return Float.NaN;
        float value = rawUnit == 0 ? rawSpeed
                : rawUnit == 1 ? rawSpeed * 1.609344f : Float.NaN;
        return Float.isFinite(value) && value <= 500f ? value : Float.NaN;
    }

    /** The stock elapsed-time row is reconstructed from its own distance/average pair. */
    public static float durationMinutes(float distanceKilometres, float averageSpeedKmh) {
        if (!Float.isFinite(distanceKilometres) || !Float.isFinite(averageSpeedKmh)
                || distanceKilometres < 0f || averageSpeedKmh < 0f) return Float.NaN;
        if (distanceKilometres == 0f && averageSpeedKmh == 0f) return 0f;
        if (averageSpeedKmh == 0f) return Float.NaN;
        float value = distanceKilometres * 60f / averageSpeedKmh;
        return Float.isFinite(value) && value >= 0f
                && value <= 10f * 365f * 24f * 60f ? value : Float.NaN;
    }

    public static boolean validRawSignalPair(int distanceRaw, int averageSpeedRaw,
                                             int speedUnitRaw) {
        return Float.isFinite(distanceKilometres(distanceRaw))
                && Float.isFinite(averageSpeedKmh(averageSpeedRaw, speedUnitRaw));
    }

    public static boolean isMetricId(String id) {
        return DISTANCE_ID.equals(id) || DURATION_ID.equals(id)
                || AVERAGE_SPEED_ID.equals(id);
    }

    private CurrentTripMetrics() {}
}
