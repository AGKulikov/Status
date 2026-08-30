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
        @NonNull public final String id;
        @NonNull public final String color;
        @NonNull public final String countdown;
        @NonNull public final String arrow;
        @NonNull public final String sectionType;
        public final int distanceMeters;
        public final int secondsLeft;
        public final double latitude;
        public final double longitude;

        TrafficLight(String color, String countdown, String arrow) {
            this("", color, countdown, arrow, "", -1, -1,
                    Double.NaN, Double.NaN);
        }

        TrafficLight(String id, String color, String countdown, String arrow,
                     String sectionType, int distanceMeters, int secondsLeft,
                     double latitude, double longitude) {
            this.id = id;
            this.color = color;
            this.countdown = countdown;
            this.arrow = arrow;
            this.sectionType = sectionType;
            this.distanceMeters = distanceMeters;
            this.secondsLeft = secondsLeft;
            this.latitude = latitude;
            this.longitude = longitude;
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
        boolean routeActive = source.routeActive;
        List<Lane> lanes = previousDirect && previous.routeActive && routeActive
                && source.lanesJson.equals(previous.bridgeLanesJson)
                ? previous.laneItems : routeActive
                ? parseLanes(source.lanesJson) : Collections.emptyList();
        List<TrafficLight> lights = previousDirect && previous.routeActive && routeActive
                && source.trafficLightsJson.equals(previous.bridgeLightsJson)
                ? previous.trafficLights : routeActive
                ? parseLights(source.trafficLightsJson) : Collections.emptyList();
        List<TrafficRun> runs = previousDirect && previous.routeActive && routeActive
                && geometry == previous.bridgeGeometry
                ? previous.trafficRuns
                : routeActive ? parseRuns(geometry == null
                ? "" : geometry.trafficSegmentsJson) : Collections.emptyList();
        double progress = Double.NaN;
        if (routeActive && source.routeTotalDistanceMeters > 0
                && source.remainingDistanceMeters >= 0) {
            progress = 1d - source.remainingDistanceMeters
                    / (double) source.routeTotalDistanceMeters;
            progress = Math.max(0d, Math.min(1d, progress));
        }
        TrafficLight first = lights.isEmpty() ? null : lights.get(0);
        return new HudNavigationState(true, routeActive,
                routeActive ? source.maneuverType : "",
                routeActive ? source.maneuverTitle : "",
                routeActive ? source.maneuverTitle : "",
                routeActive ? source.maneuverSubtext : "",
                source.street, routeActive ? source.destination : "",
                routeActive ? formatDistance(source.maneuverDistanceMeters) : "",
                routeActive ? formatDistance(source.remainingDistanceMeters) : "",
                routeActive ? formatDuration(source.remainingDurationSeconds) : "",
                routeActive ? formatArrival(source.arrivalEpochMs) : "",
                source.speedLimitKmh > 0 ? Integer.toString(source.speedLimitKmh) : "",
                source.speedKmh, "", routeActive
                ? formatDistance(source.laneDistanceMeters) : "",
                routeActive ? source.laneDistanceMeters : Double.NaN,
                routeActive && !lanes.isEmpty(), lanes,
                first == null ? "" : first.color, first == null ? "" : first.countdown,
                first == null ? "" : first.arrow,
                routeActive && !lights.isEmpty(), lights, runs, progress,
                null, null, null, null, source.lanesJson, source.trafficLightsJson,
                routeActive ? geometry : null);
    }

    @NonNull
    public static HudNavigationState fromLegacy(@NonNull NavigationDataRepository.Snapshot source) {
        List<TrafficLight> lights = new ArrayList<>();
        if (source.routeActive) {
            for (NavigationDataRepository.TrafficLight light : source.trafficLights) {
                String color = normalizedTrafficSignal(light.color);
                if (color.isEmpty()) continue;
                int seconds = countdownSeconds(light.countdown);
                lights.add(new TrafficLight(light.id, color, light.countdown,
                        light.arrow, "", light.position, seconds,
                        Double.NaN, Double.NaN));
            }
        }
        boolean routeActive = source.routeActive;
        String trafficColor = normalizedTrafficSignal(source.trafficColor);
        boolean trafficAvailable = routeActive && source.trafficAvailable
                && (!lights.isEmpty() || !trafficColor.isEmpty());
        boolean laneAvailable = routeActive && source.laneAvailable
                && (!source.lanes.trim().isEmpty() || source.lanesImage != null);
        return new HudNavigationState(false, routeActive, "",
                routeActive ? source.maneuverTitle : "",
                routeActive ? source.maneuverText : "",
                routeActive ? source.maneuverSubtext : "",
                source.street, routeActive ? source.destination : "",
                routeActive ? source.turnDistance : "",
                routeActive ? source.distance : "",
                routeActive ? source.duration : "",
                routeActive ? source.arrival : "",
                routeActive ? source.speedLimit : "", Double.NaN,
                routeActive ? source.lanes : "",
                routeActive ? source.laneDistance : "",
                routeActive ? source.laneDistanceMeters : Double.NaN,
                laneAvailable, new ArrayList<>(), trafficColor,
                trafficAvailable ? source.trafficCountdown : "",
                trafficAvailable ? source.trafficArrow : "",
                trafficAvailable, lights, new ArrayList<>(), Double.NaN,
                routeActive ? source.maneuverImage : null,
                routeActive ? source.lanesImage : null,
                routeActive ? source.jamImage : null,
                routeActive ? source.rainbowImage : null,
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
                String highlighted = value.optString("highlightedDirection", "").trim();
                if (directions.isEmpty() && highlighted.isEmpty()) continue;
                result.add(new Lane(value.optString("kind", ""), highlighted, directions));
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
                String signal = normalizedTrafficSignal(value.optString("signal", ""));
                if (signal.isEmpty()) continue;
                int seconds = value.optInt("secondsLeft", -1);
                result.add(new TrafficLight(value.optString("id", ""), signal,
                        seconds < 0 ? "" : seconds + " с", value.optString("arrow", ""),
                        value.optString("sectionType", ""),
                        value.optInt("distanceMeters", -1), seconds,
                        value.has("latitude")
                                ? value.optDouble("latitude", Double.NaN) : Double.NaN,
                        value.has("longitude")
                                ? value.optDouble("longitude", Double.NaN) : Double.NaN));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Strict live-mode contract shared by every independently positioned navigation element. */
    public boolean hasDataFor(@NonNull HudElementType type) {
        switch (type) {
            case NAV_MANEUVER_ARROW:
                return routeActive && (maneuverImage != null
                        || meaningfulManeuverType(maneuverType)
                        || hasText(maneuverTitle) || hasText(maneuverText));
            case NAV_MANEUVER_TITLE:
                return routeActive && (hasText(maneuverTitle) || hasText(maneuverText));
            case NAV_MANEUVER_SUBTEXT:
                return routeActive && hasText(maneuverSubtext);
            case NAV_STREET:
                return hasText(street);
            case NAV_DESTINATION:
                return routeActive && hasText(destination);
            case NAV_TURN_DISTANCE:
                return routeActive && hasText(turnDistance)
                        && (meaningfulManeuverType(maneuverType)
                        || hasText(maneuverTitle) || hasText(maneuverText));
            case NAV_DISTANCE_LEFT:
                return routeActive && hasText(distance);
            case NAV_TIME_LEFT:
                return routeActive && hasText(duration);
            case NAV_ARRIVAL_TIME:
                return routeActive && hasText(arrival);
            case NAV_LANES:
                return routeActive && laneAvailable
                        && (!laneItems.isEmpty() || hasText(lanes) || lanesImage != null);
            case NAV_LANE_DISTANCE:
                return routeActive && laneAvailable && hasText(laneDistance);
            case NAV_COMBINED:
                return routeActive && (maneuverImage != null
                        || meaningfulManeuverType(maneuverType)
                        || hasText(maneuverTitle) || hasText(maneuverText)
                        || hasText(turnDistance));
            case NAV_TRIP_PROGRESS:
                return routeActive && (Double.isFinite(tripProgress)
                        || hasText(distance) || hasText(duration) || hasText(arrival));
            case NAV_SPEED_LIMIT:
                return hasText(speedLimit);
            case NAV_TRAFFIC_LIGHTS:
                return routeActive && trafficAvailable
                        && (!trafficLights.isEmpty() || !normalizedTrafficSignal(
                        trafficColor).isEmpty());
            case NAV_JAM_PROGRESS:
                return routeActive && (!trafficRuns.isEmpty() || jamImage != null);
            case NAV_ROUTE_GRAPHIC:
                return routeActive && (rainbowImage != null || jamImage != null
                        || (bridgeGeometry != null
                        && hasText(bridgeGeometry.encodedPolyline)));
            case NAV_SPEED:
                return Double.isFinite(speedKmh);
            case NAV_MAP:
                return true;
            default:
                return false;
        }
    }

    private static boolean meaningfulManeuverType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return !normalized.isEmpty() && !"UNKNOWN".equals(normalized)
                && !"NONE".equals(normalized);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty() && !"—".equals(value.trim());
    }

    @NonNull private static String normalizedTrafficSignal(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return "RED".equals(normalized) || "YELLOW".equals(normalized)
                || "RED_AND_YELLOW".equals(normalized)
                || "GREEN".equals(normalized) ? normalized : "";
    }

    private static int countdownSeconds(String value) {
        if (value == null) return -1;
        int result = 0;
        boolean found = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') continue;
            found = true;
            result = Math.min(3_600, result * 10 + character - '0');
        }
        return found ? result : -1;
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
