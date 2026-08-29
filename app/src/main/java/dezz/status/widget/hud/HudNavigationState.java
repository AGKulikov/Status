/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.launcher.NavigationDataRepository;
import dezz.status.widget.navigation.NavigationRouteGeometryV2;
import dezz.status.widget.navigation.NavigationSnapshotV2;

/** One renderer-facing model, preferring the direct 30.3.0 stream over notification fallback. */
public final class HudNavigationState {
    private static final ThreadLocal<SimpleDateFormat> ARRIVAL_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm", Locale.getDefault());
                }
            };
    public static final class Lane {
        @NonNull public final String kind;
        @NonNull public final String highlightedDirection;
        @NonNull public final List<String> directions;

        Lane(String kind, String highlightedDirection, List<String> directions) {
            this.kind = kind;
            this.highlightedDirection = highlightedDirection;
            this.directions = Collections.unmodifiableList(directions);
        }
    }

    public static final class TrafficLight {
        @NonNull public final String color;
        @NonNull public final String countdown;
        @NonNull public final String arrow;

        TrafficLight(String color, String countdown, String arrow) {
            this.color = color;
            this.countdown = countdown;
            this.arrow = arrow;
        }
    }

    public static final class TrafficRun {
        public final int from;
        public final int to;
        @NonNull public final String type;

        TrafficRun(int from, int to, String type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
    }

    public final boolean direct;
    public final boolean routeActive;
    @NonNull public final String maneuverType;
    @NonNull public final String maneuverTitle;
    @NonNull public final String maneuverText;
    @NonNull public final String maneuverSubtext;
    @NonNull public final String street;
    @NonNull public final String destination;
    @NonNull public final String turnDistance;
    @NonNull public final String distance;
    @NonNull public final String duration;
    @NonNull public final String arrival;
    @NonNull public final String speedLimit;
    public final double speedKmh;
    @NonNull public final String lanes;
    @NonNull public final String laneDistance;
    public final double laneDistanceMeters;
    public final boolean laneAvailable;
    @NonNull public final List<Lane> laneItems;
    @NonNull public final String trafficColor;
    @NonNull public final String trafficCountdown;
    @NonNull public final String trafficArrow;
    public final boolean trafficAvailable;
    @NonNull public final List<TrafficLight> trafficLights;
    @NonNull public final List<TrafficRun> trafficRuns;
    public final double tripProgress;
    @Nullable public final Bitmap maneuverImage;
    @Nullable public final Bitmap lanesImage;
    @Nullable public final Bitmap jamImage;
    @Nullable public final Bitmap rainbowImage;
    @NonNull private final String bridgeLanesJson;
    @NonNull private final String bridgeLightsJson;
    @Nullable private final NavigationRouteGeometryV2 bridgeGeometry;

    private HudNavigationState(boolean direct, boolean routeActive, String maneuverType,
            String maneuverTitle, String maneuverText, String maneuverSubtext, String street,
            String destination, String turnDistance, String distance, String duration,
            String arrival, String speedLimit, double speedKmh, String lanes,
            String laneDistance, double laneDistanceMeters, boolean laneAvailable,
            List<Lane> laneItems, String trafficColor, String trafficCountdown,
            String trafficArrow, boolean trafficAvailable, List<TrafficLight> trafficLights,
            List<TrafficRun> trafficRuns, double tripProgress, Bitmap maneuverImage,
            Bitmap lanesImage, Bitmap jamImage, Bitmap rainbowImage,
            String bridgeLanesJson, String bridgeLightsJson,
            NavigationRouteGeometryV2 bridgeGeometry) {
        this.direct = direct;
        this.routeActive = routeActive;
        this.maneuverType = maneuverType;
        this.maneuverTitle = maneuverTitle;
        this.maneuverText = maneuverText;
        this.maneuverSubtext = maneuverSubtext;
        this.street = street;
        this.destination = destination;
        this.turnDistance = turnDistance;
        this.distance = distance;
        this.duration = duration;
        this.arrival = arrival;
        this.speedLimit = speedLimit;
        this.speedKmh = speedKmh;
        this.lanes = lanes;
        this.laneDistance = laneDistance;
        this.laneDistanceMeters = laneDistanceMeters;
        this.laneAvailable = laneAvailable;
        this.laneItems = Collections.unmodifiableList(laneItems);
        this.trafficColor = trafficColor;
        this.trafficCountdown = trafficCountdown;
        this.trafficArrow = trafficArrow;
        this.trafficAvailable = trafficAvailable;
        this.trafficLights = Collections.unmodifiableList(trafficLights);
        this.trafficRuns = Collections.unmodifiableList(trafficRuns);
        this.tripProgress = tripProgress;
        this.maneuverImage = maneuverImage;
        this.lanesImage = lanesImage;
        this.jamImage = jamImage;
        this.rainbowImage = rainbowImage;
        this.bridgeLanesJson = bridgeLanesJson;
        this.bridgeLightsJson = bridgeLightsJson;
        this.bridgeGeometry = bridgeGeometry;
    }

    @NonNull
    public static HudNavigationState fromBridge(@NonNull NavigationSnapshotV2 source,
            @Nullable NavigationRouteGeometryV2 geometry) {
        return fromBridge(source, geometry, null);
    }

    /** Reuses immutable parsed collections while only distance/speed/countdown text changes. */
    @NonNull
    public static HudNavigationState fromBridge(@NonNull NavigationSnapshotV2 source,
            @Nullable NavigationRouteGeometryV2 geometry,
            @Nullable HudNavigationState previous) {
        boolean previousDirect = previous != null && previous.direct;
        List<Lane> lanes = previousDirect
                && source.lanesJson.equals(previous.bridgeLanesJson)
                ? previous.laneItems : parseLanes(source.lanesJson);
        List<TrafficLight> lights = previousDirect
                && source.trafficLightsJson.equals(previous.bridgeLightsJson)
                ? previous.trafficLights : parseLights(source.trafficLightsJson);
        List<TrafficRun> runs = previousDirect && geometry == previous.bridgeGeometry
                ? previous.trafficRuns
                : parseRuns(geometry == null ? "" : geometry.trafficSegmentsJson);
        double progress = Double.NaN;
        if (source.routeTotalDistanceMeters > 0 && source.remainingDistanceMeters >= 0) {
            progress = 1d - source.remainingDistanceMeters
                    / (double) source.routeTotalDistanceMeters;
            progress = Math.max(0d, Math.min(1d, progress));
        }
        TrafficLight first = lights.isEmpty() ? null : lights.get(0);
        return new HudNavigationState(true, source.routeActive, source.maneuverType,
                source.maneuverTitle, source.maneuverTitle, source.maneuverSubtext,
                source.street, source.destination, formatDistance(source.maneuverDistanceMeters),
                formatDistance(source.remainingDistanceMeters),
                formatDuration(source.remainingDurationSeconds), formatArrival(source.arrivalEpochMs),
                source.speedLimitKmh > 0 ? Integer.toString(source.speedLimitKmh) : "",
                source.speedKmh, "", formatDistance(source.laneDistanceMeters),
                source.laneDistanceMeters, !lanes.isEmpty(), lanes,
                first == null ? "" : first.color, first == null ? "" : first.countdown,
                first == null ? "" : first.arrow, !lights.isEmpty(), lights, runs, progress,
                null, null, null, null, source.lanesJson, source.trafficLightsJson, geometry);
    }

    @NonNull
    public static HudNavigationState fromLegacy(@NonNull NavigationDataRepository.Snapshot source) {
        List<TrafficLight> lights = new ArrayList<>();
        for (NavigationDataRepository.TrafficLight light : source.trafficLights) {
            lights.add(new TrafficLight(light.color, light.countdown, light.arrow));
        }
        return new HudNavigationState(false, source.routeActive, "", source.maneuverTitle,
                source.maneuverText, source.maneuverSubtext, source.street, source.destination,
                source.turnDistance, source.distance, source.duration, source.arrival,
                source.speedLimit, Double.NaN, source.lanes, source.laneDistance,
                source.laneDistanceMeters, source.laneAvailable, new ArrayList<>(),
                source.trafficColor, source.trafficCountdown, source.trafficArrow,
                source.trafficAvailable, lights, new ArrayList<>(), Double.NaN,
                source.maneuverImage, source.lanesImage, source.jamImage, source.rainbowImage,
                "", "", null);
    }

    @NonNull private static List<Lane> parseLanes(String raw) {
        ArrayList<Lane> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < Math.min(8, values.length()); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                ArrayList<String> directions = new ArrayList<>();
                JSONArray source = value.optJSONArray("directions");
                if (source != null) for (int item = 0; item < source.length(); item++) {
                    String direction = source.optString(item, "").trim();
                    if (!direction.isEmpty()) directions.add(direction);
                }
                result.add(new Lane(value.optString("kind", ""),
                        value.optString("highlightedDirection", ""), directions));
            }
        } catch (Exception ignored) {}
        return result;
    }

    @NonNull private static List<TrafficLight> parseLights(String raw) {
        ArrayList<TrafficLight> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < Math.min(8, values.length()); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                int seconds = value.optInt("secondsLeft", -1);
                result.add(new TrafficLight(value.optString("signal", ""),
                        seconds < 0 ? "" : seconds + " с", value.optString("arrow", "")));
            }
        } catch (Exception ignored) {}
        return result;
    }

    @NonNull private static List<TrafficRun> parseRuns(String raw) {
        ArrayList<TrafficRun> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < Math.min(2_048, values.length()); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                int from = Math.max(0, value.optInt("from", 0));
                int to = Math.max(from, value.optInt("to", from));
                if (to > from) result.add(new TrafficRun(from, to,
                        value.optString("type", "UNKNOWN")));
            }
        } catch (Exception ignored) {}
        return result;
    }

    @NonNull static String formatDistance(int meters) {
        if (meters < 0) return "";
        if (meters < 1_000) return meters + " м";
        double km = meters / 1_000d;
        return (km < 10d ? String.format(Locale.getDefault(), "%.1f", km)
                : Long.toString(Math.round(km))) + " км";
    }

    @NonNull private static String formatDuration(int seconds) {
        if (seconds < 0) return "";
        int minutes = Math.max(0, (seconds + 30) / 60);
        if (minutes < 60) return minutes + " мин";
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    @NonNull private static String formatArrival(long epochMs) {
        if (epochMs <= 0L) return "";
        return ARRIVAL_FORMAT.get().format(new Date(epochMs));
    }
}
