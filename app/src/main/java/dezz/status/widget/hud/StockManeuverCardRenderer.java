/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.json.JSONObject;
import java.util.List;
import dezz.status.widget.navigation.StockManeuverCardState;
import dezz.status.widget.navigation.StockManeuverResources;

/** Shared native-resource card content for HUD and instrument surfaces. */
public final class StockManeuverCardRenderer {
    private final StockManeuverResources resources;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public StockManeuverCardRenderer(Context context) { resources = new StockManeuverResources(context); }
    public boolean available(StockManeuverCardState state) { return state.hasMain() && resources.available(state); }
    public void drawMain(Canvas canvas, StockManeuverCardState state, RectF bounds, int alpha) {
        if (!available(state)) return;
        if (!state.lanes.isEmpty()) resources.drawLanes(canvas, state.lanes, bounds, alpha);
        else if (state.imageVisible) {
            resources.draw(canvas, state.image, bounds, alpha);
            if (!state.via.isEmpty()) label(canvas, state.via, bounds, Color.WHITE,
                    alpha, bounds.height() * .36f, 600, 1, Layout.Alignment.ALIGN_CENTER);
        }
    }
    public void draw(Canvas canvas, StockManeuverCardState state, RectF bounds,
                     JSONObject options, float scale, int fontSize, int weight, int color, int detailColor) {
        if (!available(state) || bounds.isEmpty()) return;
        int alpha = Color.alpha(color);
        color |= 0xFF000000;
        boolean road = !state.nextRoad.isEmpty() && options.optBoolean("showDirection", true);
        boolean signs = !state.signs.isEmpty() && options.optBoolean("showRoadBadge", true);
        boolean following = !state.followingSigns.isEmpty() && options.optBoolean("showRoadBadge", true);
        boolean auxiliary = state.hasAuxiliary();
        int rows = (road ? 1 : 0) + (signs ? 1 : 0) + (following ? 1 : 0) + (auxiliary ? 1 : 0);
        float gap = Math.max(0, options.optInt("textRowGapPx", 2) * scale);
        gap = Math.min(gap, bounds.height() / Math.max(1, rows * 2 + 1));
        float available = Math.max(0, bounds.height() - rows * gap);
        float mainHeight = rows == 0 ? available : available * clamp(options.optInt("distanceAreaPercent", 56), 20, 80) / 100f;
        float detailHeight = rows == 0 ? 0 : (available - mainHeight) / rows;
        RectF main = new RectF(bounds.left, bounds.top, bounds.right, bounds.top + mainHeight);
        RectF icon = new RectF(main), distance = new RectF(main);
        float fraction = clamp(options.optInt("arrowAreaPercent", 38), 10, 75) / 100f;
        float arrowGap = Math.max(0, options.optInt("arrowTextGapPx", 6) * scale);
        String layout = options.optString("arrowLayout", "LEFT");
        if (layout.equals("TOP") || layout.equals("BOTTOM")) {
            if (layout.equals("TOP")) { icon.bottom = main.top + main.height() * fraction - arrowGap / 2; distance.top = icon.bottom + arrowGap; }
            else { icon.top = main.bottom - main.height() * fraction + arrowGap / 2; distance.bottom = icon.top - arrowGap; }
        } else if (layout.equals("RIGHT")) {
            icon.left = main.right - main.width() * fraction + arrowGap / 2; distance.right = icon.left - arrowGap;
        } else { icon.right = main.left + main.width() * fraction - arrowGap / 2; distance.left = icon.right + arrowGap; }
        icon = inset(icon, options, "arrow", 3, scale);
        float factor = clamp(options.optInt("sourceIconScalePercent", 100), 25, 250) / 100f;
        float w = icon.width() * factor, h = icon.height() * factor;
        icon.set(icon.centerX() - w / 2, icon.centerY() - h / 2, icon.centerX() + w / 2, icon.centerY() + h / 2);
        if (!icon.intersect(main)) icon.setEmpty();
        int saved = canvas.save();
        try {
            canvas.clipRect(bounds);
            drawMain(canvas, state, icon, alpha);
            label(canvas, state.distance, inset(distance, options, "text", 0, scale), color, alpha,
                    options.optInt("distanceFontSizeSp", fontSize) * scale, Math.max(600, weight), 1, Layout.Alignment.ALIGN_NORMAL);
            float y = main.bottom + gap;
            if (road) {
                RectF row = new RectF(bounds.left, y, bounds.right, y + detailHeight);
                label(canvas, state.nextRoad, inset(row, options, "text", 0, scale), detailColor, 255,
                        options.optInt("directionFontSizeSp", Math.max(8, fontSize / 2)) * scale,
                        weight, 2, Layout.Alignment.ALIGN_NORMAL);
                y += detailHeight + gap;
            }
            if (signs) {
                drawSigns(canvas, state.signs, new RectF(bounds.left, y, bounds.right, y + detailHeight), options, scale, fontSize, weight, alpha);
                y += detailHeight + gap;
            }
            if (auxiliary) {
                RectF row = new RectF(bounds.left, y, bounds.right, y + detailHeight);
                paint.setStyle(Paint.Style.FILL);
                int background = optionColor(options, "auxiliaryColor", 0xE60B4DB5);
                paint.setColor((background & 0xFFFFFF) | Math.round(Color.alpha(background) * alpha / 255f) << 24);
                canvas.drawRoundRect(row, Math.min(7 * scale, row.height() / 4), Math.min(7 * scale, row.height() / 4), paint);
                RectF content = inset(row, options, "text", 0, scale);
                if (!state.auxiliaryLanes.isEmpty()) resources.drawLanes(canvas, state.auxiliaryLanes, content, alpha);
                else {
                    if (!state.auxiliaryImage.isEmpty()) {
                        float side = Math.min(content.height(), content.width() * .3f);
                        resources.draw(canvas, state.auxiliaryImage, new RectF(content.left, content.top, content.left + side, content.bottom), alpha);
                        content.left += side + gap;
                    }
                    label(canvas, state.auxiliaryText, content, color, alpha,
                            options.optInt("auxiliaryFontSizeSp", Math.max(8, fontSize / 2)) * scale, weight, 1, Layout.Alignment.ALIGN_NORMAL);
                }
                y += detailHeight + gap;
            }
            if (following) drawSigns(canvas, state.followingSigns, new RectF(bounds.left, y, bounds.right, y + detailHeight), options, scale, fontSize, weight, alpha);
        } finally { canvas.restoreToCount(saved); }
    }
    private void drawSigns(Canvas canvas, List<StockManeuverCardState.Sign> signs, RectF bounds,
                           JSONObject options, float scale, int size, int weight, int alpha) {
        if (bounds.isEmpty()) return;
        float font = Math.min(options.optInt("roadBadgeFontSizeSp", Math.max(8, size / 2)) * scale, bounds.height() * .62f);
        text.setTextSize(font);
        float total = 0, gap = Math.min(3 * scale, bounds.width() / Math.max(1, signs.size() * 4));
        for (StockManeuverCardState.Sign sign : signs) total += sign.image.isEmpty() ? text.measureText(sign.text) + font : bounds.height();
        float factor = Math.min(1, Math.max(0, bounds.width() - gap * (signs.size() - 1)) / Math.max(1, total));
        float x = bounds.left;
        for (StockManeuverCardState.Sign sign : signs) {
            float width = (sign.image.isEmpty() ? text.measureText(sign.text) + font : bounds.height()) * factor;
            RectF box = new RectF(x, bounds.top, x + width, bounds.bottom);
            paint.setColor((sign.background & 0xFFFFFF) | Math.round(Color.alpha(sign.background) * alpha / 255f) << 24);
            canvas.drawRoundRect(box, Math.min(5 * scale, bounds.height() / 5), Math.min(5 * scale, bounds.height() / 5), paint);
            if (!sign.image.isEmpty()) resources.draw(canvas, sign.image, box, alpha, sign.color, true);
            else label(canvas, sign.text, box, sign.color, alpha, font * factor, weight, 1, Layout.Alignment.ALIGN_CENTER);
            x += width + gap;
        }
    }
    private void label(Canvas canvas, String value, RectF bounds, int color, int alpha,
                       float size, int weight, int lines, Layout.Alignment alignment) {
        if (value.isEmpty() || bounds.width() < 1 || bounds.height() < 1) return;
        text.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), clamp(weight, 100, 900), false));
        text.setColor((color & 0xFFFFFF) | Math.round(Color.alpha(color) * alpha / 255f) << 24);
        text.setTextSize(Math.max(1, Math.min(size, bounds.height() * .72f)));
        if (lines > 1 && text.measureText(value) > bounds.width())
            text.setTextSize(Math.max(1, Math.min(text.getTextSize(), bounds.height() * .45f)));
        else lines = 1;
        if (lines == 1 && text.measureText(value) > bounds.width()) text.setTextSize(Math.max(1, text.getTextSize() * bounds.width() / text.measureText(value)));
        StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), text, Math.max(1, (int) bounds.width()))
                .setAlignment(alignment).setIncludePad(false).setMaxLines(lines).build();
        int save = canvas.save();
        canvas.clipRect(bounds); canvas.translate(bounds.left, bounds.centerY() - layout.getHeight() / 2f);
        layout.draw(canvas); canvas.restoreToCount(save);
    }
    private static RectF inset(RectF bounds, JSONObject options, String prefix, int fallback, float scale) {
        return new RectF(bounds.left + Math.max(0, options.optInt(prefix + "PaddingLeftPx", fallback)) * scale,
                bounds.top + Math.max(0, options.optInt(prefix + "PaddingTopPx", fallback)) * scale,
                bounds.right - Math.max(0, options.optInt(prefix + "PaddingRightPx", fallback)) * scale,
                bounds.bottom - Math.max(0, options.optInt(prefix + "PaddingBottomPx", fallback)) * scale);
    }
    private static int optionColor(JSONObject options, String key, int fallback) {
        Object value = options.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try { return value instanceof String ? Color.parseColor((String) value) : fallback; }
        catch (IllegalArgumentException invalid) { return fallback; }
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
