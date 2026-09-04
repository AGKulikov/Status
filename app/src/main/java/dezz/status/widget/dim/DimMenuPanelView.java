/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;

/** Touch-free stock-like menu rendered in the lower DIM navigation area. */
public final class DimMenuPanelView extends View {
    private static final int MNAVI_BACKGROUND = 0x00FFFFFF;
    private static final int MNAVI_SELECTED = 0xFF197BC5;
    private static final int MNAVI_SELECTED_TEXT = 0xFFFFFFFF;
    private static final int MNAVI_MUTED_TEXT = 0xFF6C7984;
    @NonNull private DimMenuPanelConfig config;
    @NonNull private List<LauncherShortcutStore.Shortcut> items = new ArrayList<>();
    @NonNull private Map<String, String> statuses = new HashMap<>();
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

    void setStatuses(@NonNull Map<String, String> source) {
        statuses = new HashMap<>(source);
        invalidate();
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (config.mnaviStyle) {
            // The reference overlay is transparent and lets the stock DIM card show through.
            // SRC is deliberate: it also clears the previously selected row on invalidation.
            canvas.drawColor(MNAVI_BACKGROUND, PorterDuff.Mode.SRC);
        }
        float outerInset = 1f;
        rect.set(outerInset, outerInset, width - outerInset, height - outerInset);
        fill.setStyle(Paint.Style.FILL);
        if (!config.mnaviStyle) {
            fill.setColor(withOpacity(parse(config.backgroundColor, 0xFF11151B),
                    config.panelOpacityPercent));
            canvas.drawRoundRect(rect, config.cornerRadiusPx, config.cornerRadiusPx, fill);
        }
        if (!config.mnaviStyle && config.borderWidthPx > 0) {
            float inset = config.borderWidthPx / 2f;
            rect.set(inset, inset, width - inset, height - inset);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(config.borderWidthPx);
            stroke.setColor(parse(config.borderColor, 0x665E718A));
            canvas.drawRoundRect(rect, config.cornerRadiusPx, config.cornerRadiusPx, stroke);
        }

        float contentPadding = config.mnaviStyle ? 0f : config.contentPaddingPx;
        float rowGap = config.mnaviStyle ? 0f : config.rowGapPx;
        float rowHeight = config.mnaviStyle ? mnaviRowHeight() : config.rowHeightPx;
        float left = contentPadding;
        float right = width - contentPadding;
        float top = contentPadding;
        if (config.mnaviStyle || config.showTitle) {
            text.setColor(config.mnaviStyle ? MNAVI_MUTED_TEXT
                    : parse(config.textColor, Color.WHITE));
            text.setTextSize(sp(config.mnaviStyle ? 14 : config.titleTextSizeSp));
            text.setFakeBoldText(true);
            Paint.FontMetrics metrics = text.getFontMetrics();
            float titlePadding = config.mnaviStyle ? dp(2) : 0f;
            float baseline = top + titlePadding - metrics.top;
            String visibleTitle = ellipsize(config.title, text,
                    Math.max(0f, right - left - (config.mnaviStyle ? 0f : dp(70))));
            float titleLeft = config.mnaviStyle
                    ? left + Math.max(0f, (right - left - text.measureText(visibleTitle)) / 2f)
                    : left;
            canvas.drawText(visibleTitle, titleLeft, baseline, text);
            if (!config.mnaviStyle && !items.isEmpty()) {
                String position = (selectedIndex + 1) + " / " + items.size();
                text.setFakeBoldText(false);
                text.setTextSize(sp(Math.max(11, config.titleTextSizeSp - 5)));
                text.setColor(parse(config.mutedTextColor, 0xFFADB7C8));
                canvas.drawText(position, right - text.measureText(position), baseline, text);
            }
            // mNavi's header is a wrap-content TextView with 2dp padding on every side and the
            // list container starts immediately below it. Paint.bottom is below the baseline.
            top = baseline + metrics.bottom + dp(config.mnaviStyle ? 2 : 8);
        }

        if (items.isEmpty()) {
            text.setFakeBoldText(false);
            text.setTextSize(sp(config.mnaviStyle ? 20
                    : Math.max(12, config.rowTextSizeSp - 4)));
            text.setColor(config.mnaviStyle ? MNAVI_MUTED_TEXT
                    : parse(config.mutedTextColor, 0xFFADB7C8));
            String empty = "Добавьте действия в настройках";
            Paint.FontMetrics metrics = text.getFontMetrics();
            float baseline = (top + height) / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(ellipsize(empty, text, right - left), left, baseline, text);
            return;
        }

        int possibleRows = Math.max(1, (int) ((height - top - contentPadding
                + rowGap) / (rowHeight + rowGap)));
        int visible = Math.max(1, Math.min(items.size(),
                Math.min(config.visibleRows, possibleRows)));
        int first = Math.max(0, Math.min(selectedIndex, items.size() - visible));
        for (int row = 0; row < visible; row++) {
            int index = first + row;
            LauncherShortcutStore.Shortcut item = items.get(index);
            float rowTop = top + row * (rowHeight + rowGap);
            float rowBottom = Math.min(height - contentPadding, rowTop + rowHeight);
            boolean selected = index == selectedIndex;
            if (selected) {
                fill.setColor(config.mnaviStyle ? MNAVI_SELECTED
                        : parse(config.selectedColor, 0xFF1478FF));
                rect.set(left, rowTop, right, rowBottom);
                float selectedRadius = config.mnaviStyle ? dp(6)
                        : Math.min(14, config.cornerRadiusPx);
                canvas.drawRoundRect(rect, selectedRadius, selectedRadius, fill);
            }
            float contentLeft = left + dp(config.mnaviStyle ? 8 : 10);
            if (!config.mnaviStyle && config.showIcons) {
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
            if (config.mnaviStyle || config.showText) {
                String status = statuses.get(item.id);
                float statusLeft = right - dp(10);
                if (status != null && !status.isEmpty()) {
                    text.setFakeBoldText(false);
                    text.setTextSize(sp(config.mnaviStyle ? 17
                            : Math.max(10, config.rowTextSizeSp - 7)));
                    text.setColor(config.mnaviStyle
                            ? (selected ? MNAVI_SELECTED_TEXT : MNAVI_MUTED_TEXT)
                            : parse(selected ? config.textColor
                                    : config.mutedTextColor, Color.LTGRAY));
                    String visibleStatus = ellipsize(status, text,
                            Math.max(0f, (right - contentLeft) * .42f));
                    float statusWidth = text.measureText(visibleStatus);
                    statusLeft = right - dp(10) - statusWidth;
                    Paint.FontMetrics statusMetrics = text.getFontMetrics();
                    float statusBaseline = rowTop + (rowBottom - rowTop) / 2f
                            - (statusMetrics.ascent + statusMetrics.descent) / 2f;
                    canvas.drawText(visibleStatus, statusLeft, statusBaseline, text);
                    statusLeft -= dp(12);
                }
                text.setFakeBoldText(config.mnaviStyle || selected);
                text.setTextSize(sp(config.mnaviStyle ? 24 : config.rowTextSizeSp));
                text.setColor(config.mnaviStyle
                        ? (selected ? MNAVI_SELECTED_TEXT : MNAVI_MUTED_TEXT)
                        : parse(selected ? config.textColor
                                : config.mutedTextColor, Color.WHITE));
                Paint.FontMetrics metrics = text.getFontMetrics();
                float baseline = config.mnaviStyle
                        // Exact overlay_list_item.xml geometry: 8dp TextView padding and the
                        // default include-font-padding top/bottom metrics.
                        ? rowTop + dp(8) - metrics.top
                        : rowTop + (rowBottom - rowTop) / 2f
                                - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(ellipsize(item.title, text,
                        Math.max(0f, statusLeft - contentLeft)),
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

    /** Height of mNavi's 24sp bold, single-line TextView with 8dp padding on all sides. */
    private float mnaviRowHeight() {
        text.setTextSize(sp(24));
        text.setFakeBoldText(true);
        Paint.FontMetrics metrics = text.getFontMetrics();
        return metrics.bottom - metrics.top + dp(16);
    }
}
