/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/** Applies Natro's main-map profile without changing Navigator resources or its session owner. */
final class MainMapController {
    private static final String TAG = "NatroMainMap";
    private static final int CUSTOM_STYLE_ID = 0x4E41544D; // "NATM"
    private static final int VISIBILITY_STYLE_ID = CUSTOM_STYLE_ID + 1;
    private static final int MAX_EXPECTED_CAMERA_EVENTS = 8;

    private final Context context;
    private final MapCursorStyler cursorStyler;
    private final ArrayDeque<NavigatorStatePublisher.CameraState> expectedCameraEvents =
            new ArrayDeque<>();
    private final ArrayList<ManagedSublayer> routeSublayers = new ArrayList<>();
    private final ArrayList<ManagedSublayer> cursorSublayers = new ArrayList<>();

    private NavigationMapProfile profile = new NavigationMapProfile();
    private Object mapWindow;
    private Object map;
    private Object userLocationLayer;
    private Object routeCollection;
    private Object routePolyline;
    private Object activeRoute;
    private long activeRouteEpoch = -1L;
    private long activeJamFingerprint;
    private NavigatorStatePublisher.CameraState sourceCamera;

    private boolean originalsCaptured;
    private float originalScaleFactor = 1f;
    private Object originalFocusPoint;
    private boolean originalNightMode;
    private boolean originalModelsEnabled;
    private boolean originalAwesomeModelsEnabled;
    private Integer originalPoiLimit;

    MainMapController(Context context) {
        this.context = context.getApplicationContext();
        cursorStyler = new MapCursorStyler(this.context);
    }

    void applyConfiguration(String raw) {
        profile = NavigationMapProfile.fromConfiguration(raw, "mainMap");
        if (map == null || mapWindow == null) return;
        if (profile.enabled) applyProfile();
        else deactivate();
    }

    void attach(Object nextMapWindow, Object nextMap) {
        if (mapWindow == nextMapWindow && map == nextMap) {
            if (profile.enabled) applyProfile();
            return;
        }
        detach();
        mapWindow = nextMapWindow;
        map = nextMap;
        if (nextMapWindow == null || nextMap == null) return;
        captureOriginals();
        if (profile.enabled) applyProfile();
    }

    void detach() {
        deactivate();
        cursorStyler.detach();
        userLocationLayer = null;
        routeCollection = null;
        routePolyline = null;
        mapWindow = null;
        map = null;
        activeRoute = null;
        activeRouteEpoch = -1L;
        activeJamFingerprint = 0L;
        sourceCamera = null;
        originalsCaptured = false;
        originalFocusPoint = null;
        routeSublayers.clear();
        cursorSublayers.clear();
        expectedCameraEvents.clear();
    }

    /** Returns true only for the camera callback caused by this controller's own Map.move. */
    boolean updatePrimaryCamera(NavigatorStatePublisher.CameraState state) {
        if (state == null || !state.isValid()) return false;
        if (consumeExpectedCamera(state)) return true;
        sourceCamera = state;
        // Navigator owns follow-mode on its primary MapWindow. Writing a second CameraPosition
        // from the listener races its guidance camera and produces visible jumping on KX11.
        // Independent camera transforms belong to the HUD MapWindow only.
        return false;
    }

    void updateRoute(long routeEpoch, Object drivingRoute) {
        if (routeEpoch < activeRouteEpoch) return;
        long jamFingerprint = RoutePolylineStyler.jamFingerprint(drivingRoute);
        boolean changed = routeEpoch != activeRouteEpoch || drivingRoute != activeRoute;
        boolean jamsChanged = jamFingerprint != activeJamFingerprint;
        activeRouteEpoch = routeEpoch;
        activeRoute = drivingRoute;
        activeJamFingerprint = jamFingerprint;
        if (profile.enabled && (changed || jamsChanged)) rebuildRoute();
    }

    private void applyProfile() {
        Object currentWindow = mapWindow;
        Object currentMap = map;
        if (currentWindow == null || currentMap == null || !profile.enabled) return;
        try {
            captureOriginals();
            invoke(currentWindow, "setMaxFps", new Class<?>[]{int.class}, profile.maximumFps);
            invoke(currentWindow, "setScaleFactor", new Class<?>[]{float.class},
                    originalScaleFactor * profile.mapScalePercent / 100f);
            int width = ((Number) invoke(currentWindow, "width", new Class<?>[0])).intValue();
            int height = ((Number) invoke(currentWindow, "height", new Class<?>[0])).intValue();
            Class<?> screenPointClass = Class.forName("com.yandex.mapkit.ScreenPoint");
            Object focus = screenPointClass.getConstructor(float.class, float.class).newInstance(
                    width * profile.focusXPercent / 100f,
                    height * profile.focusYPercent / 100f);
            invoke(currentWindow, "setFocusPoint", new Class<?>[]{screenPointClass}, focus);

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
            applyStyleSlot(currentMap, CUSTOM_STYLE_ID,
                    night ? profile.nightStyleJson : profile.dayStyleJson);
            applyStyleSlot(currentMap, VISIBILITY_STYLE_ID, profile.visibilityStyleJson());

            // Do not create a second UserLocationLayer or route collection on Navigator's own
            // map. They duplicate the stock arrow/route, and hiding obfuscated 30.3 sublayers is
            // not an atomic replacement. Safe visual fields above remain supported while the
            // stock navigation presentation stays the single owner of cursor, route and camera.
            Log.i(TAG, "Stable main MapProfile applied without cursor/camera replacement");
        } catch (Throwable failure) {
            Log.w(TAG, "Some main MapProfile fields could not be applied", failure);
        }
    }

    private void captureOriginals() {
        if (originalsCaptured || mapWindow == null || map == null) return;
        try {
            originalScaleFactor = ((Number) invoke(
                    mapWindow, "getScaleFactor", new Class<?>[0])).floatValue();
            originalFocusPoint = invoke(mapWindow, "getFocusPoint", new Class<?>[0]);
            originalNightMode = Boolean.TRUE.equals(invoke(
                    map, "isNightModeEnabled", new Class<?>[0]));
            originalModelsEnabled = Boolean.TRUE.equals(invoke(
                    map, "isModelsEnabled", new Class<?>[0]));
            originalAwesomeModelsEnabled = Boolean.TRUE.equals(invoke(
                    map, "isAwesomeModelsEnabled", new Class<?>[0]));
            Object poi = invoke(map, "getPoiLimit", new Class<?>[0]);
            originalPoiLimit = poi instanceof Integer ? (Integer) poi : null;
            originalsCaptured = true;
        } catch (Throwable failure) {
            Log.w(TAG, "Could not snapshot main map defaults", failure);
        }
    }

    private void ensureUserLocationLayer() throws Exception {
        if (userLocationLayer == null) {
            Class<?> factoryClass = Class.forName("com.yandex.mapkit.MapKitFactory");
            Object mapKit = factoryClass.getMethod("getInstance").invoke(null);
            Class<?> mapKitClass = Class.forName("com.yandex.mapkit.MapKit");
            Class<?> mapWindowClass = Class.forName("com.yandex.mapkit.map.MapWindow");
            userLocationLayer = mapKitClass.getMethod(
                    "createUserLocationLayer", mapWindowClass).invoke(mapKit, mapWindow);
        }
        cursorStyler.attach(userLocationLayer);
    }

    private void configureUserLocation(int width, int height) throws Exception {
        Object layer = userLocationLayer;
        if (layer == null) return;
        invoke(layer, "setDefaultSource", new Class<?>[0]);
        invoke(layer, "setAutoZoomEnabled", new Class<?>[]{boolean.class}, false);
        invoke(layer, "setHeadingModeActive", new Class<?>[]{boolean.class}, false);
        if ("FREE".equals(profile.cameraMode)) {
            invoke(layer, "resetAnchor", new Class<?>[0]);
        } else {
            PointF anchor = new PointF(width * profile.focusXPercent / 100f,
                    height * profile.focusYPercent / 100f);
            invoke(layer, "setAnchor", new Class<?>[]{PointF.class, PointF.class},
                    anchor, anchor);
        }
        cursorStyler.apply(profile.showCursor, profile.cursorScalePercent,
                profile.cursorColor, profile.cursorOutlineColor);
    }

    private void rebuildRoute() {
        Object currentMap = map;
        if (currentMap == null || !profile.enabled) return;
        try {
            ensureManagedSublayers();
            Object collection = routeCollection;
            if (collection == null) {
                Object root = invoke(currentMap, "getMapObjects", new Class<?>[0]);
                collection = invoke(root, "addCollection", new Class<?>[0]);
                routeCollection = collection;
            }
            invoke(collection, "clear", new Class<?>[0]);
            routePolyline = null;
            Object route = activeRoute;
            if (!profile.showRoute) {
                setManagedVisibility(routeSublayers, false);
                return;
            }
            if (route == null) {
                restoreManaged(routeSublayers);
                return;
            }
            Object geometry = invoke(route, "getGeometry", new Class<?>[0]);
            if (geometry == null) return;
            Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
            Object line = invoke(collection, "addPolyline",
                    new Class<?>[]{polylineClass}, geometry);
            routePolyline = line;
            RoutePolylineStyler.apply(line, route, profile);
            setManagedVisibility(routeSublayers, false);
        } catch (Throwable failure) {
            restoreManaged(routeSublayers);
            Log.w(TAG, "Could not replace the main route presentation", failure);
        }
    }

    private void applyCamera() {
        Object currentMap = map;
        NavigatorStatePublisher.CameraState source = sourceCamera;
        if (currentMap == null || source == null || !source.isValid()
                || !profile.enabled || "FREE".equals(profile.cameraMode)) return;
        try {
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object target = pointClass.getConstructor(double.class, double.class)
                    .newInstance(source.latitude, source.longitude);
            float zoom = (float) Math.max(0d, Math.min(23d,
                    source.zoom + profile.zoomDelta));
            float azimuth = "NORTH_UP".equals(profile.cameraMode) ? 0f : source.azimuth;
            float tilt = profile.tiltDegrees;
            NavigatorStatePublisher.CameraState output = new NavigatorStatePublisher.CameraState(
                    source.latitude, source.longitude, zoom, azimuth, tilt);
            if (cameraMatches(source, output)) return;
            Class<?> cameraClass = Class.forName("com.yandex.mapkit.map.CameraPosition");
            Object camera = cameraClass.getConstructor(
                    pointClass, float.class, float.class, float.class)
                    .newInstance(target, zoom, azimuth, tilt);
            rememberExpectedCamera(output);
            invoke(currentMap, "move", new Class<?>[]{cameraClass}, camera);
        } catch (Throwable failure) {
            expectedCameraEvents.clear();
            Log.w(TAG, "Main camera profile could not be applied", failure);
        }
    }

    private void ensureManagedSublayers() throws Exception {
        Object currentMap = map;
        if (currentMap == null) return;
        Object manager = invoke(currentMap, "getSublayerManager", new Class<?>[0]);
        Class<?> ids = Class.forName("com.yandex.mapkit.map.LayerIds");
        String routeId = (String) ids.getMethod(
                "getDrivingNavigationBaseLayerId").invoke(null);
        String cursorId = (String) ids.getMethod(
                "getDrivingNavigationUserPlacemarkLayerId").invoke(null);
        int count = ((Number) invoke(manager, "size", new Class<?>[0])).intValue();
        for (int index = 0; index < count; index++) {
            Object sublayer = invoke(manager, "get", new Class<?>[]{int.class}, index);
            String layerId = String.valueOf(invoke(sublayer, "getLayerId", new Class<?>[0]));
            if (routeId.equals(layerId)) addManaged(routeSublayers, sublayer);
            else if (cursorId.equals(layerId)) addManaged(cursorSublayers, sublayer);
        }
    }

    private static void addManaged(ArrayList<ManagedSublayer> values, Object sublayer)
            throws Exception {
        for (ManagedSublayer value : values) {
            if (value.sublayer == sublayer) return;
        }
        boolean visible = Boolean.TRUE.equals(invoke(
                sublayer, "isVisible", new Class<?>[0]));
        values.add(new ManagedSublayer(sublayer, visible));
    }

    private static void setManagedVisibility(ArrayList<ManagedSublayer> values,
                                             boolean visible) {
        for (ManagedSublayer value : values) value.setVisible(visible);
    }

    private void deactivate() {
        Object currentMap = map;
        Object currentWindow = mapWindow;
        if (currentMap == null || currentWindow == null) return;
        try {
            Object collection = routeCollection;
            if (collection != null) invoke(collection, "clear", new Class<?>[0]);
            routePolyline = null;
            cursorStyler.apply(false, profile.cursorScalePercent,
                    profile.cursorColor, profile.cursorOutlineColor);
            restoreManaged(routeSublayers);
            restoreManaged(cursorSublayers);
            applyStyleSlot(currentMap, CUSTOM_STYLE_ID, "");
            applyStyleSlot(currentMap, VISIBILITY_STYLE_ID, "");
            if (originalsCaptured) {
                invoke(currentWindow, "setScaleFactor", new Class<?>[]{float.class},
                        originalScaleFactor);
                if (originalFocusPoint != null) {
                    Class<?> screenPointClass = Class.forName("com.yandex.mapkit.ScreenPoint");
                    invoke(currentWindow, "setFocusPoint",
                            new Class<?>[]{screenPointClass}, originalFocusPoint);
                }
                invoke(currentMap, "setNightModeEnabled", new Class<?>[]{boolean.class},
                        originalNightMode);
                invoke(currentMap, "setModelsEnabled", new Class<?>[]{boolean.class},
                        originalModelsEnabled);
                invoke(currentMap, "setAwesomeModelsEnabled", new Class<?>[]{boolean.class},
                        originalAwesomeModelsEnabled);
                invoke(currentMap, "setPoiLimit", new Class<?>[]{Integer.class},
                        originalPoiLimit);
            }
            // MapWindow has no public FPS getter in 30.3.0; 60 is MapKit's unrestricted ceiling.
            invoke(currentWindow, "setMaxFps", new Class<?>[]{int.class}, 60);
        } catch (Throwable failure) {
            Log.w(TAG, "Could not fully restore the main map profile", failure);
        }
        expectedCameraEvents.clear();
    }

    private static void restoreManaged(ArrayList<ManagedSublayer> values) {
        for (ManagedSublayer value : values) value.restore();
    }

    private void rememberExpectedCamera(NavigatorStatePublisher.CameraState state) {
        while (expectedCameraEvents.size() >= MAX_EXPECTED_CAMERA_EVENTS) {
            expectedCameraEvents.removeFirst();
        }
        expectedCameraEvents.addLast(state);
    }

    private boolean consumeExpectedCamera(NavigatorStatePublisher.CameraState state) {
        Iterator<NavigatorStatePublisher.CameraState> iterator = expectedCameraEvents.iterator();
        while (iterator.hasNext()) {
            NavigatorStatePublisher.CameraState expected = iterator.next();
            if (cameraMatches(expected, state)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static boolean cameraMatches(NavigatorStatePublisher.CameraState first,
                                         NavigatorStatePublisher.CameraState second) {
        return Math.abs(first.latitude - second.latitude) < 1e-7d
                && Math.abs(first.longitude - second.longitude) < 1e-7d
                && Math.abs(first.zoom - second.zoom) < 0.02f
                && angularDistance(first.azimuth, second.azimuth) < 0.1f
                && Math.abs(first.tilt - second.tilt) < 0.1f;
    }

    private static float angularDistance(float first, float second) {
        float difference = Math.abs(first - second) % 360f;
        return Math.min(difference, 360f - difference);
    }

    private static void applyStyleSlot(Object target, int id, String style) throws Exception {
        Class<?>[] signature = new Class<?>[]{int.class, String.class};
        invoke(target, "setMapStyle", signature, id, "");
        if (style != null && !style.isEmpty()) {
            invoke(target, "setMapStyle", signature, id, style);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static final class ManagedSublayer {
        final Object sublayer;
        final boolean originalVisible;

        ManagedSublayer(Object sublayer, boolean originalVisible) {
            this.sublayer = sublayer;
            this.originalVisible = originalVisible;
        }

        void setVisible(boolean visible) {
            try {
                invoke(sublayer, "setVisible", new Class<?>[]{boolean.class}, visible);
            } catch (Throwable ignored) {}
        }

        void restore() {
            setVisible(originalVisible);
        }
    }
}
