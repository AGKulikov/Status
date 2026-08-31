/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Independent rendering profiles for Navigator's main, HUD and instrument-cluster surfaces. */
public final class NavigationIntegrationConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_CHARS = 384 * 1024;

    @NonNull public MapProfile mainMap = MapProfile.defaults(Target.MAIN);
    @NonNull public MapProfile hudMap = MapProfile.defaults(Target.HUD);
    @NonNull public MapProfile clusterMap = MapProfile.defaults(Target.CLUSTER);
    @NonNull public FloatingWindowProfile mainFloatingWindow = FloatingWindowProfile.defaults();

    public enum Target { MAIN, HUD, CLUSTER }

    /** Visibility of one Yandex road-event type on the independent HUD map. */
    public enum RoadEventMode {
        HIDDEN,
        ALWAYS,
        ROUTE_ONLY;

        @NonNull
        static RoadEventMode fromJson(String raw, @NonNull RoadEventMode fallback) {
            String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            for (RoadEventMode mode : values()) {
                if (mode.name().equals(value)) return mode;
            }
            return fallback;
        }
    }

    /** Stable MapKit EventTag name plus the Russian label shown in Natro settings. */
    public static final class RoadEventSpec {
        @NonNull public final String tag;
        @NonNull public final String title;
        @NonNull public final String group;
        @NonNull public final RoadEventMode defaultMode;

        private RoadEventSpec(@NonNull String tag, @NonNull String title,
                              @NonNull String group,
                              @NonNull RoadEventMode defaultMode) {
            this.tag = tag;
            this.title = title;
            this.group = group;
            this.defaultMode = defaultMode;
        }
    }

    /** Every EventTag exported by the MapKit embedded in Navigator 30.3.0. */
    public static final RoadEventSpec[] HUD_ROAD_EVENTS = new RoadEventSpec[]{
            event("ACCIDENT", "ДТП", "Дорожные события", RoadEventMode.ALWAYS),
            event("RECONSTRUCTION", "Дорожные работы", "Дорожные события",
                    RoadEventMode.ALWAYS),
            event("CHAT", "Разговорчики", "Дорожные события", RoadEventMode.ALWAYS),
            event("LOCAL_CHAT", "Локальные разговорчики", "Дорожные события",
                    RoadEventMode.ALWAYS),
            event("CLOSED", "Перекрытие", "Дорожные события", RoadEventMode.ALWAYS),
            event("DRAWBRIDGE", "Разведение мостов", "Дорожные события",
                    RoadEventMode.ALWAYS),
            event("DANGER", "Опасный участок", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("OTHER", "Прочее", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("SPEED_CONTROL", "Камера контроля скорости", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("NO_STOPPING_CONTROL", "Камера контроля остановки",
                    "Камеры и предупреждения", RoadEventMode.ROUTE_ONLY),
            event("LANE_CONTROL", "Камера контроля полосы", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("ROAD_MARKING_CONTROL", "Камера контроля разметки",
                    "Камеры и предупреждения", RoadEventMode.ROUTE_ONLY),
            event("MOBILE_CONTROL", "Мобильная засада", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("CROSS_ROAD_CONTROL", "Камера контроля перекрёстка",
                    "Камеры и предупреждения", RoadEventMode.ROUTE_ONLY),
            event("TRAFFIC_CONTROL", "Камера контроля светофора",
                    "Камеры и предупреждения", RoadEventMode.ROUTE_ONLY),
            event("CROSS_ROAD_DANGER", "Опасный перекрёсток", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("OVERTAKING_DANGER", "Опасный обгон", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("PEDESTRIAN_DANGER", "Опасный пешеходный переход",
                    "Камеры и предупреждения", RoadEventMode.ROUTE_ONLY),
            event("SCHOOL", "Школа", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("POLICE", "Полиция", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("POLICE_PATROL", "Патруль", "Камеры и предупреждения",
                    RoadEventMode.ROUTE_ONLY),
            event("FEEDBACK", "Отзывы о дорожном событии", "Служебные события",
                    RoadEventMode.HIDDEN)
    };

    private static RoadEventSpec event(@NonNull String tag, @NonNull String title,
                                       @NonNull String group,
                                       @NonNull RoadEventMode defaultMode) {
        return new RoadEventSpec(tag, title, group, defaultMode);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        normalize();
        return new JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("mainMap", mainMap.toJson())
                .put("hudMap", hudMap.toJson())
                .put("clusterMap", clusterMap.toJson())
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
            result.clusterMap = MapProfile.fromJson(
                    Target.CLUSTER, source.optJSONObject("clusterMap"));
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
        if (clusterMap == null || clusterMap.target != Target.CLUSTER) {
            clusterMap = MapProfile.defaults(Target.CLUSTER);
        }
        if (mainFloatingWindow == null) mainFloatingWindow = FloatingWindowProfile.defaults();
        mainMap.normalize();
        hudMap.normalize();
        clusterMap.normalize();
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
        /** Final point of the active route, anchored to the last route geometry point. */
        public boolean showDestination = true;
        /** Colour the active route by congestion independently from the background traffic layer. */
        public boolean showRouteTraffic = true;
        public boolean showTraffic = true;
        /** Fresh Windshield traffic lights, rendered in their own map-object collection. */
        public boolean showTrafficLights = true;
        /** Upcoming lane sign anchored to its RoutePosition on the map. */
        public boolean showLaneGuidance = true;
        /** Scale of Yandex's original lane-sign view; 100 keeps the stock MapKit size. */
        public int laneGuidanceScalePercent = 100;
        /** User-controlled global stacking order; larger values are drawn above smaller ones. */
        public int cameraDirectionLayerPriority = 20;
        public int routeLayerPriority = 40;
        public int trafficLightLayerPriority = 50;
        public int routeLabelLayerPriority = 60;
        public int laneGuidanceLayerPriority = 80;
        public int cursorLayerPriority = 90;
        public boolean showLabels = true;
        /** Hide substrate labels and draw only verified names attached to the active route. */
        public boolean routeStreetLabelsOnly;
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
        /** Substrate road colour; route and traffic layers are rendered independently above it. */
        @NonNull public String roadColor = "";
        /** Multipliers keep 100% bit-for-bit compatible with the 2.5.7 rendering. */
        public int routeWidthPercent = 100;
        public int roadWidthPercent = 100;
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
        /** EventTag -> HIDDEN / ALWAYS / ROUTE_ONLY, in stable UI order. */
        @NonNull public final LinkedHashMap<String, RoadEventMode> roadEventModes =
                new LinkedHashMap<>();

        private MapProfile(@NonNull Target target) {
            this.target = target;
            resetRoadEventModes();
        }

        @NonNull
        public static MapProfile defaults(@NonNull Target target) {
            MapProfile result = new MapProfile(target);
            result.enabled = true;
            result.maximumFps = target == Target.CLUSTER ? 60 : 30;
            if (target == Target.HUD) {
                result.roadColor = "#FF536274";
                result.showPois = false;
                result.showBuildings = false;
                result.showModels = false;
            } else if (target == Target.CLUSTER) {
                result.roadColor = "#FF536274";
                // The instrument display keeps the useful road context but avoids expensive 3D
                // models by default. Its native-size surface is adaptively throttled by Navigator
                // while the car is stationary.
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
                    .put("showDestination", showDestination)
                    .put("showRouteTraffic", showRouteTraffic)
                    .put("showTraffic", showTraffic)
                    .put("showTrafficLights", showTrafficLights)
                    .put("showLaneGuidance", showLaneGuidance)
                    .put("laneGuidanceScalePercent", laneGuidanceScalePercent)
                    .put("cameraDirectionLayerPriority", cameraDirectionLayerPriority)
                    .put("routeLayerPriority", routeLayerPriority)
                    .put("trafficLightLayerPriority", trafficLightLayerPriority)
                    .put("routeLabelLayerPriority", routeLabelLayerPriority)
                    .put("laneGuidanceLayerPriority", laneGuidanceLayerPriority)
                    .put("cursorLayerPriority", cursorLayerPriority)
                    .put("showLabels", showLabels)
                    .put("routeStreetLabelsOnly", routeStreetLabelsOnly)
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
                    .put("roadColor", roadColor)
                    .put("routeWidthPercent", routeWidthPercent)
                    .put("roadWidthPercent", roadWidthPercent)
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
                    .put("nightStyleJson", nightStyleJson)
                    .put("roadEvents", roadEventsJson());
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
            result.showDestination = source.optBoolean(
                    "showDestination", result.showDestination);
            result.showTraffic = source.optBoolean("showTraffic", result.showTraffic);
            result.showRouteTraffic = source.optBoolean(
                    "showRouteTraffic", result.showRouteTraffic);
            result.showTrafficLights = source.optBoolean(
                    "showTrafficLights", result.showTrafficLights);
            result.showLaneGuidance = source.optBoolean(
                    "showLaneGuidance", result.showLaneGuidance);
            result.laneGuidanceScalePercent = source.optInt(
                    "laneGuidanceScalePercent", result.laneGuidanceScalePercent);
            result.cameraDirectionLayerPriority = source.optInt(
                    "cameraDirectionLayerPriority", result.cameraDirectionLayerPriority);
            result.routeLayerPriority = source.optInt(
                    "routeLayerPriority", result.routeLayerPriority);
            result.trafficLightLayerPriority = source.optInt(
                    "trafficLightLayerPriority", result.trafficLightLayerPriority);
            result.routeLabelLayerPriority = source.optInt(
                    "routeLabelLayerPriority", result.routeLabelLayerPriority);
            result.laneGuidanceLayerPriority = source.optInt(
                    "laneGuidanceLayerPriority", result.laneGuidanceLayerPriority);
            result.cursorLayerPriority = source.optInt(
                    "cursorLayerPriority", result.cursorLayerPriority);
            result.showLabels = source.optBoolean("showLabels", result.showLabels);
            result.routeStreetLabelsOnly = source.optBoolean(
                    "routeStreetLabelsOnly", result.routeStreetLabelsOnly);
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
            result.roadColor = source.optString("roadColor", result.roadColor);
            result.routeWidthPercent = source.optInt(
                    "routeWidthPercent", result.routeWidthPercent);
            result.roadWidthPercent = source.optInt(
                    "roadWidthPercent", result.roadWidthPercent);
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
            JSONObject events = source.optJSONObject("roadEvents");
            if (events != null) {
                for (RoadEventSpec spec : HUD_ROAD_EVENTS) {
                    result.roadEventModes.put(spec.tag, RoadEventMode.fromJson(
                            events.optString(spec.tag, ""), spec.defaultMode));
                }
            }
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
            laneGuidanceScalePercent = clamp(laneGuidanceScalePercent, 50, 250);
            cameraDirectionLayerPriority = clamp(cameraDirectionLayerPriority, 0, 100);
            routeLayerPriority = clamp(routeLayerPriority, 0, 100);
            trafficLightLayerPriority = clamp(trafficLightLayerPriority, 0, 100);
            routeLabelLayerPriority = clamp(routeLabelLayerPriority, 0, 100);
            laneGuidanceLayerPriority = clamp(laneGuidanceLayerPriority, 0, 100);
            cursorLayerPriority = clamp(cursorLayerPriority, 0, 100);
            routeWidthPercent = clamp(routeWidthPercent, 25, 300);
            roadWidthPercent = clamp(roadWidthPercent, 25, 300);
            routeWidth = clamp(routeWidth, 1d, 40d, 8d);
            routeOutlineWidth = clamp(routeOutlineWidth, 0d, 20d, 2d);
            trafficGradientLength = clamp(trafficGradientLength, 0d, 100d, 12d);
            maximumFps = clamp(maximumFps, 1, 60);
            cursorColor = color(cursorColor, "#FFFFC400");
            cursorOutlineColor = color(cursorOutlineColor, "#FF17191E");
            routeColor = color(routeColor, "#FFFFC400");
            routeOutlineColor = color(routeOutlineColor, "#FF16181D");
            roadColor = color(roadColor,
                    target == Target.MAIN ? "" : "#FF536274");
            trafficFreeColor = color(trafficFreeColor, "#FF39B54A");
            trafficLightColor = color(trafficLightColor, "#FFFFD54F");
            trafficHardColor = color(trafficHardColor, "#FFFF8A3D");
            trafficVeryHardColor = color(trafficVeryHardColor, "#FFF04444");
            trafficBlockedColor = color(trafficBlockedColor, "#FF7E1D2D");
            trafficUnknownColor = color(trafficUnknownColor, "#FF8A9099");
            dayStyleJson = bounded(dayStyleJson, MAX_STYLE_CHARS);
            nightStyleJson = bounded(nightStyleJson, MAX_STYLE_CHARS);
            LinkedHashMap<String, RoadEventMode> normalized = new LinkedHashMap<>();
            for (RoadEventSpec spec : HUD_ROAD_EVENTS) {
                RoadEventMode value = roadEventModes.get(spec.tag);
                normalized.put(spec.tag, value == null ? spec.defaultMode : value);
            }
            roadEventModes.clear();
            roadEventModes.putAll(normalized);
        }

        @NonNull
        public RoadEventMode roadEventMode(@NonNull String tag) {
            RoadEventMode value = roadEventModes.get(tag);
            if (value != null) return value;
            for (RoadEventSpec spec : HUD_ROAD_EVENTS) {
                if (spec.tag.equals(tag)) return spec.defaultMode;
            }
            return RoadEventMode.HIDDEN;
        }

        public void setRoadEventMode(@NonNull String tag, @NonNull RoadEventMode mode) {
            for (RoadEventSpec spec : HUD_ROAD_EVENTS) {
                if (spec.tag.equals(tag)) {
                    roadEventModes.put(tag, mode);
                    return;
                }
            }
        }

        private void resetRoadEventModes() {
            roadEventModes.clear();
            for (RoadEventSpec spec : HUD_ROAD_EVENTS) {
                roadEventModes.put(spec.tag, spec.defaultMode);
            }
        }

        @NonNull
        private JSONObject roadEventsJson() throws JSONException {
            JSONObject result = new JSONObject();
            for (Map.Entry<String, RoadEventMode> entry : roadEventModes.entrySet()) {
                result.put(entry.getKey(), entry.getValue().name());
            }
            return result;
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
        // AppleColorPicker intentionally omits FF alpha for an opaque swatch. Accept its
        // #RRGGBB output and canonicalise it so every persisted/wire colour remains #AARRGGBB.
        if (value.matches("#[0-9A-F]{6}")) return "#FF" + value.substring(1);
        return value.matches("#[0-9A-F]{8}") ? value : fallback;
    }

    @NonNull
    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= maximum && value.indexOf('\u0000') < 0 ? value : "";
    }
}
