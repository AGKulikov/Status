/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Draws one decorative HOME backdrop; unlike HUD this surface intentionally supports a shadow. */
public final class LauncherBackdropView extends View {
    @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private LauncherBackdropStore.Backdrop backdrop;

    public LauncherBackdropView(@NonNull Context context,
                                @NonNull LauncherBackdropStore.Backdrop backdrop) {
        super(context);
        this.backdrop = backdrop.copy();
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setBackdrop(@NonNull LauncherBackdropStore.Backdrop value) {
        backdrop = value.copy();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float shadowSpace = backdrop.shadowRadiusPx
                + Math.max(Math.abs(backdrop.shadowOffsetXPx),
                Math.abs(backdrop.shadowOffsetYPx));
        float inset = Math.min(Math.max(backdrop.borderWidthPx / 2f, shadowSpace),
                Math.min(getWidth(), getHeight()) / 3f);
        RectF bounds = new RectF(inset, inset,
                Math.max(inset, getWidth() - inset),
                Math.max(inset, getHeight() - inset));
        float radius = Math.max(0f, Math.min(backdrop.cornerRadiusPx,
                Math.min(bounds.width(), bounds.height()) / 2f));

        int fill = parseColor(backdrop.fillColor, 0xFF121923);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withOpacity(fill, backdrop.fillOpacityPercent));
        if (backdrop.shadowRadiusPx > 0 && backdrop.shadowOpacityPercent > 0) {
            int shadow = parseColor(backdrop.shadowColor, Color.BLACK);
            paint.setShadowLayer(backdrop.shadowRadiusPx,
                    backdrop.shadowOffsetXPx, backdrop.shadowOffsetYPx,
                    withOpacity(shadow, backdrop.shadowOpacityPercent));
        } else {
            paint.clearShadowLayer();
        }
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.clearShadowLayer();

        if (backdrop.borderWidthPx <= 0 || backdrop.borderOpacityPercent <= 0) return;
        float half = backdrop.borderWidthPx / 2f;
        RectF borderBounds = new RectF(bounds);
        borderBounds.inset(half, half);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(backdrop.borderWidthPx);
        paint.setColor(withOpacity(parseColor(backdrop.borderColor, Color.WHITE),
                backdrop.borderOpacityPercent));
        canvas.drawRoundRect(borderBounds, Math.max(0f, radius - half),
                Math.max(0f, radius - half), paint);
    }

    @ColorInt
    private static int parseColor(@Nullable String raw, @ColorInt int fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    @ColorInt
    private static int withOpacity(@ColorInt int color, int percent) {
        int alpha = Math.round(Color.alpha(color)
                * Math.max(0, Math.min(100, percent)) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
