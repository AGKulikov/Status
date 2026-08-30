/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/** A screen-facing lane sign whose geographic anchor is the upcoming route position. */
final class LaneGuidanceMapLayer {
    private static final String TAG = "NatroLaneMap";
    private static final long FRESH_MS = 1_500L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object map;
    private Object collection;
    private Object placemark;
    private Object iconStyle;
    private Object imageProvider;
    private Bitmap iconBitmap;
    private boolean enabled;
    private boolean latestRouteActive;
    private NavigatorStatePublisher.LaneGuidanceFrame latest;
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
            latest = null;
            latestSampleElapsedMs = 0L;
            latestFingerprint = Long.MIN_VALUE;
            render();
        }
    };

    LaneGuidanceMapLayer(Context context) {
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
                NavigatorStatePublisher.LaneGuidanceFrame frame) {
        if (routeActive == latestRouteActive && sampleElapsedMs == latestSampleElapsedMs
                && frame == latest) return;
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L && now >= sampleElapsedMs
                && now - sampleElapsedMs <= FRESH_MS
                && frame != null && frame.hasContent();
        latest = fresh ? frame : null;
        if (fresh) {
            latestSampleElapsedMs = sampleElapsedMs;
            scheduleExpiryIfNeeded();
        } else {
            latestSampleElapsedMs = 0L;
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
        latest = null;
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
        latest = null;
        latestSampleElapsedMs = 0L;
        latestFingerprint = Long.MIN_VALUE;
    }

    private void render() {
        if (map == null) return;
        NavigatorStatePublisher.LaneGuidanceFrame frame = latest;
        if (!enabled || !latestRouteActive || frame == null || !frame.hasContent()) {
            clearVisual();
            return;
        }
        try {
            Object currentPlacemark = ensurePlacemark(frame);
            if (renderedFingerprint != latestFingerprint) {
                applyIcon(currentPlacemark, frame);
                renderedFingerprint = latestFingerprint;
            }
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(frame.latitude, frame.longitude);
            invoke(currentPlacemark, "setGeometry", new Class<?>[]{pointClass}, point);
            invoke(currentPlacemark, "setVisible", new Class<?>[]{boolean.class}, true);
        } catch (Throwable failure) {
            Log.w(TAG, "Lane guidance map layer update failed", failure);
            clearVisual();
        }
    }

    private Object ensurePlacemark(NavigatorStatePublisher.LaneGuidanceFrame frame)
            throws Exception {
        Object currentPlacemark = placemark;
        if (currentPlacemark != null) return currentPlacemark;
        Object currentCollection = collection;
        if (currentCollection == null) {
            Object root = invoke(map, "getMapObjects", new Class<?>[0]);
            currentCollection = invoke(root, "addCollection", new Class<?>[0]);
            collection = currentCollection;
        }
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(frame.latitude, frame.longitude);
        currentPlacemark = invoke(currentCollection, "addPlacemark",
                new Class<?>[]{pointClass}, point);
        placemark = currentPlacemark;
        return currentPlacemark;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyIcon(Object currentPlacemark,
                           NavigatorStatePublisher.LaneGuidanceFrame frame)
            throws Exception {
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object style = iconStyle;
        if (style == null) {
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf(
                    (Class<? extends Enum>) rotationClass, "NO_ROTATION");
            invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                    new PointF(0.5f, 1.08f));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(42f));
            iconStyle = style;
        }
        Bitmap bitmap = createLaneBitmap(frame.lanes);
        Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                .invoke(null, bitmap);
        imageProvider = provider;
        iconBitmap = bitmap;
        invoke(currentPlacemark, "setIcon",
                new Class<?>[]{providerClass, styleClass}, provider, style);
    }

    private Bitmap createLaneBitmap(List<NavigatorStatePublisher.LaneFrame> lanes) {
        float density = context.getResources().getDisplayMetrics().density;
        int laneCount = Math.max(1, Math.min(8, lanes.size()));
        float laneWidthDp = laneCount <= 4 ? 31f : 27f;
        int width = Math.max(Math.round(76f * density),
                Math.round((18f + laneCount * laneWidthDp) * density));
        int height = Math.max(56, Math.round(70f * density));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float unit = density;
        RectF panel = new RectF(2f * unit, 2f * unit,
                width - 2f * unit, height - 10f * unit);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x66111828);
        shadow.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(panel.left, panel.top + 3f * unit,
                panel.right, panel.bottom + 3f * unit), 12f * unit, 12f * unit, shadow);

        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setShader(new LinearGradient(panel.left, panel.top,
                panel.right, panel.bottom, 0xFF0758E8, 0xFF0838A8,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(panel, 11f * unit, 11f * unit, background);
        background.setShader(null);
        background.setStyle(Paint.Style.STROKE);
        background.setStrokeWidth(Math.max(1f, unit));
        background.setColor(0x667FC5FF);
        canvas.drawRoundRect(panel, 11f * unit, 11f * unit, background);

        Path pointer = new Path();
        pointer.moveTo(width / 2f - 7f * unit, panel.bottom - 1f * unit);
        pointer.lineTo(width / 2f + 7f * unit, panel.bottom - 1f * unit);
        pointer.lineTo(width / 2f, height - 2f * unit);
        pointer.close();
        background.setStyle(Paint.Style.FILL);
        background.setColor(0xFF0838A8);
        canvas.drawPath(pointer, background);

        float contentLeft = (width - laneCount * laneWidthDp * unit) / 2f;
        for (int index = 0; index < laneCount; index++) {
            NavigatorStatePublisher.LaneFrame lane = lanes.get(index);
            float left = contentLeft + index * laneWidthDp * unit;
            RectF laneBounds = new RectF(left + 2f * unit, panel.top + 7f * unit,
                    left + (laneWidthDp - 2f) * unit, panel.bottom - 6f * unit);
            drawLane(canvas, lane, laneBounds, unit);
            if (index > 0) {
                Paint separator = new Paint(Paint.ANTI_ALIAS_FLAG);
                separator.setColor(0x266ED0FF);
                separator.setStrokeWidth(Math.max(1f, 0.7f * unit));
                canvas.drawLine(left, panel.top + 9f * unit,
                        left, panel.bottom - 8f * unit, separator);
            }
        }
        return bitmap;
    }

    private static void drawLane(Canvas canvas, NavigatorStatePublisher.LaneFrame lane,
                                 RectF bounds, float density) {
        if (lane == null || lane.directions.isEmpty()) return;
        String highlighted = lane.highlightedDirection;
        boolean recommended = highlighted != null && !highlighted.isEmpty()
                && !"UNKNOWN_DIRECTION".equals(highlighted);
        int count = Math.min(3, lane.directions.size());
        for (int index = 0; index < count; index++) {
            String direction = lane.directions.get(index);
            boolean selected = recommended && direction.equals(highlighted);
            int color = selected ? 0xFFFFFFFF
                    : recommended ? 0x669EC8FF : 0xE8FFFFFF;
            float offset = (index - (count - 1) / 2f) * 4.4f * density;
            drawDirection(canvas, direction, bounds, offset, color, density);
        }
        if ("BUS_LANE".equals(lane.kind) || "TAXI_LANE".equals(lane.kind)) {
            Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
            badge.setColor(0xE6FFFFFF);
            badge.setStyle(Paint.Style.FILL);
            canvas.drawCircle(bounds.centerX(), bounds.bottom - 1.5f * density,
                    2f * density, badge);
        }
    }

    private static void drawDirection(Canvas canvas, String direction, RectF bounds,
                                      float xOffset, int color, float density) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(2f, 3.2f * density));
        float centerX = bounds.centerX() + xOffset;
        float bottom = bounds.bottom - 4f * density;
        float top = bounds.top + 2f * density;
        float side = Math.min(bounds.width() * 0.36f, 8.5f * density);
        boolean left = direction != null && direction.startsWith("LEFT");
        boolean right = direction != null && direction.startsWith("RIGHT");
        Path path = new Path();
        if (!left && !right) {
            path.moveTo(centerX, bottom);
            path.lineTo(centerX, top + 5f * density);
            path.moveTo(centerX - 4.5f * density, top + 9.5f * density);
            path.lineTo(centerX, top + 5f * density);
            path.lineTo(centerX + 4.5f * density, top + 9.5f * density);
        } else {
            float sign = left ? -1f : 1f;
            float turnY = top + 14f * density;
            path.moveTo(centerX, bottom);
            path.lineTo(centerX, turnY + 4f * density);
            path.quadTo(centerX, turnY, centerX + sign * side, turnY);
            path.moveTo(centerX + sign * (side - 4.5f * density),
                    turnY - 4.5f * density);
            path.lineTo(centerX + sign * side, turnY);
            path.lineTo(centerX + sign * (side - 4.5f * density),
                    turnY + 4.5f * density);
            if (direction.contains("135") || direction.contains("180")) {
                path.reset();
                float outer = side * 0.85f;
                path.moveTo(centerX, bottom);
                path.lineTo(centerX, turnY + 2f * density);
                path.cubicTo(centerX, top + 3f * density,
                        centerX + sign * outer, top + 3f * density,
                        centerX + sign * outer, turnY + 1f * density);
                path.moveTo(centerX + sign * (outer - 4f * density),
                        turnY - 3f * density);
                path.lineTo(centerX + sign * outer, turnY + 1f * density);
                path.lineTo(centerX + sign * (outer - 4f * density),
                        turnY + 5f * density);
            }
        }
        canvas.drawPath(path, paint);
    }

    private void clearVisual() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        placemark = null;
        iconStyle = null;
        imageProvider = null;
        iconBitmap = null;
        renderedFingerprint = Long.MIN_VALUE;
    }

    private static long fingerprint(NavigatorStatePublisher.LaneGuidanceFrame frame) {
        if (frame == null || !frame.hasContent()) return 0L;
        long result = 0xcbf29ce484222325L;
        result = mix(result, frame.id.hashCode());
        result = mix(result, Math.round(frame.latitude * 1_000_000d));
        result = mix(result, Math.round(frame.longitude * 1_000_000d));
        for (NavigatorStatePublisher.LaneFrame lane : frame.lanes) {
            result = mix(result, lane.kind.hashCode());
            result = mix(result, lane.highlightedDirection.hashCode());
            for (String direction : lane.directions) {
                result = mix(result, direction.hashCode());
            }
        }
        return mix(result, frame.lanes.size());
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
