/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.*;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;

/** Immutable display layers captured during the original 30.3.0 balloon layout. */
final class CombinedLaneArtwork {
    private final Bitmap content;
    private final Path stockBody, leg;
    private final RectF body;
    private final RectF roadSign;
    private final float scale;
    private final int stockColor;

    private CombinedLaneArtwork(Object source, Bitmap original) throws Exception {
        Object container = call(source, "getContainer");
        ViewGroup root = (ViewGroup) call(container, "getView");
        Object texture = call(container, "getTexture");
        scale = ((Number) call(texture, "getScale")).floatValue();
        stockColor = ((Number) call(texture, "getLegColor")).intValue();
        Object rects = call(texture, "getRects");
        RectF balloon = (RectF) call(rects, "getBalloonRect");
        RectF envelope = (RectF) call(rects, "getEnvelopingRect");
        PointF offset = new PointF(balloon.left - envelope.left, balloon.top - envelope.top);
        PointF origin = (PointF) call(texture, "bodyTopLeftCorner");
        origin.offset(offset.x, offset.y);
        PointF size = (PointF) call(texture, "getBodySize");
        body = new RectF(origin.x, origin.y, origin.x + size.x, origin.y + size.y);
        stockBody = (Path) geometry(texture, "getBodyPath", origin);
        leg = "NONE".equals(((Enum<?>) call(container, "getLegPosition")).name()) ? new Path()
                : (Path) geometry(texture, "pathForLeg", offset, size);
        content = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(content);
        canvas.scale(scale, scale);
        canvas.translate(origin.x, origin.y);
        // Capture only the native children. The root's drawable is the card background.
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            canvas.save();
            canvas.translate(child.getLeft() - root.getScrollX(), child.getTop() - root.getScrollY());
            canvas.concat(child.getMatrix());
            child.draw(canvas);
            canvas.restore();
        }
        View signs = (View) call(container, "getRoadsignContainer");
        Rect bounds = new Rect();
        if (signs != null && signs.getVisibility() == View.VISIBLE) {
            signs.getDrawingRect(bounds);
            root.offsetDescendantRectToMyCoords(signs, bounds);
        }
        roadSign = new RectF(bounds);
        roadSign.offset(origin.x, origin.y);
        roadSign.set(roadSign.left * scale, roadSign.top * scale, roadSign.right * scale, roadSign.bottom * scale);
    }

    static CombinedLaneArtwork capture(Object view, Bitmap original) {
        try { return new CombinedLaneArtwork(view, original); }
        catch (Exception unsupported) {
            android.util.Log.w("NatroLaneAppearance", "Cannot capture combined balloon layers", unsupported);
            return null;
        }
    }

    Bitmap render(Bitmap original, LaneGuidanceAppearance style) {
        if (style.cardColor == null && style.signsColor == null && style.borderWidth <= 0 && style.cornerRadius < 0)
            return original;
        Bitmap result = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.save();
        canvas.scale(scale, scale);
        Path outline = new Path();
        if (style.cornerRadius < 0) outline.set(stockBody);
        else outline.addRoundRect(body, style.cornerRadius, style.cornerRadius, Path.Direction.CW);
        outline.op(leg, Path.Op.UNION);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(style.cardColor == null ? stockColor : Color.parseColor(style.cardColor));
        canvas.drawPath(outline, paint);
        canvas.clipPath(outline);
        if (style.borderWidth > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.min(2f * style.borderWidth, Math.min(body.width(), body.height()) / 2f));
            String color = style.borderColor == null ? style.signsColor : style.borderColor;
            paint.setColor(color == null ? Color.WHITE : Color.parseColor(color));
            canvas.drawPath(outline, paint);
        }
        canvas.scale(1f / scale, 1f / scale);
        Rect dst = new Rect(0, 0, result.getWidth(), result.getHeight());
        Paint glyphs = new Paint();
        if (style.signsColor != null) {
            int color = Color.parseColor(style.signsColor);
            // Retain intensity and alpha differences between recommended and secondary lanes.
            glyphs.setColorFilter(new LightingColorFilter(color | 0xff000000, 0));
            glyphs.setAlpha(Color.alpha(color));
            canvas.save();
            canvas.clipRect(roadSign, Region.Op.DIFFERENCE);
            canvas.drawBitmap(content, null, dst, glyphs);
            canvas.restore();
            canvas.clipRect(roadSign);
        }
        canvas.drawBitmap(content, null, dst, null);
        canvas.restore();
        return result;
    }

    private static Object call(Object object, String name) throws Exception {
        return geometry(object, name);
    }

    private static Object geometry(Object target, String name, PointF... points) throws Exception {
        Class<?>[] types = new Class<?>[points.length];
        java.util.Arrays.fill(types, PointF.class);
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, (Object[]) points);
            } catch (NoSuchMethodException inherited) { /* try the parent class */ }
        }
        throw new NoSuchMethodException(name);
    }
}
