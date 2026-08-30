/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Direction chevrons for the route-relevant cameras reported by Windshield. */
final class CameraDirectionMapLayer {
    private static final String TAG = "NatroCameraDirection";
    private static final long FRESH_MS = 3_000L;
    private static final int MAX_CAMERAS = 8;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Marker> markers = new ArrayList<>();
    private final Object[] providers = new Object[4];
    private final Bitmap[] bitmaps = new Bitmap[4];
    private Object map;
    private Object collection;
    private boolean enabled;
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.CameraDirectionFrame> latest =
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

    CameraDirectionMapLayer(Context context) {
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

    /** Drops only MapKit objects; a fresh Windshield sample survives a Surface recreation. */
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
        ArrayList<NavigatorStatePublisher.CameraDirectionFrame> visible = new ArrayList<>();
        for (NavigatorStatePublisher.CameraDirectionFrame camera : latest) {
            if (visible.size() >= MAX_CAMERAS) break;
            if (camera != null && camera.hasMapPosition()
                    && (camera.inFace || camera.inBack)) visible.add(camera);
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
                NavigatorStatePublisher.CameraDirectionFrame camera = visible.get(index);
                Marker marker = markers.get(index);
                if (!marker.sameContent(camera)) updateMarker(marker, camera);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Camera-direction layer update failed", failure);
            clearVisual();
        }
    }

    private void rebuild(List<NavigatorStatePublisher.CameraDirectionFrame> values,
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
        for (NavigatorStatePublisher.CameraDirectionFrame camera : values) {
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(camera.latitude, camera.longitude);
            Object placemark = invoke(currentCollection, "addPlacemark",
                    new Class<?>[]{pointClass}, point);
            Marker marker = new Marker(placemark, camera.id);
            updateMarker(marker, camera);
            markers.add(marker);
        }
        renderedStructureFingerprint = structure;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void updateMarker(Marker marker,
                              NavigatorStatePublisher.CameraDirectionFrame camera)
            throws Exception {
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object style = marker.iconStyle;
        if (style == null) {
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf((Class<? extends Enum>) rotationClass, "ROTATE");
            invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                    new PointF(0.5f, 0.5f));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(45f));
            marker.iconStyle = style;
        }
        int providerIndex = (camera.inFace ? 1 : 0) | (camera.inBack ? 2 : 0);
        if (providerIndex != marker.providerIndex) {
            Object provider = imageProvider(providerIndex, providerClass);
            invoke(marker.placemark, "setIcon",
                    new Class<?>[]{providerClass, styleClass}, provider, style);
            marker.providerIndex = providerIndex;
        }
        if (Math.abs(marker.bearingDegrees - camera.bearingDegrees) >= 0.5f
                || !finite(marker.bearingDegrees)) {
            invoke(marker.placemark, "setDirection", new Class<?>[]{float.class},
                    camera.bearingDegrees);
        }
        invoke(marker.placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        marker.bearingDegrees = camera.bearingDegrees;
        marker.inFace = camera.inFace;
        marker.inBack = camera.inBack;
    }

    private Object imageProvider(int index, Class<?> providerClass) throws Exception {
        Object provider = providers[index];
        if (provider != null) return provider;
        Bitmap bitmap = createDirectionBitmap(index);
        provider = providerClass.getMethod("fromBitmap", Bitmap.class).invoke(null, bitmap);
        bitmaps[index] = bitmap;
        providers[index] = provider;
        return provider;
    }

    /**
     * Leaves the middle transparent so the stock Yandex camera icon remains visible. The
     * chevron itself is rotated to the active route direction at the event's polyline segment.
     */
    private Bitmap createDirectionBitmap(int directionFlags) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(44, Math.round(64f * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float unit = size / 64f;
        float center = size / 2f;
        Path arrow = new Path();
        arrow.moveTo(center, 1f * unit);
        arrow.lineTo(center + 12f * unit, 15f * unit);
        arrow.lineTo(center + 5f * unit, 14f * unit);
        arrow.lineTo(center + 5f * unit, 22f * unit);
        arrow.lineTo(center - 5f * unit, 22f * unit);
        arrow.lineTo(center - 5f * unit, 14f * unit);
        arrow.lineTo(center - 12f * unit, 15f * unit);
        arrow.close();

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setStyle(Paint.Style.STROKE);
        shadow.setStrokeJoin(Paint.Join.ROUND);
        shadow.setStrokeWidth(5f * unit);
        shadow.setColor(0xDD17191E);
        canvas.drawPath(arrow, shadow);

        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(2.2f * unit);
        outline.setColor(0xFFFFFFFF);
        canvas.drawPath(arrow, outline);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        boolean inFace = (directionFlags & 1) != 0;
        boolean inBack = (directionFlags & 2) != 0;
        fill.setColor(inFace ? 0xFFFF3B30 : 0xFFFF9F0A);
        canvas.drawPath(arrow, fill);

        if (inFace && inBack) {
            fill.setColor(0xFFFFCC00);
            canvas.drawCircle(center, 17f * unit, 2.2f * unit, fill);
        }
        return bitmap;
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
            List<NavigatorStatePublisher.CameraDirectionFrame> values) {
        long result = 0xcbf29ce484222325L;
        for (NavigatorStatePublisher.CameraDirectionFrame value : values) {
            result = mix(result, value.id.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
        }
        return mix(result, values.size());
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

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class Marker {
        final Object placemark;
        final String id;
        Object iconStyle;
        int providerIndex = -1;
        float bearingDegrees = Float.NaN;
        boolean inFace;
        boolean inBack;

        Marker(Object placemark, String id) {
            this.placemark = placemark;
            this.id = id;
        }

        boolean sameContent(NavigatorStatePublisher.CameraDirectionFrame camera) {
            return id.equals(camera.id) && inFace == camera.inFace && inBack == camera.inBack
                    && finite(bearingDegrees)
                    && Math.abs(bearingDegrees - camera.bearingDegrees) < 0.5f;
        }
    }
}
