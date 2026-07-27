/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import dezz.status.widget.R;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.launcher.climate.ClimateFanIndicatorPolicy;
import dezz.status.widget.launcher.climate.ClimateFanScaleGeometry;
import dezz.status.widget.launcher.climate.ClimatePowerStatePolicy;

/**
 * Resolution-independent live climate status for the compact driver rail.
 *
 * <p>Temperature and the fan-level scale form the compact presentation. AUTO and the current
 * airflow pictogram belong to optional extended information. Fan/airflow artwork comes from the
 * user-supplied MonjaroPanel 2.0.5 package; geometry and state remain driven by live ECARX data.</p>
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
    /** Exact opaque components inside MonjaroPanel's ic_temperature.png. */
    private static final Rect FAN_ARTWORK_SOURCE = new Rect(0, 142, 53, 196);
    private static final Rect FAN_SEGMENT_SOURCE = new Rect(66, 146, 102, 194);

    private final CarIntegration integration;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artworkPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final RectF shape = new RectF();
    @Nullable private final Drawable airflowFace;
    @Nullable private final Drawable airflowLegs;
    @Nullable private final Drawable airflowFaceLegs;
    @Nullable private final Drawable airflowWindshield;
    @Nullable private final Drawable airflowFaceWindshield;
    @Nullable private final Drawable airflowLegsWindshield;
    @Nullable private final Drawable airflowAll;
    @Nullable private final Drawable airflowAuto;
    @Nullable private final Drawable airflowAutoBadge;
    @Nullable private final Bitmap fanScaleArtwork;
    private final int foregroundColor;
    private final boolean detailed;
    private final int detailsGapPx;
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
    private boolean airflowActive;
    private long airflowObservedAtMillis;
    @NonNull private String airflowLabel = "";

    public DriverClimateShortcutView(@NonNull Context context,
                                     @NonNull CarIntegration integration,
                                     @Nullable String color) {
        this(context, integration, color, false, 0);
    }

    public DriverClimateShortcutView(@NonNull Context context,
                                     @NonNull CarIntegration integration,
                                     @Nullable String color,
                                     boolean detailed) {
        this(context, integration, color, detailed, 0);
    }

    public DriverClimateShortcutView(@NonNull Context context,
                                     @NonNull CarIntegration integration,
                                     @Nullable String color,
                                     boolean detailed,
                                     int detailsGapPx) {
        super(context);
        this.integration = integration;
        foregroundColor = parseColor(color, Color.WHITE);
        this.detailed = detailed;
        this.detailsGapPx = Math.max(0, Math.min(96, detailsGapPx));
        airflowFace = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_face);
        airflowLegs = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_leg);
        airflowFaceLegs = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_face_leg);
        airflowWindshield = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_window);
        airflowFaceWindshield = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_face_window);
        airflowLegsWindshield = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_leg_window);
        airflowAll = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_all);
        airflowAuto = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_auto);
        airflowAutoBadge = loadAirflowDrawable(
                context, R.drawable.ic_driver_monjaro_blow_auto_badge);
        fanScaleArtwork = loadFanScaleArtwork(
                context, R.drawable.ic_driver_monjaro_temperature_source);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        airflowActive = true;
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
                airflowActive = state.active;
                airflowLabel = state.valueLabel;
                airflowObservedAtMillis = state.observedAtMillis;
            } else {
                airflowActive = false;
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
        // Extended climate fits the ordinary rail slot and adds only AUTO/airflow. In particular,
        // there is no decorative fan blade competing with the level scale.
        boolean expanded = detailed;
        boolean powerOff = ClimatePowerStatePolicy.isConfirmedOff(
                powerKnown, powerActive,
                fanKnown, fanActive,
                airflowKnown, airflowActive);
        if (powerOff) {
            drawTemperature(canvas, "Выкл", width / 2f, height * .50f,
                    unit, expanded, muted(foregroundColor));
            return;
        }
        boolean showFan = fanKnown && fanActive;
        int color = showFan ? foregroundColor : muted(foregroundColor);

        String temperatureText = DriverClimatePresentation.temperature(
                temperature, temperatureKnown);
        float effectiveGap = expanded
                ? Math.min(detailsGapPx, height * .14f) : 0f;
        float temperatureCenterY = height * (expanded ? .18f : showFan ? .32f : .50f)
                - effectiveGap * .20f;
        drawTemperature(canvas, temperatureText, width / 2f,
                temperatureCenterY, unit, expanded, color);
        if (!showFan) return;

        ClimateFanIndicatorPolicy.Indicator indicator =
                ClimateFanIndicatorPolicy.fromConfirmedState(fanLabel, fanLevel);
        boolean automatic = DriverClimatePresentation.automatic(
                autoKnown, autoActive, fanLabel);
        int totalBars = automatic
                ? ClimateFanIndicatorPolicy.AUTO_SEGMENTS
                : ClimateFanIndicatorPolicy.MANUAL_SEGMENTS;
        int bars = automatic
                ? (indicator.automatic ? indicator.activeSegments
                : Math.max(1, Math.min(totalBars, fanLevel + 1)))
                : (indicator.automatic ? Math.max(0, Math.min(totalBars, fanLevel))
                : indicator.activeSegments);
        float rowCenterY = height * (expanded ? .45f : .73f)
                - effectiveGap * .50f;
        drawBars(canvas, width * .12f, rowCenterY, width * .76f, unit,
                bars, totalBars, color);
        if (!expanded) return;

        if (airflowKnown || automatic) {
            drawAirflow(canvas, width / 2f,
                    height * .77f + effectiveGap * .50f, unit,
                    DriverClimatePresentation.airflowTargets(airflowLabel),
                    automatic, color);
        }
        if (automatic) {
            drawAutoBadge(canvas, width * .76f,
                    height * .61f + effectiveGap * .50f, unit, color);
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
        int logicalTotal = Math.max(1,
                Math.min(ClimateFanScaleGeometry.PHYSICAL_SLOTS, total));
        if (fanScaleArtwork != null && !fanScaleArtwork.isRecycled()) {
            drawMonjaroFanScale(canvas, startX, centerY, availableWidth, unit,
                    activeBars, logicalTotal, color);
            return;
        }
        // Defensive fallback for a resource-decoding failure.
        float gap = Math.max(.7f, unit * .009f);
        float barWidth = ClimateFanScaleGeometry.segmentWidth(
                availableWidth, gap, logicalTotal);
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

    private void drawMonjaroFanScale(@NonNull Canvas canvas, float startX, float centerY,
                                     float availableWidth, float unit, int activeBars,
                                     int logicalTotal, int color) {
        float fanSize = Math.max(5f, unit * .135f);
        float fanGap = Math.max(1f, unit * .022f);
        float segmentAreaX = startX + fanSize + fanGap;
        float segmentAreaWidth = Math.max(1f, availableWidth - fanSize - fanGap);
        float segmentGap = Math.max(.7f, unit * .009f);
        float segmentWidth = ClimateFanScaleGeometry.segmentWidth(
                segmentAreaWidth, segmentGap, logicalTotal);
        float segmentHeight = Math.max(5f, unit * .135f);

        artworkPaint.setColorFilter(new PorterDuffColorFilter(
                opaqueRgb(color), PorterDuff.Mode.SRC_IN));
        artworkPaint.setAlpha(Color.alpha(color));
        shape.set(startX, centerY - fanSize / 2f,
                startX + fanSize, centerY + fanSize / 2f);
        canvas.drawBitmap(fanScaleArtwork, FAN_ARTWORK_SOURCE, shape, artworkPaint);

        for (int index = 0; index < logicalTotal; index++) {
            int alpha = index < activeBars ? Color.alpha(color)
                    : Math.max(34, Math.round(Color.alpha(color) * .23f));
            artworkPaint.setAlpha(alpha);
            int physicalSlot = ClimateFanScaleGeometry.physicalSlot(index, logicalTotal);
            float left = segmentAreaX
                    + physicalSlot * (segmentWidth + segmentGap);
            shape.set(left, centerY - segmentHeight / 2f,
                    left + segmentWidth, centerY + segmentHeight / 2f);
            canvas.drawBitmap(fanScaleArtwork, FAN_SEGMENT_SOURCE, shape, artworkPaint);
        }
        artworkPaint.setColorFilter(null);
        artworkPaint.setAlpha(255);
    }

    /** Exact MonjaroPanel pictograms preserve all seven ECARX direction combinations. */
    private void drawAirflow(Canvas canvas, float centerX, float centerY, float unit,
                             int targets, boolean automatic, int color) {
        Drawable drawable = airflowDrawable(targets);
        if (drawable == null && automatic) drawable = airflowAuto;
        if (drawable == null) return;
        float iconSize = unit * .40f;
        int left = Math.round(centerX - iconSize / 2f);
        int top = Math.round(centerY - iconSize / 2f);
        int right = Math.round(centerX + iconSize / 2f);
        int bottom = Math.round(centerY + iconSize / 2f);
        drawAirflowLayer(canvas, drawable, left, top, right, bottom, color);
    }

    @Nullable
    private Drawable airflowDrawable(int targets) {
        int face = DriverClimatePresentation.AIRFLOW_FACE;
        int legs = DriverClimatePresentation.AIRFLOW_LEGS;
        int windshield = DriverClimatePresentation.AIRFLOW_WINDSHIELD;
        if (targets == face) return airflowFace;
        if (targets == legs) return airflowLegs;
        if (targets == (face | legs)) return airflowFaceLegs;
        if (targets == windshield) return airflowWindshield;
        if (targets == (face | windshield)) return airflowFaceWindshield;
        if (targets == (legs | windshield)) return airflowLegsWindshield;
        if (targets == (face | legs | windshield)) return airflowAll;
        return null;
    }

    private static void drawAirflowLayer(@NonNull Canvas canvas,
                                         @Nullable Drawable drawable,
                                         int left, int top, int right, int bottom,
                                         int color) {
        if (drawable == null) return;
        drawable.setTint(color);
        drawable.setBounds(left, top, right, bottom);
        drawable.draw(canvas);
    }

    @Nullable
    private static Drawable loadAirflowDrawable(@NonNull Context context, int resourceId) {
        Drawable drawable = AppCompatResources.getDrawable(context, resourceId);
        return drawable == null ? null : drawable.mutate();
    }

    @Nullable
    private static Bitmap loadFanScaleArtwork(@NonNull Context context, int resourceId) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(context.getResources(), resourceId, options);
    }

    private void drawAutoBadge(@NonNull Canvas canvas, float centerX, float centerY,
                               float unit, int color) {
        if (airflowAutoBadge != null) {
            float size = Math.max(8f, unit * .22f);
            int left = Math.round(centerX - size / 2f);
            int top = Math.round(centerY - size / 2f);
            drawAirflowLayer(canvas, airflowAutoBadge, left, top,
                    Math.round(left + size), Math.round(top + size), color);
            return;
        }
        textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(Math.max(7f, unit * .095f));
        float textWidth = textPaint.measureText("AUTO");
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float halfHeight = Math.max(unit * .065f,
                (metrics.descent - metrics.ascent) * .58f);
        float horizontalPadding = unit * .045f;
        shape.set(centerX - textWidth / 2f - horizontalPadding,
                centerY - halfHeight,
                centerX + textWidth / 2f + horizontalPadding,
                centerY + halfHeight);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(withAlpha(color, .18f));
        canvas.drawRoundRect(shape, halfHeight, halfHeight, shapePaint);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(Math.max(1f, unit * .012f));
        shapePaint.setColor(withAlpha(color, .72f));
        canvas.drawRoundRect(shape, halfHeight, halfHeight, shapePaint);
        textPaint.setColor(color);
        canvas.drawText("AUTO", centerX,
                centerY - (metrics.ascent + metrics.descent) / 2f, textPaint);
        shapePaint.setStyle(Paint.Style.FILL);
    }

    private static int opaqueRgb(int color) {
        return color | 0xFF000000;
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
            airflowActive = false;
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
