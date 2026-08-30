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

/** Screen-facing street names sourced exclusively from positions on the active route. */
final class RouteStreetLabelMapLayer {
    private static final String TAG = "NatroRouteLabels";
    private static final long FRESH_MS = 2_500L;
    private static final int MAX_LABELS = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Marker> markers = new ArrayList<>();
    private final ArrayList<NavigatorStatePublisher.RouteStreetLabelFrame> visibleScratch =
            new ArrayList<>(MAX_LABELS);
    private Object map;
    private Object collection;
    private Object textStyle;
    private boolean enabled;
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.RouteStreetLabelFrame> latest =
            Collections.emptyList();
    private long latestSampleElapsedMs;
    private long latestVisualFingerprint = Long.MIN_VALUE;
    private long renderedStructureFingerprint = Long.MIN_VALUE;
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

    RouteStreetLabelMapLayer(Context context) {
        // Keep the same construction contract as the other map-object layers. Native MapKit text
        // is used directly, so this layer needs no Context-owned bitmap or drawable resources.
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

    void apply(boolean nextEnabled) {
        if (enabled == nextEnabled) return;
        enabled = nextEnabled;
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
                List<NavigatorStatePublisher.RouteStreetLabelFrame> frames) {
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L && now >= sampleElapsedMs
                && now - sampleElapsedMs <= FRESH_MS && hasContent(frames);
        latest = fresh && frames != null ? frames : Collections.emptyList();
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
        latestRouteActive = false;
        latest = Collections.emptyList();
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
        if (!enabled || !latestRouteActive || !hasContent(latest)) {
            clearVisual();
            return;
        }
        visibleScratch.clear();
        for (NavigatorStatePublisher.RouteStreetLabelFrame frame : latest) {
            if (visibleScratch.size() >= MAX_LABELS) break;
            if (frame != null && frame.hasContent()) visibleScratch.add(frame);
        }
        if (visibleScratch.isEmpty()) {
            clearVisual();
            return;
        }
        try {
            long structure = structureFingerprint(visibleScratch);
            if (structure != renderedStructureFingerprint
                    || markers.size() != visibleScratch.size()) {
                rebuild(visibleScratch, structure);
                return;
            }
            for (int index = 0; index < visibleScratch.size(); index++) {
                NavigatorStatePublisher.RouteStreetLabelFrame frame = visibleScratch.get(index);
                Marker marker = markers.get(index);
                if (!marker.sameContent(frame)) updateMarker(marker, frame);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Route street label layer update failed", failure);
            clearVisual();
        }
    }

    private void rebuild(List<NavigatorStatePublisher.RouteStreetLabelFrame> values,
                         long structure) throws Exception {
        Object currentCollection = collection;
        if (currentCollection == null) {
            Object root = invoke(map, "getMapObjects", new Class<?>[0]);
            currentCollection = invoke(root, "addCollection", new Class<?>[0]);
            collection = currentCollection;
        }
        invoke(currentCollection, "clear", new Class<?>[0]);
        markers.clear();
        renderedStructureFingerprint = Long.MIN_VALUE;
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        for (NavigatorStatePublisher.RouteStreetLabelFrame frame : values) {
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(frame.latitude, frame.longitude);
            Object placemark = invoke(currentCollection, "addPlacemark",
                    new Class<?>[]{pointClass}, point);
            Marker marker = new Marker(placemark, frame.id);
            updateMarker(marker, frame);
            markers.add(marker);
        }
        renderedStructureFingerprint = structure;
    }

    private void updateMarker(Marker marker,
                              NavigatorStatePublisher.RouteStreetLabelFrame frame)
            throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        if (!finite(marker.latitude) || !finite(marker.longitude)
                || Math.abs(marker.latitude - frame.latitude) >= 0.000001d
                || Math.abs(marker.longitude - frame.longitude) >= 0.000001d) {
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(frame.latitude, frame.longitude);
            invoke(marker.placemark, "setGeometry", new Class<?>[]{pointClass}, point);
        }
        if (!frame.text.equals(marker.text)) {
            Class<?> styleClass = Class.forName("com.yandex.mapkit.map.TextStyle");
            invoke(marker.placemark, "setText",
                    new Class<?>[]{String.class, styleClass}, frame.text,
                    textStyle(styleClass));
        }
        invoke(marker.placemark, "setZIndex", new Class<?>[]{float.class}, 36f);
        invoke(marker.placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        marker.text = frame.text;
        marker.latitude = frame.latitude;
        marker.longitude = frame.longitude;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object textStyle(Class<?> styleClass) throws Exception {
        Object style = textStyle;
        if (style != null) return style;
        Class<?> placementClass = Class.forName("com.yandex.mapkit.map.TextStyle$Placement");
        style = styleClass.getConstructor().newInstance();
        Object placement = Enum.valueOf(
                (Class<? extends Enum>) placementClass, "CENTER");
        invoke(style, "setSize", new Class<?>[]{float.class}, 15f);
        invoke(style, "setColor", new Class<?>[]{int.class}, 0xFFF4F8FC);
        invoke(style, "setOutlineWidth", new Class<?>[]{float.class}, 3f);
        invoke(style, "setOutlineColor", new Class<?>[]{int.class}, 0xE8172230);
        invoke(style, "setPlacement", new Class<?>[]{placementClass}, placement);
        invoke(style, "setOffsetFromIcon", new Class<?>[]{boolean.class}, false);
        invoke(style, "setTextOptional", new Class<?>[]{boolean.class}, false);
        textStyle = style;
        return style;
    }

    private void clearVisual() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        markers.clear();
        renderedStructureFingerprint = Long.MIN_VALUE;
    }

    private static boolean hasContent(
            List<NavigatorStatePublisher.RouteStreetLabelFrame> values) {
        if (values == null || values.isEmpty()) return false;
        for (NavigatorStatePublisher.RouteStreetLabelFrame value : values) {
            if (value != null && value.hasContent()) return true;
        }
        return false;
    }

    /** Reuse native text objects when only the map-matched anchor moved. */
    private static long structureFingerprint(
            List<NavigatorStatePublisher.RouteStreetLabelFrame> values) {
        long result = 0xcbf29ce484222325L;
        for (NavigatorStatePublisher.RouteStreetLabelFrame value : values) {
            result = mix(result, value.id.hashCode());
        }
        return mix(result, values.size());
    }

    private static long visualFingerprint(
            List<NavigatorStatePublisher.RouteStreetLabelFrame> values) {
        if (!hasContent(values)) return 0L;
        long result = 0x517cc1b727220a95L;
        int count = 0;
        for (NavigatorStatePublisher.RouteStreetLabelFrame value : values) {
            if (value == null || !value.hasContent()) continue;
            if (count >= MAX_LABELS) break;
            count++;
            result = mix(result, value.id.hashCode());
            result = mix(result, value.text.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
        }
        return mix(result, count);
    }

    private static long mix(long value, long part) {
        return (value ^ part) * 0x100000001b3L;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class Marker {
        final Object placemark;
        final String id;
        String text = "";
        double latitude = Double.NaN;
        double longitude = Double.NaN;

        Marker(Object placemark, String id) {
            this.placemark = placemark;
            this.id = id;
        }

        boolean sameContent(NavigatorStatePublisher.RouteStreetLabelFrame frame) {
            return id.equals(frame.id) && text.equals(frame.text)
                    && finite(latitude) && finite(longitude)
                    && Math.abs(latitude - frame.latitude) < 0.000001d
                    && Math.abs(longitude - frame.longitude) < 0.000001d;
        }
    }
}
