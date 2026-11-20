/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.*;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;

/** Immutable display layers captured during the original 30.3.0 balloon layout. */
final class CombinedLaneArtwork {
    private final Picture content;
    private final Path stockBody, leg;
    private final RectF body;
    private final Path roadSigns = new Path();
    private final float width, height;
    private final float scale;
    private final int stockColor;

    private CombinedLaneArtwork(Object source, Bitmap original) throws Exception {
        // Combined views delegate to the current JointBalloonContainer. Standalone
        // ManeuverBalloonViewImpl inherits BalloonView directly (30.3.0 classes8).
        Object container;
        try { container = call(source, "getContainer"); }
        catch (NoSuchMethodException standalone) { container = source; }
        ViewGroup root = (ViewGroup) call(container, "getView");
        Object texture = call(container, "getTexture");
        scale = ((Number) call(texture, "getScale")).floatValue();
        if (!Float.isFinite(scale) || scale <= 0f) throw new IllegalArgumentException("Native balloon scale");
        width = original.getWidth() / scale;
        height = original.getHeight() / scale;
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
        content = new Picture();
        Canvas canvas = content.beginRecording((int)Math.ceil(width), (int)Math.ceil(height));
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
        content.endRecording();
        // Both standalone layout variants contain DirectionSignView. Discover them
        // by their verified class, without depending on obfuscated binding fields.
        protectRoadSigns(root, root, origin);
        try { protectRoadSign(root, (View) call(container, "getRoadsignContainer"), origin); }
        catch (NoSuchMethodException standalone) { /* covered by the view walk */ }
    }

    private void protectRoadSigns(ViewGroup root, View view, PointF origin) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.getClass().getName().equals("ru.yandex.yandexnavi.ui.guidance.context.DirectionSignView")) {
            protectRoadSign(root, view, origin);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) protectRoadSigns(root, group.getChildAt(i), origin);
        }
    }

    private void protectRoadSign(ViewGroup root, View signs, PointF origin) {
        Rect bounds = new Rect();
        if (signs != null && signs.getVisibility() == View.VISIBLE) {
            signs.getDrawingRect(bounds);
            root.offsetDescendantRectToMyCoords(signs, bounds);
        }
        RectF box = new RectF(bounds);
        box.offset(origin.x, origin.y);
        roadSigns.addRect(box, Path.Direction.CW);
    }

    static CombinedLaneArtwork capture(Object view, Bitmap original) {
        try { return new CombinedLaneArtwork(view, original); }
        catch (Exception unsupported) {
            android.util.Log.w("NatroLaneAppearance", "Cannot capture native balloon layers", unsupported);
            return null;
        }
    }

    Bitmap render(Bitmap original, LaneGuidanceAppearance style) {
        return render(original, style, scale);
    }

    Bitmap render(Bitmap original, LaneGuidanceAppearance style, float targetScale) {
        if (style.cardColor == null && style.signsColor == null && style.borderWidth <= 0 && style.cornerRadius < 0)
            if (targetScale == scale) return original;
        int w = Math.max(1, Math.round(width * targetScale)), h = Math.max(1, Math.round(height * targetScale));
        if (!Float.isFinite(targetScale) || targetScale <= 0 || (long)w*h > 4*1024*1024)
            throw new IllegalArgumentException("Target balloon size");
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.save();
        // Record native draw commands once, rasterize directly at this screen's
        // physical scale. Never enlarge an already rendered primary-screen bitmap.
        canvas.scale(targetScale, targetScale);
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
        Paint glyphs = new Paint();
        if (style.signsColor != null) {
            int color = Color.parseColor(style.signsColor);
            // Retain intensity and alpha differences between recommended and secondary lanes.
            glyphs.setColorFilter(new LightingColorFilter(color | 0xff000000, 0));
            glyphs.setAlpha(Color.alpha(color));
            canvas.save();
            canvas.clipPath(roadSigns, Region.Op.DIFFERENCE);
            canvas.saveLayer(0, 0, width, height, glyphs);
            canvas.drawPicture(content);
            canvas.restore();
            canvas.restore();
            canvas.clipPath(roadSigns);
        }
        canvas.drawPicture(content);
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
