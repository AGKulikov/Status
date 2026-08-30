/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
    private static final int TRAFFIC_STYLE_ID = CUSTOM_STYLE_ID + 2;
    /** Match the main map's road-width traffic instead of the custom thick route stroke. */
    private static final String BACKGROUND_TRAFFIC_STYLE =
            "[{\"types\":\"polyline\",\"stylers\":{\"scale\":0.45}}]";
    /** Geometry slicing is expensive; camera motion remains independent and full-rate. */
    private static final long ROUTE_GEOMETRY_INTERVAL_MS = 100L;
    private final Context context;
    private final FailureReporter reporter;
    private final MapCursorStyler cursorStyler;
    private final TrafficLightMapLayer trafficLightMapLayer;
    private final CameraDirectionMapLayer cameraDirectionMapLayer;
    private final LaneGuidanceMapLayer laneGuidanceMapLayer;
    private final RouteStreetLabelMapLayer routeStreetLabelMapLayer;
    private final String profileSection;
    private final String displayName;
    private final boolean adaptiveFrameRate;

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
    private Object roadEventStyleProvider;
    private Object roadEventsManager;
    private Object roadEventsLayer;
    private Object navigationRuntime;
    private Object navigationStyleProvider;
    private Object navigationLayer;
    private Object guidanceCamera;
    private Object routeCollection;
    private Object routePolyline;
    private Object routeDestinationPlacemark;
    private Object destinationIconStyle;
    private Object destinationImageProvider;
    private Bitmap destinationIconBitmap;
    private Object activeRoute;
    private long activeRouteEpoch = -1L;
    private long activeJamFingerprint;
    private RoutePolylineStyler.JamStyle activeJamStyle =
            RoutePolylineStyler.JamStyle.EMPTY;
    private final ArrayList<Integer> routeColorScratch = new ArrayList<>();
    private int renderedRouteSegmentIndex;
    private double renderedRouteSegmentPosition = Double.NaN;
    private int renderedRouteSegmentCount;
    private long lastRouteGeometryElapsedMs;
    private NavigatorStatePublisher.CameraState initialCamera;
    private NavigatorStatePublisher.CameraState navigationCamera;
    private AppliedCamera lastAppliedCamera;
    private boolean freeCameraInitialized;
    private boolean routeGuidanceActive;
    private double latestSpeedKmh = Double.NaN;
    private int appliedMaximumFps = -1;
    private NavigationMapProfile profile = new NavigationMapProfile();

    HudMapRenderer(Context context, FailureReporter reporter) {
        this(context, reporter, "hudMap", "HUD", false);
    }

    HudMapRenderer(Context context, FailureReporter reporter, String profileSection,
                   String displayName, boolean adaptiveFrameRate) {
        this.context = context.getApplicationContext();
        this.reporter = reporter;
        this.profileSection = profileSection;
        this.displayName = displayName;
        this.adaptiveFrameRate = adaptiveFrameRate;
        cursorStyler = new MapCursorStyler(this.context);
        trafficLightMapLayer = new TrafficLightMapLayer(this.context);
        cameraDirectionMapLayer = new CameraDirectionMapLayer(this.context);
        laneGuidanceMapLayer = new LaneGuidanceMapLayer(this.context);
        routeStreetLabelMapLayer = new RouteStreetLabelMapLayer(this.context);
    }

    void applyConfiguration(String raw) {
        NavigationMapProfile next = NavigationMapProfile.fromConfiguration(raw, profileSection);
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

    /** Background MapKit work is retained only for a live, enabled external MapWindow. */
    boolean hasActiveMapWindow() {
        return profile.enabled && mapWindow != null && surface != null && surface.isValid();
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

    /** Keeps route-only events bound to Navigator's real automotive Navigation session. */
    void updateNavigationRuntime(Object nextNavigation) {
        if (navigationRuntime == nextNavigation) return;
        removeNativeNavigationLayer();
        navigationRuntime = nextNavigation;
        if (mapWindow != null && nextNavigation != null) createNativeNavigationLayer();
        if (nextNavigation == null) {
            routeGuidanceActive = false;
            trafficLightMapLayer.clearData();
            cameraDirectionMapLayer.clearData();
            laneGuidanceMapLayer.clearData();
            routeStreetLabelMapLayer.clearData();
            applyRoadEventVisibility();
            applyCamera(false);
        }
    }

    /** Canonical navigation location, independent from every visual operation on the main map. */
    void updateNavigationState(NavigatorStatePublisher.NavigationFrame frame) {
        if (frame == null) return;
        boolean routeVisibilityChanged = routeGuidanceActive != frame.routeActive;
        routeGuidanceActive = frame.routeActive;
        if (routeVisibilityChanged) {
            // A camera-backed NavigationLayer is useful only while a user route exists. The
            // previous always-attached experiment left MapKit's native layer alive in free drive
            // and was followed by the Navigator process dying a few seconds later. Binding it to
            // the real route lifecycle also removes all route-event work when guidance is idle.
            if (routeGuidanceActive) createNativeNavigationLayer();
            else removeNativeNavigationLayer();
            applyRoadEventVisibility();
        }
        trafficLightMapLayer.update(frame.routeActive,
                frame.trafficLightsSampleElapsedMs, frame.trafficLights);
        cameraDirectionMapLayer.update(frame.routeActive,
                frame.cameraDirectionsSampleElapsedMs, frame.cameraDirections);
        laneGuidanceMapLayer.update(frame.routeActive,
                frame.laneGuidanceSampleElapsedMs, frame.laneGuidance);
        routeStreetLabelMapLayer.update(frame.routeActive,
                frame.routeStreetLabelsSampleElapsedMs, frame.routeStreetLabels);
        if (!frame.isValid()) return;
        try {
            latestSpeedKmh = Math.max(0d, frame.speedKmh);
            cursorStyler.update(frame.latitude, frame.longitude, frame.bearingDegrees);
            applyMaximumFps();
            float baseZoom = frame.routeActive
                    ? frame.speedKmh >= 90d ? 14.5f : frame.speedKmh >= 50d ? 15.2f : 16f
                    : 15.5f;
            navigationCamera = new NavigatorStatePublisher.CameraState(
                    frame.latitude, frame.longitude, baseZoom, (float) frame.bearingDegrees,
                    profile.tiltDegrees);
            // This MapWindow has one camera owner. Exact tilt/zoom/focus settings therefore work
            // independently without alternating against GuidanceCamera every second.
            applyCamera(false);
            updateRouteProgress(frame);
        } catch (Exception invalid) {
            Log.w(TAG, "Guidance frame could not be applied to " + displayName, invalid);
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    void updateRoute(long routeEpoch, Object drivingRoute) {
        RoutePolylineStyler.JamStyle jamStyle =
                RoutePolylineStyler.readJamStyle(drivingRoute);
        updateRoute(routeEpoch, drivingRoute, jamStyle.fingerprint, jamStyle);
    }

    /** Reuses the same congestion scan for the HUD and instrument-cluster map. */
    void updateRoute(long routeEpoch, Object drivingRoute, long jamFingerprint) {
        RoutePolylineStyler.JamStyle jamStyle =
                RoutePolylineStyler.readJamStyle(drivingRoute);
        updateRoute(routeEpoch, drivingRoute, jamFingerprint, jamStyle);
    }

    /** Receives the publisher-owned jam palette so two maps never scan it separately. */
    void updateRoute(long routeEpoch, Object drivingRoute, long jamFingerprint,
                     RoutePolylineStyler.JamStyle jamStyle) {
        if (routeEpoch < activeRouteEpoch) return;
        // MapKit may hand out a new Java wrapper for the same DrivingRoute on every Guidance
        // callback. Object identity is therefore not a route identity; routeEpoch is.
        boolean changed = routeEpoch != activeRouteEpoch
                || (drivingRoute == null) != (activeRoute == null);
        boolean jamsChanged = jamFingerprint != activeJamFingerprint;
        if (!changed && !jamsChanged) {
            // Keep the newest Java wrapper for RoutePosition without repeating reflected layer
            // calls for every Guidance/Windshield callback.
            activeRoute = drivingRoute;
            return;
        }
        if (changed) {
            renderedRouteSegmentIndex = 0;
            renderedRouteSegmentPosition = Double.NaN;
            renderedRouteSegmentCount = 0;
            lastRouteGeometryElapsedMs = 0L;
        }
        activeRouteEpoch = routeEpoch;
        activeRoute = drivingRoute;
        activeJamFingerprint = jamFingerprint;
        activeJamStyle = jamStyle == null ? RoutePolylineStyler.JamStyle.EMPTY : jamStyle;
        applyTrafficPresentation();
        if (changed) rebuildRoute();
        else if (jamsChanged) restyleRoute();
    }

    /**
     * In route-only mode the background layer is useful before guidance starts, then disappears
     * as soon as an active route can carry its own congestion palette.
     */
    private boolean shouldShowBackgroundTraffic() {
        boolean routeOnlyMode = profile.showRoute && profile.showRouteTraffic
                && !profile.showTraffic;
        return profile.showTraffic || (routeOnlyMode && activeRoute == null);
    }

    private void applyTrafficPresentation() {
        Object currentTraffic = trafficLayer;
        if (currentTraffic == null) return;
        try {
            boolean visible = shouldShowBackgroundTraffic();
            Object styled = invoke(currentTraffic, "setTrafficStyle",
                    new Class<?>[]{int.class, String.class}, TRAFFIC_STYLE_ID,
                    visible ? BACKGROUND_TRAFFIC_STYLE : "");
            if (styled instanceof Boolean && !((Boolean) styled)) {
                Log.w(TAG, "HUD background traffic style was rejected by MapKit");
            }
            invoke(currentTraffic, "setTrafficVisible", new Class<?>[]{boolean.class},
                    visible);
        } catch (Throwable failure) {
            Log.w(TAG, "HUD background traffic presentation could not be updated", failure);
        }
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
            trafficLightMapLayer.attach(map);
            cameraDirectionMapLayer.attach(map);
            laneGuidanceMapLayer.attach(map);
            routeStreetLabelMapLayer.attach(map);
            cursorStyler.attach(map);

            Class<?> runtimeSurfaceClass = Class.forName("com.yandex.runtime.view.Surface");
            Class<?> surfaceFactoryClass = Class.forName(
                    "com.yandex.runtime.view.SurfaceFactory");
            Object nextRuntimeSurface = surfaceFactoryClass.getMethod(
                    "from", Surface.class).invoke(null, surface);
            runtimeSurface = nextRuntimeSurface;
            invoke(nextMapWindow, "addSurface",
                    new Class<?>[]{runtimeSurfaceClass}, nextRuntimeSurface);
            runtimeSurfaceAttached = true;

            // Optional traffic enriches the map but is not allowed to take down the renderer.
            Class<?> mapWindowClass = Class.forName("com.yandex.mapkit.map.MapWindow");
            trafficLayer = createOptionalLayer(
                    mapKit, mapKitClass, mapWindowClass, nextMapWindow,
                    "createTrafficLayer");

            createRoadEventsLayer(mapKit, mapKitClass, mapWindowClass, nextMapWindow);
            applyProfile();
            createNativeNavigationLayer();
            Log.i(TAG, "Independent " + displayName
                    + " OffscreenMapWindow attached, generation=" + generation
                    + ", size=" + width + "x" + height);
            NavigationBridgeClient.reportDiagnostic(
                    "independent " + displayName + " map attached; generation=" + generation
                            + ", size=" + width + "x" + height);
        } catch (Throwable failure) {
            long failedGeneration = generation;
            String detail = shortMessage(failure);
            Log.e(TAG, "Could not attach independent " + displayName + " MapWindow", failure);
            stopRenderer(false);
            reporter.onSurfaceLost(failedGeneration, detail);
        }
    }

    private void applyProfile() {
        Object currentWindow = mapWindow;
        Object currentMap = map;
        if (currentWindow == null || currentMap == null) return;
        trafficLightMapLayer.apply(profile.showTrafficLights);
        cameraDirectionMapLayer.apply(
                !"HIDDEN".equals(profile.roadEventMode("SPEED_CONTROL")));
        laneGuidanceMapLayer.apply(profile.showLaneGuidance);
        routeStreetLabelMapLayer.apply(
                profile.showLabels && profile.routeStreetLabelsOnly);
        try {
            applyMaximumFps();
            invoke(currentWindow, "setScaleFactor", new Class<?>[]{float.class},
                    profile.mapScalePercent / 100f);
            Class<?> pointClass = Class.forName("com.yandex.mapkit.ScreenPoint");
            Object focus = pointClass.getConstructor(float.class, float.class).newInstance(
                    width * profile.focusXPercent / 100f,
                    height * profile.focusYPercent / 100f);
            invoke(currentWindow, "setFocusPoint", new Class<?>[]{pointClass}, focus);
            applyTrafficPresentation();
            cursorStyler.apply(profile.showCursor, profile.cursorScalePercent,
                    profile.cursorColor, profile.cursorOutlineColor);
            boolean systemNight = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            boolean night = profile.automaticDayNight ? systemNight : profile.nightMode;
            invoke(currentMap, "setNightModeEnabled", new Class<?>[]{boolean.class}, night);
            invoke(currentMap, "setModelsEnabled", new Class<?>[]{boolean.class},
                    profile.showModels && !profile.roadsOnly);
            invoke(currentMap, "setAwesomeModelsEnabled", new Class<?>[]{boolean.class},
                    profile.showModels && !profile.roadsOnly);
            invoke(currentMap, "setPoiLimit", new Class<?>[]{Integer.class},
                    profile.roadsOnly || !profile.showPois ? Integer.valueOf(0) : null);
            invoke(currentMap, "setTransparentBackgroundEnabled",
                    new Class<?>[]{boolean.class}, profile.roadsOnly);
            invoke(currentMap, "setRotateGesturesEnabled", new Class<?>[]{boolean.class}, false);
            invoke(currentMap, "setScrollGesturesEnabled", new Class<?>[]{boolean.class}, false);
            invoke(currentMap, "setTiltGesturesEnabled", new Class<?>[]{boolean.class}, false);

            String style = night ? profile.nightStyleJson : profile.dayStyleJson;
            applyStyleSlot(currentMap, CUSTOM_STYLE_ID, style);
            applyStyleSlot(currentMap, VISIBILITY_STYLE_ID,
                    profile.visibilityStyleJson());
            applyCamera(false);
            applyRoadEventVisibility();
            rebuildRoute();
        } catch (Throwable failure) {
            Log.w(TAG, "Some HUD MapProfile fields could not be applied", failure);
        }
    }

    /**
     * Preserve native resolution and spend frame budget only when it is visible: the cluster map
     * uses its configured ceiling while the car moves and idles at 15 FPS while stationary.
     * MapKit still receives every route/location update; only identical visual frames are skipped.
     */
    private void applyMaximumFps() throws Exception {
        Object currentWindow = mapWindow;
        if (currentWindow == null) return;
        int target = profile.maximumFps;
        if (adaptiveFrameRate && Double.isFinite(latestSpeedKmh) && latestSpeedKmh < 1d) {
            target = Math.min(target, 15);
        }
        target = Math.max(1, target);
        if (target == appliedMaximumFps) return;
        invoke(currentWindow, "setMaxFps", new Class<?>[]{int.class}, target);
        appliedMaximumFps = target;
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

    /**
     * Creates the stock Yandex event layer for ALWAYS events. The target 30.3.0 provider is
     * intentionally resolved by its verified runtime name so its icons and significance scaling
     * are identical to the main Navigator map. Camera direction is not part of StyleProvider in
     * 30.3.0; CameraDirectionMapLayer receives it separately from Windshield.
     */
    private void createRoadEventsLayer(Object mapKit, Class<?> mapKitClass,
                                       Class<?> mapWindowClass, Object currentMapWindow) {
        if (roadEventsLayer != null) return;
        try {
            Object stockProvider = Class.forName("r74.c").getConstructor(Context.class)
                    .newInstance(context);
            Class<?> styleProviderClass = Class.forName(
                    "com.yandex.mapkit.road_events_layer.StyleProvider");
            Class<?> managerClass = Class.forName(
                    "com.yandex.mapkit.road_events.RoadEventsManager");
            Object manager = mapKitClass.getMethod("createRoadEventsManager").invoke(mapKit);
            Object layer = mapKitClass.getMethod("createRoadEventsLayer", mapWindowClass,
                            styleProviderClass, managerClass)
                    .invoke(mapKit, currentMapWindow, stockProvider, manager);
            roadEventStyleProvider = stockProvider;
            roadEventsManager = manager;
            roadEventsLayer = layer;
            applyRoadEventVisibility();
            Log.i(TAG, "Standalone Yandex road-events layer attached to " + displayName);
            NavigationBridgeClient.reportDiagnostic(
                    "safe standalone road-events layer attached to independent "
                            + displayName + " MapWindow");
        } catch (Throwable failure) {
            roadEventStyleProvider = null;
            roadEventsManager = null;
            roadEventsLayer = null;
            Log.w(TAG, "HUD road-events layer unavailable: " + shortMessage(failure));
            NavigationBridgeClient.reportDiagnostic(
                    "HUD road-events layer unavailable: " + shortMessage(failure));
        }
    }

    /**
     * Route matching belongs to NavigationLayer, not to a standalone nearby-events layer. The
     * latter reports {@code isOnRoute=false} because it has no automotive Navigation attached,
     * which made every ROUTE_ONLY marker disappear in 2.5.4. Keep MapKit's camera subsystem
     * enabled (the camera-less 30.3.0 path crashes natively), park it in FREE mode, and leave all
     * actual camera movement to the exact Natro profile below.
     */
    private void createNativeNavigationLayer() {
        Object currentWindow = mapWindow;
        Object currentNavigation = navigationRuntime;
        Object eventProvider = roadEventStyleProvider;
        if (!routeGuidanceActive || currentWindow == null || currentNavigation == null
                || eventProvider == null || navigationLayer != null) return;
        try {
            Class<?> mapWindowClass = Class.forName("com.yandex.mapkit.map.MapWindow");
            Class<?> eventProviderClass = Class.forName(
                    "com.yandex.mapkit.road_events_layer.StyleProvider");
            Class<?> navigationProviderClass = Class.forName(
                    "com.yandex.mapkit.navigation.automotive.layer.styling."
                            + "NavigationStyleProvider");
            Class<?> navigationClass = Class.forName(
                    "com.yandex.mapkit.navigation.automotive.Navigation");
            Class<?> settingsClass = Class.forName(
                    "com.yandex.mapkit.navigation.automotive.layer.NavigationLayerSettings");
            Object settings = settingsClass.getConstructor().newInstance();
            invoke(settings, "setUseDefaultSublayersSetup",
                    new Class<?>[]{boolean.class}, false);
            invoke(settings, "setUseLayerCamera", new Class<?>[]{boolean.class}, true);
            invoke(settings, "setUseLayerRoadEvents", new Class<?>[]{boolean.class}, true);
            invoke(settings, "setUseLayerRoutes", new Class<?>[]{boolean.class}, false);
            invoke(settings, "setUseLayerCursor", new Class<?>[]{boolean.class}, false);
            invoke(settings, "setUseLayerRequestPoints", new Class<?>[]{boolean.class}, false);
            invoke(settings, "setUseLayerBalloonsInGuidance",
                    new Class<?>[]{boolean.class}, false);
            invoke(settings, "setUseLayerBalloonsInNavigation",
                    new Class<?>[]{boolean.class}, false);

            Object style = Class.forName(
                            "com.yandex.mapkit.styling.automotivenavigation."
                                    + "AutomotiveNavigationStyleProvider")
                    .getConstructor(Context.class).newInstance(context);
            Class<?> factoryClass = Class.forName(
                    "com.yandex.mapkit.navigation.automotive.layer.NavigationLayerFactory");
            Object layer = factoryClass.getMethod("createNavigationLayer", mapWindowClass,
                            eventProviderClass, navigationProviderClass, navigationClass,
                            settingsClass)
                    .invoke(null, currentWindow, eventProvider, style, currentNavigation,
                            settings);
            navigationStyleProvider = style;
            navigationLayer = layer;
            guidanceCamera = invoke(layer, "getCamera", new Class<?>[0]);
            parkNativeGuidanceCamera();
            applyRoadEventVisibility();
            Log.i(TAG, "Route-matched road-events layer attached to " + displayName);
            NavigationBridgeClient.reportDiagnostic(
                    "route-matched road-events layer attached to independent "
                            + displayName + " MapWindow");
        } catch (Throwable failure) {
            removeNativeNavigationLayer();
            applyRoadEventVisibility();
            Log.w(TAG, "Route-matched road-events layer unavailable: "
                    + shortMessage(failure));
            NavigationBridgeClient.reportDiagnostic(
                    "route-matched road-events layer unavailable; nearby fallback active: "
                            + shortMessage(failure));
        }
    }

    private void parkNativeGuidanceCamera() {
        Object camera = guidanceCamera;
        if (camera == null) return;
        try {
            Class<?> animationTypeClass = Class.forName("com.yandex.mapkit.Animation$Type");
            Object smooth = animationTypeClass.getField("SMOOTH").get(null);
            Class<?> animationClass = Class.forName("com.yandex.mapkit.Animation");
            Object immediate = animationClass.getConstructor(animationTypeClass, float.class)
                    .newInstance(smooth, 0f);
            Class<?> cameraModeClass = Class.forName(
                    "com.yandex.mapkit.navigation.guidance_camera.CameraMode");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object free = Enum.valueOf((Class) cameraModeClass, "FREE");
            invoke(camera, "setSwitchModesAutomatically",
                    new Class<?>[]{boolean.class}, false);
            invoke(camera, "setAutoRotation",
                    new Class<?>[]{boolean.class, animationClass}, false, immediate);
            invoke(camera, "setAutoZoom",
                    new Class<?>[]{boolean.class, animationClass}, false, immediate);
            invoke(camera, "setCameraMode",
                    new Class<?>[]{cameraModeClass, animationClass}, free, immediate);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not park GuidanceCamera", failure);
        }
    }

    private void applyRoadEventVisibility() {
        Object everywhere = roadEventsLayer;
        Object onRoute = navigationLayer;
        if (everywhere == null && onRoute == null) return;
        try {
            Class<?> eventTagClass = Class.forName("com.yandex.mapkit.road_events.EventTag");
            for (String tagName : NavigationMapProfile.ROAD_EVENT_TAGS) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object tag = Enum.valueOf((Class) eventTagClass, tagName);
                String mode = profile.roadEventMode(tagName);
                if (everywhere != null) {
                    // Never leave the user with an empty map if the automotive layer is absent:
                    // the fallback can include a nearby marker, but it preserves all selected data.
                    boolean fallbackRoute = onRoute == null && routeGuidanceActive
                            && "ROUTE_ONLY".equals(mode);
                    invoke(everywhere, "setRoadEventVisible",
                            new Class<?>[]{eventTagClass, boolean.class}, tag,
                            "ALWAYS".equals(mode) || fallbackRoute);
                }
                if (onRoute != null) {
                    invoke(onRoute, "setRoadEventVisibleOnRoute",
                            new Class<?>[]{eventTagClass, boolean.class}, tag,
                            "ROUTE_ONLY".equals(mode));
                }
            }
        } catch (Throwable failure) {
            Log.w(TAG, "HUD road-event visibility could not be applied", failure);
        }
    }

    private void removeNativeNavigationLayer() {
        Object layer = navigationLayer;
        if (layer != null) {
            try { invoke(layer, "removeFromMap", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        guidanceCamera = null;
        navigationLayer = null;
        navigationStyleProvider = null;
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
            routeDestinationPlacemark = null;
            Object route = activeRoute;
            if (route == null || (!profile.showRoute && !profile.showDestination)) return;
            RouteSlice slice = remainingRoute(route);
            if (slice == null) return;
            renderedRouteSegmentIndex = slice.firstSegmentIndex;
            renderedRouteSegmentPosition = slice.segmentPosition;
            renderedRouteSegmentCount = slice.segmentCount;
            if (profile.showRoute) {
                Class<?> polylineClass = Class.forName(
                        "com.yandex.mapkit.geometry.Polyline");
                Object line = invoke(collection, "addPolyline",
                        new Class<?>[]{polylineClass}, slice.geometry);
                routePolyline = line;
                RoutePolylineStyler.apply(line, activeJamStyle, profile,
                        slice.firstSegmentIndex, slice.segmentCount, routeColorScratch);
            }
            if (profile.showDestination && slice.destinationPoint != null) {
                addDestinationMarker(collection, slice.destinationPoint);
            }
            lastRouteGeometryElapsedMs = SystemClock.elapsedRealtime();
        } catch (Throwable failure) {
            Log.w(TAG, "Active route could not be rendered in the HUD MapWindow", failure);
        }
    }

    /** One immutable icon and the route's already-read last point; no extra Guidance polling. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addDestinationMarker(Object collection, Object point) throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object placemark = invoke(collection, "addPlacemark",
                new Class<?>[]{pointClass}, point);
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Object style = destinationIconStyle;
        if (style == null) {
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf(
                    (Class<? extends Enum>) rotationClass, "NO_ROTATION");
            invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                    new PointF(0.5f, 0.96f));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(38f));
            destinationIconStyle = style;
        }
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object provider = destinationImageProvider;
        if (provider == null) {
            Bitmap bitmap = createDestinationBitmap();
            provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                    .invoke(null, bitmap);
            destinationIconBitmap = bitmap;
            destinationImageProvider = provider;
        }
        invoke(placemark, "setIcon", new Class<?>[]{providerClass, styleClass},
                provider, style);
        invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        routeDestinationPlacemark = placemark;
    }

    /** Compact neutral endpoint pin, readable on both day and night map styles. */
    private Bitmap createDestinationBitmap() {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.max(40, Math.round(48f * density));
        int height = Math.max(50, Math.round(58f * density));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float unit = width / 48f;
        float centerX = width / 2f;

        Path pin = new Path();
        pin.moveTo(centerX, height - 2f * unit);
        pin.cubicTo(centerX - 4f * unit, height - 12f * unit,
                centerX - 18f * unit, 31f * unit, centerX - 18f * unit, 20f * unit);
        pin.cubicTo(centerX - 18f * unit, 9f * unit,
                centerX - 10f * unit, 2f * unit, centerX, 2f * unit);
        pin.cubicTo(centerX + 10f * unit, 2f * unit,
                centerX + 18f * unit, 9f * unit, centerX + 18f * unit, 20f * unit);
        pin.cubicTo(centerX + 18f * unit, 31f * unit,
                centerX + 4f * unit, height - 12f * unit, centerX, height - 2f * unit);
        pin.close();

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(0x55313842);
        canvas.save();
        canvas.translate(0f, 2f * unit);
        canvas.drawPath(pin, shadow);
        canvas.restore();

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setStyle(Paint.Style.FILL);
        body.setColor(0xFFE5E8EC);
        canvas.drawPath(pin, body);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(2f * unit);
        body.setColor(0xFF3B414B);
        canvas.drawPath(pin, body);

        Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
        target.setStyle(Paint.Style.FILL);
        target.setColor(0xFF555C67);
        canvas.drawCircle(centerX, 20f * unit, 7f * unit, target);
        target.setColor(0xFFF8F9FA);
        canvas.drawCircle(centerX, 20f * unit, 3f * unit, target);
        return bitmap;
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
            RoutePolylineStyler.apply(line, activeJamStyle, profile,
                    renderedRouteSegmentIndex, renderedRouteSegmentCount,
                    routeColorScratch);
        } catch (Throwable failure) {
            Log.w(TAG, "HUD route traffic palette could not be updated", failure);
        }
    }

    /**
     * Re-slices the visible route from the same map-matched point as the cursor. A backward GPS
     * correction is applied immediately: route geometry is not historical progress and therefore
     * must restore the previously hidden section whenever RoutePosition moves back.
     */
    private void updateRouteProgress(NavigatorStatePublisher.NavigationFrame frame) {
        Object line = routePolyline;
        Object route = activeRoute;
        if (line == null || route == null || !profile.showRoute
                || !frame.routeProgressValid) return;
        try {
            Object cursorPoint = frame.currentRoutePoint;
            if (cursorPoint == null && finite(frame.latitude) && finite(frame.longitude)) {
                Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
                cursorPoint = pointClass.getConstructor(double.class, double.class)
                        .newInstance(frame.latitude, frame.longitude);
            }
            RouteProgress progress = new RouteProgress(
                    frame.routeSegmentIndex, frame.routeSegmentPosition,
                    cursorPoint);
            if (!routeProgressChanged(progress)) return;
            boolean movingBackward = isBehindRenderedProgress(progress);
            long now = SystemClock.elapsedRealtime();
            // Forward/current movement is capped at 10 geometry updates per second on the KX11.
            // A reverse correction bypasses that performance throttle so the line immediately
            // returns to the cursor instead of remaining irreversibly clipped ahead of it.
            if (!movingBackward && progress.segmentIndex == renderedRouteSegmentIndex
                    && now - lastRouteGeometryElapsedMs < ROUTE_GEOMETRY_INTERVAL_MS) return;
            // Publisher already read RoutePosition once for both maps. The first geometry point
            // is that exact map-matched cursor point; the rest comes from the active route.
            RouteSlice slice = remainingRoute(route, progress);
            if (slice == null) return;
            Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
            invoke(line, "setGeometry", new Class<?>[]{polylineClass}, slice.geometry);
            renderedRouteSegmentIndex = slice.firstSegmentIndex;
            renderedRouteSegmentPosition = slice.segmentPosition;
            renderedRouteSegmentCount = slice.segmentCount;
            lastRouteGeometryElapsedMs = now;
            RoutePolylineStyler.applyProgressColors(line, activeJamStyle, profile,
                    slice.firstSegmentIndex, slice.segmentCount, routeColorScratch);
        } catch (Throwable failure) {
            Log.w(TAG, "HUD route progress could not be advanced", failure);
        }
    }

    private boolean routeProgressChanged(RouteProgress progress) {
        return progress.segmentIndex != renderedRouteSegmentIndex
                || Double.isNaN(renderedRouteSegmentPosition)
                || Math.abs(progress.segmentPosition - renderedRouteSegmentPosition) > 0.000001d;
    }

    private boolean isBehindRenderedProgress(RouteProgress progress) {
        return progress.segmentIndex < renderedRouteSegmentIndex
                || (progress.segmentIndex == renderedRouteSegmentIndex
                && !Double.isNaN(renderedRouteSegmentPosition)
                && progress.segmentPosition < renderedRouteSegmentPosition);
    }

    private static RouteSlice remainingRoute(Object route) throws Exception {
        return remainingRoute(route, currentRouteProgress(route));
    }

    private static RouteSlice remainingRoute(Object route, RouteProgress progress)
            throws Exception {
        if (progress == null) return null;
        Object fullGeometry = invoke(route, "getGeometry", new Class<?>[0]);
        if (fullGeometry == null) return null;
        List<?> points = list(invoke(fullGeometry, "getPoints", new Class<?>[0]));
        if (points.size() < 2) return null;

        int segmentIndex = Math.max(0, Math.min(points.size() - 2, progress.segmentIndex));
        double segmentPosition = progress.segmentPosition;
        Object currentPoint = progress.currentPoint;
        if (currentPoint == null) currentPoint = points.get(segmentIndex);

        ArrayList<Object> remaining = new ArrayList<>(points.size() - segmentIndex);
        remaining.add(currentPoint);
        for (int index = segmentIndex + 1; index < points.size(); index++) {
            remaining.add(points.get(index));
        }
        Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
        Object geometry = polylineClass.getConstructor(List.class).newInstance(remaining);
        return new RouteSlice(geometry, segmentIndex, segmentPosition,
                Math.max(0, remaining.size() - 1), points.get(points.size() - 1));
    }

    /** RoutePosition-only read; deliberately does not touch or copy the full route geometry. */
    private static RouteProgress currentRouteProgress(Object route) throws Exception {
        int segmentIndex = 0;
        double segmentPosition = 0d;
        Object currentPoint = null;
        Object routePosition = invoke(route, "getRoutePosition", new Class<?>[0]);
        if (routePosition != null) {
            currentPoint = invoke(routePosition, "getPoint", new Class<?>[0]);
        }
        // The cursor-owned RoutePosition is reversible. DrivingRoute.getPosition() represents
        // completed guidance progress and is only a continuity fallback during native rerouting.
        Object polylinePosition = null;
        if (routePosition != null) {
            String routeId = String.valueOf(invoke(route, "getRouteId", new Class<?>[0]));
            polylinePosition = invoke(routePosition, "positionOnRoute",
                    new Class<?>[]{String.class}, routeId);
        }
        if (polylinePosition == null) {
            polylinePosition = invoke(route, "getPosition", new Class<?>[0]);
        }
        if (polylinePosition != null) {
            segmentIndex = ((Number) invoke(polylinePosition, "getSegmentIndex",
                    new Class<?>[0])).intValue();
            segmentPosition = ((Number) invoke(polylinePosition, "getSegmentPosition",
                    new Class<?>[0])).doubleValue();
        }
        segmentIndex = Math.max(0, segmentIndex);
        segmentPosition = Math.max(0d, Math.min(1d, segmentPosition));
        return new RouteProgress(segmentIndex, segmentPosition, currentPoint);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> ? (List<?>) value : new ArrayList<>();
    }

    private static final class RouteSlice {
        final Object geometry;
        final int firstSegmentIndex;
        final double segmentPosition;
        final int segmentCount;
        final Object destinationPoint;

        RouteSlice(Object geometry, int firstSegmentIndex, double segmentPosition,
                   int segmentCount, Object destinationPoint) {
            this.geometry = geometry;
            this.firstSegmentIndex = firstSegmentIndex;
            this.segmentPosition = segmentPosition;
            this.segmentCount = segmentCount;
            this.destinationPoint = destinationPoint;
        }
    }

    private static final class RouteProgress {
        final int segmentIndex;
        final double segmentPosition;
        final Object currentPoint;

        RouteProgress(int segmentIndex, double segmentPosition, Object currentPoint) {
            this.segmentIndex = segmentIndex;
            this.segmentPosition = segmentPosition;
            this.currentPoint = currentPoint;
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
        removeNativeNavigationLayer();
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
        appliedMaximumFps = -1;
        map = null;
        trafficLayer = null;
        roadEventsLayer = null;
        roadEventsManager = null;
        roadEventStyleProvider = null;
        cursorStyler.detach();
        trafficLightMapLayer.detachMap();
        cameraDirectionMapLayer.detachMap();
        laneGuidanceMapLayer.detachMap();
        routeStreetLabelMapLayer.detachMap();
        routeCollection = null;
        routePolyline = null;
        routeDestinationPlacemark = null;
        routeColorScratch.clear();
        renderedRouteSegmentIndex = 0;
        renderedRouteSegmentPosition = Double.NaN;
        renderedRouteSegmentCount = 0;
        lastRouteGeometryElapsedMs = 0L;
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
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
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
