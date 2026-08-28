/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Independent rendering profiles for Navigator's main surface and Natro's HUD surface. */
public final class NavigationIntegrationConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_CHARS = 384 * 1024;

    @NonNull public MapProfile mainMap = MapProfile.defaults(Target.MAIN);
    @NonNull public MapProfile hudMap = MapProfile.defaults(Target.HUD);
    @NonNull public FloatingWindowProfile mainFloatingWindow = FloatingWindowProfile.defaults();

    public enum Target { MAIN, HUD }

    @NonNull
    public JSONObject toJson() throws JSONException {
        normalize();
        return new JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("mainMap", mainMap.toJson())
                .put("hudMap", hudMap.toJson())
                .put("mainFloatingWindow", mainFloatingWindow.toJson());
    }

    @NonNull
    public static NavigationIntegrationConfig fromJson(@NonNull String raw) {
        if (raw.length() > MAX_JSON_CHARS || raw.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Navigation configuration is too large");
        }
        try {
            JSONObject source = new JSONObject(raw);
            int schema = source.optInt("schema", SCHEMA_VERSION);
            if (schema != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported navigation configuration schema " + schema);
            }
            NavigationIntegrationConfig result = new NavigationIntegrationConfig();
            result.mainMap = MapProfile.fromJson(Target.MAIN, source.optJSONObject("mainMap"));
            result.hudMap = MapProfile.fromJson(Target.HUD, source.optJSONObject("hudMap"));
            result.mainFloatingWindow = FloatingWindowProfile.fromJson(
                    source.optJSONObject("mainFloatingWindow"));
            result.normalize();
            return result;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid navigation configuration", error);
        }
    }

    public void normalize() {
        if (mainMap == null || mainMap.target != Target.MAIN) {
            mainMap = MapProfile.defaults(Target.MAIN);
        }
        if (hudMap == null || hudMap.target != Target.HUD) {
            hudMap = MapProfile.defaults(Target.HUD);
        }
        if (mainFloatingWindow == null) mainFloatingWindow = FloatingWindowProfile.defaults();
        mainMap.normalize();
        hudMap.normalize();
        mainFloatingWindow.normalize();
    }

    /** Visual map settings. Geometry of the HUD map stays in the HUD element layout. */
    public static final class MapProfile {
        private static final int MAX_STYLE_CHARS = 128 * 1024;

        @NonNull public final Target target;
        public boolean enabled;
        @NonNull public String cameraMode = "FOLLOW_ROUTE";
        public double zoomDelta;
        public int tiltDegrees = 60;
        public int focusXPercent = 50;
        public int focusYPercent = 72;
        public int mapScalePercent = 100;
        public boolean automaticDayNight = true;
        public boolean nightMode;
        public boolean showRoute = true;
        public boolean showTraffic = true;
        public boolean showLabels = true;
        public boolean showPois = true;
        public boolean showBuildings = true;
        public boolean showParks = true;
        public boolean showWater = true;
        public boolean showModels;
        public boolean showCursor = true;
        /** HUD-only substrate mode: keep road geometry and make every other map pixel alpha. */
        public boolean roadsOnly;
        public int cursorScalePercent = 100;
        @NonNull public String cursorColor = "#FFFFC400";
        @NonNull public String cursorOutlineColor = "#FF17191E";
        @NonNull public String routeColor = "#FFFFC400";
        @NonNull public String routeOutlineColor = "#FF16181D";
        public double routeWidth = 8d;
        public double routeOutlineWidth = 2d;
        @NonNull public String trafficFreeColor = "#FF39B54A";
        @NonNull public String trafficLightColor = "#FFFFD54F";
        @NonNull public String trafficHardColor = "#FFFF8A3D";
        @NonNull public String trafficVeryHardColor = "#FFF04444";
        @NonNull public String trafficBlockedColor = "#FF7E1D2D";
        @NonNull public String trafficUnknownColor = "#FF8A9099";
        public double trafficGradientLength = 12d;
        public int maximumFps;
        @NonNull public String dayStyleJson = "";
        @NonNull public String nightStyleJson = "";

        private MapProfile(@NonNull Target target) {
            this.target = target;
        }

        @NonNull
        public static MapProfile defaults(@NonNull Target target) {
            MapProfile result = new MapProfile(target);
            result.enabled = true;
            result.maximumFps = target == Target.HUD ? 20 : 30;
            if (target == Target.HUD) {
                result.showPois = false;
                result.showBuildings = false;
                result.showModels = false;
            }
            return result;
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            normalize();
            return new JSONObject()
                    .put("target", target.name())
                    .put("enabled", enabled)
                    .put("cameraMode", cameraMode)
                    .put("zoomDelta", zoomDelta)
                    .put("tiltDegrees", tiltDegrees)
                    .put("focusXPercent", focusXPercent)
                    .put("focusYPercent", focusYPercent)
                    .put("mapScalePercent", mapScalePercent)
                    .put("automaticDayNight", automaticDayNight)
                    .put("nightMode", nightMode)
                    .put("showRoute", showRoute)
                    .put("showTraffic", showTraffic)
                    .put("showLabels", showLabels)
                    .put("showPois", showPois)
                    .put("showBuildings", showBuildings)
                    .put("showParks", showParks)
                    .put("showWater", showWater)
                    .put("showModels", showModels)
                    .put("showCursor", showCursor)
                    .put("roadsOnly", roadsOnly)
                    .put("cursorScalePercent", cursorScalePercent)
                    .put("cursorColor", cursorColor)
                    .put("cursorOutlineColor", cursorOutlineColor)
                    .put("routeColor", routeColor)
                    .put("routeOutlineColor", routeOutlineColor)
                    .put("routeWidth", routeWidth)
                    .put("routeOutlineWidth", routeOutlineWidth)
                    .put("trafficFreeColor", trafficFreeColor)
                    .put("trafficLightColor", trafficLightColor)
                    .put("trafficHardColor", trafficHardColor)
                    .put("trafficVeryHardColor", trafficVeryHardColor)
                    .put("trafficBlockedColor", trafficBlockedColor)
                    .put("trafficUnknownColor", trafficUnknownColor)
                    .put("trafficGradientLength", trafficGradientLength)
                    .put("maximumFps", maximumFps)
                    .put("dayStyleJson", dayStyleJson)
                    .put("nightStyleJson", nightStyleJson);
        }

        @NonNull
        static MapProfile fromJson(@NonNull Target target, JSONObject source) {
            MapProfile result = defaults(target);
            if (source == null) return result;
            result.enabled = source.optBoolean("enabled", result.enabled);
            result.cameraMode = source.optString("cameraMode", result.cameraMode);
            result.zoomDelta = source.optDouble("zoomDelta", result.zoomDelta);
            result.tiltDegrees = source.optInt("tiltDegrees", result.tiltDegrees);
            result.focusXPercent = source.optInt("focusXPercent", result.focusXPercent);
            result.focusYPercent = source.optInt("focusYPercent", result.focusYPercent);
            result.mapScalePercent = source.optInt(
                    "mapScalePercent", result.mapScalePercent);
            result.automaticDayNight = source.optBoolean(
                    "automaticDayNight", result.automaticDayNight);
            result.nightMode = source.optBoolean("nightMode", result.nightMode);
            result.showRoute = source.optBoolean("showRoute", result.showRoute);
            result.showTraffic = source.optBoolean("showTraffic", result.showTraffic);
            result.showLabels = source.optBoolean("showLabels", result.showLabels);
            result.showPois = source.optBoolean("showPois", result.showPois);
            result.showBuildings = source.optBoolean("showBuildings", result.showBuildings);
            result.showParks = source.optBoolean("showParks", result.showParks);
            result.showWater = source.optBoolean("showWater", result.showWater);
            result.showModels = source.optBoolean("showModels", result.showModels);
            result.showCursor = source.optBoolean("showCursor", result.showCursor);
            result.roadsOnly = source.optBoolean("roadsOnly", result.roadsOnly);
            result.cursorScalePercent = source.optInt(
                    "cursorScalePercent", result.cursorScalePercent);
            result.cursorColor = source.optString("cursorColor", result.cursorColor);
            result.cursorOutlineColor = source.optString(
                    "cursorOutlineColor", result.cursorOutlineColor);
            result.routeColor = source.optString("routeColor", result.routeColor);
            result.routeOutlineColor = source.optString(
                    "routeOutlineColor", result.routeOutlineColor);
            result.routeWidth = source.optDouble("routeWidth", result.routeWidth);
            result.routeOutlineWidth = source.optDouble(
                    "routeOutlineWidth", result.routeOutlineWidth);
            result.trafficFreeColor = source.optString(
                    "trafficFreeColor", result.trafficFreeColor);
            result.trafficLightColor = source.optString(
                    "trafficLightColor", result.trafficLightColor);
            result.trafficHardColor = source.optString(
                    "trafficHardColor", result.trafficHardColor);
            result.trafficVeryHardColor = source.optString(
                    "trafficVeryHardColor", result.trafficVeryHardColor);
            result.trafficBlockedColor = source.optString(
                    "trafficBlockedColor", result.trafficBlockedColor);
            result.trafficUnknownColor = source.optString(
                    "trafficUnknownColor", result.trafficUnknownColor);
            result.trafficGradientLength = source.optDouble(
                    "trafficGradientLength", result.trafficGradientLength);
            result.maximumFps = source.optInt("maximumFps", result.maximumFps);
            result.dayStyleJson = source.optString("dayStyleJson", result.dayStyleJson);
            result.nightStyleJson = source.optString("nightStyleJson", result.nightStyleJson);
            result.normalize();
            return result;
        }

        void normalize() {
            cameraMode = enumText(cameraMode, "FOLLOW_ROUTE",
                    "FOLLOW_ROUTE", "NORTH_UP", "HEADING_UP", "FREE");
            zoomDelta = clamp(zoomDelta, -8d, 8d, 0d);
            tiltDegrees = clamp(tiltDegrees, 0, 80);
            focusXPercent = clamp(focusXPercent, 0, 100);
            focusYPercent = clamp(focusYPercent, 0, 100);
            mapScalePercent = clamp(mapScalePercent, 50, 300);
            cursorScalePercent = clamp(cursorScalePercent, 25, 300);
            routeWidth = clamp(routeWidth, 1d, 40d, 8d);
            routeOutlineWidth = clamp(routeOutlineWidth, 0d, 20d, 2d);
            trafficGradientLength = clamp(trafficGradientLength, 0d, 100d, 12d);
            maximumFps = clamp(maximumFps, 1, 60);
            cursorColor = color(cursorColor, "#FFFFC400");
            cursorOutlineColor = color(cursorOutlineColor, "#FF17191E");
            routeColor = color(routeColor, "#FFFFC400");
            routeOutlineColor = color(routeOutlineColor, "#FF16181D");
            trafficFreeColor = color(trafficFreeColor, "#FF39B54A");
            trafficLightColor = color(trafficLightColor, "#FFFFD54F");
            trafficHardColor = color(trafficHardColor, "#FFFF8A3D");
            trafficVeryHardColor = color(trafficVeryHardColor, "#FFF04444");
            trafficBlockedColor = color(trafficBlockedColor, "#FF7E1D2D");
            trafficUnknownColor = color(trafficUnknownColor, "#FF8A9099");
            dayStyleJson = bounded(dayStyleJson, MAX_STYLE_CHARS);
            nightStyleJson = bounded(nightStyleJson, MAX_STYLE_CHARS);
        }
    }

    /** Main-display freeform window settings; the Navigator patch applies them to its own task. */
    public static final class FloatingWindowProfile {
        public boolean enabled = true;
        public int leftPercent = 4;
        public int topPercent = 6;
        public int widthPercent = 75;
        public int heightPercent = 82;
        public boolean movementLocked;
        public boolean resizeLocked;
        public int cornerRadiusDp = 24;
        public int opacityPercent = 100;
        public int borderWidthDp;
        @NonNull public String borderColor = "#00000000";
        public int shadowRadiusDp = 20;
        @NonNull public String shadowColor = "#66000000";
        @NonNull public String backgroundColor = "#00000000";
        public boolean aspectRatioLocked;
        public boolean rememberGeometry = true;
        public boolean dragHandleVisible = true;
        public boolean resizeHandleVisible = true;
        public boolean modeButtonVisible = true;
        @NonNull public String modeButtonPosition = "TOP_LEFT";
        public int modeButtonSizeDp = 50;
        public int modeButtonOpacityPercent = 85;
        public boolean closeButtonVisible = true;
        public boolean keepAboveLauncher = true;

        @NonNull
        public static FloatingWindowProfile defaults() {
            return new FloatingWindowProfile();
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            normalize();
            return new JSONObject()
                    .put("enabled", enabled)
                    .put("leftPercent", leftPercent)
                    .put("topPercent", topPercent)
                    .put("widthPercent", widthPercent)
                    .put("heightPercent", heightPercent)
                    .put("movementLocked", movementLocked)
                    .put("resizeLocked", resizeLocked)
                    .put("cornerRadiusDp", cornerRadiusDp)
                    .put("opacityPercent", opacityPercent)
                    .put("borderWidthDp", borderWidthDp)
                    .put("borderColor", borderColor)
                    .put("shadowRadiusDp", shadowRadiusDp)
                    .put("shadowColor", shadowColor)
                    .put("backgroundColor", backgroundColor)
                    .put("aspectRatioLocked", aspectRatioLocked)
                    .put("rememberGeometry", rememberGeometry)
                    .put("dragHandleVisible", dragHandleVisible)
                    .put("resizeHandleVisible", resizeHandleVisible)
                    .put("modeButtonVisible", modeButtonVisible)
                    .put("modeButtonPosition", modeButtonPosition)
                    .put("modeButtonSizeDp", modeButtonSizeDp)
                    .put("modeButtonOpacityPercent", modeButtonOpacityPercent)
                    .put("closeButtonVisible", closeButtonVisible)
                    .put("keepAboveLauncher", keepAboveLauncher);
        }

        @NonNull
        static FloatingWindowProfile fromJson(JSONObject source) {
            FloatingWindowProfile result = defaults();
            if (source == null) return result;
            result.enabled = source.optBoolean("enabled", result.enabled);
            result.leftPercent = source.optInt("leftPercent", result.leftPercent);
            result.topPercent = source.optInt("topPercent", result.topPercent);
            result.widthPercent = source.optInt("widthPercent", result.widthPercent);
            result.heightPercent = source.optInt("heightPercent", result.heightPercent);
            result.movementLocked = source.optBoolean(
                    "movementLocked", result.movementLocked);
            result.resizeLocked = source.optBoolean("resizeLocked", result.resizeLocked);
            result.cornerRadiusDp = source.optInt("cornerRadiusDp", result.cornerRadiusDp);
            result.opacityPercent = source.optInt("opacityPercent", result.opacityPercent);
            result.borderWidthDp = source.optInt("borderWidthDp", result.borderWidthDp);
            result.borderColor = source.optString("borderColor", result.borderColor);
            result.shadowRadiusDp = source.optInt("shadowRadiusDp", result.shadowRadiusDp);
            result.shadowColor = source.optString("shadowColor", result.shadowColor);
            result.backgroundColor = source.optString(
                    "backgroundColor", result.backgroundColor);
            result.aspectRatioLocked = source.optBoolean(
                    "aspectRatioLocked", result.aspectRatioLocked);
            result.rememberGeometry = source.optBoolean(
                    "rememberGeometry", result.rememberGeometry);
            result.dragHandleVisible = source.optBoolean(
                    "dragHandleVisible", result.dragHandleVisible);
            result.resizeHandleVisible = source.optBoolean(
                    "resizeHandleVisible", result.resizeHandleVisible);
            result.modeButtonVisible = source.optBoolean(
                    "modeButtonVisible", result.modeButtonVisible);
            result.modeButtonPosition = source.optString(
                    "modeButtonPosition", result.modeButtonPosition);
            result.modeButtonSizeDp = source.optInt(
                    "modeButtonSizeDp", result.modeButtonSizeDp);
            result.modeButtonOpacityPercent = source.optInt(
                    "modeButtonOpacityPercent", result.modeButtonOpacityPercent);
            result.closeButtonVisible = source.optBoolean(
                    "closeButtonVisible", result.closeButtonVisible);
            result.keepAboveLauncher = source.optBoolean(
                    "keepAboveLauncher", result.keepAboveLauncher);
            result.normalize();
            return result;
        }

        void normalize() {
            widthPercent = clamp(widthPercent, 20, 100);
            heightPercent = clamp(heightPercent, 20, 100);
            leftPercent = clamp(leftPercent, 0, 100 - widthPercent);
            topPercent = clamp(topPercent, 0, 100 - heightPercent);
            cornerRadiusDp = clamp(cornerRadiusDp, 0, 160);
            opacityPercent = clamp(opacityPercent, 20, 100);
            borderWidthDp = clamp(borderWidthDp, 0, 24);
            borderColor = color(borderColor, "#00000000");
            shadowRadiusDp = clamp(shadowRadiusDp, 0, 96);
            shadowColor = color(shadowColor, "#66000000");
            // The field remains serialized for old Navigator builds, but an opaque outer plane
            // is invalid for the KX11 floating-window contract.
            backgroundColor = "#00000000";
            modeButtonPosition = enumText(modeButtonPosition, "TOP_LEFT",
                    "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT");
            modeButtonSizeDp = clamp(modeButtonSizeDp, 28, 96);
            modeButtonOpacityPercent = clamp(modeButtonOpacityPercent, 20, 100);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
    }

    @NonNull
    private static String enumText(String raw, String fallback, String... allowed) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        return fallback;
    }

    @NonNull
    private static String color(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return value.matches("#[0-9A-F]{8}") ? value : fallback;
    }

    @NonNull
    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= maximum && value.indexOf('\u0000') < 0 ? value : "";
    }
}
