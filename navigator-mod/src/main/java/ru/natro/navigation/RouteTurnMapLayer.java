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

/** Native MapKit manoeuvre arrows attached to the active route polyline. */
final class RouteTurnMapLayer {
    private static final String TAG = "NatroRouteTurns";
    private static final long FRESH_MS = 2_500L;
    private static final int MAX_TURNS = 10;
    private static final double EPSILON = 0.000001d;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Object> arrows = new ArrayList<>();
    private Object routePolyline;
    private int firstSegmentIndex;
    private double firstSegmentPosition;
    private int segmentCount;
    private Object defaultManeuverStyle;
    private Object defaultArrowStyle;
    private boolean enabled;
    private int scalePercent = 100;
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.RouteTurnFrame> latest = Collections.emptyList();
    private long latestSampleElapsedMs;
    private long latestFingerprint = Long.MIN_VALUE;
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
            latestFingerprint = Long.MIN_VALUE;
            render();
        }
    };

    RouteTurnMapLayer(Context ignoredContext) {
        // Context was needed by the removed bitmap renderer. Keep the constructor stable so the
        // isolated Navigator patch does not broaden its host integration surface.
    }

    /**
     * Re-bases full-route manoeuvre positions onto HudMapRenderer's remaining-route geometry.
     * Updating the first point inside the same segment keeps the existing native arrows; changing
     * segment or polyline recreates them once, avoiding a new MapKit object every navigation frame.
     */
    void attachRoute(Object nextPolyline, int nextFirstSegmentIndex,
                     double nextFirstSegmentPosition, int nextSegmentCount) {
        int normalizedIndex = Math.max(0, nextFirstSegmentIndex);
        double normalizedPosition = Math.max(0d, Math.min(1d, nextFirstSegmentPosition));
        int normalizedCount = Math.max(0, nextSegmentCount);
        boolean identityChanged = routePolyline != nextPolyline
                || firstSegmentIndex != normalizedIndex;
        routePolyline = nextPolyline;
        firstSegmentIndex = normalizedIndex;
        firstSegmentPosition = normalizedPosition;
        segmentCount = normalizedCount;
        if (!identityChanged) return;
        clearVisual();
        discardExpiredSource();
        scheduleExpiryIfNeeded();
        render();
    }

    void detachMap() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        clearVisual();
        routePolyline = null;
        firstSegmentIndex = 0;
        firstSegmentPosition = 0d;
        segmentCount = 0;
    }

    void apply(boolean nextEnabled, int nextScalePercent, int ignoredLayerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        boolean presentationChanged = scalePercent != nextScale;
        if (enabled == nextEnabled && !presentationChanged) return;
        enabled = nextEnabled;
        scalePercent = nextScale;
        if (presentationChanged) renderedFingerprint = Long.MIN_VALUE;
        if (enabled) {
            discardExpiredSource();
            scheduleExpiryIfNeeded();
        } else {
            main.removeCallbacks(expire);
            expiryPosted = false;
        }
        render();
    }

    void update(boolean routeActive, long sampleElapsedMs,
                List<NavigatorStatePublisher.RouteTurnFrame> frames) {
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L && now >= sampleElapsedMs
                && now - sampleElapsedMs <= FRESH_MS && hasContent(frames);
        latest = fresh && frames != null ? frames : Collections.emptyList();
        latestSampleElapsedMs = fresh ? sampleElapsedMs : 0L;
        if (fresh) scheduleExpiryIfNeeded();
        else {
            main.removeCallbacks(expire);
            expiryPosted = false;
        }
        long fingerprint = fingerprint(latest);
        if (fingerprint == latestFingerprint) return;
        latestFingerprint = fingerprint;
        if (enabled && routePolyline != null) render();
    }

    void clearData() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        latestRouteActive = false;
        latest = Collections.emptyList();
        latestSampleElapsedMs = 0L;
        latestFingerprint = Long.MIN_VALUE;
        render();
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
        latest = Collections.emptyList();
        latestSampleElapsedMs = 0L;
        latestFingerprint = Long.MIN_VALUE;
    }

    private void render() {
        Object line = routePolyline;
        if (line == null) return;
        if (!enabled || !latestRouteActive || !hasContent(latest) || segmentCount <= 0) {
            clearVisual();
            return;
        }
        if (renderedFingerprint == latestFingerprint) return;
        ArrayList<Object> stale = new ArrayList<>(arrows);
        arrows.clear();
        try {
            ensureDefaultStyle();
            int count = 0;
            for (NavigatorStatePublisher.RouteTurnFrame frame : latest) {
                if (count++ >= MAX_TURNS) break;
                if (frame == null || !frame.hasContent()) continue;
                addNativeArrow(line, frame);
            }
            applyDefaultManeuverStyle(line);
            applyScaledArrowStyle();
            hide(stale);
            renderedFingerprint = latestFingerprint;
        } catch (Throwable failure) {
            hide(stale);
            Log.w(TAG, "Native route-turn layer update failed", failure);
            clearVisual();
        }
    }

    private void ensureDefaultStyle() throws Exception {
        if (defaultManeuverStyle != null && defaultArrowStyle != null) return;
        Class<?> helperClass = Class.forName(
                "com.yandex.mapkit.directions.driving.RouteHelper");
        Object maneuver = helperClass.getMethod("createDefaultManeuverStyle").invoke(null);
        if (maneuver == null) throw new IllegalStateException("MapKit returned no maneuver style");
        Object arrow = invoke(maneuver, "getArrow", new Class<?>[0]);
        if (arrow == null) throw new IllegalStateException("MapKit returned no arrow style");
        defaultManeuverStyle = maneuver;
        defaultArrowStyle = arrow;
    }

    private void addNativeArrow(Object line, NavigatorStatePublisher.RouteTurnFrame frame)
            throws Exception {
        int relativeSegment = frame.routeSegmentIndex - firstSegmentIndex;
        double relativePosition = frame.routeSegmentPosition;
        if (relativeSegment == 0) {
            if (relativePosition + EPSILON < firstSegmentPosition) return;
            double remaining = 1d - firstSegmentPosition;
            relativePosition = remaining <= EPSILON ? 0d
                    : (relativePosition - firstSegmentPosition) / remaining;
        }
        if (relativeSegment < 0 || relativeSegment >= segmentCount) return;
        relativePosition = Math.max(0d, Math.min(1d, relativePosition));
        Class<?> positionClass = Class.forName(
                "com.yandex.mapkit.geometry.PolylinePosition");
        Object position = positionClass.getConstructor(int.class, double.class)
                .newInstance(relativeSegment, relativePosition);
        float length = number(defaultArrowStyle, "getLength", 1f);
        int fillColor = integer(defaultArrowStyle, "getFillColor", 0xFFFFFFFF);
        Object arrow = invoke(line, "addArrow",
                new Class<?>[]{positionClass, float.class, int.class},
                position, length, fillColor);
        if (arrow != null) arrows.add(arrow);
    }

    private void applyDefaultManeuverStyle(Object line) throws Exception {
        Class<?> helperClass = Class.forName(
                "com.yandex.mapkit.directions.driving.RouteHelper");
        Class<?> polylineClass = Class.forName("com.yandex.mapkit.map.PolylineMapObject");
        Class<?> styleClass = Class.forName(
                "com.yandex.mapkit.directions.driving.ManeuverStyle");
        helperClass.getMethod("applyManeuverStyle", polylineClass, styleClass)
                .invoke(null, line, defaultManeuverStyle);
    }

    private void applyScaledArrowStyle() throws Exception {
        float scale = scalePercent / 100f;
        int fill = integer(defaultArrowStyle, "getFillColor", 0xFFFFFFFF);
        int outline = integer(defaultArrowStyle, "getOutlineColor", 0xFF20242A);
        float length = number(defaultArrowStyle, "getLength", 1f) * scale;
        float outlineWidth = number(defaultArrowStyle, "getOutlineWidth", 0f) * scale;
        float triangleHeight = number(defaultArrowStyle, "getTriangleHeight", 0f) * scale;
        boolean visible = bool(defaultArrowStyle, "getEnabled", true);
        for (Object arrow : arrows) {
            invoke(arrow, "setFillColor", new Class<?>[]{int.class}, fill);
            invoke(arrow, "setOutlineColor", new Class<?>[]{int.class}, outline);
            invoke(arrow, "setLength", new Class<?>[]{float.class}, length);
            invoke(arrow, "setOutlineWidth", new Class<?>[]{float.class}, outlineWidth);
            invoke(arrow, "setTriangleHeight", new Class<?>[]{float.class}, triangleHeight);
            invoke(arrow, "setVisible", new Class<?>[]{boolean.class}, visible);
        }
    }

    private void clearVisual() {
        hide(arrows);
        arrows.clear();
        renderedFingerprint = Long.MIN_VALUE;
    }

    private static void hide(List<Object> values) {
        for (Object arrow : values) {
            try { invoke(arrow, "setVisible", new Class<?>[]{boolean.class}, false); }
            catch (Throwable ignored) {}
        }
    }

    private static boolean hasContent(List<NavigatorStatePublisher.RouteTurnFrame> values) {
        if (values == null || values.isEmpty()) return false;
        for (NavigatorStatePublisher.RouteTurnFrame value : values) {
            if (value != null && value.hasContent()) return true;
        }
        return false;
    }

    private static long fingerprint(List<NavigatorStatePublisher.RouteTurnFrame> values) {
        if (!hasContent(values)) return 0L;
        long result = 0xcbf29ce484222325L;
        int count = 0;
        for (NavigatorStatePublisher.RouteTurnFrame value : values) {
            if (value == null || !value.hasContent()) continue;
            if (count++ >= MAX_TURNS) break;
            result = mix(result, value.id.hashCode());
            result = mix(result, value.action.hashCode());
            result = mix(result, value.routeSegmentIndex);
            result = mix(result, Math.round(value.routeSegmentPosition * 1_000_000d));
        }
        return mix(result, count);
    }

    private static long mix(long value, long part) {
        return (value ^ part) * 0x100000001b3L;
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

    private static boolean bool(Object target, String getter, boolean fallback) {
        try {
            Object value = invoke(target, getter, new Class<?>[0]);
            return value instanceof Boolean ? ((Boolean) value) : fallback;
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
