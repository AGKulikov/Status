/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PointF;
import android.util.Log;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Clean-room owner of the second MapKit MapWindow rendered directly into Natro's HUD Surface.
 *
 * <p>Reflection keeps this patch source compilable against the Android SDK alone. At runtime every
 * resolved class and method is present in the exact 30.3.0 baseline. The renderer uses MapKit's
 * public external-surface path: createOffscreenMapWindow -> SurfaceFactory.from -> addSurface,
 * followed by removeSurface during teardown. It never calls captureScreenshot.</p>
 */
final class HudMapRenderer {
    interface FailureReporter {
        void onSurfaceLost(long generation, String detail);
    }

    private static final String TAG = "NatroHudMap";
    /** High, stable slots avoid replacing styles that Navigator itself may install. */
    private static final int CUSTOM_STYLE_ID = 0x4E415452; // "NATR"
    private static final int VISIBILITY_STYLE_ID = CUSTOM_STYLE_ID + 1;
    private final Context context;
    private final FailureReporter reporter;

    private Surface surface;
    private int width;
    private int height;
    private long generation = -1L;
    private Object offscreenMapWindow;
    private Object runtimeSurface;
    private boolean runtimeSurfaceAttached;
    private Object mapWindow;
    private Object map;
    private Object trafficLayer;
    private Object userLocationLayer;
    private Object routeCollection;
    private Object routePolyline;
    private Object activeRoute;
    private long activeRouteEpoch = -1L;
    private NavigatorStatePublisher.CameraState primaryCamera;
    private boolean freeCameraInitialized;
    private HudProfile profile = new HudProfile();

    HudMapRenderer(Context context, FailureReporter reporter) {
        this.context = context.getApplicationContext();
        this.reporter = reporter;
    }

    void applyConfiguration(String raw) {
        HudProfile next = HudProfile.fromConfiguration(raw);
        boolean enabledChanged = profile.enabled != next.enabled;
        boolean cameraModeChanged = !profile.cameraMode.equals(next.cameraMode);
        profile = next;
        if (cameraModeChanged) freeCameraInitialized = false;
        if (surface == null) return;
        if (enabledChanged) {
            if (profile.enabled) startRenderer();
            else stopRenderer(false);
        } else if (mapWindow != null) {
            applyProfile();
        } else if (profile.enabled) {
            startRenderer();
        }
    }

    void attach(Surface next, int nextWidth, int nextHeight, long nextGeneration) {
        if (next == null || !next.isValid() || nextWidth <= 0 || nextHeight <= 0
                || nextGeneration < 0L) {
            if (next != null) try { next.release(); } catch (RuntimeException ignored) {}
            return;
        }
        if (nextGeneration <= generation) {
            try { next.release(); } catch (RuntimeException ignored) {}
            return;
        }
        stopRenderer(true);
        surface = next;
        width = nextWidth;
        height = nextHeight;
        generation = nextGeneration;
        if (profile.enabled) startRenderer();
    }

    void detach(long detachedGeneration) {
        if (detachedGeneration != generation) return;
        stopRenderer(true);
    }

    void disconnect() {
        stopRenderer(true);
    }

    void updatePrimaryCamera(NavigatorStatePublisher.CameraState state) {
        if (state == null || !state.isValid()) return;
        primaryCamera = state;
        applyCamera();
    }

    void updateRoute(long routeEpoch, Object drivingRoute) {
        if (routeEpoch < activeRouteEpoch) return;
        boolean changed = routeEpoch != activeRouteEpoch || drivingRoute != activeRoute;
        activeRouteEpoch = routeEpoch;
        activeRoute = drivingRoute;
        if (changed) rebuildRoute();
    }

    private void startRenderer() {
        if (surface == null || !surface.isValid() || mapWindow != null || !profile.enabled) return;
        try {
            Class<?> factoryClass = Class.forName("com.yandex.mapkit.MapKitFactory");
            Object mapKit = factoryClass.getMethod("getInstance").invoke(null);
            Class<?> mapKitClass = Class.forName("com.yandex.mapkit.MapKit");
            Object nextOffscreen = mapKitClass.getMethod(
                    "createOffscreenMapWindow", int.class, int.class)
                    .invoke(mapKit, width, height);
            offscreenMapWindow = nextOffscreen;
            Object nextMapWindow = invoke(nextOffscreen, "getMapWindow", new Class<?>[0]);
            mapWindow = nextMapWindow;
            map = invoke(nextMapWindow, "getMap", new Class<?>[0]);

            Class<?> runtimeSurfaceClass = Class.forName("com.yandex.runtime.view.Surface");
            Class<?> surfaceFactoryClass = Class.forName(
                    "com.yandex.runtime.view.SurfaceFactory");
            Object nextRuntimeSurface = surfaceFactoryClass.getMethod(
                    "from", Surface.class).invoke(null, surface);
            runtimeSurface = nextRuntimeSurface;
            invoke(nextMapWindow, "addSurface",
                    new Class<?>[]{runtimeSurfaceClass}, nextRuntimeSurface);
            runtimeSurfaceAttached = true;

            // These enrich the map, but neither is allowed to take down the core renderer.
            Class<?> mapWindowClass = Class.forName("com.yandex.mapkit.map.MapWindow");
            trafficLayer = createOptionalLayer(
                    mapKit, mapKitClass, mapWindowClass, nextMapWindow,
                    "createTrafficLayer");
            userLocationLayer = createOptionalLayer(
                    mapKit, mapKitClass, mapWindowClass, nextMapWindow,
                    "createUserLocationLayer");

            applyProfile();
            Log.i(TAG, "Independent HUD OffscreenMapWindow attached, generation=" + generation
                    + ", size=" + width + "x" + height);
        } catch (Throwable failure) {
            long failedGeneration = generation;
            String detail = shortMessage(failure);
            Log.e(TAG, "Could not attach independent HUD MapWindow", failure);
            stopRenderer(false);
            reporter.onSurfaceLost(failedGeneration, detail);
        }
    }

    private void applyProfile() {
        Object currentWindow = mapWindow;
        Object currentMap = map;
        if (currentWindow == null || currentMap == null) return;
        try {
            invoke(currentWindow, "setMaxFps", new Class<?>[]{int.class}, profile.maximumFps);
            invoke(currentWindow, "setScaleFactor", new Class<?>[]{float.class},
                    profile.mapScalePercent / 100f);
            Class<?> pointClass = Class.forName("com.yandex.mapkit.ScreenPoint");
            Object focus = pointClass.getConstructor(float.class, float.class).newInstance(
                    width * profile.focusXPercent / 100f,
                    height * profile.focusYPercent / 100f);
            invoke(currentWindow, "setFocusPoint", new Class<?>[]{pointClass}, focus);
            Object currentTraffic = trafficLayer;
            if (currentTraffic != null) {
                invoke(currentTraffic, "setTrafficVisible",
                        new Class<?>[]{boolean.class}, profile.showTraffic);
            }
            Object currentLocation = userLocationLayer;
            if (currentLocation != null) {
                invoke(currentLocation, "setDefaultSource", new Class<?>[0]);
                invoke(currentLocation, "setVisible", new Class<?>[]{boolean.class},
                        profile.showCursor);
                boolean free = "FREE".equals(profile.cameraMode);
                invoke(currentLocation, "setAutoZoomEnabled",
                        new Class<?>[]{boolean.class}, false);
                invoke(currentLocation, "setHeadingModeActive",
                        new Class<?>[]{boolean.class}, false);
                if (free) {
                    invoke(currentLocation, "resetAnchor", new Class<?>[0]);
                } else {
                    PointF anchor = new PointF(width * profile.focusXPercent / 100f,
                            height * profile.focusYPercent / 100f);
                    invoke(currentLocation, "setAnchor",
                            new Class<?>[]{PointF.class, PointF.class}, anchor, anchor);
                }
            }
            boolean systemNight = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            boolean night = profile.automaticDayNight ? systemNight : profile.nightMode;
            invoke(currentMap, "setNightModeEnabled", new Class<?>[]{boolean.class}, night);
            invoke(currentMap, "setModelsEnabled", new Class<?>[]{boolean.class},
                    profile.showModels);
            invoke(currentMap, "setAwesomeModelsEnabled", new Class<?>[]{boolean.class},
                    profile.showModels);
            invoke(currentMap, "setPoiLimit", new Class<?>[]{Integer.class},
                    profile.showPois ? null : Integer.valueOf(0));
            invoke(currentMap, "setTransparentBackgroundEnabled",
                    new Class<?>[]{boolean.class}, false);
            invoke(currentMap, "setRotateGesturesEnabled", new Class<?>[]{boolean.class}, false);
            invoke(currentMap, "setScrollGesturesEnabled", new Class<?>[]{boolean.class}, false);
            invoke(currentMap, "setTiltGesturesEnabled", new Class<?>[]{boolean.class}, false);

            String style = night ? profile.nightStyleJson : profile.dayStyleJson;
            applyStyleSlot(currentMap, CUSTOM_STYLE_ID, style);
            applyStyleSlot(currentMap, VISIBILITY_STYLE_ID,
                    profile.visibilityStyleJson());
            applyCamera();
            rebuildRoute();
        } catch (Throwable failure) {
            Log.w(TAG, "Some HUD MapProfile fields could not be applied", failure);
        }
    }

    /** Mirrors only navigation state; all HUD camera parameters remain independently editable. */
    private void applyCamera() {
        Object currentMap = map;
        NavigatorStatePublisher.CameraState source = primaryCamera;
        if (currentMap == null || source == null || !source.isValid()) return;
        boolean free = "FREE".equals(profile.cameraMode);
        if (free && freeCameraInitialized) return;
        try {
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object target = pointClass.getConstructor(double.class, double.class)
                    .newInstance(source.latitude, source.longitude);
            float zoom = (float) Math.max(0d, Math.min(23d,
                    source.zoom + profile.zoomDelta));
            float azimuth = "NORTH_UP".equals(profile.cameraMode) ? 0f : source.azimuth;
            float tilt = profile.tiltDegrees;
            Class<?> cameraClass = Class.forName("com.yandex.mapkit.map.CameraPosition");
            Object camera = cameraClass.getConstructor(
                    pointClass, float.class, float.class, float.class)
                    .newInstance(target, zoom, azimuth, tilt);
            invoke(currentMap, "move", new Class<?>[]{cameraClass}, camera);
            if (free) freeCameraInitialized = true;
        } catch (Throwable failure) {
            Log.w(TAG, "HUD camera synchronization failed", failure);
        }
    }

    /** Renders the exact active DrivingRoute geometry into this independent MapWindow. */
    private void rebuildRoute() {
        Object currentMap = map;
        if (currentMap == null) return;
        try {
            Object collection = routeCollection;
            if (collection == null) {
                Object root = invoke(currentMap, "getMapObjects", new Class<?>[0]);
                collection = invoke(root, "addCollection", new Class<?>[0]);
                routeCollection = collection;
            }
            invoke(collection, "clear", new Class<?>[0]);
            routePolyline = null;
            Object route = activeRoute;
            if (!profile.showRoute || route == null) return;
            Object geometry = invoke(route, "getGeometry", new Class<?>[0]);
            if (geometry == null) return;
            Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
            Object line = invoke(collection, "addPolyline",
                    new Class<?>[]{polylineClass}, geometry);
            routePolyline = line;
            invoke(line, "setStrokeColor", new Class<?>[]{int.class},
                    Color.parseColor(profile.routeColor));
            invoke(line, "setOutlineColor", new Class<?>[]{int.class},
                    Color.parseColor(profile.routeOutlineColor));
            invoke(line, "setStrokeWidth", new Class<?>[]{float.class},
                    (float) profile.routeWidth);
            invoke(line, "setOutlineWidth", new Class<?>[]{float.class},
                    (float) profile.routeOutlineWidth);
            invoke(line, "setZIndex", new Class<?>[]{float.class}, 100f);
            invoke(line, "setVisible", new Class<?>[]{boolean.class}, true);
        } catch (Throwable failure) {
            Log.w(TAG, "Active route could not be rendered in the HUD MapWindow", failure);
        }
    }

    private static Object createOptionalLayer(Object mapKit, Class<?> mapKitClass,
                                              Class<?> mapWindowClass, Object mapWindow,
                                              String methodName) {
        try {
            return mapKitClass.getMethod(methodName, mapWindowClass)
                    .invoke(mapKit, mapWindow);
        } catch (Throwable failure) {
            Log.w(TAG, "Optional HUD layer unavailable: " + methodName + ": "
                    + shortMessage(failure));
            return null;
        }
    }

    /** Empty string is MapKit's documented way to clear one previously applied style slot. */
    private static void applyStyleSlot(Object target, int id, String style) throws Exception {
        Class<?>[] signature = new Class<?>[]{int.class, String.class};
        invoke(target, "setMapStyle", signature, id, "");
        if (style != null && !style.isEmpty()) {
            invoke(target, "setMapStyle", signature, id, style);
        }
    }

    private void stopRenderer(boolean releaseSurface) {
        Object currentMapWindow = mapWindow;
        Object currentRuntimeSurface = runtimeSurface;
        Surface currentSurface = surface;
        if (runtimeSurfaceAttached && currentMapWindow != null
                && currentRuntimeSurface != null) {
            try {
                Class<?> runtimeSurfaceClass = Class.forName(
                        "com.yandex.runtime.view.Surface");
                invoke(currentMapWindow, "removeSurface",
                        new Class<?>[]{runtimeSurfaceClass}, currentRuntimeSurface);
            } catch (Throwable ignored) {}
        }
        runtimeSurfaceAttached = false;
        runtimeSurface = null;
        offscreenMapWindow = null;
        mapWindow = null;
        map = null;
        trafficLayer = null;
        userLocationLayer = null;
        routeCollection = null;
        routePolyline = null;
        freeCameraInitialized = false;
        if (releaseSurface && currentSurface != null) {
            try { currentSurface.release(); } catch (RuntimeException ignored) {}
            surface = null;
            width = 0;
            height = 0;
        }
        if (releaseSurface) generation = -1L;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static String shortMessage(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null && value.getCause() != value) value = value.getCause();
        String detail = value.getMessage();
        String result = value.getClass().getSimpleName()
                + (detail == null || detail.isEmpty() ? "" : ": " + detail);
        return result.length() > 240 ? result.substring(0, 240) : result;
    }

    private static final class HudProfile {
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
        String routeColor = "#FFFFC400";
        String routeOutlineColor = "#FF16181D";
        double routeWidth = 8d;
        double routeOutlineWidth = 2d;
        String dayStyleJson = "";
        String nightStyleJson = "";

        static HudProfile fromConfiguration(String raw) {
            HudProfile result = new HudProfile();
            if (raw == null || raw.length() > 384 * 1024 || raw.indexOf('\u0000') >= 0) {
                return result;
            }
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject source = root.optJSONObject("hudMap");
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
                result.routeColor = color(source.optString(
                        "routeColor", "#FFFFC400"), "#FFFFC400");
                result.routeOutlineColor = color(source.optString(
                        "routeOutlineColor", "#FF16181D"), "#FF16181D");
                result.routeWidth = clamp(
                        source.optDouble("routeWidth", 8d), 1d, 40d, 8d);
                result.routeOutlineWidth = clamp(source.optDouble(
                        "routeOutlineWidth", 2d), 0d, 20d, 2d);
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
            return value.length() <= 128 * 1024 && value.indexOf('\u0000') < 0 ? value : "";
        }

        private static String enumText(String raw) {
            String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            return "NORTH_UP".equals(value) || "HEADING_UP".equals(value)
                    || "FREE".equals(value) ? value : "FOLLOW_ROUTE";
        }
    }
}
