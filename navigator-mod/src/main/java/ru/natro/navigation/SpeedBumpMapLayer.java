/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stock vector pins for every artificial unevenness on the exact active DrivingRoute. */
final class SpeedBumpMapLayer {
    private static final String TAG = "NatroSpeedBumps";
    private static final String STOCK_DRAWABLE =
            "mapkit_styling_automotive_route_speed_bump";
    /** The drawable's white tail ends at y=28.8 in its 32-unit viewport. */
    private static final float PIN_ANCHOR_X = .50f;
    private static final float PIN_ANCHOR_Y = .90f;

    private final Context context;
    private final MapOverlayPlacementCoordinator placementCoordinator;
    private final ArrayList<Marker> markers = new ArrayList<>();
    private Object map;
    private Object collection;
    private boolean enabled;
    private int scalePercent = 100;
    private float zIndex = NavigationMapProfile.layerZ(55);
    private long routeEpoch = Long.MIN_VALUE;
    private Object activeRoute;
    private List<SpeedBump> speedBumps = Collections.emptyList();
    private boolean routeActive;
    private boolean routeProgressValid;
    private int routeSegmentIndex;
    private double routeSegmentPosition;
    private Bitmap iconBitmap;
    private Object imageProvider;

    SpeedBumpMapLayer(Context context,
                      MapOverlayPlacementCoordinator placementCoordinator) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.placementCoordinator = placementCoordinator;
    }

    void attach(Object nextMap) {
        if (map == nextMap) return;
        detachMap();
        map = nextMap;
        render();
    }

    void detachMap() {
        clearVisual();
        collection = null;
        map = null;
    }

    void apply(boolean nextEnabled, int nextScalePercent, int layerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = scalePercent != nextScale || zIndex != nextZ;
        boolean visibilityChanged = enabled != nextEnabled;
        if (!presentationChanged && !visibilityChanged) return;
        enabled = nextEnabled;
        scalePercent = nextScale;
        zIndex = nextZ;
        MapObjectLayerFactory.setZIndex(collection, nextZ);
        // Disabling clears the native placemarks. Re-enabling must therefore rebuild them even
        // when scale/z-order stayed unchanged; a visibility-only update would have no markers.
        if (presentationChanged || visibilityChanged) render();
    }

    /** Reads immutable route pins only when the canonical route epoch changes. */
    void updateRoute(long nextRouteEpoch, Object drivingRoute) {
        if (nextRouteEpoch < routeEpoch) return;
        boolean changed = nextRouteEpoch != routeEpoch
                || (drivingRoute == null) != (activeRoute == null);
        activeRoute = drivingRoute;
        if (!changed) return;
        routeEpoch = nextRouteEpoch;
        // Do not expose pins from a newly installed route under the previous route's state.
        // NavigationBridgeClient applies the matching primitive frame immediately afterwards.
        routeActive = false;
        speedBumps = drivingRoute == null
                ? Collections.emptyList() : readSpeedBumps(drivingRoute, nextRouteEpoch);
        render();
    }

    void updateNavigationState(boolean nextRouteActive, boolean progressValid,
                               int segmentIndex, double segmentPosition) {
        int nextSegmentIndex = Math.max(0, segmentIndex);
        double nextSegmentPosition = Math.max(0d, Math.min(1d, segmentPosition));
        boolean changed = routeActive != nextRouteActive
                || routeProgressValid != progressValid
                || routeSegmentIndex != nextSegmentIndex
                || Double.compare(routeSegmentPosition, nextSegmentPosition) != 0;
        routeActive = nextRouteActive;
        routeProgressValid = progressValid;
        routeSegmentIndex = nextSegmentIndex;
        routeSegmentPosition = nextSegmentPosition;
        if (changed) updateVisibility();
    }

    void clearData() {
        routeActive = false;
        routeProgressValid = false;
        updateVisibility();
    }

    /** Keeps fixed pin tails on route while reserving their real screen footprint for balloons. */
    void relayout() {
        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_SPEED_BUMPS);
        for (Marker marker : markers) {
            if (!marker.visible) continue;
            placementCoordinator.reserveFixed(
                    MapOverlayPlacementCoordinator.OWNER_SPEED_BUMPS,
                    marker.speedBump.id,
                    marker.speedBump.latitude, marker.speedBump.longitude,
                    marker.bitmapWidth, marker.bitmapHeight,
                    PIN_ANCHOR_X, PIN_ANCHOR_Y);
        }
    }

    private List<SpeedBump> readSpeedBumps(Object route, long epoch) {
        ArrayList<SpeedBump> result = new ArrayList<>();
        try {
            Object geometry = invoke(route, "getGeometry", new Class<?>[0]);
            Object raw = invoke(route, "getSpeedBumps", new Class<?>[0]);
            if (geometry == null || !(raw instanceof List<?>)) return result;
            Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
            Class<?> positionClass = Class.forName(
                    "com.yandex.mapkit.geometry.PolylinePosition");
            Class<?> utilsClass = Class.forName(
                    "com.yandex.mapkit.geometry.geo.PolylineUtils");
            Method pointAt = ReflectMethods.publicMethod(utilsClass,
                    "pointByPolylinePosition",
                    new Class<?>[]{polylineClass, positionClass});
            int sourceIndex = 0;
            for (Object value : (List<?>) raw) {
                try {
                    Object position = invoke(value, "getPosition", new Class<?>[0]);
                    if (position == null) continue;
                    int segmentIndex = ((Number) invoke(position, "getSegmentIndex",
                            new Class<?>[0])).intValue();
                    double segmentPosition = ((Number) invoke(position, "getSegmentPosition",
                            new Class<?>[0])).doubleValue();
                    if (segmentIndex < 0 || !finite(segmentPosition)
                            || segmentPosition < 0d || segmentPosition > 1d) continue;
                    Object point = pointAt.invoke(null, geometry, position);
                    if (point == null) continue;
                    double latitude = ((Number) invoke(point, "getLatitude",
                            new Class<?>[0])).doubleValue();
                    double longitude = ((Number) invoke(point, "getLongitude",
                            new Class<?>[0])).doubleValue();
                    if (!validCoordinate(latitude, longitude)) continue;
                    result.add(new SpeedBump(
                            "speed-bump:" + epoch + ':' + sourceIndex,
                            latitude, longitude, segmentIndex, segmentPosition));
                } catch (Throwable malformed) {
                    // One malformed native wrapper cannot suppress later valid route pins.
                } finally {
                    sourceIndex++;
                }
            }
        } catch (Throwable unavailable) {
            Log.w(TAG, "DrivingRoute speed bumps could not be read", unavailable);
        }
        return result.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void render() {
        clearVisual();
        if (map == null || !enabled || speedBumps.isEmpty()) return;
        try {
            Object currentCollection = collection;
            if (currentCollection == null) {
                currentCollection = MapObjectLayerFactory.create(map,
                        MapSublayerOrder.SPEED_BUMPS,
                        // Every route bump remains present; movable balloons avoid reservations.
                        MapObjectLayerFactory.IGNORE, zIndex);
                collection = currentCollection;
            } else {
                MapObjectLayerFactory.setZIndex(currentCollection, zIndex);
            }
            Bitmap bitmap = createStockBitmap();
            if (bitmap == null) {
                Log.w(TAG, "Navigator stock speed-bump drawable is unavailable");
                return;
            }
            Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
            Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                    .invoke(null, bitmap);
            iconBitmap = bitmap;
            imageProvider = provider;
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            Object noRotation = Enum.valueOf(
                    (Class<? extends Enum>) rotationClass, "NO_ROTATION");
            for (SpeedBump speedBump : speedBumps) {
                Object point = pointClass.getConstructor(double.class, double.class)
                        .newInstance(speedBump.latitude, speedBump.longitude);
                Object placemark = invoke(currentCollection, "addPlacemark",
                        new Class<?>[]{pointClass}, point);
                Object style = styleClass.getConstructor().newInstance();
                invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                        new PointF(PIN_ANCHOR_X, PIN_ANCHOR_Y));
                invoke(style, "setRotationType", new Class<?>[]{rotationClass}, noRotation);
                // The vector drawable is rasterised directly at the selected physical size.
                // MapKit receives it 1:1 and never shrinks a pre-rendered final layer.
                invoke(style, "setScale", new Class<?>[]{Float.class}, Float.valueOf(1f));
                invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
                invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
                invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
                invoke(placemark, "setIcon", new Class<?>[]{providerClass, styleClass},
                        provider, style);
                Marker marker = new Marker(speedBump, placemark,
                        bitmap.getWidth(), bitmap.getHeight());
                markers.add(marker);
            }
            updateVisibility();
            relayout();
        } catch (Throwable failure) {
            Log.w(TAG, "Speed-bump route layer could not be rendered", failure);
            clearVisual();
        }
    }

    private Bitmap createStockBitmap() {
        try {
            int resource = context.getResources().getIdentifier(
                    STOCK_DRAWABLE, "drawable", context.getPackageName());
            if (resource == 0) return null;
            Drawable drawable = context.getResources().getDrawable(resource, context.getTheme());
            int intrinsicWidth = drawable.getIntrinsicWidth();
            int intrinsicHeight = drawable.getIntrinsicHeight();
            float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
            if (intrinsicWidth <= 0) intrinsicWidth = Math.round(28f * density);
            if (intrinsicHeight <= 0) intrinsicHeight = Math.round(32f * density);
            int width = Math.max(1, Math.round(intrinsicWidth * scalePercent / 100f));
            int height = Math.max(1, Math.round(intrinsicHeight * scalePercent / 100f));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private void updateVisibility() {
        for (Marker marker : markers) {
            boolean visible = enabled && routeActive && !isPassed(marker.speedBump);
            if (marker.visible == visible) continue;
            try {
                invoke(marker.placemark, "setVisible", new Class<?>[]{boolean.class}, visible);
                marker.visible = visible;
            } catch (Throwable failure) {
                Log.w(TAG, "Speed-bump visibility update failed", failure);
            }
        }
    }

    private boolean isPassed(SpeedBump speedBump) {
        if (!routeProgressValid) return false;
        if (speedBump.routeSegmentIndex != routeSegmentIndex) {
            return speedBump.routeSegmentIndex < routeSegmentIndex;
        }
        return speedBump.routeSegmentPosition + 0.000001d < routeSegmentPosition;
    }

    private void clearVisual() {
        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_SPEED_BUMPS);
        Object currentCollection = collection;
        if (currentCollection != null) {
            try {
                invoke(currentCollection, "clear", new Class<?>[0]);
            } catch (Throwable ignored) {}
        }
        markers.clear();
        imageProvider = null;
        iconBitmap = null;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return finite(latitude) && latitude >= -90d && latitude <= 90d
                && finite(longitude) && longitude >= -180d && longitude <= 180d;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class SpeedBump {
        final String id;
        final double latitude;
        final double longitude;
        final int routeSegmentIndex;
        final double routeSegmentPosition;

        SpeedBump(String id, double latitude, double longitude,
                  int routeSegmentIndex, double routeSegmentPosition) {
            this.id = id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.routeSegmentIndex = routeSegmentIndex;
            this.routeSegmentPosition = routeSegmentPosition;
        }
    }

    private static final class Marker {
        final SpeedBump speedBump;
        final Object placemark;
        final int bitmapWidth;
        final int bitmapHeight;
        boolean visible = true;

        Marker(SpeedBump speedBump, Object placemark, int bitmapWidth, int bitmapHeight) {
            this.speedBump = speedBump;
            this.placemark = placemark;
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
        }
    }
}
