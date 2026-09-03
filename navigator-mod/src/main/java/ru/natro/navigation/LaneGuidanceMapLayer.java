/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The stock Yandex lane balloon, anchored to its upcoming RoutePosition. */
final class LaneGuidanceMapLayer {
    private static final String TAG = "NatroLaneMap";
    private static final long FRESH_MS = 1_500L;

    private final Context context;
    private final MapOverlayPlacementCoordinator placementCoordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object map;
    private Object collection;
    private Object placemark;
    private Object iconStyle;
    private Object imageProvider;
    private Object balloonTexture;
    private List<MapOverlayPlacementCoordinator.Footprint> placementFootprints =
            Collections.emptyList();
    private int iconWidth;
    private int iconHeight;
    private boolean enabled;
    private boolean nightMode;
    private boolean placeOnRight = true;
    private int scalePercent = 100;
    private float zIndex = NavigationMapProfile.layerZ(80);
    private boolean latestRouteActive;
    private NavigatorStatePublisher.LaneGuidanceFrame latest;
    private long latestSampleElapsedMs;
    private long latestFingerprint = Long.MIN_VALUE;
    private long renderedFingerprint = Long.MIN_VALUE;
    private boolean expiryPosted;
    private MapOverlayPlacementCoordinator.Placement placement;

    private final Runnable expire = new Runnable() {
        @Override public void run() {
            expiryPosted = false;
            if (latestSampleElapsedMs <= 0L) return;
            long remaining = FRESH_MS
                    - (SystemClock.elapsedRealtime() - latestSampleElapsedMs);
            if (remaining > 0L) {
                expiryPosted = main.postDelayed(this, remaining);
                return;
            }
            latest = null;
            latestSampleElapsedMs = 0L;
            latestFingerprint = Long.MIN_VALUE;
            render();
        }
    };

    LaneGuidanceMapLayer(Context context) {
        this(context, new MapOverlayPlacementCoordinator());
    }

    LaneGuidanceMapLayer(Context context,
                         MapOverlayPlacementCoordinator placementCoordinator) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.placementCoordinator = placementCoordinator;
    }

    void attach(Object nextMap) {
        if (map == nextMap) return;
        detachMap();
        map = nextMap;
        discardExpiredSource();
        scheduleExpiryIfNeeded();
        render();
    }

    void detachMap() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        clearVisual();
        collection = null;
        map = null;
    }

    void apply(boolean nextEnabled, int nextScalePercent, boolean nextNightMode,
               boolean nextPlaceOnRight, int layerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = scalePercent != nextScale || nightMode != nextNightMode
                || placeOnRight != nextPlaceOnRight || zIndex != nextZ;
        boolean enabledChanged = enabled != nextEnabled;
        if (!presentationChanged && !enabledChanged) return;
        enabled = nextEnabled;
        scalePercent = nextScale;
        nightMode = nextNightMode;
        placeOnRight = nextPlaceOnRight;
        zIndex = nextZ;
        MapObjectLayerFactory.setZIndex(collection, nextZ);
        if (presentationChanged) {
            iconStyle = null;
            renderedFingerprint = Long.MIN_VALUE;
        }
        if (!enabled) {
            main.removeCallbacks(expire);
            expiryPosted = false;
        } else {
            discardExpiredSource();
            scheduleExpiryIfNeeded();
        }
        render();
    }

    void update(boolean routeActive, long sampleElapsedMs,
                NavigatorStatePublisher.LaneGuidanceFrame frame) {
        if (routeActive == latestRouteActive && sampleElapsedMs == latestSampleElapsedMs
                && frame == latest) return;
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L && now >= sampleElapsedMs
                && now - sampleElapsedMs <= FRESH_MS
                && frame != null && frame.hasContent();
        latest = fresh ? frame : null;
        if (fresh) {
            latestSampleElapsedMs = sampleElapsedMs;
            scheduleExpiryIfNeeded();
        } else {
            latestSampleElapsedMs = 0L;
            main.removeCallbacks(expire);
            expiryPosted = false;
        }
        long fingerprint = fingerprint(latest);
        if (fingerprint == latestFingerprint) return;
        latestFingerprint = fingerprint;
        if (enabled && map != null) render();
    }

    void clearData() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        latestRouteActive = false;
        latest = null;
        latestSampleElapsedMs = 0L;
        latestFingerprint = Long.MIN_VALUE;
        render();
    }

    /** Participates in the shared lane/light/camera collision pass after a camera move. */
    void relayout() {
        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_LANES);
        NavigatorStatePublisher.LaneGuidanceFrame frame = latest;
        if (!enabled || !latestRouteActive || frame == null || !frame.hasContent()
                || placemark == null || iconStyle == null || imageProvider == null
                || balloonTexture == null || iconWidth <= 0 || iconHeight <= 0) return;
        try {
            MapOverlayPlacementCoordinator.Placement next = placementCoordinator.reserve(
                    MapOverlayPlacementCoordinator.OWNER_LANES, frame.id,
                    frame.latitude, frame.longitude,
                    iconWidth, iconHeight, placeOnRight,
                    frame.routeSegmentIndex, frame.routeSegmentPosition, placement,
                    placementFootprints);
            applyPlacement(next);
        } catch (Throwable failure) {
            Log.w(TAG, "Lane balloon could not be repositioned", failure);
        }
    }

    private void scheduleExpiryIfNeeded() {
        if (!enabled || map == null || latestSampleElapsedMs <= 0L || expiryPosted) return;
        long age = SystemClock.elapsedRealtime() - latestSampleElapsedMs;
        if (age < 0L || age > FRESH_MS) return;
        expiryPosted = main.postDelayed(expire, Math.max(1L, FRESH_MS - age));
    }

    private void discardExpiredSource() {
        if (latestSampleElapsedMs <= 0L) return;
        long age = SystemClock.elapsedRealtime() - latestSampleElapsedMs;
        if (age >= 0L && age <= FRESH_MS) return;
        latest = null;
        latestSampleElapsedMs = 0L;
        latestFingerprint = Long.MIN_VALUE;
    }

    private void render() {
        if (map == null) return;
        NavigatorStatePublisher.LaneGuidanceFrame frame = latest;
        if (!enabled || !latestRouteActive || frame == null || !frame.hasContent()) {
            clearVisual();
            return;
        }
        try {
            Object currentPlacemark = ensurePlacemark(frame);
            if (renderedFingerprint != latestFingerprint) {
                applyOriginalYandexIcon(currentPlacemark, frame);
                renderedFingerprint = latestFingerprint;
            }
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(frame.latitude, frame.longitude);
            invoke(currentPlacemark, "setGeometry", new Class<?>[]{pointClass}, point);
            invoke(currentPlacemark, "setVisible", new Class<?>[]{boolean.class}, true);
        } catch (Throwable failure) {
            // Do not draw guessed arrows: if the stock renderer is unavailable, hiding the item is
            // safer than presenting incorrect lane information to the driver.
            Log.w(TAG, "Original Yandex lane renderer failed", failure);
            clearVisual();
        }
    }

    private Object ensurePlacemark(NavigatorStatePublisher.LaneGuidanceFrame frame)
            throws Exception {
        Object currentPlacemark = placemark;
        if (currentPlacemark != null) return currentPlacemark;
        Object currentCollection = collection;
        if (currentCollection == null) {
            currentCollection = MapObjectLayerFactory.create(map,
                    MapSublayerOrder.LANE_GUIDANCE,
                    MapObjectLayerFactory.MAJOR, zIndex);
            collection = currentCollection;
        }
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(frame.latitude, frame.longitude);
        currentPlacemark = invoke(currentCollection, "addPlacemark",
                new Class<?>[]{pointClass}, point);
        placemark = currentPlacemark;
        return currentPlacemark;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyOriginalYandexIcon(Object currentPlacemark,
                                         NavigatorStatePublisher.LaneGuidanceFrame frame)
            throws Exception {
        Class<?> laneSignClass = Class.forName(
                "com.yandex.mapkit.directions.driving.LaneSign");
        if (!laneSignClass.isInstance(frame.laneSign)) {
            throw new IllegalArgumentException("LaneSign type mismatch");
        }
        Class<?> directionSignClass = Class.forName(
                "com.yandex.mapkit.directions.driving.DirectionSign");
        Class<?> laneBalloonClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.layer.LaneSignBalloon");
        Object laneBalloon = laneBalloonClass
                .getConstructor(laneSignClass, directionSignClass)
                .newInstance(frame.laneSign, null);
        Class<?> balloonClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.layer.Balloon");
        Object balloon = balloonClass.getMethod("fromLaneSign", laneBalloonClass)
                .invoke(null, laneBalloon);

        Class<?> colorsClass = Class.forName(
                "com.yandex.mapkit.styling.automotive.balloons.BalloonColors");
        Class<?> factoryClass = Class.forName(
                "com.yandex.mapkit.styling.automotivenavigation.balloons."
                        + "LaneSignBalloonTextureFactory");
        Object factory = factoryClass.getConstructor(Context.class, colorsClass)
                .newInstance(context, null);
        Object texture = factoryClass
                .getMethod("createTexture", balloonClass, boolean.class, float.class)
                .invoke(factory, balloon, nightMode, scalePercent / 100f);
        List<MapOverlayPlacementCoordinator.Footprint> footprints =
                measureBalloonFootprints(texture);
        int measuredWidth = 1;
        int measuredHeight = 1;
        for (MapOverlayPlacementCoordinator.Footprint footprint : footprints) {
            measuredWidth = Math.max(measuredWidth, footprint.width);
            measuredHeight = Math.max(measuredHeight, footprint.height);
        }

        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_LANES);
        MapOverlayPlacementCoordinator.Placement nextPlacement = placementCoordinator.reserve(
                MapOverlayPlacementCoordinator.OWNER_LANES, frame.id,
                frame.latitude, frame.longitude,
                measuredWidth, measuredHeight, placeOnRight,
                frame.routeSegmentIndex, frame.routeSegmentPosition, placement, footprints);

        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object style = iconStyle;
        if (style == null) {
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf(
                    (Class<? extends Enum>) rotationClass, "NO_ROTATION");
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
            iconStyle = style;
        }
        Object exactAnchor = balloonAnchor(nextPlacement.legName);
        Object provider = invoke(texture, "create",
                new Class<?>[]{exactAnchor.getClass()}, exactAnchor);
        Object geometry = invoke(texture, "getBalloonGeometry",
                new Class<?>[]{exactAnchor.getClass()}, exactAnchor);
        PointF imageAnchor = (PointF) invoke(geometry, "getImageAnchor", new Class<?>[0]);
        imageProvider = provider;
        balloonTexture = texture;
        placementFootprints = footprints;
        iconWidth = Math.max(1, Math.round(((Number) invoke(
                geometry, "getWidth", new Class<?>[0])).floatValue()));
        iconHeight = Math.max(1, Math.round(((Number) invoke(
                geometry, "getHeight", new Class<?>[0])).floatValue()));
        placement = nextPlacement;
        invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                imageAnchor);
        invoke(currentPlacemark, "setIcon",
                new Class<?>[]{providerClass, styleClass}, provider, style);
    }

    private void applyPlacement(MapOverlayPlacementCoordinator.Placement next)
            throws Exception {
        if (next == null) return;
        Object style = iconStyle;
        Object provider = imageProvider;
        Object texture = balloonTexture;
        Object currentPlacemark = placemark;
        if (style == null || provider == null || texture == null
                || currentPlacemark == null) return;
        if (placement != null && placement.sameSlot(next)) return;
        Object exactAnchor = balloonAnchor(next.legName);
        provider = invoke(texture, "create",
                new Class<?>[]{exactAnchor.getClass()}, exactAnchor);
        Object geometry = invoke(texture, "getBalloonGeometry",
                new Class<?>[]{exactAnchor.getClass()}, exactAnchor);
        PointF imageAnchor = (PointF) invoke(geometry, "getImageAnchor", new Class<?>[0]);
        imageProvider = provider;
        iconWidth = Math.max(1, Math.round(((Number) invoke(
                geometry, "getWidth", new Class<?>[0])).floatValue()));
        iconHeight = Math.max(1, Math.round(((Number) invoke(
                geometry, "getHeight", new Class<?>[0])).floatValue()));
        invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                imageAnchor);
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        invoke(currentPlacemark, "setIcon",
                new Class<?>[]{providerClass, styleClass}, provider, style);
        placement = next;
    }

    /** Measures all eight real stock bitmaps and anchors before choosing a side. */
    private static List<MapOverlayPlacementCoordinator.Footprint> measureBalloonFootprints(
            Object texture) throws Exception {
        ArrayList<MapOverlayPlacementCoordinator.Footprint> result = new ArrayList<>(8);
        for (String legName : MapOverlayPlacementCoordinator.placementLegNames()) {
            Object anchor = balloonAnchor(legName);
            Object geometry = invoke(texture, "getBalloonGeometry",
                    new Class<?>[]{anchor.getClass()}, anchor);
            int width = Math.max(1, Math.round(((Number) invoke(
                    geometry, "getWidth", new Class<?>[0])).floatValue()));
            int height = Math.max(1, Math.round(((Number) invoke(
                    geometry, "getHeight", new Class<?>[0])).floatValue()));
            PointF imageAnchor = (PointF) invoke(
                    geometry, "getImageAnchor", new Class<?>[0]);
            result.add(new MapOverlayPlacementCoordinator.Footprint(
                    legName, width, height, imageAnchor.x, imageAnchor.y));
        }
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object balloonAnchor(String legName) throws Exception {
        String[] parts = legName.split("_");
        String vertical;
        String horizontal;
        if (parts.length == 2) {
            if ("LEFT".equals(parts[0]) || "RIGHT".equals(parts[0])) {
                horizontal = parts[0];
                vertical = parts[1];
            } else {
                vertical = parts[0];
                horizontal = parts[1];
            }
        } else {
            vertical = "CENTER";
            horizontal = "LEFT";
        }
        Class<?> verticalClass = Class.forName(
                "com.yandex.mapkit.navigation.balloons.VerticalPosition");
        Class<?> horizontalClass = Class.forName(
                "com.yandex.mapkit.navigation.balloons.HorizontalPosition");
        Object verticalValue = Enum.valueOf((Class<? extends Enum>) verticalClass, vertical);
        Object horizontalValue = Enum.valueOf((Class<? extends Enum>) horizontalClass, horizontal);
        Class<?> anchorClass = Class.forName(
                "com.yandex.mapkit.navigation.balloons.BalloonAnchor");
        return anchorClass.getConstructor(verticalClass, horizontalClass)
                .newInstance(verticalValue, horizontalValue);
    }

    private void clearVisual() {
        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_LANES);
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        placemark = null;
        iconStyle = null;
        imageProvider = null;
        balloonTexture = null;
        placementFootprints = Collections.emptyList();
        iconWidth = 0;
        iconHeight = 0;
        placement = null;
        renderedFingerprint = Long.MIN_VALUE;
    }

    private static long fingerprint(NavigatorStatePublisher.LaneGuidanceFrame frame) {
        if (frame == null || !frame.hasContent()) return 0L;
        long result = 0xcbf29ce484222325L;
        result = mix(result, frame.id.hashCode());
        result = mix(result, Math.round(frame.latitude * 1_000_000d));
        result = mix(result, Math.round(frame.longitude * 1_000_000d));
        result = mix(result, frame.routeSegmentIndex);
        result = mix(result, Double.doubleToLongBits(frame.routeSegmentPosition));
        for (NavigatorStatePublisher.LaneFrame lane : frame.lanes) {
            result = mix(result, lane.kind.hashCode());
            result = mix(result, lane.highlightedDirection.hashCode());
            for (String direction : lane.directions) result = mix(result, direction.hashCode());
        }
        return mix(result, frame.lanes.size());
    }

    private static long mix(long value, long part) {
        return (value ^ part) * 0x100000001b3L;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }
}
