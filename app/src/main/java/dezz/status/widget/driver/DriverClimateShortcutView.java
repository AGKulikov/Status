/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.launcher.climate.ClimateFanIndicatorPolicy;

/**
 * Resolution-independent live climate icon modelled after the old Monjaro driver control.
 *
 * <p>Temperature and fan are drawn directly on Canvas, so increasing the per-button icon size
 * never magnifies a bitmap. AUTO and manual fan modes intentionally have distinct lower rows.</p>
 */
public final class DriverClimateShortcutView extends View {
    /** Keep boot/reconnect behavior identical to the main climate panel. */
    private static final long STATE_FRESH_MS = 75_000L;
    private static final String TEMP_DRIVER = "climate.temp_driver";
    private static final String FAN = "climate.fan";
    private static final Set<String> CONTROL_IDS = new LinkedHashSet<>(
            Arrays.asList(TEMP_DRIVER, FAN));

    private final CarIntegration integration;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final int foregroundColor;
    private final CarIntegration.ControlStateListener listener = this::onControlState;
    private final Runnable expiry = this::expireStaleState;

    private boolean subscribed;
    private boolean temperatureKnown;
    private double temperature;
    private long temperatureObservedAtMillis;
    private boolean fanKnown;
    private boolean fanActive;
    private int fanLevel;
    private long fanObservedAtMillis;
    @NonNull private String fanLabel = "";

    public DriverClimateShortcutView(@NonNull Context context,
                                     @NonNull CarIntegration integration,
                                     @Nullable String color) {
        super(context);
        this.integration = integration;
        foregroundColor = parseColor(color, Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Fixed sample used only by settings when no vehicle state has arrived yet. */
    public void showPreviewSample() {
        temperatureKnown = true;
        temperature = 22d;
        fanKnown = true;
        fanActive = true;
        fanLevel = 5;
        fanLabel = "5";
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!subscribed) {
            subscribed = true;
            integration.subscribeControlStates(CONTROL_IDS, listener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (subscribed) {
            subscribed = false;
            integration.unsubscribeControlStates(listener);
        }
        removeCallbacks(expiry);
        super.onDetachedFromWindow();
    }

    private void onControlState(@NonNull CarControlState state) {
        if (TEMP_DRIVER.equals(state.controlId)) {
            temperatureKnown = isFresh(state) && state.available && state.known
                    && Double.isFinite(state.value);
            if (temperatureKnown) {
                temperature = state.value;
                temperatureObservedAtMillis = state.observedAtMillis;
            } else {
                temperatureObservedAtMillis = 0;
            }
        } else if (FAN.equals(state.controlId)) {
            fanKnown = isFresh(state) && state.available && state.known;
            if (fanKnown) {
                fanActive = state.active;
                fanLevel = state.level;
                fanLabel = state.valueLabel;
                fanObservedAtMillis = state.observedAtMillis;
            } else {
                fanActive = false;
                fanLevel = 0;
                fanLabel = "";
                fanObservedAtMillis = 0;
            }
        }
        scheduleExpiry();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;
        float unit = Math.min(width, height);
        boolean showFan = fanKnown && fanActive;
        int color = showFan ? foregroundColor : muted(foregroundColor);

        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(Math.max(12f, unit * .39f));
        String temperatureText = DriverClimatePresentation.temperature(
                temperature, temperatureKnown);
        Paint.FontMetrics temperatureMetrics = textPaint.getFontMetrics();
        float temperatureCenterY = height * (showFan ? .31f : .50f);
        float temperatureBaseline = temperatureCenterY
                - (temperatureMetrics.ascent + temperatureMetrics.descent) / 2f;
        canvas.drawText(temperatureText, width / 2f, temperatureBaseline, textPaint);
        if (!showFan) return;

        ClimateFanIndicatorPolicy.Indicator indicator =
                ClimateFanIndicatorPolicy.fromConfirmedState(fanLabel, fanLevel);
        int bars = indicator.activeSegments;
        int totalBars = indicator.totalSegments;
        float rowCenterY = height * .73f;
        float glyphCenterX = width * .18f;
        drawFanGlyph(canvas, glyphCenterX, rowCenterY, unit, color);
        drawBars(canvas, width * .34f, rowCenterY, width * .62f, unit,
                bars, totalBars, color);
    }

    private void drawFanGlyph(Canvas canvas, float centerX, float centerY,
                              float unit, int color) {
        shapePaint.setColor(color);
        shapePaint.setStyle(Paint.Style.FILL);
        float hub = Math.max(1.5f, unit * .027f);
        canvas.drawCircle(centerX, centerY, hub, shapePaint);
        float bladeWidth = unit * .055f;
        float bladeHeight = unit * .13f;
        for (int index = 0; index < 4; index++) {
            canvas.save();
            canvas.rotate(index * 90f + 20f, centerX, centerY);
            shape.set(centerX - bladeWidth / 2f,
                    centerY - bladeHeight,
                    centerX + bladeWidth / 2f,
                    centerY - hub * 1.3f);
            canvas.drawRoundRect(shape, bladeWidth / 2f, bladeWidth / 2f, shapePaint);
            canvas.restore();
        }
    }

    private void drawBars(Canvas canvas, float startX, float centerY, float availableWidth,
                          float unit, int activeBars, int total, int color) {
        float gap = Math.max(.7f, unit * (total > 5 ? .010f : .018f));
        float barWidth = Math.max(.75f, (availableWidth - gap * (total - 1)) / total);
        float barHeight = Math.max(4f, unit * .115f);
        for (int index = 0; index < total; index++) {
            int alpha = index < activeBars ? Color.alpha(color)
                    : Math.max(34, Math.round(Color.alpha(color) * .23f));
            shapePaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            float left = startX + index * (barWidth + gap);
            canvas.save();
            canvas.rotate(-14f, left + barWidth / 2f, centerY);
            shape.set(left, centerY - barHeight / 2f,
                    left + barWidth, centerY + barHeight / 2f);
            canvas.drawRoundRect(shape, barWidth * .35f, barWidth * .35f, shapePaint);
            canvas.restore();
        }
    }

    private static int parseColor(@Nullable String raw, int fallback) {
        if (raw == null || "none".equalsIgnoreCase(raw)) return fallback;
        try {
            return Color.parseColor(raw);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int muted(int color) {
        int alpha = Math.max(36, Math.round(Color.alpha(color) * .42f));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static boolean isFresh(@NonNull CarControlState state) {
        return isFresh(state.observedAtMillis);
    }

    private static boolean isFresh(long observedAtMillis) {
        if (observedAtMillis <= 0) return false;
        long age = System.currentTimeMillis() - observedAtMillis;
        return age >= 0 && age <= STATE_FRESH_MS;
    }

    private void scheduleExpiry() {
        removeCallbacks(expiry);
        if (temperatureObservedAtMillis <= 0 && fanObservedAtMillis <= 0) return;
        long oldest = temperatureObservedAtMillis <= 0 ? fanObservedAtMillis
                : fanObservedAtMillis <= 0 ? temperatureObservedAtMillis
                : Math.min(temperatureObservedAtMillis, fanObservedAtMillis);
        long remaining = STATE_FRESH_MS
                - Math.max(0, System.currentTimeMillis() - oldest);
        postDelayed(expiry, Math.max(1, remaining + 1));
    }

    private void expireStaleState() {
        if (temperatureKnown && !isFresh(temperatureObservedAtMillis)) {
            temperatureKnown = false;
            temperatureObservedAtMillis = 0;
        }
        if (fanKnown && !isFresh(fanObservedAtMillis)) {
            fanKnown = false;
            fanActive = false;
            fanLevel = 0;
            fanLabel = "";
            fanObservedAtMillis = 0;
        }
        invalidate();
        scheduleExpiry();
    }
}
