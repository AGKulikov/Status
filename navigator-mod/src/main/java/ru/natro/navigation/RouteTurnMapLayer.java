/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Stock MapKit manoeuvre arrows attached to the exact active-route geometry. */
final class RouteTurnMapLayer {
    private static final String TAG = "NatroRouteTurns";
    private static final long FRESH_MS = 2_500L;
    private static final int MAX_FALLBACK_TURNS = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Object routePolyline;
    private Object drivingRoute;
    private Object defaultArrowStyle;
    private Object visibleManeuverStyle;
    private Object hiddenManeuverStyle;
    private int cachedStyleScalePercent = -1;
    private boolean nativeManeuversAdded;
    private boolean fallbackArrowsAdded;
    private boolean enabled;
    private int scalePercent = 100;
    private boolean routeActive;
    private List<NavigatorStatePublisher.RouteTurnFrame> latest = Collections.emptyList();
    private long latestSampleElapsedMs;
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
            latestSampleElapsedMs = 0L;
            latest = Collections.emptyList();
            if (routeActive) {
                routeActive = false;
                render();
            }
        }
    };

    RouteTurnMapLayer(Context ignoredContext) {
        // All visual assets and geometry come from MapKit. Context remains in the constructor only
        // to keep the isolated Navigator patch ABI stable.
    }

    /**
     * RouteHelper requires route.geometry to equal polyline.geometry. HudMapRenderer therefore
     * keeps the full geometry immutable and hides travelled progress with Subpolyline instead of
     * slicing the line. That lets MapKit add exactly the same manoeuvre arrows as its stock route.
     */
    void attachRoute(Object nextPolyline, Object nextDrivingRoute) {
        if (routePolyline == nextPolyline && drivingRoute == nextDrivingRoute) return;
        routePolyline = nextPolyline;
        drivingRoute = nextDrivingRoute;
        nativeManeuversAdded = false;
        fallbackArrowsAdded = false;
        if (nextPolyline == null || nextDrivingRoute == null) return;
        try {
            Class<?> helperClass = Class.forName(
                    "com.yandex.mapkit.directions.driving.RouteHelper");
            Class<?> polylineClass = Class.forName(
                    "com.yandex.mapkit.map.PolylineMapObject");
            Class<?> routeClass = Class.forName(
                    "com.yandex.mapkit.directions.driving.DrivingRoute");
            helperClass.getMethod("addManeuvers", polylineClass, routeClass)
                    .invoke(null, nextPolyline, nextDrivingRoute);
            nativeManeuversAdded = !arrows(nextPolyline).isEmpty();
            if (!nativeManeuversAdded) {
                Log.w(TAG, "RouteHelper added no manoeuvre arrows; native fallback is armed");
            }
        } catch (Throwable failure) {
            Log.w(TAG, "RouteHelper could not add stock manoeuvres", failure);
        }
        scheduleExpiryIfNeeded();
        render();
    }

    void detachMap() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        routePolyline = null;
        drivingRoute = null;
        nativeManeuversAdded = false;
        fallbackArrowsAdded = false;
        routeActive = false;
        latest = Collections.emptyList();
        latestSampleElapsedMs = 0L;
    }

    void apply(boolean nextEnabled, int nextScalePercent, int ignoredLayerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        boolean changed = enabled != nextEnabled || scalePercent != nextScale;
        enabled = nextEnabled;
        if (scalePercent != nextScale) {
            scalePercent = nextScale;
            visibleManeuverStyle = null;
            hiddenManeuverStyle = null;
            cachedStyleScalePercent = -1;
        }
        if (enabled) {
            // The layer can be disabled longer than FRESH_MS. Do not let the last route sample
            // become visible again if the setting is re-enabled after the expiry callback was
            // intentionally cancelled while hidden.
            discardExpiredSource();
            scheduleExpiryIfNeeded();
        } else {
            main.removeCallbacks(expire);
            expiryPosted = false;
        }
        if (changed) render();
    }

    void update(boolean nextRouteActive, long sampleElapsedMs,
                List<NavigatorStatePublisher.RouteTurnFrame> frames) {
        long now = SystemClock.elapsedRealtime();
        boolean fresh = nextRouteActive && sampleElapsedMs > 0L
                && now >= sampleElapsedMs
                && now - sampleElapsedMs <= FRESH_MS;
        boolean routeStateChanged = routeActive != fresh;
        routeActive = fresh;
        latest = fresh && frames != null ? frames : Collections.emptyList();
        latestSampleElapsedMs = fresh ? sampleElapsedMs : 0L;
        if (fresh) scheduleExpiryIfNeeded();
        else {
            main.removeCallbacks(expire);
            expiryPosted = false;
        }
        boolean fallbackBecameReady = fresh && !nativeManeuversAdded
                && !fallbackArrowsAdded && hasContent(latest);
        // Navigation snapshots arrive up to ten times per second. Reapplying the native style on
        // every sample is both unnecessary and another possible source of a flashing route.
        if (routeStateChanged || fallbackBecameReady) render();
    }

    void clearData() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        boolean wasActive = routeActive;
        routeActive = false;
        latest = Collections.emptyList();
        latestSampleElapsedMs = 0L;
        if (wasActive) render();
    }

    private void scheduleExpiryIfNeeded() {
        if (!enabled || routePolyline == null || latestSampleElapsedMs <= 0L || expiryPosted) {
            return;
        }
        long age = SystemClock.elapsedRealtime() - latestSampleElapsedMs;
        if (age < 0L || age > FRESH_MS) return;
        expiryPosted = main.postDelayed(expire, Math.max(1L, FRESH_MS - age));
    }

    private void discardExpiredSource() {
        if (latestSampleElapsedMs <= 0L) return;
        long age = SystemClock.elapsedRealtime() - latestSampleElapsedMs;
        if (age >= 0L && age <= FRESH_MS) return;
        latestSampleElapsedMs = 0L;
        latest = Collections.emptyList();
        routeActive = false;
    }

    private void render() {
        Object line = routePolyline;
        if (line == null) return;
        try {
            boolean visible = enabled && routeActive;
            if (visible && !nativeManeuversAdded && !fallbackArrowsAdded
                    && hasContent(latest)) {
                addFallbackArrows(line, latest);
                fallbackArrowsAdded = true;
            }
            applyManeuverStyle(line, maneuverStyle(visible));
            // MapKit's documented default ManeuverStyle deliberately has enabled=false. The old
            // implementation copied that flag back onto every Arrow, making all successful
            // addArrow calls invisible. The cloned stock style above always uses the requested
            // visibility and this explicit pass also covers 30.3.0 implementations that cache it.
            for (Object arrow : arrows(line)) {
                invoke(arrow, "setVisible", new Class<?>[]{boolean.class}, visible);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Stock route-turn presentation failed", failure);
        }
    }

    /** Native addArrow is retained only as a compatibility fallback; no custom icon is drawn. */
    private void addFallbackArrows(
            Object line, List<NavigatorStatePublisher.RouteTurnFrame> frames) throws Exception {
        Object arrowStyle = defaultArrowStyle();
        Class<?> positionClass = Class.forName(
                "com.yandex.mapkit.geometry.PolylinePosition");
        float length = number(arrowStyle, "getLength", 80f) * scalePercent / 100f;
        int fillColor = integer(arrowStyle, "getFillColor", 0xFF000000);
        int count = 0;
        for (NavigatorStatePublisher.RouteTurnFrame frame : frames) {
            if (count >= MAX_FALLBACK_TURNS) break;
            if (frame == null || !frame.hasContent()) continue;
            try {
                Object position = positionClass.getConstructor(int.class, double.class)
                        .newInstance(frame.routeSegmentIndex, frame.routeSegmentPosition);
                invoke(line, "addArrow",
                        new Class<?>[]{positionClass, float.class, int.class},
                        position, length, fillColor);
                count++;
            } catch (Throwable invalidPosition) {
                Log.w(TAG, "One fallback manoeuvre position was rejected", invalidPosition);
            }
        }
    }

    /** Clones MapKit's stock dimensions and colours but explicitly enables the style. */
    private Object maneuverStyle(boolean visible) throws Exception {
        if (cachedStyleScalePercent == scalePercent
                && (visible ? visibleManeuverStyle : hiddenManeuverStyle) != null) {
            return visible ? visibleManeuverStyle : hiddenManeuverStyle;
        }
        Object source = defaultArrowStyle();
        float scale = scalePercent / 100f;
        Class<?> arrowStyleClass = Class.forName(
                "com.yandex.mapkit.directions.driving.ArrowManeuverStyle");
        Object arrowStyle = arrowStyleClass.getConstructor(
                        int.class, int.class, float.class, float.class, float.class,
                        boolean.class)
                .newInstance(
                        integer(source, "getFillColor", 0xFF000000),
                        integer(source, "getOutlineColor", 0xFFFFFFFF),
                        number(source, "getOutlineWidth", 2f) * scale,
                        number(source, "getLength", 80f) * scale,
                        number(source, "getTriangleHeight", 16f) * scale,
                        visible);
        Class<?> styleClass = Class.forName(
                "com.yandex.mapkit.directions.driving.ManeuverStyle");
        Object result = styleClass.getConstructor(arrowStyleClass).newInstance(arrowStyle);
        cachedStyleScalePercent = scalePercent;
        if (visible) visibleManeuverStyle = result;
        else hiddenManeuverStyle = result;
        return result;
    }

    private Object defaultArrowStyle() throws Exception {
        if (defaultArrowStyle != null) return defaultArrowStyle;
        Class<?> helperClass = Class.forName(
                "com.yandex.mapkit.directions.driving.RouteHelper");
        Object maneuver = helperClass.getMethod("createDefaultManeuverStyle").invoke(null);
        if (maneuver == null) throw new IllegalStateException("MapKit returned no maneuver style");
        Object arrow = invoke(maneuver, "getArrow", new Class<?>[0]);
        if (arrow == null) throw new IllegalStateException("MapKit returned no arrow style");
        defaultArrowStyle = arrow;
        return arrow;
    }

    private static void applyManeuverStyle(Object line, Object style) throws Exception {
        Class<?> helperClass = Class.forName(
                "com.yandex.mapkit.directions.driving.RouteHelper");
        Class<?> polylineClass = Class.forName("com.yandex.mapkit.map.PolylineMapObject");
        Class<?> styleClass = Class.forName(
                "com.yandex.mapkit.directions.driving.ManeuverStyle");
        helperClass.getMethod("applyManeuverStyle", polylineClass, styleClass)
                .invoke(null, line, style);
    }

    private static List<?> arrows(Object line) {
        try {
            Object value = invoke(line, "arrows", new Class<?>[0]);
            return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
        } catch (Throwable unavailable) {
            return Collections.emptyList();
        }
    }

    private static boolean hasContent(List<NavigatorStatePublisher.RouteTurnFrame> frames) {
        if (frames == null || frames.isEmpty()) return false;
        for (NavigatorStatePublisher.RouteTurnFrame frame : frames) {
            if (frame != null && frame.hasContent()) return true;
        }
        return false;
    }

    private static float number(Object target, String getter, float fallback) {
        try {
            Object value = invoke(target, getter, new Class<?>[0]);
            return value instanceof Number ? ((Number) value).floatValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int integer(Object target, String getter, int fallback) {
        try {
            Object value = invoke(target, getter, new Class<?>[0]);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }
}
