/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Validated, target-specific map configuration received from Natro. */
final class NavigationMapProfile {
    private static final int MAX_CONFIGURATION_CHARS = 384 * 1024;
    private static final int MAX_STYLE_CHARS = 128 * 1024;

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
    boolean showTraffic = true;
    boolean showCursor = true;
    String cameraMode = "FOLLOW_ROUTE";
    double zoomDelta;
    int tiltDegrees = 60;
    int focusXPercent = 50;
    int focusYPercent = 72;
    int mapScalePercent = 100;
    int maximumFps = 20;
    int cursorScalePercent = 100;
    String cursorColor = "#FFFFC400";
    String cursorOutlineColor = "#FF17191E";
    String routeColor = "#FFFFC400";
    String routeOutlineColor = "#FF16181D";
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
            result.showBuildings = source.optBoolean("showBuildings", true);
            result.showParks = source.optBoolean("showParks", true);
            result.showWater = source.optBoolean("showWater", true);
            result.showModels = source.optBoolean("showModels", false);
            result.showRoute = source.optBoolean("showRoute", true);
            result.showTraffic = source.optBoolean("showTraffic", true);
            result.showCursor = source.optBoolean("showCursor", true);
            result.cameraMode = enumText(source.optString(
                    "cameraMode", "FOLLOW_ROUTE"));
            result.zoomDelta = clamp(source.optDouble("zoomDelta", 0d), -8d, 8d, 0d);
            result.tiltDegrees = clamp(source.optInt("tiltDegrees", 60), 0, 80);
            result.focusXPercent = clamp(source.optInt("focusXPercent", 50), 0, 100);
            result.focusYPercent = clamp(source.optInt("focusYPercent", 72), 0, 100);
            result.mapScalePercent = clamp(
                    source.optInt("mapScalePercent", 100), 50, 300);
            result.maximumFps = clamp(source.optInt("maximumFps", 20), 1, 60);
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
        } catch (JSONException | RuntimeException ignored) {}
        return result;
    }

    String visibilityStyleJson() {
        StringBuilder rules = new StringBuilder(384).append('[');
        boolean needsComma = false;
        if (!showLabels) {
            needsComma = appendRule(rules, needsComma,
                    "{\"elements\":\"label\",\"stylers\":{\"visibility\":\"off\"}}");
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

    private static double clamp(double value, double minimum, double maximum,
                                double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value)
                ? fallback : Math.max(minimum, Math.min(maximum, value));
    }

    private static String color(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim();
        try {
            Color.parseColor(value);
            return value;
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
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
}
