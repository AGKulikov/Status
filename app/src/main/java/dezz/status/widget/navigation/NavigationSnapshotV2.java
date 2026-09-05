/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable route state consumed by every independently positioned HUD navigation element. */
public final class NavigationSnapshotV2 {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_CHARS = 96 * 1024;

    public final long sequence;
    public final long routeEpoch;
    public final long sourceTimestampMs;
    public final boolean routeActive;
    public final double latitude;
    public final double longitude;
    public final double bearingDegrees;
    public final double speedKmh;
    @NonNull public final String maneuverType;
    @NonNull public final String maneuverTitle;
    @NonNull public final String maneuverSubtext;
    @NonNull public final String maneuverIdentity;
    @NonNull public final String maneuverNextRoad;
    @NonNull public final String maneuverDirectionSignsJson;
    @NonNull public final String maneuverAuxiliaryType;
    @NonNull public final String maneuverAuxiliaryText;
    @NonNull public final String maneuverAuxiliaryManeuverType;
    public final int maneuverAuxiliaryDistanceMeters;
    /** Exact distance label currently shown beside Navigator's stock maneuver artwork. */
    @NonNull public final String maneuverDisplayDistance;
    @NonNull public final String maneuverCardJson;
    @NonNull public final String street;
    @NonNull public final String destination;
    public final int maneuverDistanceMeters;
    public final int routeTotalDistanceMeters;
    public final int remainingDistanceMeters;
    public final int remainingDurationSeconds;
    /** Nullable Guidance.leftInTrafficJam() encoded as -1/-1 when Navigator hides its card. */
    public final int trafficJamDurationSeconds;
    public final int trafficJamDistanceMeters;
    public final long arrivalEpochMs;
    public final int speedLimitKmh;
    public final int laneDistanceMeters;
    @NonNull public final String lanesJson;
    @NonNull public final String trafficLightsJson;

    /** Wall-clock liveness check for the same-device Navigator bridge. */
    public boolean isFreshAt(long nowEpochMs, long maximumAgeMs) {
        if (sourceTimestampMs <= 0L || maximumAgeMs <= 0L) return false;
        long age = nowEpochMs - sourceTimestampMs;
        // A small forward skew is tolerated across the two application processes. Larger clock
        // changes fail closed so an old route can never remain on HUD indefinitely.
        return age >= -5_000L && age <= maximumAgeMs;
    }

    public NavigationSnapshotV2(long sequence, long routeEpoch, long sourceTimestampMs,
            boolean routeActive, double latitude, double longitude, double bearingDegrees,
            double speedKmh, @NonNull String maneuverType, @NonNull String maneuverTitle,
            @NonNull String maneuverSubtext, @NonNull String street,
            @NonNull String destination, int maneuverDistanceMeters,
            int remainingDistanceMeters, int remainingDurationSeconds, long arrivalEpochMs,
            int speedLimitKmh, @NonNull String lanesJson,
            @NonNull String trafficLightsJson) {
        this(sequence, routeEpoch, sourceTimestampMs, routeActive, latitude, longitude,
                bearingDegrees, speedKmh, maneuverType, maneuverTitle, maneuverSubtext, street,
                destination, maneuverDistanceMeters, -1, remainingDistanceMeters,
                remainingDurationSeconds, -1, -1, arrivalEpochMs, speedLimitKmh, -1,
                lanesJson, trafficLightsJson);
    }

    public NavigationSnapshotV2(long sequence, long routeEpoch, long sourceTimestampMs,
            boolean routeActive, double latitude, double longitude, double bearingDegrees,
            double speedKmh, @NonNull String maneuverType, @NonNull String maneuverTitle,
            @NonNull String maneuverSubtext, @NonNull String street,
            @NonNull String destination, int maneuverDistanceMeters,
            int routeTotalDistanceMeters, int remainingDistanceMeters,
            int remainingDurationSeconds, long arrivalEpochMs, int speedLimitKmh,
            int laneDistanceMeters, @NonNull String lanesJson,
            @NonNull String trafficLightsJson) {
        this(sequence, routeEpoch, sourceTimestampMs, routeActive, latitude, longitude,
                bearingDegrees, speedKmh, maneuverType, maneuverTitle, maneuverSubtext, street,
                destination, maneuverDistanceMeters, routeTotalDistanceMeters,
                remainingDistanceMeters, remainingDurationSeconds, -1, -1, arrivalEpochMs,
                speedLimitKmh, laneDistanceMeters, lanesJson, trafficLightsJson);
    }

    public NavigationSnapshotV2(long sequence, long routeEpoch, long sourceTimestampMs,
            boolean routeActive, double latitude, double longitude, double bearingDegrees,
            double speedKmh, @NonNull String maneuverType, @NonNull String maneuverTitle,
            @NonNull String maneuverSubtext, @NonNull String street,
            @NonNull String destination, int maneuverDistanceMeters,
            int routeTotalDistanceMeters, int remainingDistanceMeters,
            int remainingDurationSeconds, int trafficJamDurationSeconds,
            int trafficJamDistanceMeters, long arrivalEpochMs, int speedLimitKmh,
            int laneDistanceMeters, @NonNull String lanesJson,
            @NonNull String trafficLightsJson) {
        this(sequence, routeEpoch, sourceTimestampMs, routeActive, latitude, longitude,
                bearingDegrees, speedKmh, maneuverType, maneuverTitle, maneuverSubtext, street,
                destination, maneuverDistanceMeters, routeTotalDistanceMeters,
                remainingDistanceMeters, remainingDurationSeconds, trafficJamDurationSeconds,
                trafficJamDistanceMeters, arrivalEpochMs, speedLimitKmh, laneDistanceMeters,
                lanesJson, trafficLightsJson, "", "", "[]", "", "", "", -1, "");
    }

    public NavigationSnapshotV2(long sequence, long routeEpoch, long sourceTimestampMs,
            boolean routeActive, double latitude, double longitude, double bearingDegrees,
            double speedKmh, @NonNull String maneuverType, @NonNull String maneuverTitle,
            @NonNull String maneuverSubtext, @NonNull String street,
            @NonNull String destination, int maneuverDistanceMeters,
            int routeTotalDistanceMeters, int remainingDistanceMeters,
            int remainingDurationSeconds, int trafficJamDurationSeconds,
            int trafficJamDistanceMeters, long arrivalEpochMs, int speedLimitKmh,
            int laneDistanceMeters, @NonNull String lanesJson,
            @NonNull String trafficLightsJson, @NonNull String maneuverIdentity,
            @NonNull String maneuverNextRoad, @NonNull String maneuverDirectionSignsJson,
            @NonNull String maneuverAuxiliaryType, @NonNull String maneuverAuxiliaryText,
            @NonNull String maneuverAuxiliaryManeuverType,
            int maneuverAuxiliaryDistanceMeters) {
        this(sequence, routeEpoch, sourceTimestampMs, routeActive, latitude, longitude,
                bearingDegrees, speedKmh, maneuverType, maneuverTitle, maneuverSubtext, street,
                destination, maneuverDistanceMeters, routeTotalDistanceMeters,
                remainingDistanceMeters, remainingDurationSeconds, trafficJamDurationSeconds,
                trafficJamDistanceMeters, arrivalEpochMs, speedLimitKmh, laneDistanceMeters,
                lanesJson, trafficLightsJson, maneuverIdentity, maneuverNextRoad,
                maneuverDirectionSignsJson, maneuverAuxiliaryType, maneuverAuxiliaryText,
                maneuverAuxiliaryManeuverType, maneuverAuxiliaryDistanceMeters, "");
    }

    public NavigationSnapshotV2(long sequence, long routeEpoch, long sourceTimestampMs,
            boolean routeActive, double latitude, double longitude, double bearingDegrees,
            double speedKmh, @NonNull String maneuverType, @NonNull String maneuverTitle,
            @NonNull String maneuverSubtext, @NonNull String street,
            @NonNull String destination, int maneuverDistanceMeters,
            int routeTotalDistanceMeters, int remainingDistanceMeters,
            int remainingDurationSeconds, int trafficJamDurationSeconds,
            int trafficJamDistanceMeters, long arrivalEpochMs, int speedLimitKmh,
            int laneDistanceMeters, @NonNull String lanesJson,
            @NonNull String trafficLightsJson, @NonNull String maneuverIdentity,
            @NonNull String maneuverNextRoad, @NonNull String maneuverDirectionSignsJson,
            @NonNull String maneuverAuxiliaryType, @NonNull String maneuverAuxiliaryText,
            @NonNull String maneuverAuxiliaryManeuverType,
            int maneuverAuxiliaryDistanceMeters, @NonNull String maneuverDisplayDistance) {
        this(sequence, routeEpoch, sourceTimestampMs, routeActive, latitude, longitude,
                bearingDegrees, speedKmh, maneuverType, maneuverTitle, maneuverSubtext, street,
                destination, maneuverDistanceMeters, routeTotalDistanceMeters,
                remainingDistanceMeters, remainingDurationSeconds, trafficJamDurationSeconds,
                trafficJamDistanceMeters, arrivalEpochMs, speedLimitKmh, laneDistanceMeters,
                lanesJson, trafficLightsJson, maneuverIdentity, maneuverNextRoad,
                maneuverDirectionSignsJson, maneuverAuxiliaryType, maneuverAuxiliaryText,
                maneuverAuxiliaryManeuverType, maneuverAuxiliaryDistanceMeters,
                maneuverDisplayDistance, "");
    }

    public NavigationSnapshotV2(long sequence, long routeEpoch, long sourceTimestampMs,
            boolean routeActive, double latitude, double longitude, double bearingDegrees,
            double speedKmh, @NonNull String maneuverType, @NonNull String maneuverTitle,
            @NonNull String maneuverSubtext, @NonNull String street,
            @NonNull String destination, int maneuverDistanceMeters,
            int routeTotalDistanceMeters, int remainingDistanceMeters,
            int remainingDurationSeconds, int trafficJamDurationSeconds,
            int trafficJamDistanceMeters, long arrivalEpochMs, int speedLimitKmh,
            int laneDistanceMeters, @NonNull String lanesJson,
            @NonNull String trafficLightsJson, @NonNull String maneuverIdentity,
            @NonNull String maneuverNextRoad, @NonNull String maneuverDirectionSignsJson,
            @NonNull String maneuverAuxiliaryType, @NonNull String maneuverAuxiliaryText,
            @NonNull String maneuverAuxiliaryManeuverType,
            int maneuverAuxiliaryDistanceMeters, @NonNull String maneuverDisplayDistance,
            @NonNull String maneuverCardJson) {
        this.sequence = Math.max(0L, sequence);
        this.routeEpoch = Math.max(0L, routeEpoch);
        this.sourceTimestampMs = Math.max(0L, sourceTimestampMs);
        this.routeActive = routeActive;
        this.latitude = coordinate(latitude, -90d, 90d);
        this.longitude = coordinate(longitude, -180d, 180d);
        this.bearingDegrees = finite(bearingDegrees) ? normalizeBearing(bearingDegrees) : Double.NaN;
        this.speedKmh = finite(speedKmh) ? Math.max(0d, Math.min(400d, speedKmh)) : Double.NaN;
        this.maneuverType = bounded(maneuverType, 96);
        this.maneuverTitle = bounded(maneuverTitle, 4_096);
        this.maneuverSubtext = bounded(maneuverSubtext, 4_096);
        this.maneuverIdentity = bounded(maneuverIdentity, 512);
        this.maneuverNextRoad = bounded(maneuverNextRoad, 4_096);
        this.maneuverDirectionSignsJson = bounded(maneuverDirectionSignsJson, 32_768);
        this.maneuverAuxiliaryType = bounded(maneuverAuxiliaryType, 96);
        this.maneuverAuxiliaryText = bounded(maneuverAuxiliaryText, 4_096);
        this.maneuverAuxiliaryManeuverType = bounded(maneuverAuxiliaryManeuverType, 96);
        this.maneuverAuxiliaryDistanceMeters = nonNegative(maneuverAuxiliaryDistanceMeters);
        this.maneuverDisplayDistance = bounded(maneuverDisplayDistance, 96);
        this.maneuverCardJson = maneuverCardJson.length() <= 32768 ? maneuverCardJson
                : "{\"schema\":1,\"visible\":false}";
        this.street = bounded(street, 4_096);
        this.destination = bounded(destination, 4_096);
        this.maneuverDistanceMeters = nonNegative(maneuverDistanceMeters);
        this.routeTotalDistanceMeters = nonNegative(routeTotalDistanceMeters);
        this.remainingDistanceMeters = nonNegative(remainingDistanceMeters);
        this.remainingDurationSeconds = nonNegative(remainingDurationSeconds);
        this.trafficJamDurationSeconds = nonNegative(trafficJamDurationSeconds);
        this.trafficJamDistanceMeters = nonNegative(trafficJamDistanceMeters);
        this.arrivalEpochMs = Math.max(0L, arrivalEpochMs);
        this.speedLimitKmh = Math.max(0, Math.min(300, speedLimitKmh));
        this.laneDistanceMeters = nonNegative(laneDistanceMeters);
        this.lanesJson = bounded(lanesJson, 32_768);
        this.trafficLightsJson = bounded(trafficLightsJson, 32_768);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject result = new JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("sequence", sequence)
                .put("routeEpoch", routeEpoch)
                .put("sourceTimestampMs", sourceTimestampMs)
                .put("routeActive", routeActive)
                .put("maneuverType", maneuverType)
                .put("maneuverTitle", maneuverTitle)
                .put("maneuverSubtext", maneuverSubtext)
                .put("maneuverIdentity", maneuverIdentity)
                .put("maneuverNextRoad", maneuverNextRoad)
                .put("maneuverDirectionSignsJson", maneuverDirectionSignsJson)
                .put("maneuverAuxiliaryType", maneuverAuxiliaryType)
                .put("maneuverAuxiliaryText", maneuverAuxiliaryText)
                .put("maneuverAuxiliaryManeuverType", maneuverAuxiliaryManeuverType)
                .put("maneuverAuxiliaryDistanceMeters", maneuverAuxiliaryDistanceMeters)
                .put("maneuverDisplayDistance", maneuverDisplayDistance)
                .put("maneuverCardJson", maneuverCardJson)
                .put("street", street)
                .put("destination", destination)
                .put("maneuverDistanceMeters", maneuverDistanceMeters)
                .put("routeTotalDistanceMeters", routeTotalDistanceMeters)
                .put("remainingDistanceMeters", remainingDistanceMeters)
                .put("remainingDurationSeconds", remainingDurationSeconds)
                .put("trafficJamDurationSeconds", trafficJamDurationSeconds)
                .put("trafficJamDistanceMeters", trafficJamDistanceMeters)
                .put("arrivalEpochMs", arrivalEpochMs)
                .put("speedLimitKmh", speedLimitKmh)
                .put("laneDistanceMeters", laneDistanceMeters)
                .put("lanesJson", lanesJson)
                .put("trafficLightsJson", trafficLightsJson);
        if (finite(latitude)) result.put("latitude", latitude);
        if (finite(longitude)) result.put("longitude", longitude);
        if (finite(bearingDegrees)) result.put("bearingDegrees", bearingDegrees);
        if (finite(speedKmh)) result.put("speedKmh", speedKmh);
        return result;
    }

    @NonNull
    public static NavigationSnapshotV2 fromJson(@NonNull String raw) {
        if (raw.length() > MAX_JSON_CHARS || raw.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Navigation snapshot is too large");
        }
        try {
            JSONObject source = new JSONObject(raw);
            int schema = source.optInt("schema", SCHEMA_VERSION);
            if (schema != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported navigation snapshot schema " + schema);
            }
            return new NavigationSnapshotV2(
                    source.optLong("sequence", 0L),
                    source.optLong("routeEpoch", 0L),
                    source.optLong("sourceTimestampMs", 0L),
                    source.optBoolean("routeActive", false),
                    source.optDouble("latitude", Double.NaN),
                    source.optDouble("longitude", Double.NaN),
                    source.optDouble("bearingDegrees", Double.NaN),
                    source.optDouble("speedKmh", Double.NaN),
                    source.optString("maneuverType", ""),
                    source.optString("maneuverTitle", ""),
                    source.optString("maneuverSubtext", ""),
                    source.optString("street", ""),
                    source.optString("destination", ""),
                    source.optInt("maneuverDistanceMeters", -1),
                    source.optInt("routeTotalDistanceMeters", -1),
                    source.optInt("remainingDistanceMeters", -1),
                    source.optInt("remainingDurationSeconds", -1),
                    source.optInt("trafficJamDurationSeconds", -1),
                    source.optInt("trafficJamDistanceMeters", -1),
                    source.optLong("arrivalEpochMs", 0L),
                    source.optInt("speedLimitKmh", 0),
                    source.optInt("laneDistanceMeters", -1),
                    source.optString("lanesJson", ""),
                    source.optString("trafficLightsJson", ""),
                    source.optString("maneuverIdentity", ""),
                    source.optString("maneuverNextRoad", ""),
                    source.optString("maneuverDirectionSignsJson", "[]"),
                    source.optString("maneuverAuxiliaryType", ""),
                    source.optString("maneuverAuxiliaryText", ""),
                    source.optString("maneuverAuxiliaryManeuverType", ""),
                    source.optInt("maneuverAuxiliaryDistanceMeters", -1),
                    source.optString("maneuverDisplayDistance", ""),
                    source.optString("maneuverCardJson", ""));
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid navigation snapshot", error);
        }
    }

    private static int nonNegative(int value) {
        return value < 0 ? -1 : value;
    }

    private static double coordinate(double value, double minimum, double maximum) {
        return finite(value) && value >= minimum && value <= maximum ? value : Double.NaN;
    }

    private static double normalizeBearing(double value) {
        double result = value % 360d;
        return result < 0d ? result + 360d : result;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    @NonNull
    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= maximum && value.indexOf('\u0000') < 0 ? value : "";
    }
}
