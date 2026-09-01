/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Flat street-name glyphs aligned with and painted directly over the active route. */
final class RouteStreetLabelMapLayer {
    private static final String TAG = "NatroRouteLabels";
    private static final long FRESH_MS = 2_500L;
    private static final int MAX_LABELS = 6;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Bitmap> bitmaps = new ArrayList<>();
    private final ArrayList<Object> providers = new ArrayList<>();
    private Object map;
    private Object collection;
    private boolean enabled;
    private int scalePercent = 100;
    private float zIndex = NavigationMapProfile.layerZ(60);
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.RouteStreetLabelFrame> latest =
            Collections.emptyList();
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

    RouteStreetLabelMapLayer(Context context) {
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
                List<NavigatorStatePublisher.RouteStreetLabelFrame> frames) {
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
                        "ru.natro.navigation.route_street_labels",
                        MapObjectLayerFactory.MINOR, zIndex);
                collection = currentCollection;
            }
            invoke(currentCollection, "clear", new Class<?>[0]);
            bitmaps.clear();
            providers.clear();
            int count = 0;
            for (NavigatorStatePublisher.RouteStreetLabelFrame frame : latest) {
                if (count++ >= MAX_LABELS) break;
                if (frame == null || !frame.hasContent()) continue;
                addLabel(currentCollection, frame);
            }
            renderedFingerprint = latestFingerprint;
        } catch (Throwable failure) {
            Log.w(TAG, "Route street label layer update failed", failure);
            clearVisual();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addLabel(Object target,
                          NavigatorStatePublisher.RouteStreetLabelFrame frame)
            throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(frame.latitude, frame.longitude);
        Object placemark = invoke(target, "addPlacemark", new Class<?>[]{pointClass}, point);
        Bitmap bitmap = createLabelBitmap(frame.text);
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
        // Icon direction follows the bitmap's vertical axis, while the text baseline follows X.
        // Rotate that baseline by a quarter turn so the glyph lies on, not across, the route.
        invoke(placemark, "setDirection", new Class<?>[]{float.class},
                readableBearing(frame.bearingDegrees + 90f));
        invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        bitmaps.add(bitmap);
        providers.add(provider);
    }

    private Bitmap createLabelBitmap(String text) {
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float scale = scalePercent / 100f;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        fill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setTextSize(15f * density * scale);
        fill.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics metrics = fill.getFontMetrics();
        float padding = 5f * density * scale;
        int width = Math.max(1, Math.min(1024,
                Math.round(fill.measureText(text) + padding * 2f)));
        int height = Math.max(1, Math.min(256,
                Math.round(metrics.descent - metrics.ascent + padding * 2f)));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float baseline = padding - metrics.ascent;
        Paint outline = new Paint(fill);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(Math.max(2f, 2.5f * density * scale));
        outline.setColor(0xE8172230);
        canvas.drawText(text, padding, baseline, outline);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
        canvas.drawText(text, padding, baseline, fill);
        return bitmap;
    }

    /** Keep text upright when the route geometry itself runs south/west. */
    private static float readableBearing(float raw) {
        float value = raw % 360f;
        if (value < 0f) value += 360f;
        if (value > 90f && value < 270f) value = (value + 180f) % 360f;
        return value;
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

    private static boolean hasContent(
            List<NavigatorStatePublisher.RouteStreetLabelFrame> values) {
        if (values == null || values.isEmpty()) return false;
        for (NavigatorStatePublisher.RouteStreetLabelFrame value : values) {
            if (value != null && value.hasContent()) return true;
        }
        return false;
    }

    private static long fingerprint(
            List<NavigatorStatePublisher.RouteStreetLabelFrame> values) {
        if (!hasContent(values)) return 0L;
        long result = 0x517cc1b727220a95L;
        int count = 0;
        for (NavigatorStatePublisher.RouteStreetLabelFrame value : values) {
            if (value == null || !value.hasContent()) continue;
            if (count++ >= MAX_LABELS) break;
            result = mix(result, value.id.hashCode());
            result = mix(result, value.text.hashCode());
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
