/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

/** Stable IDs and the explicitly gated KX11 conversion hypothesis for the current trip. */
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
     * The bundled ECARX implementation exposes the raw PA distance as an int without unit
     * metadata. Dividing by ten is the current KX11 hypothesis because it reproduces the stock
     * one-decimal presentation; a paired raw/API/stock-screen capture remains mandatory.
     */
    public static float distanceKilometres(int rawTenthsKilometre) {
        if (rawTenthsKilometre < 0 || rawTenthsKilometre > 100_000_000) return Float.NaN;
        return rawTenthsKilometre / 10f;
    }

    /** Minute interpretation is likewise provisional until the paired KX11 capture is recorded. */
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
