/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.Log;

import java.lang.reflect.Method;

/** Owns one stable, vector-rendered vehicle placemark for an independent MapWindow. */
final class MapCursorStyler {
    private static final String TAG = "NatroMapCursor";

    private final Context context;
    private Object map;
    private Object collection;
    private Object placemark;
    private Object iconStyle;
    private Object imageProvider;
    private Bitmap iconBitmap;
    private boolean visible;
    private int scalePercent = 100;
    private int fillColor = Color.parseColor("#FFFFC400");
    private int outlineColor = Color.parseColor("#FF17191E");
    private float zIndex = NavigationMapProfile.layerZ(90);
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private float bearingDegrees;

    MapCursorStyler(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
    }

    /**
     * Binds directly to MapObjects instead of UserLocationLayer.
     *
     * <p>MapKit 30.3.0 can replace its internal arrow/pin icon while keeping the same
     * UserLocationView instance. The old listener therefore lost the custom arrow after a GPS or
     * route-state transition. A single publisher-fed placemark has no such state switch and also
     * avoids a second location source in each offscreen MapWindow.</p>
     */
    void attach(Object nextMap) throws Exception {
        if (map == nextMap) return;
        detach();
        map = nextMap;
        if (visible && hasPosition()) ensurePlacemark();
    }

    void apply(boolean nextVisible, int nextScalePercent, String nextFill,
               String nextOutline, int layerPriority) throws Exception {
        int normalizedScale = Math.max(25, Math.min(300, nextScalePercent));
        int normalizedFill = Color.parseColor(nextFill);
        int normalizedOutline = Color.parseColor(nextOutline);
        float normalizedZ = NavigationMapProfile.layerZ(layerPriority);
        boolean styleChanged = scalePercent != normalizedScale
                || fillColor != normalizedFill || outlineColor != normalizedOutline
                || zIndex != normalizedZ;
        visible = nextVisible;
        scalePercent = normalizedScale;
        fillColor = normalizedFill;
        outlineColor = normalizedOutline;
        zIndex = normalizedZ;
        if (styleChanged) {
            imageProvider = null;
            iconBitmap = null;
            iconStyle = null;
        }
        if (visible && hasPosition()) ensurePlacemark();
        Object currentPlacemark = placemark;
        if (currentPlacemark != null) {
            if (styleChanged) applyIcon(currentPlacemark);
            invoke(currentPlacemark, "setVisible", new Class<?>[]{boolean.class}, visible);
        }
    }

    /** Uses the already sampled Guidance frame; no independent GPS or polling is started. */
    void update(double nextLatitude, double nextLongitude, double nextBearingDegrees) {
        if (!finite(nextLatitude) || nextLatitude < -90d || nextLatitude > 90d
                || !finite(nextLongitude) || nextLongitude < -180d || nextLongitude > 180d) {
            return;
        }
        latitude = nextLatitude;
        longitude = nextLongitude;
        bearingDegrees = normalizeBearing(nextBearingDegrees);
        if (!visible || map == null) return;
        try {
            Object currentPlacemark = ensurePlacemark();
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Object point = pointClass.getConstructor(double.class, double.class)
                    .newInstance(latitude, longitude);
            invoke(currentPlacemark, "setGeometry", new Class<?>[]{pointClass}, point);
            invoke(currentPlacemark, "setDirection", new Class<?>[]{float.class},
                    bearingDegrees);
            invoke(currentPlacemark, "setVisible", new Class<?>[]{boolean.class}, true);
        } catch (Throwable failure) {
            Log.w(TAG, "Could not update independent-map cursor", failure);
        }
    }

    void detach() {
        Object currentCollection = collection;
        if (currentCollection != null) {
            try { invoke(currentCollection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        map = null;
        collection = null;
        placemark = null;
        iconStyle = null;
        imageProvider = null;
        iconBitmap = null;
    }

    private Object ensurePlacemark() throws Exception {
        Object currentPlacemark = placemark;
        if (currentPlacemark != null) return currentPlacemark;
        Object currentMap = map;
        if (currentMap == null || !hasPosition()) return null;
        Object currentCollection = collection;
        if (currentCollection == null) {
            Object root = invoke(currentMap, "getMapObjects", new Class<?>[0]);
            currentCollection = invoke(root, "addCollection", new Class<?>[0]);
            collection = currentCollection;
        }
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(latitude, longitude);
        currentPlacemark = invoke(currentCollection, "addPlacemark",
                new Class<?>[]{pointClass}, point);
        placemark = currentPlacemark;
        applyIcon(currentPlacemark);
        invoke(currentPlacemark, "setDirection", new Class<?>[]{float.class},
                bearingDegrees);
        invoke(currentPlacemark, "setVisible", new Class<?>[]{boolean.class}, visible);
        return currentPlacemark;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyIcon(Object currentPlacemark) throws Exception {
        if (currentPlacemark == null) return;
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object style = iconStyle;
        if (style == null) {
            style = styleClass.getConstructor().newInstance();
            Object rotation = Enum.valueOf((Class<? extends Enum>) rotationClass, "ROTATE");
            invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                    new PointF(0.5f, 0.5f));
            invoke(style, "setRotationType", new Class<?>[]{rotationClass}, rotation);
            invoke(style, "setScale", new Class<?>[]{Float.class},
                    Float.valueOf(scalePercent / 100f));
            invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
            invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
            invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
            iconStyle = style;
        }
        Object provider = imageProvider;
        if (provider == null) {
            Bitmap bitmap = createCursorBitmap();
            provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                    .invoke(null, bitmap);
            iconBitmap = bitmap;
            imageProvider = provider;
        }
        invoke(currentPlacemark, "setIcon",
                new Class<?>[]{providerClass, styleClass}, provider, style);
    }

    private Bitmap createCursorBitmap() {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(32, Math.round(48f * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float width = bitmap.getWidth();
        float height = bitmap.getHeight();
        Path path = new Path();
        path.moveTo(width * 0.50f, height * 0.06f);
        path.lineTo(width * 0.88f, height * 0.88f);
        path.lineTo(width * 0.50f, height * 0.70f);
        path.lineTo(width * 0.12f, height * 0.88f);
        path.close();

        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setColor(outlineColor);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setStrokeWidth(Math.max(2f, 2.5f * density));
        canvas.drawPath(path, outline);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(fillColor);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fill);
        return bitmap;
    }

    private boolean hasPosition() {
        return finite(latitude) && latitude >= -90d && latitude <= 90d
                && finite(longitude) && longitude >= -180d && longitude <= 180d;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static float normalizeBearing(double value) {
        if (!finite(value)) return 0f;
        double result = value % 360d;
        if (result < 0d) result += 360d;
        return (float) result;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }
}
