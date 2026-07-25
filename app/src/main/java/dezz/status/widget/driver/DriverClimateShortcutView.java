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
import dezz.status.widget.launcher.climate.ClimateFanScaleGeometry;
import dezz.status.widget.launcher.climate.ClimatePowerStatePolicy;

/**
 * Resolution-independent live climate status for the compact driver rail.
 *
 * <p>The three fixed rows are temperature, fan-level scale and the current airflow pictogram.
 * Everything is drawn directly on Canvas, so increasing the per-button icon size never magnifies
 * a bitmap.</p>
 */
public final class DriverClimateShortcutView extends View {
    /** Keep boot/reconnect behavior identical to the main climate panel. */
    private static final long STATE_FRESH_MS = 75_000L;
    private static final String POWER = "climate.power";
    private static final String TEMP_DRIVER = "climate.temp_driver";
    private static final String FAN = "climate.fan";
    private static final String AUTO = "climate.auto";
    private static final String AIRFLOW = "climate.airflow";
    private static final Set<String> CONTROL_IDS = new LinkedHashSet<>(
            Arrays.asList(POWER, TEMP_DRIVER, FAN, AUTO, AIRFLOW));

    private final CarIntegration integration;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final int foregroundColor;
    private final boolean detailed;
    private final CarIntegration.ControlStateListener listener = this::onControlState;
    private final Runnable expiry = this::expireStaleState;

    private boolean subscribed;
    private boolean powerKnown;
    private boolean powerActive;
    private long powerObservedAtMillis;
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
                                     boolean detailed) {
        super(context);
        this.integration = integration;
        foregroundColor = parseColor(color, Color.WHITE);
        this.detailed = detailed;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Fixed sample used only by settings when no vehicle state has arrived yet. */
    public void showPreviewSample() {
        powerKnown = true;
        powerActive = true;
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
        if (POWER.equals(state.controlId)) {
            powerKnown = isFresh(state) && state.available && state.known;
            if (powerKnown) {
                powerActive = state.active;
                powerObservedAtMillis = state.observedAtMillis;
            } else {
                powerActive = false;
                powerObservedAtMillis = 0;
            }
        } else if (TEMP_DRIVER.equals(state.controlId)) {
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
        // Detailed climate fits the ordinary rail slot and keeps three stable rows. In particular,
        // there is no decorative fan blade competing with the level scale.
        boolean expanded = detailed;
        boolean powerOff = ClimatePowerStatePolicy.isConfirmedOff(powerKnown, powerActive);
        if (powerOff) {
            drawTemperature(canvas, "Выкл", width / 2f, height * .50f,
                    unit, expanded, muted(foregroundColor));
            return;
        }
        boolean showFan = fanKnown && fanActive;
        int color = showFan ? foregroundColor : muted(foregroundColor);

        String temperatureText = DriverClimatePresentation.temperature(
                temperature, temperatureKnown);
        float temperatureCenterY = height * (expanded ? .18f : showFan ? .32f : .50f);
        drawTemperature(canvas, temperatureText, width / 2f,
                temperatureCenterY, unit, expanded, color);
        if (!showFan) return;

        ClimateFanIndicatorPolicy.Indicator indicator =
                ClimateFanIndicatorPolicy.fromConfirmedState(fanLabel, fanLevel);
        int bars = indicator.activeSegments;
        int totalBars = indicator.totalSegments;
        float rowCenterY = height * (expanded ? .45f : .73f);
        drawBars(canvas, width * .12f, rowCenterY, width * .76f, unit,
                bars, totalBars, color);
        if (!expanded) return;

        if (airflowKnown) {
            drawAirflow(canvas, width / 2f, height * .77f, unit,
                    DriverClimatePresentation.airflowTargets(airflowLabel), color);
        }
    }

    private void drawTemperature(@NonNull Canvas canvas, @NonNull String value,
                                 float centerX, float centerY, float unit,
                                 boolean compactDetailed, int color) {
        float primarySize = Math.max(12f, unit * (compactDetailed ? .31f : .39f));
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(color);
        int decimal = value.indexOf('.');
        if (decimal <= 0 || decimal >= value.length() - 1) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(primarySize);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            canvas.drawText(value, centerX,
                    centerY - (metrics.ascent + metrics.descent) / 2f, textPaint);
            return;
        }
        String whole = value.substring(0, decimal);
        String fraction = value.substring(decimal);
        textPaint.setTextSize(primarySize);
        float wholeWidth = textPaint.measureText(whole);
        Paint.FontMetrics primaryMetrics = textPaint.getFontMetrics();
        float baseline = centerY
                - (primaryMetrics.ascent + primaryMetrics.descent) / 2f;
        float fractionSize = Math.max(8f, primarySize * .58f);
        textPaint.setTextSize(fractionSize);
        float fractionWidth = textPaint.measureText(fraction);
        float start = centerX - (wholeWidth + fractionWidth) / 2f;
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(primarySize);
        canvas.drawText(whole, start, baseline, textPaint);
        textPaint.setTextSize(fractionSize);
        canvas.drawText(fraction, start + wholeWidth,
                baseline - primarySize * .08f, textPaint);
    }

    private void drawBars(Canvas canvas, float startX, float centerY, float availableWidth,
                          float unit, int activeBars, int total, int color) {
        // Manual and AUTO share one immutable nine-slot geometry. AUTO draws its five logical
        // divisions in slots 0/2/4/6/8, so switching mode cannot resize or shift the scale.
        int physicalSlots = ClimateFanScaleGeometry.PHYSICAL_SLOTS;
        int logicalTotal = Math.max(1, Math.min(physicalSlots, total));
        float gap = Math.max(.7f, unit * .010f);
        float barWidth = Math.max(.75f,
                (availableWidth - gap * (physicalSlots - 1)) / physicalSlots);
        float barHeight = Math.max(4f, unit * .115f);
        for (int index = 0; index < logicalTotal; index++) {
            int alpha = index < activeBars ? Color.alpha(color)
                    : Math.max(34, Math.round(Color.alpha(color) * .23f));
            shapePaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            int physicalSlot = ClimateFanScaleGeometry.physicalSlot(index, logicalTotal);
            float left = startX + physicalSlot * (barWidth + gap);
            canvas.save();
            canvas.rotate(-14f, left + barWidth / 2f, centerY);
            shape.set(left, centerY - barHeight / 2f,
                    left + barWidth, centerY + barHeight / 2f);
            canvas.drawRoundRect(shape, barWidth * .35f, barWidth * .35f, shapePaint);
            canvas.restore();
        }
    }

    /**
     * Standard seated-person airflow pictogram. The independently lit upper, middle and lower
     * arrows represent windshield, face and feet, preserving all seven ECARX combinations.
     */
    private void drawAirflow(Canvas canvas, float centerX, float centerY, float unit,
                             int targets, int color) {
        if (targets == 0) return;
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(Math.max(1.4f, unit * .023f));
        shapePaint.setStrokeCap(Paint.Cap.ROUND);
        shapePaint.setStrokeJoin(Paint.Join.ROUND);
        shapePaint.setColor(withAlpha(color, .62f));

        // A recognisable side-profile passenger and seat replace the former stick figure.
        float headX = centerX + unit * .15f;
        float headY = centerY - unit * .105f;
        float headRadius = unit * .047f;
        canvas.drawCircle(headX, headY, headRadius, shapePaint);
        Path passenger = new Path();
        passenger.moveTo(centerX + unit * .105f, centerY - unit * .045f);
        passenger.lineTo(centerX + unit * .035f, centerY - unit * .015f);
        passenger.lineTo(centerX + unit * .055f, centerY + unit * .085f);
        passenger.lineTo(centerX + unit * .145f, centerY + unit * .09f);
        passenger.lineTo(centerX + unit * .225f, centerY + unit * .165f);
        canvas.drawPath(passenger, shapePaint);
        Path seat = new Path();
        seat.moveTo(centerX + unit * .005f, centerY - unit * .055f);
        seat.lineTo(centerX + unit * .025f, centerY + unit * .11f);
        seat.lineTo(centerX + unit * .17f, centerY + unit * .11f);
        canvas.drawPath(seat, shapePaint);

        // A short slanted windshield cue keeps the upper arrow unambiguous.
        canvas.drawLine(centerX + unit * .055f, centerY - unit * .185f,
                centerX + unit * .225f, centerY - unit * .155f, shapePaint);

        shapePaint.setColor(color);
        shapePaint.setStrokeWidth(Math.max(1.8f, unit * .032f));
        if ((targets & DriverClimatePresentation.AIRFLOW_WINDSHIELD) != 0) {
            drawDirectionArrow(canvas, centerX - unit * .25f, centerY - unit * .075f,
                    unit * .27f, -24f, unit, color);
        }
        if ((targets & DriverClimatePresentation.AIRFLOW_FACE) != 0) {
            drawDirectionArrow(canvas, centerX - unit * .28f, centerY - unit * .015f,
                    unit * .285f, 0f, unit, color);
        }
        if ((targets & DriverClimatePresentation.AIRFLOW_LEGS) != 0) {
            drawDirectionArrow(canvas, centerX - unit * .25f, centerY + unit * .055f,
                    unit * .30f, 23f, unit, color);
        }
        shapePaint.setStyle(Paint.Style.FILL);
    }

    private void drawDirectionArrow(Canvas canvas, float startX, float startY, float length,
                                    float degrees, float unit, int color) {
        double radians = Math.toRadians(degrees);
        float endX = startX + (float) Math.cos(radians) * length;
        float endY = startY + (float) Math.sin(radians) * length;
        shapePaint.setColor(color);
        canvas.drawLine(startX, startY, endX, endY, shapePaint);
        float wing = Math.max(2.5f, unit * .055f);
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
        long oldest = oldestPositive(powerObservedAtMillis, temperatureObservedAtMillis,
                fanObservedAtMillis, autoObservedAtMillis, airflowObservedAtMillis);
        if (oldest <= 0) return;
        long remaining = STATE_FRESH_MS
                - Math.max(0, System.currentTimeMillis() - oldest);
        postDelayed(expiry, Math.max(1, remaining + 1));
    }

    private void expireStaleState() {
        if (powerKnown && !isFresh(powerObservedAtMillis)) {
            powerKnown = false;
            powerActive = false;
            powerObservedAtMillis = 0;
        }
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
