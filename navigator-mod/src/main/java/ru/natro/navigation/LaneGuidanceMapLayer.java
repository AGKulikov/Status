/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;

/** The stock Yandex lane balloon, anchored to its upcoming RoutePosition. */
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
    private boolean nightMode;
    private int scalePercent = 100;
    private float zIndex = NavigationMapProfile.layerZ(80);
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

    void apply(boolean nextEnabled, int nextScalePercent, boolean nextNightMode,
               int layerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = scalePercent != nextScale || nightMode != nextNightMode
                || zIndex != nextZ;
        boolean enabledChanged = enabled != nextEnabled;
        if (!presentationChanged && !enabledChanged) return;
        enabled = nextEnabled;
        scalePercent = nextScale;
        nightMode = nextNightMode;
        zIndex = nextZ;
        if (presentationChanged) {
            iconStyle = null;
            renderedFingerprint = Long.MIN_VALUE;
        }
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
                applyOriginalYandexIcon(currentPlacemark, frame);
                renderedFingerprint = latestFingerprint;
            }
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(frame.latitude, frame.longitude);
            invoke(currentPlacemark, "setGeometry", new Class<?>[]{pointClass}, point);
            invoke(currentPlacemark, "setVisible", new Class<?>[]{boolean.class}, true);
        } catch (Throwable failure) {
            // Do not draw guessed arrows: if the stock renderer is unavailable, hiding the item is
            // safer than presenting incorrect lane information to the driver.
            Log.w(TAG, "Original Yandex lane renderer failed", failure);
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
    private void applyOriginalYandexIcon(Object currentPlacemark,
                                         NavigatorStatePublisher.LaneGuidanceFrame frame)
            throws Exception {
        Class<?> laneSignClass = Class.forName(
                "com.yandex.mapkit.directions.driving.LaneSign");
        if (!laneSignClass.isInstance(frame.laneSign)) {
            throw new IllegalArgumentException("LaneSign type mismatch");
        }
        Class<?> directionSignClass = Class.forName(
                "com.yandex.mapkit.directions.driving.DirectionSign");
        Class<?> laneBalloonClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.layer.LaneSignBalloon");
        Object laneBalloon = laneBalloonClass
                .getConstructor(laneSignClass, directionSignClass)
                .newInstance(frame.laneSign, null);
        Class<?> balloonClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.layer.Balloon");
        Object balloon = balloonClass.getMethod("fromLaneSign", laneBalloonClass)
                .invoke(null, laneBalloon);

        Class<?> colorsClass = Class.forName(
                "com.yandex.mapkit.styling.automotive.balloons.BalloonColors");
        Class<?> factoryClass = Class.forName(
                "com.yandex.mapkit.styling.automotivenavigation.balloons."
                        + "LaneSignBalloonTextureFactory");
        Object factory = factoryClass.getConstructor(Context.class, colorsClass)
                .newInstance(context, null);
        View original = (View) factoryClass
                .getMethod("createView", balloonClass, boolean.class)
                .invoke(factory, balloon, nightMode);
        Bitmap bitmap = drawView(original, scalePercent / 100f);

        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object style = iconStyle;
        if (style == null) {
            Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf(
                    (Class<? extends Enum>) rotationClass, "NO_ROTATION");
            invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                    new PointF(0.5f, 1.04f));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
            iconStyle = style;
        }
        Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                .invoke(null, bitmap);
        imageProvider = provider;
        iconBitmap = bitmap;
        invoke(currentPlacemark, "setIcon",
                new Class<?>[]{providerClass, styleClass}, provider, style);
    }

    private static Bitmap drawView(View view, float scale) {
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(unspecified, unspecified);
        int naturalWidth = Math.max(1, view.getMeasuredWidth());
        int naturalHeight = Math.max(1, view.getMeasuredHeight());
        if (naturalWidth > 2_048 || naturalHeight > 2_048) {
            throw new IllegalArgumentException("Lane view is unbounded");
        }
        view.layout(0, 0, naturalWidth, naturalHeight);
        int width = Math.max(1, Math.round(naturalWidth * scale));
        int height = Math.max(1, Math.round(naturalHeight * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        view.draw(canvas);
        return bitmap;
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
            for (String direction : lane.directions) result = mix(result, direction.hashCode());
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
