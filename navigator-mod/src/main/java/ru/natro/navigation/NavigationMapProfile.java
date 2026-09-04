/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;

/** Validated, target-specific map configuration received from Natro. */
final class NavigationMapProfile {
    private static final int MAX_CONFIGURATION_CHARS = 384 * 1024;
    private static final int MAX_STYLE_CHARS = 128 * 1024;
    /**
     * The official MapKit substrate taxonomy marks every road with at least one of these tags.
     * Hiding objects that have none of them preserves the complete road geometry while removing
     * land, water, buildings, boundaries, transit, POI and every other substrate object.
     */
    private static final String ROAD_TAGS_JSON = "[\"road\",\"road_1\",\"road_2\",\"road_3\","
            + "\"road_4\",\"road_5\",\"road_6\",\"road_7\",\"road_limited\","
            + "\"road_unclassified\",\"road_minor\",\"road_construction\",\"ferry\","
            + "\"ice_road\",\"path\",\"crosswalk\",\"underpass\",\"road_surface\","
            + "\"road_marking\"]";
    static final String[] ROAD_EVENT_TAGS = {
            "ACCIDENT", "RECONSTRUCTION", "CHAT", "LOCAL_CHAT", "CLOSED", "DRAWBRIDGE",
            "DANGER", "OTHER", "SPEED_CONTROL", "NO_STOPPING_CONTROL", "LANE_CONTROL",
            "ROAD_MARKING_CONTROL", "MOBILE_CONTROL", "CROSS_ROAD_CONTROL",
            "TRAFFIC_CONTROL", "CROSS_ROAD_DANGER", "OVERTAKING_DANGER",
            "PEDESTRIAN_DANGER", "SCHOOL", "POLICE", "POLICE_PATROL", "FEEDBACK"
    };

    boolean enabled;
    boolean automaticDayNight = true;
    boolean nightMode;
    boolean showPois;
    boolean showLabels = true;
    boolean showBuildings = true;
    boolean showParks = true;
    boolean showWater = true;
    boolean showModels;
    boolean showRoute = true;
    boolean showDestination = true;
    boolean showRouteTraffic = true;
    boolean showTraffic = true;
    /** Independent custom collection; unrelated to background traffic and route jams. */
    boolean showTrafficLights = true;
    /** Exact DrivingRoute speed-bump pins; MapKit exposes no map-wide bump source. */
    boolean showSpeedBumps = true;
    boolean showRouteTurns = true;
    /** Screen-facing sign anchored to the upcoming LaneSign RoutePosition. */
    boolean showLaneGuidance = true;
    boolean showHudSpeedCameras = true;
    int laneGuidanceScalePercent = 100;
    int cameraScalePercent = 100;
    int cameraDirectionLengthPercent = 100;
    int cameraDirectionWidthPercent = 100;
    String cameraDirectionColor = "#FF168BFF";
    int cameraDirectionOpacityPercent = 30;
    int trafficLightScalePercent = 100;
    /** Empty keeps TrafficLightViewImpl's stock colours; otherwise body and tail are recoloured. */
    String trafficLightCardColor = "";
    int speedBumpScalePercent = 100;
    int routeTurnLengthPercent = 100;
    /** Independent scale of ArrowManeuverStyle.triangleHeight. */
    int routeTurnHeadSizePercent = 100;
    /** Null keeps the corresponding colour from MapKit's stock maneuver style. */
    String routeTurnFillColor;
    String routeTurnOutlineColor;
    double routeTurnOutlineWidth = 2d;
    int routeLabelScalePercent = 100;
    int roadEventScalePercent = 100;
    int destinationScalePercent = 100;
    boolean manualLayerPrioritiesEnabled;
    int cameraDirectionLayerPriority = 30;
    int roadEventLayerPriority = 40;
    int routeLayerPriority = 50;
    int destinationLayerPriority = 90;
    int trafficLightLayerPriority = 70;
    int speedBumpLayerPriority = 55;
    /** Legacy transport key. Native polyline arrows always inherit routeLayerPriority. */
    int routeTurnLayerPriority = 50;
    int laneGuidanceLayerPriority = 80;
    int cursorLayerPriority = 60;
    boolean showCursor = true;
    boolean roadsOnly;
    String cameraMode = "FOLLOW_ROUTE";
    boolean fixedZoomEnabled;
    double fixedZoomLevel = 16d;
    double zoomDelta;
    int tiltDegrees = 60;
    int focusXPercent = 50;
    int focusYPercent = 72;
    int mapScalePercent = 100;
    int maximumFps = 30;
    int cursorScalePercent = 100;
    String cursorColor = "#FFFFC400";
    String cursorOutlineColor = "#FF17191E";
    String routeColor = "#FFFFC400";
    String routeOutlineColor = "#FF16181D";
    String roadColor = "";
    int routeWidthPercent = 100;
    int roadWidthPercent = 100;
    double routeWidth = 8d;
    double routeOutlineWidth = 2d;
    String trafficFreeColor = "#FF39B54A";
    String trafficLightColor = "#FFFFD54F";
    String trafficHardColor = "#FFFF8A3D";
    String trafficVeryHardColor = "#FFF04444";
    String trafficBlockedColor = "#FF7E1D2D";
    String trafficUnknownColor = "#FF8A9099";
    double trafficGradientLength = 12d;
    String dayStyleJson = "";
    String nightStyleJson = "";
    final LinkedHashMap<String, String> roadEventModes = new LinkedHashMap<>();

    NavigationMapProfile() {
        for (String tag : ROAD_EVENT_TAGS) roadEventModes.put(tag, defaultRoadEventMode(tag));
    }

    static NavigationMapProfile fromConfiguration(String raw, String section) {
        NavigationMapProfile result = new NavigationMapProfile();
        if (raw == null || raw.length() > MAX_CONFIGURATION_CHARS
                || raw.indexOf('\u0000') >= 0) {
            return result;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject source = root.optJSONObject(section);
            if (source == null) return result;
            result.enabled = source.optBoolean("enabled", false);
            result.automaticDayNight = source.optBoolean("automaticDayNight", true);
            result.nightMode = source.optBoolean("nightMode", false);
            result.showPois = source.optBoolean("showPois", false);
            result.showLabels = source.optBoolean("showLabels", true);
            // 2.5.7-2.6.4 stored a request for a hand-drawn route-only label layer. Native
            // substrate labels cannot be filtered by active-route membership through public
            // MapKit. Migrate that old opt-in to visible stock Yandex road labels so an update
            // never makes the street names disappear.
            if (source.optBoolean("routeStreetLabelsOnly", false)) {
                result.showLabels = true;
            }
            result.showBuildings = source.optBoolean("showBuildings", true);
            result.showParks = source.optBoolean("showParks", true);
            result.showWater = source.optBoolean("showWater", true);
            result.showModels = source.optBoolean("showModels", false);
            result.showRoute = source.optBoolean("showRoute", true);
            result.showDestination = source.optBoolean("showDestination", true);
            result.showTraffic = source.optBoolean("showTraffic", true);
            // Old configurations had one switch for both layers. Preserve that behaviour until
            // Natro sends the new independent route-traffic value.
            result.showRouteTraffic = source.optBoolean(
                    "showRouteTraffic", result.showTraffic);
            result.showTrafficLights = source.optBoolean("showTrafficLights", true);
            result.showSpeedBumps = source.optBoolean("showSpeedBumps", true);
            result.showRouteTurns = source.optBoolean("showRouteTurns", true);
            result.showLaneGuidance = source.optBoolean("showLaneGuidance", true);
            result.showHudSpeedCameras = source.optBoolean("showHudSpeedCameras", true);
            result.laneGuidanceScalePercent = clamp(
                    source.optInt("laneGuidanceScalePercent", 100), 50, 250);
            result.cameraScalePercent = clamp(
                    source.optInt("cameraScalePercent", 100), 50, 250);
            int legacyCameraDirectionScale = source.optInt(
                    "cameraDirectionScalePercent", 100);
            result.cameraDirectionLengthPercent = clamp(source.optInt(
                    "cameraDirectionLengthPercent", legacyCameraDirectionScale), 10, 300);
            result.cameraDirectionWidthPercent = clamp(source.optInt(
                    "cameraDirectionWidthPercent", legacyCameraDirectionScale), 10, 300);
            result.cameraDirectionColor = color(source.optString(
                    "cameraDirectionColor", result.cameraDirectionColor),
                    result.cameraDirectionColor);
            result.cameraDirectionOpacityPercent = clamp(
                    source.optInt("cameraDirectionOpacityPercent", 30), 0, 100);
            result.trafficLightScalePercent = clamp(
                    source.optInt("trafficLightScalePercent", 100), 50, 250);
            String configuredTrafficLightCardColor = optionalColor(
                    source, "trafficLightCardColor");
            result.trafficLightCardColor = configuredTrafficLightCardColor == null
                    ? "" : configuredTrafficLightCardColor;
            result.speedBumpScalePercent = clamp(
                    source.optInt("speedBumpScalePercent", 100), 50, 250);
            int legacyRouteTurnScale = source.optInt("routeTurnScalePercent", 100);
            result.routeTurnLengthPercent = clamp(source.optInt(
                    "routeTurnLengthPercent", legacyRouteTurnScale), 10, 250);
            result.routeTurnHeadSizePercent = clamp(source.optInt(
                    "routeTurnHeadSizePercent", legacyRouteTurnScale), 10, 250);
            result.routeTurnFillColor = optionalColor(source, "routeTurnFillColor");
            result.routeTurnOutlineColor = optionalColor(source, "routeTurnOutlineColor");
            result.routeTurnOutlineWidth = clamp(source.optDouble(
                    "routeTurnOutlineWidth", 2d), 0d, 20d, 2d);
            result.routeLabelScalePercent = clamp(
                    source.optInt("routeLabelScalePercent", 100), 50, 250);
            result.roadEventScalePercent = clamp(
                    source.optInt("roadEventScalePercent", 100), 50, 250);
            result.destinationScalePercent = clamp(
                    source.optInt("destinationScalePercent", 100), 50, 250);
            result.manualLayerPrioritiesEnabled = source.optBoolean(
                    "manualLayerPrioritiesEnabled", false);
            result.cameraDirectionLayerPriority = clamp(
                    source.optInt("cameraDirectionLayerPriority", 30), 0, 100);
            result.roadEventLayerPriority = clamp(
                    source.optInt("roadEventLayerPriority", 40), 0, 100);
            result.routeLayerPriority = clamp(
                    source.optInt("routeLayerPriority", 50), 0, 100);
            result.destinationLayerPriority = clamp(
                    source.optInt("destinationLayerPriority", 90), 0, 100);
            result.trafficLightLayerPriority = clamp(
                    source.optInt("trafficLightLayerPriority", 70), 0, 100);
            result.speedBumpLayerPriority = clamp(
                    source.optInt("speedBumpLayerPriority", 55), 0, 100);
            result.routeTurnLayerPriority = clamp(
                    source.optInt("routeTurnLayerPriority", result.routeLayerPriority), 0, 100);
            result.laneGuidanceLayerPriority = clamp(
                    source.optInt("laneGuidanceLayerPriority", 80), 0, 100);
            result.cursorLayerPriority = clamp(
                    source.optInt("cursorLayerPriority", 60), 0, 100);
            if (hasLegacyDefaultLayerPriorities(result)) {
                result.cameraDirectionLayerPriority = 30;
                result.roadEventLayerPriority = 40;
                result.routeLayerPriority = 50;
                result.destinationLayerPriority = 90;
                result.trafficLightLayerPriority = 70;
                result.laneGuidanceLayerPriority = 80;
                result.cursorLayerPriority = 60;
            }
            // MapKit Arrow belongs to PolylineMapObject and exposes no independent z-index.
            result.routeTurnLayerPriority = result.routeLayerPriority;
            result.showCursor = source.optBoolean("showCursor", true);
            result.roadsOnly = source.optBoolean("roadsOnly", false);
            result.cameraMode = enumText(source.optString(
                    "cameraMode", "FOLLOW_ROUTE"));
            result.fixedZoomEnabled = source.optBoolean("fixedZoomEnabled", false);
            result.fixedZoomLevel = clamp(source.optDouble(
                    "fixedZoomLevel", 16d), 2d, 21d, 16d);
            result.zoomDelta = clamp(source.optDouble("zoomDelta", 0d), -8d, 8d, 0d);
            result.tiltDegrees = clamp(source.optInt("tiltDegrees", 60), 0, 80);
            result.focusXPercent = clamp(source.optInt("focusXPercent", 50), 0, 100);
            result.focusYPercent = clamp(source.optInt("focusYPercent", 72), 0, 100);
            result.mapScalePercent = clamp(
                    source.optInt("mapScalePercent", 100), 50, 300);
            result.maximumFps = clamp(source.optInt("maximumFps", 30), 1, 60);
            result.cursorScalePercent = clamp(
                    source.optInt("cursorScalePercent", 100), 25, 300);
            result.cursorColor = color(source.optString(
                    "cursorColor", result.cursorColor), result.cursorColor);
            result.cursorOutlineColor = color(source.optString(
                    "cursorOutlineColor", result.cursorOutlineColor),
                    result.cursorOutlineColor);
            result.routeColor = color(source.optString(
                    "routeColor", result.routeColor), result.routeColor);
            result.routeOutlineColor = color(source.optString(
                    "routeOutlineColor", result.routeOutlineColor),
                    result.routeOutlineColor);
            result.roadColor = color(source.optString(
                    "roadColor", result.roadColor), result.roadColor);
            result.routeWidthPercent = clamp(
                    source.optInt("routeWidthPercent", 100), 25, 300);
            result.roadWidthPercent = clamp(
                    source.optInt("roadWidthPercent", 100), 25, 300);
            result.routeWidth = clamp(
                    source.optDouble("routeWidth", 8d), 1d, 40d, 8d);
            result.routeOutlineWidth = clamp(source.optDouble(
                    "routeOutlineWidth", 2d), 0d, 20d, 2d);
            result.trafficFreeColor = color(source.optString(
                    "trafficFreeColor", result.trafficFreeColor), result.trafficFreeColor);
            result.trafficLightColor = color(source.optString(
                    "trafficLightColor", result.trafficLightColor), result.trafficLightColor);
            result.trafficHardColor = color(source.optString(
                    "trafficHardColor", result.trafficHardColor), result.trafficHardColor);
            result.trafficVeryHardColor = color(source.optString(
                    "trafficVeryHardColor", result.trafficVeryHardColor),
                    result.trafficVeryHardColor);
            result.trafficBlockedColor = color(source.optString(
                    "trafficBlockedColor", result.trafficBlockedColor),
                    result.trafficBlockedColor);
            result.trafficUnknownColor = color(source.optString(
                    "trafficUnknownColor", result.trafficUnknownColor),
                    result.trafficUnknownColor);
            result.trafficGradientLength = clamp(source.optDouble(
                    "trafficGradientLength", result.trafficGradientLength),
                    0d, 100d, 12d);
            result.dayStyleJson = bounded(source.optString("dayStyleJson", ""));
            result.nightStyleJson = bounded(source.optString("nightStyleJson", ""));
            JSONObject events = source.optJSONObject("roadEvents");
            if (events != null) {
                for (String tag : ROAD_EVENT_TAGS) {
                    result.roadEventModes.put(tag, eventMode(
                            events.optString(tag, ""), defaultRoadEventMode(tag)));
                }
            }
        } catch (JSONException | RuntimeException ignored) {}
        return result;
    }

    String roadEventMode(String tag) {
        String value = roadEventModes.get(tag);
        return value == null ? "HIDDEN" : value;
    }

    int effectiveCameraPriority() {
        return manualLayerPrioritiesEnabled ? cameraDirectionLayerPriority : 30;
    }

    int effectiveRoadEventPriority() {
        return manualLayerPrioritiesEnabled ? roadEventLayerPriority : 40;
    }

    int effectiveRoutePriority() {
        return manualLayerPrioritiesEnabled ? routeLayerPriority : 50;
    }

    int effectiveDestinationPriority() {
        return manualLayerPrioritiesEnabled ? destinationLayerPriority : 90;
    }

    int effectiveTrafficLightPriority() {
        return manualLayerPrioritiesEnabled ? trafficLightLayerPriority : 70;
    }

    int effectiveSpeedBumpPriority() {
        return manualLayerPrioritiesEnabled ? speedBumpLayerPriority : 55;
    }

    int effectiveLanePriority() {
        return manualLayerPrioritiesEnabled ? laneGuidanceLayerPriority : 80;
    }

    int effectiveCursorPriority() {
        return manualLayerPrioritiesEnabled ? cursorLayerPriority : 60;
    }

    String visibilityStyleJson() {
        StringBuilder rules = new StringBuilder(768).append('[');
        boolean needsComma = false;
        if (roadsOnly) {
            needsComma = appendRule(rules, needsComma,
                    "{\"tags\":{\"none\":" + ROAD_TAGS_JSON + "},"
                            + "\"stylers\":{\"visibility\":\"off\"}}");
        }
        if (!roadColor.isEmpty() || roadWidthPercent != 100) {
            StringBuilder stylers = new StringBuilder("{");
            boolean stylerComma = false;
            if (!roadColor.isEmpty()) {
                stylers.append("\"color\":\"").append(mapKitColor(roadColor)).append('"');
                stylerComma = true;
            }
            if (roadWidthPercent != 100) {
                if (stylerComma) stylers.append(',');
                // MapKit's geometry scale styler changes the substrate stroke only; icons,
                // labels, route and traffic overlays remain independently sized.
                stylers.append("\"scale\":")
                        .append(String.format(Locale.ROOT, "%.2f", roadWidthPercent / 100d));
            }
            stylers.append('}');
            needsComma = appendRule(rules, needsComma,
                    "{\"tags\":{\"any\":" + ROAD_TAGS_JSON + "},"
                            + "\"elements\":\"geometry\",\"stylers\":" + stylers + "}");
        }
        if (!showLabels) {
            needsComma = appendRule(rules, needsComma,
                    "{\"elements\":\"label\",\"stylers\":{\"visibility\":\"off\"}}");
        } else if (routeLabelScalePercent != 100) {
            // Keep the exact Yandex font, outline, road curvature and collision rules. Only the
            // supported substrate scale styler is changed; no text is rasterized by Natro.
            needsComma = appendRule(rules, needsComma,
                    "{\"tags\":{\"any\":" + ROAD_TAGS_JSON + "},"
                            + "\"elements\":\"label.text\",\"stylers\":{\"scale\":"
                            + String.format(Locale.ROOT, "%.2f",
                            routeLabelScalePercent / 100d) + "}}");
        }
        if (!showBuildings) {
            needsComma = appendRule(rules, needsComma,
                    "{\"tags\":{\"all\":[\"building\"]},"
                            + "\"stylers\":{\"visibility\":\"off\"}}");
        }
        if (!showParks) {
            needsComma = appendRule(rules, needsComma,
                    "{\"tags\":{\"any\":[\"park\",\"national_park\"]},"
                            + "\"stylers\":{\"visibility\":\"off\"}}");
        }
        if (!showWater) {
            appendRule(rules, needsComma,
                    "{\"tags\":{\"all\":[\"water\"]},"
                            + "\"stylers\":{\"visibility\":\"off\"}}");
        }
        rules.append(']');
        return rules.length() == 2 ? "" : rules.toString();
    }

    int trafficColor(int paletteIndex) {
        String value;
        switch (paletteIndex) {
            case 1: value = trafficFreeColor; break;
            case 2: value = trafficLightColor; break;
            case 3: value = trafficHardColor; break;
            case 4: value = trafficVeryHardColor; break;
            case 5: value = trafficBlockedColor; break;
            case 6: value = trafficUnknownColor; break;
            default: value = routeColor; break;
        }
        return Color.parseColor(value);
    }

    private static boolean appendRule(StringBuilder target, boolean comma, String rule) {
        if (comma) target.append(',');
        target.append(rule);
        return true;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** One shared numeric range makes the order comparable across separate collections. */
    static float layerZ(int priority) {
        return 50f + clamp(priority, 0, 100);
    }

    /** Migrates the exact 2.6.8 preset; any user-edited tuple is preserved. */
    private static boolean hasLegacyDefaultLayerPriorities(NavigationMapProfile value) {
        return value.cameraDirectionLayerPriority == 20
                && value.roadEventLayerPriority == 30
                && value.routeLayerPriority == 40
                && value.destinationLayerPriority == 45
                && value.trafficLightLayerPriority == 50
                && value.routeTurnLayerPriority == 55
                && value.laneGuidanceLayerPriority == 80
                && value.cursorLayerPriority == 90;
    }

    private static double clamp(double value, double minimum, double maximum,
                                double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value)
                ? fallback : Math.max(minimum, Math.min(maximum, value));
    }

    private static String color(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.matches("#[0-9A-F]{6}")) value = "#FF" + value.substring(1);
        try {
            Color.parseColor(value);
            return value;
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }

    private static String optionalColor(JSONObject source, String key) {
        if (!source.has(key) || source.isNull(key)) return null;
        String value = source.optString(key, "").trim().toUpperCase(Locale.ROOT);
        if (value.matches("#[0-9A-F]{6}")) value = "#FF" + value.substring(1);
        try {
            Color.parseColor(value);
            return value;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Natro stores Android #AARRGGBB; MapKit styles require #RRGGBBAA. */
    private static String mapKitColor(String androidColor) {
        int value = Color.parseColor(androidColor);
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X",
                Color.red(value), Color.green(value), Color.blue(value), Color.alpha(value));
    }

    private static String bounded(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= MAX_STYLE_CHARS && value.indexOf('\u0000') < 0 ? value : "";
    }

    private static String enumText(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return "NORTH_UP".equals(value) || "HEADING_UP".equals(value)
                || "FREE".equals(value) ? value : "FOLLOW_ROUTE";
    }

    private static String eventMode(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return "HIDDEN".equals(value) || "ALWAYS".equals(value)
                || "ROUTE_ONLY".equals(value) ? value : fallback;
    }

    private static String defaultRoadEventMode(String tag) {
        if ("FEEDBACK".equals(tag)) return "HIDDEN";
        if ("ACCIDENT".equals(tag) || "RECONSTRUCTION".equals(tag)
                || "CHAT".equals(tag) || "LOCAL_CHAT".equals(tag)
                || "CLOSED".equals(tag) || "DRAWBRIDGE".equals(tag)) {
            return "ALWAYS";
        }
        return "ROUTE_ONLY";
    }
}
