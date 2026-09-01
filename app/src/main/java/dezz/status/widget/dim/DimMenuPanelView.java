/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;

/** Touch-free stock-like menu rendered in the lower DIM navigation area. */
public final class DimMenuPanelView extends View {
    @NonNull private DimMenuPanelConfig config;
    @NonNull private List<LauncherShortcutStore.Shortcut> items = new ArrayList<>();
    private int selectedIndex;
    @NonNull private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final RectF rect = new RectF();

    public DimMenuPanelView(@NonNull Context context, @NonNull DimMenuPanelConfig config,
                            @NonNull List<LauncherShortcutStore.Shortcut> items) {
        super(context);
        this.config = config.copy();
        setItems(items);
        setFocusable(false);
        setClickable(false);
    }

    public void update(@NonNull DimMenuPanelConfig next,
                       @NonNull List<LauncherShortcutStore.Shortcut> nextItems,
                       int selection) {
        config = next.copy();
        items = copies(nextItems);
        selectedIndex = boundedSelection(selection);
        invalidate();
    }

    void setItems(@NonNull List<LauncherShortcutStore.Shortcut> source) {
        items = copies(source);
        selectedIndex = boundedSelection(selectedIndex);
        invalidate();
    }

    void setSelectedIndex(int value) {
        selectedIndex = boundedSelection(value);
        invalidate();
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        rect.set(1f, 1f, width - 1f, height - 1f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withOpacity(parse(config.backgroundColor, 0xFF11151B),
                config.panelOpacityPercent));
        canvas.drawRoundRect(rect, config.cornerRadiusPx, config.cornerRadiusPx, fill);
        if (config.borderWidthPx > 0) {
            float inset = config.borderWidthPx / 2f;
            rect.set(inset, inset, width - inset, height - inset);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(config.borderWidthPx);
            stroke.setColor(parse(config.borderColor, 0x665E718A));
            canvas.drawRoundRect(rect, config.cornerRadiusPx, config.cornerRadiusPx, stroke);
        }

        float left = config.contentPaddingPx;
        float right = width - config.contentPaddingPx;
        float top = config.contentPaddingPx;
        if (config.showTitle) {
            text.setColor(parse(config.textColor, Color.WHITE));
            text.setTextSize(sp(config.titleTextSizeSp));
            text.setFakeBoldText(true);
            Paint.FontMetrics metrics = text.getFontMetrics();
            float baseline = top - metrics.top;
            canvas.drawText(ellipsize(config.title, text,
                    Math.max(0f, right - left - dp(70))), left, baseline, text);
            if (!items.isEmpty()) {
                String position = (selectedIndex + 1) + " / " + items.size();
                text.setFakeBoldText(false);
                text.setTextSize(sp(Math.max(11, config.titleTextSizeSp - 5)));
                text.setColor(parse(config.mutedTextColor, 0xFFADB7C8));
                canvas.drawText(position, right - text.measureText(position), baseline, text);
            }
            top = baseline - metrics.bottom + dp(8);
        }

        if (items.isEmpty()) {
            text.setFakeBoldText(false);
            text.setTextSize(sp(Math.max(12, config.rowTextSizeSp - 4)));
            text.setColor(parse(config.mutedTextColor, 0xFFADB7C8));
            String empty = "Добавьте действия в настройках";
            Paint.FontMetrics metrics = text.getFontMetrics();
            float baseline = (top + height) / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(ellipsize(empty, text, right - left), left, baseline, text);
            return;
        }

        int possibleRows = Math.max(1, (int) ((height - top - config.contentPaddingPx
                + config.rowGapPx) / (config.rowHeightPx + config.rowGapPx)));
        int visible = Math.max(1, Math.min(items.size(),
                Math.min(config.visibleRows, possibleRows)));
        int first = Math.max(0, Math.min(selectedIndex, items.size() - visible));
        for (int row = 0; row < visible; row++) {
            int index = first + row;
            LauncherShortcutStore.Shortcut item = items.get(index);
            float rowTop = top + row * (config.rowHeightPx + config.rowGapPx);
            float rowBottom = Math.min(height - config.contentPaddingPx,
                    rowTop + config.rowHeightPx);
            boolean selected = index == selectedIndex;
            if (selected) {
                fill.setColor(parse(config.selectedColor, 0xFF1478FF));
                rect.set(left, rowTop, right, rowBottom);
                canvas.drawRoundRect(rect, Math.min(14, config.cornerRadiusPx),
                        Math.min(14, config.cornerRadiusPx), fill);
            }
            float contentLeft = left + dp(10);
            if (config.showIcons) {
                int icon = Math.min(config.iconSizePx,
                        Math.max(16, (int) (rowBottom - rowTop - dp(8))));
                int iconTop = Math.round(rowTop + (rowBottom - rowTop - icon) / 2f);
                Drawable drawable = LauncherIconResolver.resolve(getContext(), item,
                        selected ? config.textColor : config.mutedTextColor);
                if (drawable != null) {
                    drawable.setBounds(Math.round(contentLeft), iconTop,
                            Math.round(contentLeft) + icon, iconTop + icon);
                    drawable.draw(canvas);
                }
                contentLeft += icon + dp(12);
            }
            if (config.showText) {
                text.setFakeBoldText(selected);
                text.setTextSize(sp(config.rowTextSizeSp));
                text.setColor(parse(selected ? config.textColor
                        : config.mutedTextColor, Color.WHITE));
                Paint.FontMetrics metrics = text.getFontMetrics();
                float baseline = rowTop + (rowBottom - rowTop) / 2f
                        - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(ellipsize(item.title, text,
                        Math.max(0f, right - contentLeft - dp(10))),
                        contentLeft, baseline, text);
            }
        }
    }

    @NonNull
    private static List<LauncherShortcutStore.Shortcut> copies(
            @NonNull List<LauncherShortcutStore.Shortcut> source) {
        List<LauncherShortcutStore.Shortcut> result = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut value : source) result.add(value.copy());
        return result;
    }

    private int boundedSelection(int value) {
        return items.isEmpty() ? 0 : Math.max(0, Math.min(items.size() - 1, value));
    }

    @ColorInt
    private static int parse(String value, @ColorInt int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    @ColorInt
    private static int withOpacity(@ColorInt int color, int percent) {
        int alpha = Math.round(Color.alpha(color)
                * Math.max(0, Math.min(100, percent)) / 100f);
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @NonNull
    private static String ellipsize(@NonNull String value, @NonNull Paint paint, float width) {
        if (paint.measureText(value) <= width) return value;
        String ellipsis = "…";
        float available = Math.max(0f, width - paint.measureText(ellipsis));
        int count = paint.breakText(value, true, available, null);
        return value.substring(0, Math.max(0, count)) + ellipsis;
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
