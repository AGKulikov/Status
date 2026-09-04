/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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
    private final Context context;
    private final FailureReporter reporter;
    private final MapCursorStyler cursorStyler;
    private final MapOverlayPlacementCoordinator overlayPlacement;
    private final TrafficLightMapLayer trafficLightMapLayer;
    private final CameraDirectionMapLayer cameraDirectionMapLayer;
    private final SpeedBumpMapLayer speedBumpMapLayer;
    private final LaneGuidanceMapLayer laneGuidanceMapLayer;
    private final RouteTurnMapLayer routeTurnMapLayer;
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
    private ScaledRoadEventStyleProvider scaledRoadEventStyleProvider;
    private Object roadEventsManager;
    private Object roadEventsLayer;
    private Object routeCollection;
    private Object destinationCollection;
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
    private NavigatorStatePublisher.CameraState initialCamera;
    private NavigatorStatePublisher.CameraState navigationCamera;
    private NavigatorStatePublisher.NavigationFrame latestNavigationFrame;
    /** Frozen source position for FREE mode; profile tilt/zoom may still change around it. */
    private NavigatorStatePublisher.CameraState freeCameraSource;
    private AppliedCamera lastAppliedCamera;
    private boolean routeGuidanceActive;
    private double latestSpeedKmh = Double.NaN;
    private int appliedMaximumFps = -1;
    private long appliedLayerOrderFingerprint = Long.MIN_VALUE;
    private long lastLayerOrderApplyElapsedMs;
    private long lastOverlayLayoutElapsedMs;
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
        overlayPlacement = new MapOverlayPlacementCoordinator();
        trafficLightMapLayer = new TrafficLightMapLayer(this.context, overlayPlacement);
        cameraDirectionMapLayer = new CameraDirectionMapLayer(this.context, overlayPlacement);
        speedBumpMapLayer = new SpeedBumpMapLayer(this.context, overlayPlacement);
        laneGuidanceMapLayer = new LaneGuidanceMapLayer(this.context, overlayPlacement);
        routeTurnMapLayer = new RouteTurnMapLayer(this.context);
    }

    void applyConfiguration(String raw) {
        NavigationMapProfile next = NavigationMapProfile.fromConfiguration(raw, profileSection);
        boolean enabledChanged = profile.enabled != next.enabled;
        boolean cameraModeChanged = !profile.cameraMode.equals(next.cameraMode);
        profile = next;
        if (cameraModeChanged) {
            freeCameraSource = null;
        }
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
        // Ignore only an older revocation. The host may coalesce a resize generation and revoke
        // it before its delayed ATTACH reaches us; that newer revocation must still stop the
        // previously attached generation that points at the same destroyed producer Surface.
        if (detachedGeneration < generation) return;
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

    /** Clears route-scoped presentation when Navigator no longer has a Navigation session. */
    void updateNavigationRuntime(Object nextNavigation) {
        if (nextNavigation == null) {
            routeGuidanceActive = false;
            latestNavigationFrame = null;
            overlayPlacement.updateNavigationState(false, false, 0, Double.NaN,
                    Double.NaN, Double.NaN, 0);
            trafficLightMapLayer.clearData();
            cameraDirectionMapLayer.clearData();
            speedBumpMapLayer.clearData();
            laneGuidanceMapLayer.clearData();
            routeTurnMapLayer.clearData();
            applyRoadEventVisibility();
            applyCamera(false);
        }
    }

    /** Canonical navigation location, independent from every visual operation on the main map. */
    void updateNavigationState(NavigatorStatePublisher.NavigationFrame frame) {
        if (frame == null) return;
        latestNavigationFrame = frame;
        syncOverlayNavigationState();
        boolean routeVisibilityChanged = routeGuidanceActive != frame.routeActive;
        routeGuidanceActive = frame.routeActive;
        if (routeVisibilityChanged) {
            applyRoadEventVisibility();
        }
        trafficLightMapLayer.update(frame.routeActive,
                frame.trafficLightsSampleElapsedMs, frame.trafficLights);
        cameraDirectionMapLayer.update(frame.routeActive,
                frame.cameraDirectionsSampleElapsedMs, frame.cameraDirections);
        speedBumpMapLayer.updateNavigationState(frame.routeActive,
                frame.routeProgressValid, frame.routeSegmentIndex,
                frame.routeSegmentPosition);
        laneGuidanceMapLayer.update(frame.routeActive,
                frame.laneGuidanceSampleElapsedMs, frame.laneGuidance);
        routeTurnMapLayer.update(frame.routeActive,
                frame.routeTurnsSampleElapsedMs, frame.routeTurns);
        applySublayerOrder();
        if (!frame.isValid()) {
            relayoutOverlays(false);
            return;
        }
        try {
            latestSpeedKmh = Math.max(0d, frame.speedKmh);
            cursorStyler.update(frame.latitude, frame.longitude, frame.bearingDegrees);
            applyMaximumFps();
            float baseZoom = profile.fixedZoomEnabled
                    ? (float) profile.fixedZoomLevel
                    : frame.routeActive
                    ? frame.speedKmh >= 90d ? 14.5f : frame.speedKmh >= 50d ? 15.2f : 16f
                    : 15.5f;
            navigationCamera = new NavigatorStatePublisher.CameraState(
                    frame.latitude, frame.longitude, baseZoom, (float) frame.bearingDegrees,
                    profile.tiltDegrees);
            // This MapWindow has one camera owner. Exact tilt/zoom/focus settings therefore work
            // independently without alternating against GuidanceCamera every second.
            applyCamera(false);
            relayoutOverlays(false);
            updateRouteProgress(frame);
        } catch (Exception invalid) {
            Log.w(TAG, "Guidance frame could not be applied to " + displayName, invalid);
        }
    }

    /** Live HUD Speed cameras forwarded by the authenticated Natro bridge. */
    void updateExternalCameras(String raw) {
        cameraDirectionMapLayer.updateExternal(raw);
        relayoutOverlays(false);
        applySublayerOrder();
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
        overlayPlacement.updateRoute(routeEpoch, drivingRoute);
        speedBumpMapLayer.updateRoute(routeEpoch, drivingRoute);
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
            overlayPlacement.attach(nextMapWindow, width, height);
            overlayPlacement.updateRoute(activeRouteEpoch, activeRoute);
            syncOverlayNavigationState();
            trafficLightMapLayer.attach(map);
            cameraDirectionMapLayer.attach(map);
            speedBumpMapLayer.attach(map);
            laneGuidanceMapLayer.attach(map);
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
        boolean systemNight = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean night = profile.automaticDayNight ? systemNight : profile.nightMode;
        boolean roadEventScaleChanged = scaledRoadEventStyleProvider != null
                && scaledRoadEventStyleProvider.setScales(
                profile.roadEventScalePercent, profile.cameraScalePercent);
        trafficLightMapLayer.apply(profile.showTrafficLights, night,
                profile.trafficLightScalePercent, profile.trafficLightCardColor,
                profile.effectiveTrafficLightPriority());
        speedBumpMapLayer.apply(profile.showSpeedBumps,
                profile.speedBumpScalePercent,
                profile.effectiveSpeedBumpPriority());
        cameraDirectionMapLayer.apply(
                !"HIDDEN".equals(profile.roadEventMode("SPEED_CONTROL")),
                profile.showHudSpeedCameras,
                profile.cameraScalePercent,
                profile.cameraDirectionLengthPercent,
                profile.cameraDirectionWidthPercent,
                profile.cameraDirectionColor,
                profile.cameraDirectionOpacityPercent,
                profile.effectiveCameraPriority());
        laneGuidanceMapLayer.apply(profile.showLaneGuidance,
                profile.laneGuidanceScalePercent, night,
                profile.focusXPercent <= 55,
                profile.effectiveLanePriority());
        routeTurnMapLayer.apply(profile.showRouteTurns,
                profile.routeTurnLengthPercent,
                profile.routeTurnHeadSizePercent,
                profile.routeTurnFillColor,
                profile.routeTurnOutlineColor,
                profile.routeTurnOutlineWidth);
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
                    profile.cursorColor, profile.cursorOutlineColor,
                    profile.effectiveCursorPriority());
            syncOverlayNavigationState();
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
            relayoutOverlays(true);
            applySublayerOrder();
            if (roadEventScaleChanged) resetRoadEventVisibilityForStyleRefresh();
            applyRoadEventVisibility();
            destinationIconStyle = null;
            destinationImageProvider = null;
            destinationIconBitmap = null;
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
        boolean free = "FREE".equals(profile.cameraMode);
        NavigatorStatePublisher.CameraState liveSource = navigationCamera != null
                ? navigationCamera : initialCamera;
        if (currentMap == null || liveSource == null || !liveSource.isValid()) return;
        if (!free) freeCameraSource = null;
        if (free && freeCameraSource == null) freeCameraSource = liveSource;
        // FREE freezes the camera's geographical anchor, not the user's camera settings. The old
        // early return also froze tilt/zoom after the first frame, which made their sliders appear
        // broken until the renderer was recreated.
        NavigatorStatePublisher.CameraState source = free ? freeCameraSource : liveSource;
        try {
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object target = pointClass.getConstructor(double.class, double.class)
                    .newInstance(source.latitude, source.longitude);
            double requestedZoom = profile.fixedZoomEnabled
                    ? profile.fixedZoomLevel : source.zoom + profile.zoomDelta;
            float zoom = (float) Math.max(0d, Math.min(23d, requestedZoom));
            float azimuth = "NORTH_UP".equals(profile.cameraMode) ? 0f : source.azimuth;
            float tilt = profile.tiltDegrees;
            AppliedCamera next = new AppliedCamera(
                    source.latitude, source.longitude, zoom, azimuth, tilt);
            if (next.nearlyEquals(lastAppliedCamera)) {
                AppliedCamera actual = readAppliedCamera(currentMap);
                // GuidanceCamera and OEM lifecycle can replace a camera position after our move.
                // Trust a cached request only while MapKit still reports the same output values.
                if (next.nearlyEquals(actual)) return;
            }
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
            AppliedCamera actual = readAppliedCamera(currentMap);
            lastAppliedCamera = actual == null ? next : actual;
        } catch (Throwable failure) {
            Log.w(TAG, "HUD camera synchronization failed", failure);
        }
    }

    /** Reads MapKit's effective position so a silent clamp or later native override is corrected. */
    private static AppliedCamera readAppliedCamera(Object currentMap) {
        try {
            Object camera = invoke(currentMap, "getCameraPosition", new Class<?>[0]);
            Object target = invoke(camera, "getTarget", new Class<?>[0]);
            return new AppliedCamera(
                    ((Number) invoke(target, "getLatitude", new Class<?>[0])).doubleValue(),
                    ((Number) invoke(target, "getLongitude", new Class<?>[0])).doubleValue(),
                    ((Number) invoke(camera, "getZoom", new Class<?>[0])).floatValue(),
                    ((Number) invoke(camera, "getAzimuth", new Class<?>[0])).floatValue(),
                    ((Number) invoke(camera, "getTilt", new Class<?>[0])).floatValue());
        } catch (Throwable unavailable) {
            return null;
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
            ScaledRoadEventStyleProvider scaledProvider =
                    new ScaledRoadEventStyleProvider(stockProvider, styleProviderClass);
            scaledProvider.setScales(profile.roadEventScalePercent,
                    profile.cameraScalePercent);
            Object provider = scaledProvider.proxy();
            Class<?> managerClass = Class.forName(
                    "com.yandex.mapkit.road_events.RoadEventsManager");
            Object manager = mapKitClass.getMethod("createRoadEventsManager").invoke(mapKit);
            Object layer = mapKitClass.getMethod("createRoadEventsLayer", mapWindowClass,
                            styleProviderClass, managerClass)
                    .invoke(mapKit, currentMapWindow, provider, manager);
            roadEventStyleProvider = provider;
            scaledRoadEventStyleProvider = scaledProvider;
            roadEventsManager = manager;
            roadEventsLayer = layer;
            applyRoadEventVisibility();
            Log.i(TAG, "Standalone Yandex road-events layer attached to " + displayName);
            NavigationBridgeClient.reportDiagnostic(
                    "safe standalone road-events layer attached to independent "
                            + displayName + " MapWindow");
            NavigationBridgeClient.reportDiagnostic(
                    "Automotive NavigationLayer is deliberately forbidden on independent "
                            + displayName + " MapWindow");
        } catch (Throwable failure) {
            roadEventStyleProvider = null;
            scaledRoadEventStyleProvider = null;
            roadEventsManager = null;
            roadEventsLayer = null;
            Log.w(TAG, "HUD road-events layer unavailable: " + shortMessage(failure));
            NavigationBridgeClient.reportDiagnostic(
                    "HUD road-events layer unavailable: " + shortMessage(failure));
        }
    }

    private void applyRoadEventVisibility() {
        Object everywhere = roadEventsLayer;
        if (everywhere == null) return;
        try {
            Class<?> eventTagClass = Class.forName("com.yandex.mapkit.road_events.EventTag");
            boolean unifiedCameraLayer = routeGuidanceActive
                    && !"HIDDEN".equals(profile.roadEventMode("SPEED_CONTROL"));
            for (String tagName : NavigationMapProfile.ROAD_EVENT_TAGS) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object tag = Enum.valueOf((Class) eventTagClass, tagName);
                String mode = profile.roadEventMode(tagName);
                boolean mergedCameraTag = unifiedCameraLayer
                        && isUnifiedCameraControlTag(tagName);
                // Automotive NavigationLayer is deliberately forbidden on an independent
                // OffscreenMapWindow: MapKit 30.3.0 terminates the process asynchronously with
                // either camera configuration. The stable RoadEventsLayer remains source-native.
                // ROUTE_ONLY is gated by fresh route state; MapKit may include a nearby event.
                boolean visible = !mergedCameraTag && ("ALWAYS".equals(mode)
                        || (routeGuidanceActive && "ROUTE_ONLY".equals(mode)));
                invoke(everywhere, "setRoadEventVisible",
                        new Class<?>[]{eventTagClass, boolean.class}, tag, visible);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "HUD road-event visibility could not be applied", failure);
        }
    }

    /** These tags are rendered as compact detail glyphs inside one unified camera marker. */
    private static boolean isUnifiedCameraControlTag(String tag) {
        return "SPEED_CONTROL".equals(tag) || "NO_STOPPING_CONTROL".equals(tag)
                || "LANE_CONTROL".equals(tag) || "ROAD_MARKING_CONTROL".equals(tag)
                || "MOBILE_CONTROL".equals(tag) || "CROSS_ROAD_CONTROL".equals(tag)
                || "TRAFFIC_CONTROL".equals(tag);
    }

    /** A visibility round-trip makes MapKit request stock styles again after a live scale edit. */
    private void resetRoadEventVisibilityForStyleRefresh() {
        Object everywhere = roadEventsLayer;
        if (everywhere == null) return;
        try {
            Class<?> eventTagClass = Class.forName("com.yandex.mapkit.road_events.EventTag");
            for (String tagName : NavigationMapProfile.ROAD_EVENT_TAGS) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object tag = Enum.valueOf((Class) eventTagClass, tagName);
                invoke(everywhere, "setRoadEventVisible",
                        new Class<?>[]{eventTagClass, boolean.class}, tag, false);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Road-event scale refresh failed", failure);
        }
    }

    /**
     * Inserts custom layers into Navigator-compatible feature slots without moving Yandex-owned
     * substrate or label layers. Rechecking once per second also covers late MapKit sublayers.
     */
    private void applySublayerOrder() {
        Object currentMap = map;
        if (currentMap == null) return;
        try {
            Object manager = invoke(currentMap, "getSublayerManager", new Class<?>[0]);
            int count = ((Number) invoke(manager, "size", new Class<?>[0])).intValue();
            long fingerprint = count;
            fingerprint = fingerprint * 131L
                    + (profile.manualLayerPrioritiesEnabled ? 1L : 0L);
            fingerprint = fingerprint * 131L + profile.effectiveCameraPriority();
            fingerprint = fingerprint * 131L + profile.effectiveRoadEventPriority();
            fingerprint = fingerprint * 131L + profile.effectiveRoutePriority();
            fingerprint = fingerprint * 131L + profile.effectiveDestinationPriority();
            fingerprint = fingerprint * 131L + profile.effectiveSpeedBumpPriority();
            fingerprint = fingerprint * 131L + profile.effectiveTrafficLightPriority();
            fingerprint = fingerprint * 131L + profile.effectiveLanePriority();
            fingerprint = fingerprint * 131L + profile.effectiveCursorPriority();
            long now = android.os.SystemClock.elapsedRealtime();
            if (fingerprint == appliedLayerOrderFingerprint
                    && now - lastLayerOrderApplyElapsedMs < 1_000L) return;
            MapSublayerOrder.apply(currentMap, profile);
            appliedLayerOrderFingerprint = fingerprint;
            lastLayerOrderApplyElapsedMs = now;
        } catch (Throwable failure) {
            Log.w(TAG, "HUD sublayer order could not be applied", failure);
        }
    }

    /** Renders the exact active DrivingRoute geometry into this independent MapWindow. */
    private void rebuildRoute() {
        Object currentMap = map;
        if (currentMap == null) return;
        try {
            routeTurnMapLayer.attachRoute(null, null);
            Object collection = routeCollection;
            if (collection == null) {
                collection = MapObjectLayerFactory.create(currentMap,
                        MapSublayerOrder.ROUTE,
                        // MINOR keeps the route from displacing stock substrate labels. MapKit
                        // therefore owns the street-name font, outline, curvature and collision
                        // behaviour instead of Natro painting text placemarks over the map.
                        MapObjectLayerFactory.MINOR,
                        NavigationMapProfile.layerZ(profile.effectiveRoutePriority()));
                routeCollection = collection;
            } else {
                MapObjectLayerFactory.setZIndex(collection,
                        NavigationMapProfile.layerZ(profile.effectiveRoutePriority()));
            }
            invoke(collection, "clear", new Class<?>[0]);
            if (destinationCollection != null) {
                invoke(destinationCollection, "clear", new Class<?>[0]);
            }
            routePolyline = null;
            routeDestinationPlacemark = null;
            Object route = activeRoute;
            if (route == null || (!profile.showRoute && !profile.showDestination)) return;
            // Keep the route geometry immutable for its whole epoch. MapKit documents
            // PolylineMapObject.hide(Subpolyline) as the efficient way to hide travelled parts;
            // replacing geometry on every location frame resets native line state and visibly
            // flashes on both KX11 external surfaces.
            RouteSlice slice = fullRoute(route);
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
                routeTurnMapLayer.attachRoute(line, route);
                RouteProgress progress = currentRouteProgress(route);
                if (progress != null) applyRouteProgressMask(line, progress);
            }
            if (profile.showDestination && slice.destinationPoint != null) {
                Object destinations = destinationCollection;
                if (destinations == null) {
                    destinations = MapObjectLayerFactory.create(currentMap,
                            MapSublayerOrder.DESTINATION,
                            MapObjectLayerFactory.EQUAL,
                            NavigationMapProfile.layerZ(
                                    profile.effectiveDestinationPriority()));
                    destinationCollection = destinations;
                } else {
                    MapObjectLayerFactory.setZIndex(destinations,
                            NavigationMapProfile.layerZ(
                                    profile.effectiveDestinationPriority()));
                }
                addDestinationMarker(destinations, slice.destinationPoint);
            }
            applySublayerOrder();
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
            // createDestinationBitmap() already renders the vector path at its requested physical
            // size. Keep MapKit at 1:1 so a small endpoint never comes from bitmap downscaling.
            invoke(style, "setScale", new Class<?>[]{Float.class},
                    Float.valueOf(1f));
            invoke(style, "setZIndex", new Class<?>[]{Float.class},
                    Float.valueOf(NavigationMapProfile.layerZ(
                            profile.effectiveDestinationPriority())));
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
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float requestedScale = profile.destinationScalePercent / 100f;
        int width = Math.max(8,
                Math.round(Math.max(40, Math.round(48f * density)) * requestedScale));
        int height = Math.max(10,
                Math.round(Math.max(50, Math.round(58f * density)) * requestedScale));
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
                    0, renderedRouteSegmentCount,
                    routeColorScratch);
        } catch (Throwable failure) {
            Log.w(TAG, "HUD route traffic palette could not be updated", failure);
        }
    }

    /**
     * Hides the travelled prefix without replacing polyline geometry. A backward GPS correction
     * simply replaces the hidden Subpolyline with a shorter one, immediately restoring the route
     * while preserving MapKit's frame, traffic palette and native manoeuvre arrows.
     */
    private void updateRouteProgress(NavigatorStatePublisher.NavigationFrame frame) {
        Object line = routePolyline;
        Object route = activeRoute;
        if (line == null || route == null || !profile.showRoute
                || !frame.routeProgressValid) return;
        try {
            RouteProgress progress = new RouteProgress(
                    frame.routeSegmentIndex, frame.routeSegmentPosition);
            if (!routeProgressChanged(progress)) return;
            applyRouteProgressMask(line, progress);
        } catch (Throwable failure) {
            Log.w(TAG, "HUD route progress could not be advanced", failure);
        }
    }

    /** One atomic hide call cancels the previous mask, including when progress moves backwards. */
    private void applyRouteProgressMask(Object line, RouteProgress rawProgress) throws Exception {
        int segmentCount = Math.max(0, renderedRouteSegmentCount);
        if (segmentCount <= 0) return;
        int segmentIndex = Math.max(0, Math.min(segmentCount - 1,
                rawProgress.segmentIndex));
        double segmentPosition = Math.max(0d, Math.min(1d,
                rawProgress.segmentPosition));
        if (segmentIndex == 0 && segmentPosition <= 0.000001d) {
            // The List overload explicitly cancels every previous hidden range. This is the
            // reversible zero-progress case and avoids constructing an invalid empty range.
            invoke(line, "hide", new Class<?>[]{List.class}, Collections.emptyList());
        } else {
            Class<?> positionClass = Class.forName(
                    "com.yandex.mapkit.geometry.PolylinePosition");
            Object begin = positionClass.getConstructor(int.class, double.class)
                    .newInstance(0, 0d);
            Object end = positionClass.getConstructor(int.class, double.class)
                    .newInstance(segmentIndex, segmentPosition);
            Class<?> subpolylineClass = Class.forName(
                    "com.yandex.mapkit.geometry.Subpolyline");
            Object travelled = subpolylineClass.getConstructor(positionClass, positionClass)
                    .newInstance(begin, end);
            invoke(line, "hide", new Class<?>[]{subpolylineClass}, travelled);
        }
        renderedRouteSegmentIndex = segmentIndex;
        renderedRouteSegmentPosition = segmentPosition;
    }

    private boolean routeProgressChanged(RouteProgress progress) {
        return progress.segmentIndex != renderedRouteSegmentIndex
                || Double.isNaN(renderedRouteSegmentPosition)
                || Math.abs(progress.segmentPosition - renderedRouteSegmentPosition) > 0.000001d;
    }

    private static RouteSlice fullRoute(Object route) throws Exception {
        Object fullGeometry = invoke(route, "getGeometry", new Class<?>[0]);
        if (fullGeometry == null) return null;
        List<?> points = list(invoke(fullGeometry, "getPoints", new Class<?>[0]));
        if (points.size() < 2) return null;
        return new RouteSlice(fullGeometry, 0, 0d,
                points.size() - 1, points.get(points.size() - 1));
    }

    /** RoutePosition-only read; deliberately does not touch or copy the full route geometry. */
    private static RouteProgress currentRouteProgress(Object route) throws Exception {
        int segmentIndex = 0;
        double segmentPosition = 0d;
        Object routePosition = invoke(route, "getRoutePosition", new Class<?>[0]);
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
        return new RouteProgress(segmentIndex, segmentPosition);
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
        RouteProgress(int segmentIndex, double segmentPosition) {
            this.segmentIndex = segmentIndex;
            this.segmentPosition = segmentPosition;
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
        appliedMaximumFps = -1;
        appliedLayerOrderFingerprint = Long.MIN_VALUE;
        lastLayerOrderApplyElapsedMs = 0L;
        map = null;
        trafficLayer = null;
        roadEventsLayer = null;
        roadEventsManager = null;
        roadEventStyleProvider = null;
        scaledRoadEventStyleProvider = null;
        cursorStyler.detach();
        trafficLightMapLayer.detachMap();
        cameraDirectionMapLayer.detachMap();
        speedBumpMapLayer.detachMap();
        laneGuidanceMapLayer.detachMap();
        overlayPlacement.detach();
        routeTurnMapLayer.detachMap();
        routeCollection = null;
        destinationCollection = null;
        routePolyline = null;
        routeDestinationPlacemark = null;
        routeColorScratch.clear();
        renderedRouteSegmentIndex = 0;
        renderedRouteSegmentPosition = Double.NaN;
        renderedRouteSegmentCount = 0;
        freeCameraSource = null;
        lastAppliedCamera = null;
        lastOverlayLayoutElapsedMs = 0L;
        if (releaseSurface && currentSurface != null) {
            try { currentSurface.release(); } catch (RuntimeException ignored) {}
            surface = null;
            width = 0;
            height = 0;
        }
        if (releaseSurface) generation = -1L;
    }

    /** Re-evaluates collisions after camera movement without redrawing unchanged textures. */
    private void relayoutOverlays(boolean force) {
        if (mapWindow == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastOverlayLayoutElapsedMs < 200L) return;
        lastOverlayLayoutElapsedMs = now;
        overlayPlacement.beginLayout();
        // Cameras are immovable point objects and reserve first. Movable lane/traffic balloons
        // choose a free side around them; a collision may never push a camera off the route.
        cameraDirectionMapLayer.relayout();
        speedBumpMapLayer.relayout();
        laneGuidanceMapLayer.relayout();
        trafficLightMapLayer.relayout();
    }

    /** Keeps cursor protection in the same physical pixel space as MapCursorStyler. */
    private void syncOverlayNavigationState() {
        NavigatorStatePublisher.NavigationFrame frame = latestNavigationFrame;
        if (frame == null) {
            overlayPlacement.updateNavigationState(false, false, 0, Double.NaN,
                    Double.NaN, Double.NaN, 0);
            return;
        }
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        int textureSize = Math.max(32, Math.round(48f * density));
        int displaySize = profile.showCursor
                ? Math.max(1, Math.round(textureSize * profile.cursorScalePercent / 100f)) : 0;
        overlayPlacement.updateNavigationState(frame.routeActive, frame.routeProgressValid,
                frame.routeSegmentIndex, frame.routeSegmentPosition,
                frame.latitude, frame.longitude, displaySize);
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
