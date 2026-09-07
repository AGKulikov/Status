/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/** Appearance adapter for the verified Navigator 30.3.0 native lane balloon. */
final class LaneGuidanceAppearance {
    private static final String TAG = "NatroLaneAppearance";
    final String cardColor, signsColor, borderColor;
    final int borderWidth, cornerRadius;

    LaneGuidanceAppearance(NavigationMapProfile profile) {
        cardColor = profile.laneGuidanceCardColor;
        signsColor = profile.laneGuidanceSignsColor;
        borderColor = profile.laneGuidanceBorderColor;
        borderWidth = profile.laneGuidanceBorderWidthPx;
        cornerRadius = profile.laneGuidanceCornerRadiusPx;
    }

    boolean same(LaneGuidanceAppearance other) {
        return other != null && Objects.equals(cardColor, other.cardColor)
                && Objects.equals(signsColor, other.signsColor)
                && Objects.equals(borderColor, other.borderColor)
                && borderWidth == other.borderWidth && cornerRadius == other.cornerRadius;
    }

    void configureFactory(Object factory) throws Exception {
        // Change the native palette before LaneSignContainerBuilder builds the original layers.
        setDayNight(factory, "backgroundColor", cardColor);
        setDayNight(factory, "laneColor", signsColor);
        if (cornerRadius < 0) return;
        Field field = field(factory, "balloonParams");
        Object params = field.get(factory);
        String[] getters = {"getSizeCornerLeg", "getSizeCornerLegInnerPart",
                "getWidthCenterLeg", "getHeightCenterLeg", "getLegOffset"};
        Object[] values = new Object[6];
        for (int i = 0; i < getters.length; i++) values[i] = call(params, getters[i], new Class<?>[0]);
        values[5] = (float) cornerRadius;
        Object copy = params.getClass().getConstructor(float.class, float.class, float.class,
                float.class, float.class, float.class).newInstance(values);
        field.set(factory, copy);
    }

    void configureTexture(Object factory) throws Exception {
        if (cornerRadius < 0 && cardColor == null) return;
        View view = (View) field(factory, "view").get(factory);
        Drawable original = view.getBackground();
        if (cardColor != null && Color.alpha(Color.parseColor(cardColor)) < 255) {
            // Native BalloonTexture already fills body and leg; avoid painting alpha twice.
            original = original.mutate();
            original.setAlpha(0);
            view.setBackground(original);
        }
        if (cornerRadius < 0) return;
        if (!(original instanceof GradientDrawable)) {
            throw new IllegalStateException("Unexpected native lane background");
        }
        // The same radius is used by the native body path and by its original background.
        GradientDrawable background = (GradientDrawable) original.mutate();
        background.setCornerRadius(cornerRadius);
        view.setBackground(background);
    }

    Object createImage(Object texture, Object anchor, float scale) throws Exception {
        Object provider = call(texture, "create", new Class<?>[]{anchor.getClass()}, anchor);
        if (borderWidth <= 0) return provider;
        try {
            Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
            Bitmap original = (Bitmap) providerClass.getMethod("getImage").invoke(provider);
            View view = (View) call(texture, "getView", new Class<?>[]{anchor.getClass()}, anchor);
            PointF bodySize = (PointF) call(texture, "getBodySize", new Class<?>[]{View.class}, view);
            PointF origin = (PointF) call(texture, "bodyTopLeftCorner",
                    new Class<?>[]{anchor.getClass()}, anchor);
            // This factory has no shadow, so native texture and body coordinates share origin.
            if (field(texture, "shadow").get(texture) != null) {
                throw new IllegalStateException("Unexpected native lane shadow");
            }
            Path outline = new Path((Path) call(texture, "getBodyPath",
                    new Class<?>[]{PointF.class, View.class}, origin, view));
            Path leg = (Path) call(texture, "pathForLeg",
                    new Class<?>[]{PointF.class, PointF.class, anchor.getClass()},
                    new PointF(), bodySize, anchor);
            if (!outline.op(leg, Path.Op.UNION)) throw new IllegalStateException("Native contour union failed");
            Bitmap result = original.copy(Bitmap.Config.ARGB_8888, true);
            if (result == null) return provider;
            Canvas canvas = new Canvas(result);
            canvas.scale(scale, scale);
            canvas.clipPath(outline);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(2f * Math.min(borderWidth, Math.min(bodySize.x, bodySize.y) / 4f));
            stroke.setColor(borderColor == null
                    ? signsColor == null ? Color.WHITE : Color.parseColor(signsColor)
                    : Color.parseColor(borderColor));
            canvas.drawPath(outline, stroke);
            // Inside-only outline: dimensions, anchor, all eight collision footprints stay native.
            return providerClass.getMethod("fromBitmap", Bitmap.class).invoke(null, result);
        } catch (Exception unsupported) {
            Log.w(TAG, "Native lane outline unavailable; keeping original image", unsupported);
            return provider;
        }
    }

    private static void setDayNight(Object factory, String name, String color) throws Exception {
        if (color == null) return;
        Field target = field(factory, name);
        int value = Color.parseColor(color);
        target.set(factory, target.getType().getConstructor(int.class, int.class).newInstance(value, value));
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field found = type.getDeclaredField(name); found.setAccessible(true); return found; }
            catch (NoSuchFieldException next) { /* continue to the native texture base */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException next) { /* continue to the native texture base */ }
        }
        throw new NoSuchMethodException(name);
    }
}
