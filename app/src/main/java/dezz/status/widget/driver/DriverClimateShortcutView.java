/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
 * <p>Temperature, fan and the stock-style outlet figure are drawn directly on one compact Canvas.
 * The optional AUTO/outlet detail shares the fan row and never changes shortcut height.</p>
 */
public final class DriverClimateShortcutView extends View {
    /** Keep boot/reconnect behavior identical to the main climate panel. */
    private static final long STATE_FRESH_MS = 75_000L;
    private static final String TEMP_DRIVER = "climate.temp_driver";
    private static final String FAN = "climate.fan";
    private static final String AUTO = "climate.auto";
    private static final String AIRFLOW = "climate.airflow";
    private static final Set<String> CONTROL_IDS = new LinkedHashSet<>(
            Arrays.asList(TEMP_DRIVER, FAN, AUTO, AIRFLOW));

    private final CarIntegration integration;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final Path path = new Path();
    private final int foregroundColor;
    private final boolean showMode;
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
    private boolean autoKnown;
    private boolean autoActive;
    private long autoObservedAtMillis;
    private boolean airflowKnown;
    private long airflowObservedAtMillis;
    @NonNull private String airflowLabel = "";

    public DriverClimateShortcutView(@NonNull Context context,
                                     @NonNull CarIntegration integration,
                                     @Nullable String color) {
        this(context, integration, color, false);
    }

    public DriverClimateShortcutView(@NonNull Context context,
                                     @NonNull CarIntegration integration,
                                     @Nullable String color,
                                     boolean showMode) {
        super(context);
        this.integration = integration;
        this.showMode = showMode;
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
        autoKnown = true;
        autoActive = false;
        airflowKnown = true;
        airflowLabel = "Лицо + ноги";
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
        } else if (AUTO.equals(state.controlId)) {
            autoKnown = isFresh(state) && state.available && state.known;
            if (autoKnown) {
                autoActive = state.active;
                autoObservedAtMillis = state.observedAtMillis;
            } else {
                autoActive = false;
                autoObservedAtMillis = 0;
            }
        } else if (AIRFLOW.equals(state.controlId)) {
            airflowKnown = isFresh(state) && state.available && state.known;
            if (airflowKnown) {
                airflowLabel = state.valueLabel;
                airflowObservedAtMillis = state.observedAtMillis;
            } else {
                airflowLabel = "";
                airflowObservedAtMillis = 0;
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

        String temperatureText = DriverClimatePresentation.temperature(
                temperature, temperatureKnown);
        drawTemperature(canvas, temperatureText, width / 2f,
                height * (showFan ? .34f : .50f), unit, color);
        if (!showFan) return;

        ClimateFanIndicatorPolicy.Indicator indicator =
                ClimateFanIndicatorPolicy.fromConfirmedState(fanLabel, fanLevel);
        int bars = indicator.activeSegments;
        int totalBars = indicator.totalSegments;
        float rowCenterY = height * .72f;
        float glyphCenterX = width * .13f;
        drawFanGlyph(canvas, glyphCenterX, rowCenterY, unit, color);
        float barsStart = width * .24f;
        float barsWidth = width * (showMode ? .47f : .71f);
        drawBars(canvas, barsStart, rowCenterY, barsWidth, unit,
                bars, totalBars, color);
        if (!showMode) return;

        // The fan descriptor is switched only after ECARX confirms the corresponding source.
        // Derive both the scale and mode glyph from that same snapshot so asynchronous AUTO and
        // FAN callbacks cannot produce a one-frame visual twitch.
        boolean automatic = indicator.automatic;
        float modeCenterX = width * .855f;
        if (automatic) {
            drawAuto(canvas, modeCenterX, rowCenterY, unit, color);
        } else if (airflowKnown) {
            drawStockAirflow(canvas, modeCenterX, rowCenterY, unit,
                    DriverClimatePresentation.airflowTargets(airflowLabel), color);
        }
    }

    private void drawTemperature(@NonNull Canvas canvas, @NonNull String value,
                                 float centerX, float centerY, float unit, int color) {
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(color);
        if ("—".equals(value) || value.indexOf('.') < 0) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(Math.max(12f, unit * .38f));
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            canvas.drawText(value, centerX,
                    centerY - (metrics.ascent + metrics.descent) / 2f, textPaint);
            return;
        }

        int decimal = value.indexOf('.');
        String whole = value.substring(0, decimal);
        String fraction = value.substring(decimal);
        float wholeSize = Math.max(12f, unit * .38f);
        float fractionSize = Math.max(8f, unit * .22f);
        textPaint.setTextSize(wholeSize);
        float wholeWidth = textPaint.measureText(whole);
        Paint.FontMetrics wholeMetrics = textPaint.getFontMetrics();
        float baseline = centerY - (wholeMetrics.ascent + wholeMetrics.descent) / 2f;
        textPaint.setTextSize(fractionSize);
        float fractionWidth = textPaint.measureText(fraction);
        float left = centerX - (wholeWidth + fractionWidth) / 2f;

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(wholeSize);
        canvas.drawText(whole, left, baseline, textPaint);
        textPaint.setTextSize(fractionSize);
        canvas.drawText(fraction, left + wholeWidth,
                baseline - unit * .015f, textPaint);
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
        int count = Math.max(1, total);
        // Both the five-position AUTO scale and nine-position manual scale occupy the exact same
        // envelope. Only segment count/fill changes, so switching modes cannot move the row.
        float slotWidth = availableWidth / count;
        float gap = Math.max(.6f, Math.min(unit * .010f, slotWidth * .24f));
        float barWidth = Math.max(.75f, slotWidth - gap);
        float barHeight = Math.max(3.5f, unit * .105f);
        for (int index = 0; index < total; index++) {
            int alpha = index < activeBars ? Color.alpha(color)
                    : Math.max(34, Math.round(Color.alpha(color) * .23f));
            shapePaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            float left = startX + index * slotWidth + gap / 2f;
            canvas.save();
            canvas.rotate(-14f, left + barWidth / 2f, centerY);
            shape.set(left, centerY - barHeight / 2f,
                    left + barWidth, centerY + barHeight / 2f);
            canvas.drawRoundRect(shape, barWidth * .35f, barWidth * .35f, shapePaint);
            canvas.restore();
        }
    }

    private void drawAuto(Canvas canvas, float centerX, float centerY, float unit, int color) {
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(Math.max(7f, unit * .135f));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText("AUTO", centerX,
                centerY - (metrics.ascent + metrics.descent) / 2f, textPaint);
    }

    /** Stock-style seated passenger with independently lit windshield, face and foot streams. */
    private void drawStockAirflow(Canvas canvas, float centerX, float centerY, float unit,
                                  int targets, int color) {
        if (targets == 0) return;
        float scale = unit * .34f;
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(Math.max(1.05f, unit * .016f));
        shapePaint.setStrokeCap(Paint.Cap.ROUND);
        shapePaint.setStrokeJoin(Paint.Join.ROUND);
        shapePaint.setColor(withAlpha(color, .78f));

        float figureX = centerX + scale * .20f;
        float headY = centerY - scale * .22f;
        canvas.drawCircle(figureX, headY, scale * .095f, shapePaint);
        path.reset();
        path.moveTo(figureX - scale * .06f, headY + scale * .12f);
        path.cubicTo(figureX - scale * .19f, centerY - scale * .02f,
                figureX - scale * .18f, centerY + scale * .12f,
                figureX - scale * .06f, centerY + scale * .16f);
        path.lineTo(figureX + scale * .18f, centerY + scale * .16f);
        path.lineTo(figureX + scale * .31f, centerY + scale * .34f);
        canvas.drawPath(path, shapePaint);
        canvas.drawLine(figureX - scale * .24f, centerY + scale * .17f,
                figureX + scale * .02f, centerY + scale * .17f, shapePaint);

        shapePaint.setColor(color);
        shapePaint.setStrokeWidth(Math.max(1.15f, unit * .019f));
        if ((targets & DriverClimatePresentation.AIRFLOW_WINDSHIELD) != 0) {
            drawWindshieldDefrost(canvas, centerX - scale * .20f,
                    centerY - scale * .27f, scale, color);
        }
        if ((targets & DriverClimatePresentation.AIRFLOW_FACE) != 0) {
            drawDirectionArrow(canvas, centerX - scale * .56f, centerY - scale * .12f,
                    scale * .48f, -4f, scale, color);
        }
        if ((targets & DriverClimatePresentation.AIRFLOW_LEGS) != 0) {
            drawDirectionArrow(canvas, centerX - scale * .50f, centerY + scale * .12f,
                    scale * .43f, 23f, scale, color);
        }
        shapePaint.setStyle(Paint.Style.FILL);
    }

    private void drawWindshieldDefrost(Canvas canvas, float centerX, float centerY,
                                       float scale, int color) {
        shapePaint.setColor(color);
        shape.set(centerX - scale * .27f, centerY - scale * .12f,
                centerX + scale * .27f, centerY + scale * .17f);
        canvas.drawArc(shape, 200f, 140f, false, shapePaint);
        for (int index = -1; index <= 1; index++) {
            float x = centerX + index * scale * .13f;
            canvas.drawLine(x, centerY + scale * .02f,
                    x + scale * .02f, centerY - scale * .15f, shapePaint);
        }
    }

    private void drawDirectionArrow(Canvas canvas, float startX, float startY, float length,
                                    float degrees, float unit, int color) {
        double radians = Math.toRadians(degrees);
        float endX = startX + (float) Math.cos(radians) * length;
        float endY = startY + (float) Math.sin(radians) * length;
        shapePaint.setColor(color);
        canvas.drawLine(startX, startY, endX, endY, shapePaint);
        float wing = Math.max(1.6f, unit * .12f);
        double left = radians + Math.toRadians(150);
        double right = radians - Math.toRadians(150);
        canvas.drawLine(endX, endY,
                endX + (float) Math.cos(left) * wing,
                endY + (float) Math.sin(left) * wing, shapePaint);
        canvas.drawLine(endX, endY,
                endX + (float) Math.cos(right) * wing,
                endY + (float) Math.sin(right) * wing, shapePaint);
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

    private static int withAlpha(int color, float fraction) {
        int alpha = Math.max(0, Math.min(255,
                Math.round(Color.alpha(color) * fraction)));
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
        long oldest = oldestPositive(temperatureObservedAtMillis, fanObservedAtMillis,
                autoObservedAtMillis, airflowObservedAtMillis);
        if (oldest <= 0) return;
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
        if (autoKnown && !isFresh(autoObservedAtMillis)) {
            autoKnown = false;
            autoActive = false;
            autoObservedAtMillis = 0;
        }
        if (airflowKnown && !isFresh(airflowObservedAtMillis)) {
            airflowKnown = false;
            airflowLabel = "";
            airflowObservedAtMillis = 0;
        }
        invalidate();
        scheduleExpiry();
    }

    private static long oldestPositive(long... values) {
        long oldest = 0;
        for (long value : values) {
            if (value > 0 && (oldest == 0 || value < oldest)) oldest = value;
        }
        return oldest;
    }
}
