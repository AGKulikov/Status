/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

/** Stable IDs and KX11 conversion rules for the stock “Trip 2” PA fields. */
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

    /**
     * {@code PA_TS_OdometerTripMeter2} is the dedicated Trip 2 distance, not the vehicle's
     * lifetime odometer. The one-decimal scale remains subject to the paired KX11 screen gate.
     */
    public static float distanceKilometres(int rawTenthsKilometre) {
        if (rawTenthsKilometre < 0 || rawTenthsKilometre > 100_000_000) return Float.NaN;
        return rawTenthsKilometre / 10f;
    }

    /** Dedicated Trip 2 time; the minute interpretation remains subject to the KX11 gate. */
    public static float durationMinutes(long rawMinutes) {
        if (rawMinutes < 0L || rawMinutes > 10L * 365L * 24L * 60L) return Float.NaN;
        return rawMinutes;
    }

    /** Same-snapshot derived value; zero elapsed time cannot produce a meaningful average. */
    public static float averageSpeedKmh(float distanceKilometres, float durationMinutes) {
        if (!Float.isFinite(distanceKilometres) || !Float.isFinite(durationMinutes)
                || distanceKilometres < 0f || durationMinutes <= 0f) return Float.NaN;
        float value = distanceKilometres * 60f / durationMinutes;
        return Float.isFinite(value) && value >= 0f && value <= 500f ? value : Float.NaN;
    }

    public static boolean isMetricId(String id) {
        return DISTANCE_ID.equals(id) || DURATION_ID.equals(id)
                || AVERAGE_SPEED_ID.equals(id);
    }

    private CurrentTripMetrics() {}
}
