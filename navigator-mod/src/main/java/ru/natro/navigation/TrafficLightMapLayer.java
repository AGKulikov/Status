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

/** Stock Yandex signal-and-seconds balloons anchored to upcoming route traffic lights. */
final class TrafficLightMapLayer {
    private static final String TAG = "NatroTrafficLights";
    private static final long FRESH_MS = 3_000L;
    private static final int MAX_LIGHTS = 12;
    /** Windshield may expose several lane sections for one physical intersection. */
    private static final double MIN_SEPARATION_METERS = 30d;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Marker> markers = new ArrayList<>();
    private final ArrayList<NavigatorStatePublisher.TrafficLightFrame> visibleScratch =
            new ArrayList<>(MAX_LIGHTS);
    private Object map;
    private Object collection;
    private boolean enabled;
    private boolean nightMode;
    private float zIndex = NavigationMapProfile.layerZ(50);
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.TrafficLightFrame> latest =
            Collections.emptyList();
    private long latestSampleElapsedMs;
    private long latestVisualFingerprint = Long.MIN_VALUE;
    private long renderedStructureFingerprint = Long.MIN_VALUE;
    private long renderedVisualFingerprint = Long.MIN_VALUE;
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

    TrafficLightMapLayer(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
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

    void apply(boolean nextEnabled, boolean nextNightMode, int layerPriority) {
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = nightMode != nextNightMode || zIndex != nextZ;
        boolean enabledChanged = enabled != nextEnabled;
        if (!presentationChanged && !enabledChanged) return;
        enabled = nextEnabled;
        nightMode = nextNightMode;
        zIndex = nextZ;
        if (presentationChanged) renderedVisualFingerprint = Long.MIN_VALUE;
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
                List<NavigatorStatePublisher.TrafficLightFrame> values) {
        if (routeActive == latestRouteActive
                && sampleElapsedMs == latestSampleElapsedMs && values == latest) return;
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L
                && now >= sampleElapsedMs && now - sampleElapsedMs <= FRESH_MS;
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
        selectSeparatedLights(latest, visibleScratch);
        if (visibleScratch.isEmpty()) {
            clearVisual();
            return;
        }
        try {
            long structure = structureFingerprint(visibleScratch);
            long visual = visualFingerprint(visibleScratch);
            if (structure != renderedStructureFingerprint
                    || markers.size() != visibleScratch.size()) {
                rebuild(visibleScratch, structure, visual);
            } else if (visual != renderedVisualFingerprint) {
                applyOriginalYandexViews(visibleScratch);
                renderedVisualFingerprint = visual;
            }
        } catch (Throwable failure) {
            // A guessed fallback could show a wrong phase to the driver. Hide instead.
            Log.w(TAG, "Original Yandex traffic-light renderer failed", failure);
            clearVisual();
        }
    }

    private static void selectSeparatedLights(
            List<NavigatorStatePublisher.TrafficLightFrame> source,
            ArrayList<NavigatorStatePublisher.TrafficLightFrame> target) {
        target.clear();
        for (NavigatorStatePublisher.TrafficLightFrame candidate : source) {
            if (candidate == null || !candidate.hasMapPosition()) continue;
            int overlapIndex = -1;
            for (int index = 0; index < target.size(); index++) {
                NavigatorStatePublisher.TrafficLightFrame accepted = target.get(index);
                if (distanceMeters(candidate.latitude, candidate.longitude,
                        accepted.latitude, accepted.longitude) < MIN_SEPARATION_METERS) {
                    overlapIndex = index;
                    break;
                }
            }
            if (overlapIndex < 0) {
                target.add(candidate);
            } else if (prefer(candidate, target.get(overlapIndex))) {
                target.set(overlapIndex, candidate);
            }
            if (target.size() >= MAX_LIGHTS) break;
        }
    }

    /** Prefer the main section, then the nearest sample, for one physical intersection. */
    private static boolean prefer(NavigatorStatePublisher.TrafficLightFrame candidate,
                                  NavigatorStatePublisher.TrafficLightFrame accepted) {
        boolean candidateMain = "MAIN".equals(candidate.sectionType);
        boolean acceptedMain = "MAIN".equals(accepted.sectionType);
        if (candidateMain != acceptedMain) return candidateMain;
        if (candidate.distanceMeters < 0) return false;
        return accepted.distanceMeters < 0 || candidate.distanceMeters < accepted.distanceMeters;
    }

    private void rebuild(List<NavigatorStatePublisher.TrafficLightFrame> values,
                         long structure, long visual) throws Exception {
        Object currentCollection = collection;
        if (currentCollection == null) {
            Object root = invoke(map, "getMapObjects", new Class<?>[0]);
            currentCollection = invoke(root, "addCollection", new Class<?>[0]);
            collection = currentCollection;
        }
        invoke(currentCollection, "clear", new Class<?>[0]);
        markers.clear();
        renderedStructureFingerprint = Long.MIN_VALUE;
        renderedVisualFingerprint = Long.MIN_VALUE;
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        for (NavigatorStatePublisher.TrafficLightFrame light : values) {
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(light.latitude, light.longitude);
            Object placemark = invoke(currentCollection, "addPlacemark",
                    new Class<?>[]{pointClass}, point);
            markers.add(new Marker(placemark));
        }
        applyOriginalYandexViews(values);
        renderedStructureFingerprint = structure;
        renderedVisualFingerprint = visual;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyOriginalYandexViews(
            List<NavigatorStatePublisher.TrafficLightFrame> values) throws Exception {
        Class<?> viewClass = Class.forName(
                "ru.yandex.yandexnavi.ui.traffic.TrafficLightViewImpl");
        Class<?> signalClass = Class.forName(
                "com.yandex.mapkit.directions.traffic_lights.Signal");
        Class<?> arrowClass = Class.forName(
                "com.yandex.mapkit.directions.traffic_lights.RouteDirectionArrow");
        Class<?> legClass = Class.forName("com.yandex.navikit.ui.balloons.LegPlacement");
        Class<?> accentClass = Class.forName("com.yandex.navikit.ui.balloons.BalloonAccent");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
        Object noRotation = Enum.valueOf((Class<? extends Enum>) rotationClass,
                "NO_ROTATION");
        Object noLeg = Enum.valueOf((Class<? extends Enum>) legClass, "NONE");
        Object primary = Enum.valueOf((Class<? extends Enum>) accentClass, "PRIMARY");

        for (int index = 0; index < values.size(); index++) {
            NavigatorStatePublisher.TrafficLightFrame light = values.get(index);
            Marker marker = markers.get(index);
            Object view = viewClass.getConstructor(Context.class, float.class)
                    .newInstance(context, 1f);
            Object signal = enumValue(signalClass, light.signal, "GREEN");
            Object arrow = enumValue(arrowClass, light.arrow, "FORWARD");
            viewClass.getMethod("setSignal", signalClass).invoke(view, signal);
            viewClass.getMethod("setTime", Integer.class).invoke(view,
                    light.secondsLeft < 0 ? null : Integer.valueOf(light.secondsLeft));
            viewClass.getMethod("setArrowDirection", arrowClass).invoke(view, arrow);
            viewClass.getMethod("setIsAdditional", boolean.class).invoke(view,
                    "ADDITIONAL".equals(light.sectionType));
            viewClass.getMethod("setLegPlacement", legClass).invoke(view, noLeg);
            viewClass.getMethod("setAccent", accentClass).invoke(view, primary);
            viewClass.getMethod("setIsNightMode", boolean.class).invoke(view, nightMode);
            Object provider = viewClass.getMethod("createTexture").invoke(view);
            Object anchor = viewClass.getMethod("getAnchor").invoke(view);
            float anchorX = ((Number) invoke(anchor, "getX", new Class<?>[0])).floatValue();
            float anchorY = ((Number) invoke(anchor, "getY", new Class<?>[0])).floatValue();

            Object style = styleClass.getConstructor().newInstance();
            invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                    new PointF(anchorX, anchorY));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, noRotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
            invoke(marker.placemark, "setIcon",
                    new Class<?>[]{providerClass, styleClass}, provider, style);
            invoke(marker.placemark, "setVisible", new Class<?>[]{boolean.class}, true);
            marker.view = view;
            marker.imageProvider = provider;
            marker.iconStyle = style;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> enumClass, String raw, String fallback) {
        String name = raw == null || raw.isEmpty() ? fallback : raw;
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass, name);
        } catch (IllegalArgumentException invalid) {
            return Enum.valueOf((Class<? extends Enum>) enumClass, fallback);
        }
    }

    private void clearVisual() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        markers.clear();
        renderedStructureFingerprint = Long.MIN_VALUE;
        renderedVisualFingerprint = Long.MIN_VALUE;
    }

    private static long structureFingerprint(
            List<NavigatorStatePublisher.TrafficLightFrame> values) {
        long result = 0xcbf29ce484222325L;
        int count = 0;
        for (NavigatorStatePublisher.TrafficLightFrame value : values) {
            if (value == null || !value.hasMapPosition()) continue;
            count++;
            result = mix(result, value.id.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
        }
        return mix(result, count);
    }

    private static long visualFingerprint(
            List<NavigatorStatePublisher.TrafficLightFrame> values) {
        long result = structureFingerprint(values);
        int count = 0;
        for (NavigatorStatePublisher.TrafficLightFrame value : values) {
            if (value == null || !value.hasMapPosition()) continue;
            if (count++ >= MAX_LIGHTS) break;
            result = mix(result, value.secondsLeft);
            result = mix(result, value.signal.hashCode());
            result = mix(result, value.sectionType.hashCode());
            result = mix(result, value.arrow.hashCode());
        }
        return mix(result, count);
    }

    private static double distanceMeters(double fromLatitude, double fromLongitude,
                                         double toLatitude, double toLongitude) {
        double latitudeDelta = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double from = Math.toRadians(fromLatitude);
        double to = Math.toRadians(toLatitude);
        double sinLatitude = Math.sin(latitudeDelta / 2d);
        double sinLongitude = Math.sin(longitudeDelta / 2d);
        double value = sinLatitude * sinLatitude
                + Math.cos(from) * Math.cos(to) * sinLongitude * sinLongitude;
        return 2d * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(value)));
    }

    private static long mix(long value, long part) {
        return (value ^ part) * 0x100000001b3L;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class Marker {
        final Object placemark;
        Object view;
        Object imageProvider;
        Object iconStyle;

        Marker(Object placemark) {
            this.placemark = placemark;
        }
    }
}
