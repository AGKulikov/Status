/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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

/** Flat, original-style manoeuvre arrows painted directly on the active route. */
final class RouteTurnMapLayer {
    private static final String TAG = "NatroRouteTurns";
    private static final long FRESH_MS = 2_500L;
    private static final int MAX_TURNS = 10;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Bitmap> bitmaps = new ArrayList<>();
    private final ArrayList<Object> providers = new ArrayList<>();
    private Object map;
    private Object collection;
    private boolean enabled;
    private int scalePercent = 100;
    private float zIndex = NavigationMapProfile.layerZ(55);
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

    RouteTurnMapLayer(Context context) {
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

    void apply(boolean nextEnabled, int nextScalePercent, int layerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = scalePercent != nextScale || zIndex != nextZ;
        if (enabled == nextEnabled && !presentationChanged) return;
        enabled = nextEnabled;
        scalePercent = nextScale;
        zIndex = nextZ;
        MapObjectLayerFactory.setZIndex(collection, nextZ);
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
        if (enabled && map != null) render();
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
        latestFingerprint = Long.MIN_VALUE;
    }

    private void render() {
        if (map == null) return;
        if (!enabled || !latestRouteActive || !hasContent(latest)) {
            clearVisual();
            return;
        }
        if (renderedFingerprint == latestFingerprint) return;
        try {
            Object currentCollection = collection;
            if (currentCollection == null) {
                currentCollection = MapObjectLayerFactory.create(map,
                        "ru.natro.navigation.route_turns",
                        MapObjectLayerFactory.MAJOR, zIndex);
                collection = currentCollection;
            }
            invoke(currentCollection, "clear", new Class<?>[0]);
            bitmaps.clear();
            providers.clear();
            int count = 0;
            for (NavigatorStatePublisher.RouteTurnFrame frame : latest) {
                if (count++ >= MAX_TURNS) break;
                if (frame == null || !frame.hasContent()) continue;
                addArrow(currentCollection, frame);
            }
            renderedFingerprint = latestFingerprint;
        } catch (Throwable failure) {
            Log.w(TAG, "Route-turn layer update failed", failure);
            clearVisual();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addArrow(Object target, NavigatorStatePublisher.RouteTurnFrame frame)
            throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(frame.latitude, frame.longitude);
        Object placemark = invoke(target, "addPlacemark", new Class<?>[]{pointClass}, point);
        Bitmap bitmap = createArrowBitmap(frame.action);
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                .invoke(null, bitmap);
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
        Object rotate = Enum.valueOf((Class<? extends Enum>) rotationClass, "ROTATE");
        Object style = styleClass.getConstructor().newInstance();
        invoke(style, "setAnchor", new Class<?>[]{PointF.class}, new PointF(.5f, .5f));
        invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotate);
        invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.TRUE);
        invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
        invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
        invoke(placemark, "setIcon", new Class<?>[]{providerClass, styleClass}, provider, style);
        invoke(placemark, "setDirection", new Class<?>[]{float.class}, frame.bearingDegrees);
        invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        bitmaps.add(bitmap);
        providers.add(provider);
    }

    private Bitmap createArrowBitmap(String rawAction) {
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float scale = scalePercent / 100f;
        int size = Math.max(30, Math.min(220, Math.round(46f * density * scale)));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        String action = rawAction == null ? "" : rawAction;
        int direction = action.contains("LEFT") ? -1 : action.contains("RIGHT") ? 1 : 0;
        Paint outline = arrowPaint(size, 0xCC1A2029, size * .19f);
        Paint white = arrowPaint(size, Color.WHITE, size * .115f);
        Path path = arrowPath(action, direction, size);
        canvas.drawPath(path, outline);
        canvas.drawPath(path, white);
        drawArrowHead(canvas, outline, pathEnd(action, direction, size), direction, size);
        drawArrowHead(canvas, white, pathEnd(action, direction, size), direction, size);
        return bitmap;
    }

    private static Paint arrowPaint(int size, int color, float width) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(2f, width));
        paint.setColor(color);
        return paint;
    }

    private static Path arrowPath(String action, int direction, int size) {
        float s = size;
        Path path = new Path();
        path.moveTo(.50f * s, .86f * s);
        if (action.contains("UTURN")) {
            float side = direction < 0 ? .25f : .75f;
            path.lineTo(.50f * s, .48f * s);
            path.cubicTo(.50f * s, .20f * s, side, .18f * s, side, .42f * s);
        } else if (action.contains("ROUNDABOUT")) {
            RectF circle = new RectF(.27f * s, .25f * s, .73f * s, .71f * s);
            path.lineTo(.50f * s, .68f * s);
            path.addArc(circle, 90f, direction < 0 ? 245f : -245f);
        } else if (direction != 0) {
            float endX = direction < 0 ? .18f * s : .82f * s;
            float bendY = action.contains("SLIGHT") ? .48f * s : .56f * s;
            float endY = action.contains("SLIGHT") ? .30f * s : .34f * s;
            path.lineTo(.50f * s, bendY);
            path.quadTo(.50f * s, endY, endX, endY);
        } else {
            path.lineTo(.50f * s, .20f * s);
        }
        return path;
    }

    private static PointF pathEnd(String action, int direction, int size) {
        float s = size;
        if (action.contains("UTURN")) {
            return new PointF((direction < 0 ? .25f : .75f) * s, .42f * s);
        }
        if (action.contains("ROUNDABOUT")) {
            return new PointF((direction < 0 ? .28f : .72f) * s, .39f * s);
        }
        if (direction != 0) {
            return new PointF((direction < 0 ? .18f : .82f) * s,
                    (action.contains("SLIGHT") ? .30f : .34f) * s);
        }
        return new PointF(.50f * s, .20f * s);
    }

    private static void drawArrowHead(Canvas canvas, Paint paint, PointF end,
                                      int direction, int size) {
        float s = size;
        Path head = new Path();
        if (direction < 0) {
            head.moveTo(end.x + .15f * s, end.y - .12f * s);
            head.lineTo(end.x, end.y);
            head.lineTo(end.x + .15f * s, end.y + .12f * s);
        } else if (direction > 0) {
            head.moveTo(end.x - .15f * s, end.y - .12f * s);
            head.lineTo(end.x, end.y);
            head.lineTo(end.x - .15f * s, end.y + .12f * s);
        } else {
            head.moveTo(end.x - .13f * s, end.y + .15f * s);
            head.lineTo(end.x, end.y);
            head.lineTo(end.x + .13f * s, end.y + .15f * s);
        }
        canvas.drawPath(head, paint);
    }

    private void clearVisual() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        bitmaps.clear();
        providers.clear();
        renderedFingerprint = Long.MIN_VALUE;
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
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
            result = mix(result, Math.round(value.bearingDegrees * 10f));
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
