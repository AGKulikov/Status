/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Original-style translucent camera viewing sectors above the independent map. */
final class CameraDirectionMapLayer {
    private static final String TAG = "NatroCameraDirection";
    private static final long FRESH_MS = 3_000L;
    private static final int MAX_CAMERAS = 8;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;
    private static final double SECTOR_LENGTH_METERS = 105d;
    private static final double SECTOR_HALF_ANGLE_DEGREES = 13d;
    /** Yandex's direction annotation is a blue translucent plane, not another pin/arrow. */
    private static final int SECTOR_FILL = 0x4D168BFF;
    private static final int SECTOR_STROKE = 0x66166BFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Object map;
    private Object collection;
    private boolean enabled;
    private float zIndex = NavigationMapProfile.layerZ(20);
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.CameraDirectionFrame> latest =
            Collections.emptyList();
    private long latestSampleElapsedMs;
    private long latestVisualFingerprint = Long.MIN_VALUE;
    private long renderedFingerprint = Long.MIN_VALUE;
    private boolean expiryPosted;

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
            latest = Collections.emptyList();
            latestSampleElapsedMs = 0L;
            latestVisualFingerprint = Long.MIN_VALUE;
            render();
        }
    };

    CameraDirectionMapLayer(Context context) {
        // Kept for the same construction contract as the other MapKit object layers.
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

    void apply(boolean nextEnabled, int layerPriority) {
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean priorityChanged = zIndex != nextZ;
        if (enabled == nextEnabled && !priorityChanged) return;
        enabled = nextEnabled;
        zIndex = nextZ;
        if (priorityChanged) renderedFingerprint = Long.MIN_VALUE;
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
                List<NavigatorStatePublisher.CameraDirectionFrame> values) {
        if (routeActive == latestRouteActive && sampleElapsedMs == latestSampleElapsedMs
                && values == latest) return;
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L && now >= sampleElapsedMs
                && now - sampleElapsedMs <= FRESH_MS;
        latest = fresh && values != null ? values : Collections.emptyList();
        if (fresh) {
            latestSampleElapsedMs = sampleElapsedMs;
            scheduleExpiryIfNeeded();
        } else {
            latestSampleElapsedMs = 0L;
            main.removeCallbacks(expire);
            expiryPosted = false;
        }
        long fingerprint = visualFingerprint(latest);
        if (fingerprint == latestVisualFingerprint) return;
        latestVisualFingerprint = fingerprint;
        if (enabled && map != null) render();
    }

    void clearData() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        latest = Collections.emptyList();
        latestRouteActive = false;
        latestSampleElapsedMs = 0L;
        latestVisualFingerprint = Long.MIN_VALUE;
        render();
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
        latest = Collections.emptyList();
        latestSampleElapsedMs = 0L;
        latestVisualFingerprint = Long.MIN_VALUE;
    }

    private void render() {
        if (map == null) return;
        if (!enabled || !latestRouteActive || latest.isEmpty()) {
            clearVisual();
            return;
        }
        if (renderedFingerprint == latestVisualFingerprint) return;
        try {
            Object currentCollection = collection;
            if (currentCollection == null) {
                Object root = invoke(map, "getMapObjects", new Class<?>[0]);
                currentCollection = invoke(root, "addCollection", new Class<?>[0]);
                collection = currentCollection;
            }
            invoke(currentCollection, "clear", new Class<?>[0]);
            int count = 0;
            for (NavigatorStatePublisher.CameraDirectionFrame camera : latest) {
                if (count >= MAX_CAMERAS) break;
                if (camera == null || !camera.hasMapPosition()
                        || (!camera.inFace && !camera.inBack)) continue;
                count++;
                // inFace looks towards approaching traffic; inBack looks along its travel.
                if (camera.inFace) addSector(currentCollection, camera,
                        camera.bearingDegrees + 180d);
                if (camera.inBack) addSector(currentCollection, camera,
                        camera.bearingDegrees);
            }
            renderedFingerprint = latestVisualFingerprint;
        } catch (Throwable failure) {
            Log.w(TAG, "Camera direction sector update failed", failure);
            clearVisual();
        }
    }

    private void addSector(Object target,
                           NavigatorStatePublisher.CameraDirectionFrame camera,
                           double directionDegrees) throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        ArrayList<Object> points = new ArrayList<>(4);
        points.add(pointClass.getConstructor(double.class, double.class)
                .newInstance(camera.latitude, camera.longitude));
        double[] left = destination(camera.latitude, camera.longitude,
                directionDegrees - SECTOR_HALF_ANGLE_DEGREES, SECTOR_LENGTH_METERS);
        double[] right = destination(camera.latitude, camera.longitude,
                directionDegrees + SECTOR_HALF_ANGLE_DEGREES, SECTOR_LENGTH_METERS);
        points.add(pointClass.getConstructor(double.class, double.class)
                .newInstance(left[0], left[1]));
        points.add(pointClass.getConstructor(double.class, double.class)
                .newInstance(right[0], right[1]));
        points.add(points.get(0));

        Class<?> ringClass = Class.forName("com.yandex.mapkit.geometry.LinearRing");
        Object ring = ringClass.getConstructor(List.class).newInstance(points);
        Class<?> polygonClass = Class.forName("com.yandex.mapkit.geometry.Polygon");
        Object polygon = polygonClass.getConstructor(ringClass, List.class)
                .newInstance(ring, Collections.emptyList());
        Object mapObject = invoke(target, "addPolygon",
                new Class<?>[]{polygonClass}, polygon);
        invoke(mapObject, "setFillColor", new Class<?>[]{int.class}, SECTOR_FILL);
        invoke(mapObject, "setStrokeColor", new Class<?>[]{int.class}, SECTOR_STROKE);
        invoke(mapObject, "setStrokeWidth", new Class<?>[]{float.class}, 0.8f);
        invoke(mapObject, "setGeodesic", new Class<?>[]{boolean.class}, false);
        invoke(mapObject, "setZIndex", new Class<?>[]{float.class}, zIndex);
        invoke(mapObject, "setVisible", new Class<?>[]{boolean.class}, true);
    }

    private static double[] destination(double latitude, double longitude,
                                        double bearingDegrees, double distanceMeters) {
        double angular = distanceMeters / EARTH_RADIUS_METERS;
        double bearing = Math.toRadians(bearingDegrees);
        double fromLatitude = Math.toRadians(latitude);
        double fromLongitude = Math.toRadians(longitude);
        double toLatitude = Math.asin(Math.sin(fromLatitude) * Math.cos(angular)
                + Math.cos(fromLatitude) * Math.sin(angular) * Math.cos(bearing));
        double toLongitude = fromLongitude + Math.atan2(
                Math.sin(bearing) * Math.sin(angular) * Math.cos(fromLatitude),
                Math.cos(angular) - Math.sin(fromLatitude) * Math.sin(toLatitude));
        return new double[]{Math.toDegrees(toLatitude), Math.toDegrees(toLongitude)};
    }

    private void clearVisual() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        renderedFingerprint = Long.MIN_VALUE;
    }

    private static long visualFingerprint(
            List<NavigatorStatePublisher.CameraDirectionFrame> values) {
        long result = 0x517cc1b727220a95L;
        int count = 0;
        for (NavigatorStatePublisher.CameraDirectionFrame value : values) {
            if (value == null || !value.hasMapPosition()) continue;
            if (count >= MAX_CAMERAS) break;
            count++;
            result = mix(result, value.id.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
            result = mix(result, Math.round(value.bearingDegrees * 10f));
            result = mix(result, value.inFace ? 1 : 0);
            result = mix(result, value.inBack ? 1 : 0);
        }
        return mix(result, count);
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
