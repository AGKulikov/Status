/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A map-object collection dedicated only to fresh route traffic lights.
 *
 * <p>The publisher samples Windshield once for both independent maps. This layer performs no
 * Guidance reflection, does not touch Navigator's primary map and replaces its bitmap icon only
 * when a signal/countdown actually changes.</p>
 */
final class TrafficLightMapLayer {
    private static final String TAG = "NatroTrafficLights";
    private static final long FRESH_MS = 3_000L;
    private static final int MAX_LIGHTS = 8;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Marker> markers = new ArrayList<>();
    private Object map;
    private Object collection;
    private boolean enabled;
    private List<NavigatorStatePublisher.TrafficLightFrame> latest =
            Collections.emptyList();
    private long latestSampleElapsedMs;
    private long latestVisualFingerprint = Long.MIN_VALUE;
    private long renderedStructureFingerprint = Long.MIN_VALUE;
    private boolean expiryPosted;
    private boolean latestRouteActive;

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

    /** Drops only MapKit objects; fresh source data remains ready for the next Surface. */
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
                && sampleElapsedMs == latestSampleElapsedMs && values == latest) {
            return;
        }
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L
                && now >= sampleElapsedMs && now - sampleElapsedMs <= FRESH_MS;
        latest = fresh && values != null ? values : Collections.emptyList();
        if (!fresh) {
            latestSampleElapsedMs = 0L;
            main.removeCallbacks(expire);
            expiryPosted = false;
        } else {
            latestSampleElapsedMs = sampleElapsedMs;
            scheduleExpiryIfNeeded();
        }
        long fingerprint = visualFingerprint(latest);
        if (fingerprint == latestVisualFingerprint) return;
        latestVisualFingerprint = fingerprint;
        if (!enabled || map == null) return;
        render();
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

    /** Hidden/detached layers keep the latest sample but own no periodic deadline wake. */
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
        if (!enabled || latest.isEmpty()) {
            clearVisual();
            return;
        }
        ArrayList<NavigatorStatePublisher.TrafficLightFrame> visible = new ArrayList<>();
        for (NavigatorStatePublisher.TrafficLightFrame light : latest) {
            if (visible.size() >= MAX_LIGHTS) break;
            // This is specifically the countdown layer. A signal without a timer remains useful
            // to the standalone HUD module, but must not become another static map POI.
            if (light != null && light.hasMapPosition() && light.secondsLeft >= 0) {
                visible.add(light);
            }
        }
        if (visible.isEmpty()) {
            clearVisual();
            return;
        }
        try {
            long structure = structureFingerprint(visible);
            if (structure != renderedStructureFingerprint || markers.size() != visible.size()) {
                rebuild(visible, structure);
                return;
            }
            for (int index = 0; index < visible.size(); index++) {
                NavigatorStatePublisher.TrafficLightFrame light = visible.get(index);
                Marker marker = markers.get(index);
                if (!marker.sameContent(light)) updateMarker(marker, light);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Traffic-light map layer update failed", failure);
            clearVisual();
        }
    }

    private void rebuild(List<NavigatorStatePublisher.TrafficLightFrame> values,
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
        for (NavigatorStatePublisher.TrafficLightFrame light : values) {
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(light.latitude, light.longitude);
            Object placemark = invoke(currentCollection, "addPlacemark",
                    new Class<?>[]{pointClass}, point);
            Marker marker = new Marker(placemark, light);
            updateMarker(marker, light);
            markers.add(marker);
        }
        renderedStructureFingerprint = structure;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void updateMarker(Marker marker,
                              NavigatorStatePublisher.TrafficLightFrame light)
            throws Exception {
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object style = marker.iconStyle;
        if (style == null) {
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf(
                    (Class<? extends Enum>) rotationClass, "NO_ROTATION");
            invoke(style, "setAnchor", new Class<?>[]{PointF.class}, new PointF(0.5f, 1f));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(30f));
            marker.iconStyle = style;
        }

        RenderedIcon rendered = createImageProvider(light, providerClass);
        invoke(marker.placemark, "setIcon", new Class<?>[]{providerClass, styleClass},
                rendered.provider, style);
        marker.imageProvider = rendered.provider;
        marker.iconBitmap = rendered.bitmap;
        marker.signal = light.signal;
        marker.secondsLeft = light.secondsLeft;
        marker.arrow = light.arrow;
    }

    private RenderedIcon createImageProvider(
            NavigatorStatePublisher.TrafficLightFrame light, Class<?> providerClass)
            throws Exception {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.max(56, Math.round(70f * density));
        int height = Math.max(52, Math.round(64f * density));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawTrafficLightIcon(new Canvas(bitmap), light, density, width, height);
        Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                .invoke(null, bitmap);
        return new RenderedIcon(provider, bitmap);
    }

    private void clearVisual() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        markers.clear();
        renderedStructureFingerprint = Long.MIN_VALUE;
    }

    private static long structureFingerprint(
            List<NavigatorStatePublisher.TrafficLightFrame> values) {
        long result = 0xcbf29ce484222325L;
        for (NavigatorStatePublisher.TrafficLightFrame value : values) {
            result = mix(result, value.id.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
        }
        return mix(result, values.size());
    }

    private static long visualFingerprint(
            List<NavigatorStatePublisher.TrafficLightFrame> values) {
        long result = 0x517cc1b727220a95L;
        int count = 0;
        for (NavigatorStatePublisher.TrafficLightFrame value : values) {
            if (value == null || !value.hasMapPosition() || value.secondsLeft < 0) continue;
            if (count >= MAX_LIGHTS) break;
            count++;
            result = mix(result, value.id.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
            result = mix(result, value.signal.hashCode());
            result = mix(result, value.secondsLeft);
            result = mix(result, value.arrow.hashCode());
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

    private static final class Marker {
        final Object placemark;
        String signal = "";
        int secondsLeft = Integer.MIN_VALUE;
        String arrow = "";
        Object iconStyle;
        Object imageProvider;
        Bitmap iconBitmap;

        Marker(Object placemark, NavigatorStatePublisher.TrafficLightFrame ignored) {
            this.placemark = placemark;
        }

        boolean sameContent(NavigatorStatePublisher.TrafficLightFrame light) {
            return secondsLeft == light.secondsLeft && signal.equals(light.signal)
                    && arrow.equals(light.arrow);
        }
    }

    private static final class RenderedIcon {
        final Object provider;
        final Bitmap bitmap;

        RenderedIcon(Object provider, Bitmap bitmap) {
            this.provider = provider;
            this.bitmap = bitmap;
        }
    }

    /** One direct Canvas pass avoids allocating and laying out an Android View per countdown. */
    private static void drawTrafficLightIcon(
            Canvas canvas, NavigatorStatePublisher.TrafficLightFrame light,
            float density, int width, int height) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF box = new RectF();
        String signal = light.signal;
        int seconds = light.secondsLeft;
        String arrow = arrowGlyph(light.arrow);
        float left = 2f * density;
        float top = 2f * density;
        float housingWidth = 25f * density;
        float housingHeight = 56f * density;
        float radius = 7f * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xED17191E);
        box.set(left, top, left + housingWidth, top + housingHeight);
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(0xCCFFFFFF);
        canvas.drawRoundRect(box, radius, radius, paint);

        float centerX = left + housingWidth / 2f;
        float lightRadius = 6.2f * density;
        drawLamp(canvas, paint, signal, density, centerX,
                top + 10.5f * density, lightRadius, "RED", 0xFFFF3B30);
        drawLamp(canvas, paint, signal, density, centerX,
                top + 27.5f * density, lightRadius, "YELLOW", 0xFFFFCC00);
        drawLamp(canvas, paint, signal, density, centerX,
                top + 44.5f * density, lightRadius, "GREEN", 0xFF34C759);

        if (seconds < 0 && arrow.isEmpty()) return;
        float badgeLeft = left + housingWidth - 1f * density;
        float badgeTop = top + 13f * density;
        box.set(badgeLeft, badgeTop, width - 2f * density,
                Math.min(height - 2f * density, top + 45f * density));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF2262930);
        canvas.drawRoundRect(box, 9f * density, 9f * density, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setColor(Color.WHITE);
        paint.setTextSize((seconds >= 10 ? 15f : 17f) * density);
        String value = seconds < 0 ? arrow : Integer.toString(seconds);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(value, box.centerX(), baseline, paint);
        if (seconds >= 0 && !arrow.isEmpty()) {
            paint.setTextSize(8f * density);
            canvas.drawText(arrow, box.right - 6f * density,
                    box.bottom - 3f * density, paint);
        }
    }

    private static void drawLamp(Canvas canvas, Paint paint, String signal, float density,
                                 float x, float y, float radius,
                                 String lamp, int activeColor) {
        boolean active = lamp.equals(signal) || "RED_AND_YELLOW".equals(signal)
                && ("RED".equals(lamp) || "YELLOW".equals(lamp));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? activeColor : 0xFF3B3E44);
        canvas.drawCircle(x, y, radius, paint);
        if (active) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(0xE6FFFFFF);
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    private static String arrowGlyph(String raw) {
        if (raw == null) return "";
        if (raw.contains("LEFT")) return "←";
        if (raw.contains("RIGHT")) return "→";
        if (raw.contains("STRAIGHT") || raw.contains("FORWARD")) return "↑";
        return "";
    }
}
