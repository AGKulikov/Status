/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;
import android.view.Surface;

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
    private final MapCursorStyler cursorStyler;

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
    private long activeJamFingerprint;
    private NavigatorStatePublisher.CameraState initialCamera;
    private NavigatorStatePublisher.CameraState navigationCamera;
    private AppliedCamera lastAppliedCamera;
    private boolean freeCameraInitialized;
    private NavigationMapProfile profile = new NavigationMapProfile();

    HudMapRenderer(Context context, FailureReporter reporter) {
        this.context = context.getApplicationContext();
        this.reporter = reporter;
        cursorStyler = new MapCursorStyler(this.context);
    }

    void applyConfiguration(String raw) {
        NavigationMapProfile next = NavigationMapProfile.fromConfiguration(raw, "hudMap");
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

    /**
     * Uses one primary-camera sample only to avoid a blank cold-start map before Guidance emits
     * its first location. Subsequent pan/zoom/rotation gestures on the main map are ignored.
     */
    void updateInitialCamera(NavigatorStatePublisher.CameraState state) {
        if (state == null || !state.isValid() || initialCamera != null
                || navigationCamera != null) return;
        initialCamera = state;
        applyCamera(false);
    }

    /** Canonical navigation location, independent from every visual operation on the main map. */
    void updateNavigationState(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.length() > 256 * 1024) return;
        try {
            JSONObject snapshot = new JSONObject(snapshotJson);
            if (!snapshot.has("latitude") || !snapshot.has("longitude")) return;
            double latitude = snapshot.optDouble("latitude", Double.NaN);
            double longitude = snapshot.optDouble("longitude", Double.NaN);
            double bearing = snapshot.optDouble("bearingDegrees", 0d);
            double speed = snapshot.optDouble("speedKmh", 0d);
            boolean routeActive = snapshot.optBoolean("routeActive", false);
            float baseZoom = routeActive
                    ? speed >= 90d ? 14.5f : speed >= 50d ? 15.2f : 16f
                    : 15.5f;
            NavigatorStatePublisher.CameraState next =
                    new NavigatorStatePublisher.CameraState(latitude, longitude, baseZoom,
                            finite(bearing) ? (float) bearing : 0f, profile.tiltDegrees);
            if (!next.isValid()) return;
            navigationCamera = next;
            applyCamera(true);
        } catch (Exception invalid) {
            Log.w(TAG, "Rejected invalid Guidance camera snapshot", invalid);
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    void updateRoute(long routeEpoch, Object drivingRoute) {
        if (routeEpoch < activeRouteEpoch) return;
        long jamFingerprint = RoutePolylineStyler.jamFingerprint(drivingRoute);
        // MapKit may hand out a new Java wrapper for the same DrivingRoute on every Guidance
        // callback. Object identity is therefore not a route identity; routeEpoch is.
        boolean changed = routeEpoch != activeRouteEpoch
                || (drivingRoute == null) != (activeRoute == null);
        boolean jamsChanged = jamFingerprint != activeJamFingerprint;
        activeRouteEpoch = routeEpoch;
        activeRoute = drivingRoute;
        activeJamFingerprint = jamFingerprint;
        if (changed) rebuildRoute();
        else if (jamsChanged) restyleRoute();
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
            if (userLocationLayer != null) {
                try {
                    cursorStyler.attach(userLocationLayer);
                } catch (Throwable cursorFailure) {
                    Log.w(TAG, "Custom HUD cursor listener unavailable: "
                            + shortMessage(cursorFailure));
                }
            }

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
                try {
                    invoke(currentLocation, "setDefaultSource", new Class<?>[0]);
                    invoke(currentLocation, "setVisible", new Class<?>[]{boolean.class},
                            profile.showCursor);
                    invoke(currentLocation, "setAutoZoomEnabled",
                            new Class<?>[]{boolean.class}, false);
                    invoke(currentLocation, "setHeadingModeActive",
                            new Class<?>[]{boolean.class}, false);
                    // The HUD camera has exactly one owner: Guidance snapshots below. setAnchor
                    // turns UserLocationLayer into a second camera controller, which made MapKit
                    // alternate between its GPS camera and our route-matched camera every second.
                    // The MapWindow focus point already positions the visible cursor correctly.
                    invoke(currentLocation, "resetAnchor", new Class<?>[0]);
                    cursorStyler.apply(profile.showCursor, profile.cursorScalePercent,
                            profile.cursorColor, profile.cursorOutlineColor);
                } catch (Throwable cursorFailure) {
                    Log.w(TAG, "HUD cursor profile could not be applied", cursorFailure);
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
            applyCamera(false);
            rebuildRoute();
        } catch (Throwable failure) {
            Log.w(TAG, "Some HUD MapProfile fields could not be applied", failure);
        }
    }

    /** Follows Guidance location only; all HUD camera parameters remain independently editable. */
    private void applyCamera(boolean animate) {
        Object currentMap = map;
        NavigatorStatePublisher.CameraState source = navigationCamera != null
                ? navigationCamera : initialCamera;
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
            AppliedCamera next = new AppliedCamera(
                    source.latitude, source.longitude, zoom, azimuth, tilt);
            if (next.nearlyEquals(lastAppliedCamera)) return;
            Class<?> cameraClass = Class.forName("com.yandex.mapkit.map.CameraPosition");
            Object camera = cameraClass.getConstructor(
                    pointClass, float.class, float.class, float.class)
                    .newInstance(target, zoom, azimuth, tilt);
            if (animate && lastAppliedCamera != null) {
                Class<?> animationTypeClass = Class.forName(
                        "com.yandex.mapkit.Animation$Type");
                Object smooth = animationTypeClass.getField("SMOOTH").get(null);
                Class<?> animationClass = Class.forName("com.yandex.mapkit.Animation");
                Object animation = animationClass.getConstructor(
                        animationTypeClass, float.class).newInstance(smooth, 0.65f);
                invoke(currentMap, "move",
                        new Class<?>[]{cameraClass, animationClass}, camera, animation);
            } else {
                invoke(currentMap, "move", new Class<?>[]{cameraClass}, camera);
            }
            lastAppliedCamera = next;
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
            RoutePolylineStyler.apply(line, route, profile);
        } catch (Throwable failure) {
            Log.w(TAG, "Active route could not be rendered in the HUD MapWindow", failure);
        }
    }

    /** Updates traffic colours in place so MapKit never presents a frame without the route. */
    private void restyleRoute() {
        Object line = routePolyline;
        Object route = activeRoute;
        if (!profile.showRoute || route == null) return;
        if (line == null) {
            rebuildRoute();
            return;
        }
        try {
            RoutePolylineStyler.apply(line, route, profile);
        } catch (Throwable failure) {
            Log.w(TAG, "HUD route traffic palette could not be updated", failure);
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
        cursorStyler.detach();
        userLocationLayer = null;
        routeCollection = null;
        routePolyline = null;
        freeCameraInitialized = false;
        lastAppliedCamera = null;
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

    /** Output-space camera comparison: profile changes are included, duplicate snapshots are not. */
    private static final class AppliedCamera {
        final double latitude;
        final double longitude;
        final float zoom;
        final float azimuth;
        final float tilt;

        AppliedCamera(double latitude, double longitude, float zoom, float azimuth, float tilt) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.zoom = zoom;
            this.azimuth = azimuth;
            this.tilt = tilt;
        }

        boolean nearlyEquals(AppliedCamera other) {
            return other != null
                    && Math.abs(latitude - other.latitude) < 0.0000001d
                    && Math.abs(longitude - other.longitude) < 0.0000001d
                    && Math.abs(zoom - other.zoom) < 0.01f
                    && angularDistance(azimuth, other.azimuth) < 0.25f
                    && Math.abs(tilt - other.tilt) < 0.25f;
        }

        private static float angularDistance(float first, float second) {
            float delta = Math.abs(first - second) % 360f;
            return Math.min(delta, 360f - delta);
        }
    }

}
