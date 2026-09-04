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

/** Stock route pins for every DrivingRoute traffic light, including lights without live data. */
final class RouteTrafficLightMapLayer {
    private static final String TAG = "NatroRouteLights";
    private static final String STOCK_DAY =
            "mapkit_styling_automotive_route_trafficlight_day";
    private static final String STOCK_NIGHT =
            "mapkit_styling_automotive_route_trafficlight_night";
    private static final float PIN_ANCHOR_X = .50f;
    /** Stock 22x36 vector ends with its route-point tail at y=34. */
    private static final float PIN_ANCHOR_Y = 34f / 36f;
    private static final double LIVE_POINT_TOLERANCE_METERS = 24d;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private final Context context;
    private final MapOverlayPlacementCoordinator placementCoordinator;
    private final ArrayList<Marker> markers = new ArrayList<>();
    private Object map;
    private Object collection;
    private boolean enabled;
    private boolean night;
    private int scalePercent = 100;
    private float zIndex = NavigationMapProfile.layerZ(68);
    private long routeEpoch = Long.MIN_VALUE;
    private Object activeRoute;
    private List<RouteLight> routeLights = Collections.emptyList();
    private List<NavigatorStatePublisher.TrafficLightFrame> liveLights =
            Collections.emptyList();
    private boolean routeActive;
    private boolean routeProgressValid;
    private int routeSegmentIndex;
    private double routeSegmentPosition;
    private Bitmap iconBitmap;
    private Object imageProvider;

    RouteTrafficLightMapLayer(Context context,
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

    void apply(boolean nextEnabled, boolean nextNight, int nextScalePercent,
               int layerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = night != nextNight || scalePercent != nextScale
                || zIndex != nextZ;
        boolean visibilityChanged = enabled != nextEnabled;
        if (!presentationChanged && !visibilityChanged) return;
        enabled = nextEnabled;
        night = nextNight;
        scalePercent = nextScale;
        zIndex = nextZ;
        MapObjectLayerFactory.setZIndex(collection, nextZ);
        render();
    }

    /** The full route list is immutable for one route epoch and is read only once. */
    void updateRoute(long nextRouteEpoch, Object drivingRoute) {
        if (nextRouteEpoch < routeEpoch) return;
        boolean changed = nextRouteEpoch != routeEpoch
                || (drivingRoute == null) != (activeRoute == null);
        activeRoute = drivingRoute;
        if (!changed) return;
        routeEpoch = nextRouteEpoch;
        routeActive = false;
        liveLights = Collections.emptyList();
        routeLights = drivingRoute == null ? Collections.emptyList()
                : readRouteLights(drivingRoute, nextRouteEpoch);
        render();
    }

    /** Live Windshield cards suppress the matching plain route pin in the same frame. */
    void updateNavigationState(boolean nextRouteActive, boolean progressValid,
                               int segmentIndex, double segmentPosition,
                               List<NavigatorStatePublisher.TrafficLightFrame> nextLiveLights) {
        int nextSegmentIndex = Math.max(0, segmentIndex);
        double nextSegmentPosition = Math.max(0d, Math.min(1d, segmentPosition));
        List<NavigatorStatePublisher.TrafficLightFrame> safeLive = nextRouteActive
                && nextLiveLights != null ? nextLiveLights : Collections.emptyList();
        boolean changed = routeActive != nextRouteActive
                || routeProgressValid != progressValid
                || routeSegmentIndex != nextSegmentIndex
                || Double.compare(routeSegmentPosition, nextSegmentPosition) != 0
                || liveLights != safeLive;
        routeActive = nextRouteActive;
        routeProgressValid = progressValid;
        routeSegmentIndex = nextSegmentIndex;
        routeSegmentPosition = nextSegmentPosition;
        liveLights = safeLive;
        if (changed) updateVisibility();
    }

    void clearData() {
        routeActive = false;
        routeProgressValid = false;
        liveLights = Collections.emptyList();
        updateVisibility();
    }

    void relayout() {
        placementCoordinator.clearOwner(
                MapOverlayPlacementCoordinator.OWNER_ROUTE_TRAFFIC_LIGHTS);
        for (Marker marker : markers) {
            if (!marker.visible) continue;
            placementCoordinator.reserveFixed(
                    MapOverlayPlacementCoordinator.OWNER_ROUTE_TRAFFIC_LIGHTS,
                    marker.light.id, marker.light.latitude, marker.light.longitude,
                    marker.bitmapWidth, marker.bitmapHeight, PIN_ANCHOR_X, PIN_ANCHOR_Y);
        }
    }

    private List<RouteLight> readRouteLights(Object route, long epoch) {
        ArrayList<RouteLight> result = new ArrayList<>();
        try {
            Object geometry = invoke(route, "getGeometry", new Class<?>[0]);
            Object raw = invoke(route, "getTrafficLights", new Class<?>[0]);
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
                    String sourceId = String.valueOf(invoke(value, "getId",
                            new Class<?>[0]));
                    if (sourceId == null || "null".equals(sourceId)) sourceId = "";
                    String id = "route-traffic-light:" + epoch + ':'
                            + (sourceId.isEmpty() ? sourceIndex : sourceId);
                    result.add(new RouteLight(id, sourceId, latitude, longitude,
                            segmentIndex, segmentPosition));
                } catch (Throwable malformed) {
                    // Preserve every later valid route pin when one vendor wrapper is transient.
                } finally {
                    sourceIndex++;
                }
            }
        } catch (Throwable unavailable) {
            Log.w(TAG, "DrivingRoute traffic lights could not be read", unavailable);
        }
        return result.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void render() {
        clearVisual();
        if (map == null || !enabled || routeLights.isEmpty()) return;
        try {
            Object currentCollection = collection;
            if (currentCollection == null) {
                currentCollection = MapObjectLayerFactory.create(map,
                        MapSublayerOrder.ROUTE_TRAFFIC_LIGHTS,
                        MapObjectLayerFactory.IGNORE, zIndex);
                collection = currentCollection;
            } else {
                MapObjectLayerFactory.setZIndex(currentCollection, zIndex);
            }
            Bitmap bitmap = createStockBitmap();
            if (bitmap == null) {
                Log.w(TAG, "Navigator stock route-traffic-light drawable is unavailable");
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
            for (RouteLight light : routeLights) {
                Object point = pointClass.getConstructor(double.class, double.class)
                        .newInstance(light.latitude, light.longitude);
                Object placemark = invoke(currentCollection, "addPlacemark",
                        new Class<?>[]{pointClass}, point);
                Object style = styleClass.getConstructor().newInstance();
                invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                        new PointF(PIN_ANCHOR_X, PIN_ANCHOR_Y));
                invoke(style, "setRotationType", new Class<?>[]{rotationClass}, noRotation);
                // Rasterise the stock vector at its selected final physical size. MapKit does no
                // second resize, so 50/75% remain crisp instead of sampling a 100% bitmap twice.
                invoke(style, "setScale", new Class<?>[]{Float.class}, Float.valueOf(1f));
                invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
                invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
                invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
                invoke(placemark, "setIcon", new Class<?>[]{providerClass, styleClass},
                        provider, style);
                markers.add(new Marker(light, placemark,
                        bitmap.getWidth(), bitmap.getHeight()));
            }
            updateVisibility();
            relayout();
        } catch (Throwable failure) {
            Log.w(TAG, "Route traffic-light layer could not be rendered", failure);
            clearVisual();
        }
    }

    private Bitmap createStockBitmap() {
        try {
            String name = night ? STOCK_NIGHT : STOCK_DAY;
            int resource = context.getResources().getIdentifier(
                    name, "drawable", context.getPackageName());
            if (resource == 0) return null;
            Drawable drawable = context.getResources().getDrawable(resource, context.getTheme());
            int intrinsicWidth = drawable.getIntrinsicWidth();
            int intrinsicHeight = drawable.getIntrinsicHeight();
            float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
            if (intrinsicWidth <= 0) intrinsicWidth = Math.round(22f * density);
            if (intrinsicHeight <= 0) intrinsicHeight = Math.round(36f * density);
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
            boolean visible = enabled && routeActive && !isPassed(marker.light)
                    && !hasMatchingLiveLight(marker.light);
            if (marker.visible == visible) continue;
            try {
                invoke(marker.placemark, "setVisible", new Class<?>[]{boolean.class}, visible);
                marker.visible = visible;
            } catch (Throwable failure) {
                Log.w(TAG, "Route traffic-light visibility update failed", failure);
            }
        }
    }

    private boolean hasMatchingLiveLight(RouteLight routeLight) {
        for (NavigatorStatePublisher.TrafficLightFrame live : liveLights) {
            if (live == null) continue;
            if (live.routeSegmentIndex >= 0
                    && live.routeSegmentIndex == routeLight.routeSegmentIndex
                    && finite(live.routeSegmentPosition)
                    && Math.abs(live.routeSegmentPosition
                    - routeLight.routeSegmentPosition) <= .002d) return true;
            if (validCoordinate(live.latitude, live.longitude)
                    && distanceMeters(routeLight.latitude, routeLight.longitude,
                    live.latitude, live.longitude) <= LIVE_POINT_TOLERANCE_METERS) return true;
        }
        return false;
    }

    private boolean isPassed(RouteLight light) {
        if (!routeProgressValid) return false;
        if (light.routeSegmentIndex != routeSegmentIndex) {
            return light.routeSegmentIndex < routeSegmentIndex;
        }
        return light.routeSegmentPosition + .000001d < routeSegmentPosition;
    }

    private void clearVisual() {
        placementCoordinator.clearOwner(
                MapOverlayPlacementCoordinator.OWNER_ROUTE_TRAFFIC_LIGHTS);
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        markers.clear();
        imageProvider = null;
        iconBitmap = null;
    }

    private static double distanceMeters(double latitudeA, double longitudeA,
                                         double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta * .5d) * Math.sin(latitudeDelta * .5d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta * .5d) * Math.sin(longitudeDelta * .5d);
        return 2d * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
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

    private static final class RouteLight {
        final String id;
        final String sourceId;
        final double latitude;
        final double longitude;
        final int routeSegmentIndex;
        final double routeSegmentPosition;

        RouteLight(String id, String sourceId, double latitude, double longitude,
                   int routeSegmentIndex, double routeSegmentPosition) {
            this.id = id;
            this.sourceId = sourceId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.routeSegmentIndex = routeSegmentIndex;
            this.routeSegmentPosition = routeSegmentPosition;
        }
    }

    private static final class Marker {
        final RouteLight light;
        final Object placemark;
        final int bitmapWidth;
        final int bitmapHeight;
        boolean visible = true;

        Marker(RouteLight light, Object placemark, int bitmapWidth, int bitmapHeight) {
            this.light = light;
            this.placemark = placemark;
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
        }
    }
}
