/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One hardware-accelerated Canvas for every non-map instrument. Static artwork is raster-cached;
 * the VSync callback snapshots primitives and redraws only while something is changing.
 */
public final class InstrumentClusterView extends View implements Choreographer.FrameCallback {
    public interface EditorListener {
        void onSelectionChanged(@Nullable InstrumentElementConfig element);
        void onGeometryChanged(@NonNull InstrumentElementConfig element, boolean committed);
    }

    private static final float SPEED_MAX = 260f;
    private static final float RPM_MAX = 8_000f;
    private static final float GAUGE_START_DEGREES = 135f;
    private static final float GAUGE_SWEEP_DEGREES = 270f;
    private static final Typeface SERIF_REGULAR = Typeface.create(
            Typeface.SERIF, Typeface.NORMAL);
    private static final Typeface SERIF_BOLD = Typeface.create(
            Typeface.SERIF, Typeface.BOLD);
    private static final Typeface MONO_REGULAR = Typeface.create(
            Typeface.MONOSPACE, Typeface.NORMAL);
    private static final Typeface MONO_BOLD = Typeface.create(
            Typeface.MONOSPACE, Typeface.BOLD);

    @NonNull private final InstrumentTelemetryRepository telemetry;
    @NonNull private final InstrumentTelemetryRepository.Frame frame =
            new InstrumentTelemetryRepository.Frame();
    @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    @NonNull private final Paint clearPaint = new Paint();
    @NonNull private final RectF rect = new RectF();
    @NonNull private final RectF gaugeArc = new RectF();
    @NonNull private final Calendar calendar = Calendar.getInstance();
    @NonNull private final char[] clockBuffer = {'0', '0', ':', '0', '0'};
    @NonNull private final List<RuntimeElement> runtimeElements = new ArrayList<>();
    @NonNull private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private final boolean editorMode;
    @Nullable private final EditorListener editorListener;
    @NonNull private InstrumentPanelConfig config;
    @NonNull private final AtomicBoolean telemetryWakePosted = new AtomicBoolean();
    @NonNull private final InstrumentTelemetryRepository.UpdateListener telemetryListener =
            this::onTelemetryChanged;
    @NonNull private final Runnable telemetryWake = () -> {
        telemetryWakePosted.set(false);
        scheduleFrame();
    };
    @NonNull private final Runnable clockWake = () -> {
        if (!isRenderingActive() || !config.hasVisibleClock()) return;
        updateClock(System.currentTimeMillis() / 60_000L);
        invalidate();
        scheduleClockWake();
    };

    @Nullable private Bitmap staticLayer;
    private boolean staticLayerDirty = true;
    private boolean attached;
    private boolean windowVisible;
    private boolean telemetryAcquired;
    private boolean frameCallbackPosted;
    private long lastFrameNanos;
    private long lastGeneration = Long.MIN_VALUE;
    private long lastClockMinute = Long.MIN_VALUE;
    @NonNull private String clockText = "--:--";
    @Nullable private String selectedId;
    @Nullable private InstrumentElementConfig dragging;
    private boolean resizing;
    private float touchStartX;
    private float touchStartY;
    private int originalX;
    private int originalY;
    private int originalWidth;
    private int originalHeight;

    public InstrumentClusterView(@NonNull Context context,
                                 @NonNull InstrumentPanelConfig config,
                                 boolean editorMode,
                                 @Nullable EditorListener editorListener) {
        super(context);
        this.telemetry = InstrumentTelemetryRepository.get(context);
        this.editorMode = editorMode;
        this.editorListener = editorListener;
        clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setFocusable(editorMode);
        setClickable(editorMode);
        setWillNotDraw(false);
        setConfig(config);
    }

    public void setConfig(@NonNull InstrumentPanelConfig value) {
        config = value;
        config.normalize();
        runtimeElements.clear();
        for (InstrumentElementConfig element : config.elements) {
            runtimeElements.add(new RuntimeElement(element));
        }
        if (telemetryAcquired) {
            telemetry.updateMetrics(telemetryListener, config.telemetryMetricIds());
        }
        if (isRenderingActive() && config.hasVisibleClock()) {
            updateClock(System.currentTimeMillis() / 60_000L);
        }
        staticLayerDirty = true;
        invalidate();
        scheduleClockWake();
        scheduleFrame();
    }

    @NonNull
    public InstrumentPanelConfig getConfig() {
        return config;
    }

    public void select(@Nullable String id) {
        selectedId = id;
        invalidate();
        if (editorListener != null) editorListener.onSelectionChanged(find(id));
    }

    @Nullable
    public InstrumentElementConfig selected() {
        return find(selectedId);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (attached) return;
        attached = true;
        windowVisible = getWindowVisibility() == VISIBLE;
        updateRenderingState();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        windowVisible = false;
        updateRenderingState();
        recycleStaticLayer();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        updateRenderingState();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        staticLayerDirty = true;
    }

    @Override public void doFrame(long frameTimeNanos) {
        frameCallbackPosted = false;
        if (!isRenderingActive()) return;
        long availableGeneration = telemetry.generation();
        boolean telemetryChanged = availableGeneration != lastGeneration;
        if (telemetryChanged) {
            telemetry.snapshot(frame);
            lastGeneration = frame.generation;
        }
        float deltaSeconds = lastFrameNanos == 0L
                ? 1f / 60f : Math.min(.1f, (frameTimeNanos - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = frameTimeNanos;

        boolean animating = false;
        for (RuntimeElement runtime : runtimeElements) {
            if (!runtime.config.enabled || !runtime.config.type.isAnalogGauge()) continue;
            float target = valueFor(runtime.config.type);
            if (!Float.isFinite(target)) continue;
            if (!runtime.initialized) {
                runtime.smoothedValue = target;
                runtime.initialized = true;
            } else {
                int responseMillis = runtime.config.responseMillis;
                if (responseMillis <= 0) {
                    runtime.smoothedValue = target;
                } else {
                    float tau = responseMillis / 1000f;
                    // Stable first-order response without a transcendental operation per gauge.
                    float alpha = Math.min(1f, deltaSeconds / (tau + deltaSeconds));
                    runtime.smoothedValue += (target - runtime.smoothedValue) * alpha;
                }
            }
            if (Math.abs(target - runtime.smoothedValue) > gaugeTolerance(runtime.config.type)) {
                animating = true;
            }
            else runtime.smoothedValue = target;
        }

        if (telemetryChanged || animating) invalidate();
        if (animating) scheduleFrame();
    }

    private void updateRenderingState() {
        boolean shouldRender = isRenderingActive();
        if (shouldRender && !telemetryAcquired) {
            telemetryAcquired = true;
            lastGeneration = Long.MIN_VALUE;
            lastFrameNanos = 0L;
            telemetry.acquire(telemetryListener, config.telemetryMetricIds());
            if (config.hasVisibleClock()) {
                updateClock(System.currentTimeMillis() / 60_000L);
            }
            scheduleClockWake();
            scheduleFrame();
        } else if (!shouldRender && telemetryAcquired) {
            telemetryAcquired = false;
            telemetry.release(telemetryListener);
            if (frameCallbackPosted) {
                Choreographer.getInstance().removeFrameCallback(this);
                frameCallbackPosted = false;
            }
            removeCallbacks(clockWake);
            removeCallbacks(telemetryWake);
            telemetryWakePosted.set(false);
        }
    }

    private boolean isRenderingActive() {
        return attached && windowVisible;
    }

    private void onTelemetryChanged() {
        if (!telemetryWakePosted.compareAndSet(false, true)) return;
        if (!post(telemetryWake)) telemetryWakePosted.set(false);
    }

    private void scheduleFrame() {
        if (!isRenderingActive() || frameCallbackPosted) return;
        frameCallbackPosted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void scheduleClockWake() {
        removeCallbacks(clockWake);
        if (!isRenderingActive() || !config.hasVisibleClock()) return;
        long now = System.currentTimeMillis();
        postDelayed(clockWake, 60_050L - now % 60_000L);
    }

    private static float gaugeTolerance(@NonNull InstrumentElementType type) {
        if (type == InstrumentElementType.ANALOG_TACHOMETER) return 1.5f;
        if (type == InstrumentElementType.ANALOG_SPEEDOMETER) return .04f;
        return .01f;
    }

    private void updateClock(long minute) {
        lastClockMinute = minute;
        calendar.setTimeInMillis(minute * 60_000L);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int value = calendar.get(Calendar.MINUTE);
        clockBuffer[0] = (char) ('0' + hour / 10);
        clockBuffer[1] = (char) ('0' + hour % 10);
        clockBuffer[3] = (char) ('0' + value / 10);
        clockBuffer[4] = (char) ('0' + value % 10);
        clockText = new String(clockBuffer);
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (staticLayerDirty || staticLayer == null
                || staticLayer.getWidth() != getWidth()
                || staticLayer.getHeight() != getHeight()) {
            rebuildStaticLayer();
        }
        if (staticLayer != null) canvas.drawBitmap(staticLayer, 0f, 0f, null);
        for (RuntimeElement runtime : runtimeElements) {
            InstrumentElementConfig element = runtime.config;
            if (!element.enabled || element.type == InstrumentElementType.NAV_MAP) continue;
            bounds(element, rect);
            drawDynamicElement(canvas, runtime, rect);
        }
        if (editorMode) drawEditor(canvas);
    }

    private void rebuildStaticLayer() {
        staticLayerDirty = false;
        Bitmap layer = staticLayer;
        if (layer == null || layer.isRecycled()
                || layer.getWidth() != getWidth() || layer.getHeight() != getHeight()) {
            recycleStaticLayer();
            try {
                layer = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                staticLayer = layer;
            } catch (OutOfMemoryError ignored) {
                return;
            }
        }
        Canvas canvas = new Canvas(layer);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (!config.transparentBackground) {
            canvas.drawColor(config.defaultStyle.backgroundColor);
        }
        for (RuntimeElement runtime : runtimeElements) {
            InstrumentElementConfig element = runtime.config;
            if (!element.enabled) continue;
            bounds(element, rect);
            if (element.type == InstrumentElementType.NAV_MAP) {
                // Let the independent TextureView below this Canvas remain visible.
                canvas.drawRect(rect, clearPaint);
            } else {
                drawStaticElement(canvas, element, rect);
            }
        }
    }

    private void recycleStaticLayer() {
        Bitmap layer = staticLayer;
        staticLayer = null;
        if (layer != null && !layer.isRecycled()) layer.recycle();
    }

    private void drawStaticElement(@NonNull Canvas canvas,
                                   @NonNull InstrumentElementConfig element,
                                   @NonNull RectF bounds) {
        InstrumentStyleFamily style = element.style;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        if (element.type.isAnalogGauge()) {
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            float radius = Math.min(bounds.width(), bounds.height()) * .46f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(style.backgroundColor, alpha));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, radius * gaugeRingWidth(style)));
            paint.setColor(withAlpha(style.secondaryColor, alpha));
            canvas.drawCircle(cx, cy, radius, paint);
            drawStyleGaugeFace(canvas, style, cx, cy, radius, alpha);
            drawGaugeTicks(canvas, element, cx, cy, radius, alpha);
            return;
        }

        drawDigitalFace(canvas, element, bounds, alpha);
    }

    private void drawDigitalFace(@NonNull Canvas canvas,
                                 @NonNull InstrumentElementConfig element,
                                 @NonNull RectF bounds, int alpha) {
        InstrumentStyleFamily style = element.style;
        float shortSide = Math.min(bounds.width(), bounds.height());
        float corner = shortSide * (style == InstrumentStyleFamily.SUPERSPORT ? .06f : .18f);
        int backgroundAlpha = Math.min(alpha,
                style == InstrumentStyleFamily.MINIMAL_PANORAMA ? 80
                        : style == InstrumentStyleFamily.NAVIGATION_FIRST ? 150 : 224);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(style.backgroundColor, backgroundAlpha));
        if (style == InstrumentStyleFamily.FIVE_DIAL_HERITAGE) {
            canvas.drawOval(bounds, paint);
        } else {
            canvas.drawRoundRect(bounds, corner, corner, paint);
        }

        paint.setStrokeCap(Paint.Cap.ROUND);
        switch (style) {
            case GRAND_TOURER:
            case EXECUTIVE_GLASS:
            case RETRO_MECHANICAL:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, bounds.height() * .018f));
                paint.setColor(withAlpha(style.accentColor,
                        style == InstrumentStyleFamily.EXECUTIVE_GLASS ? alpha / 2 : alpha));
                canvas.drawRoundRect(bounds, corner, corner, paint);
                if (style == InstrumentStyleFamily.GRAND_TOURER) {
                    paint.setColor(withAlpha(style.secondaryColor, alpha));
                    canvas.drawLine(bounds.left + bounds.width() * .18f,
                            bounds.bottom - bounds.height() * .12f,
                            bounds.right - bounds.width() * .18f,
                            bounds.bottom - bounds.height() * .12f, paint);
                }
                break;
            case M_SPORT_ARCS:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(3f, bounds.height() * .055f));
                paint.setColor(withAlpha(style.accentColor, alpha));
                canvas.drawLine(bounds.left + bounds.width() * .06f,
                        bounds.top + bounds.height() * .18f,
                        bounds.left + bounds.width() * .06f,
                        bounds.bottom - bounds.height() * .18f, paint);
                paint.setColor(withAlpha(style.secondaryColor, alpha));
                canvas.drawLine(bounds.left + bounds.width() * .15f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.right - bounds.width() * .08f,
                        bounds.bottom - bounds.height() * .08f, paint);
                break;
            case VIRTUAL_CLASSIC:
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(style.accentColor, alpha));
                canvas.drawRect(bounds.left, bounds.top,
                        bounds.left + Math.max(3f, bounds.width() * .025f), bounds.bottom, paint);
                break;
            case FIVE_DIAL_HERITAGE:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, bounds.height() * .025f));
                paint.setColor(withAlpha(style.secondaryColor, alpha));
                canvas.drawOval(bounds, paint);
                break;
            case SUPERSPORT:
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(style.accentColor, alpha));
                canvas.drawRect(bounds.left, bounds.top, bounds.right,
                        bounds.top + Math.max(3f, bounds.height() * .07f), paint);
                break;
            case NAVIGATION_FIRST:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, bounds.height() * .025f));
                paint.setColor(withAlpha(style.accentColor, alpha / 2));
                canvas.drawLine(bounds.left + bounds.width() * .12f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.right - bounds.width() * .12f,
                        bounds.bottom - bounds.height() * .08f, paint);
                break;
            case MINIMAL_PANORAMA:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, bounds.height() * .012f));
                paint.setColor(withAlpha(style.secondaryColor, alpha));
                canvas.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.bottom, paint);
                break;
            case EV_FLOW:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, bounds.height() * .028f));
                paint.setColor(withAlpha(style.accentColor, alpha));
                canvas.drawLine(bounds.left + bounds.width() * .10f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.centerX() - bounds.width() * .03f,
                        bounds.bottom - bounds.height() * .08f, paint);
                paint.setColor(withAlpha(style.secondaryColor, alpha));
                canvas.drawLine(bounds.centerX() + bounds.width() * .03f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.right - bounds.width() * .10f,
                        bounds.bottom - bounds.height() * .08f, paint);
                break;
            default:
                break;
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawGaugeTicks(@NonNull Canvas canvas,
                                @NonNull InstrumentElementConfig element,
                                float cx, float cy, float radius, int alpha) {
        int majorTicks = gaugeMajorTicks(element.style, element.type);
        int minorPerMajor = gaugeMinorTicks(element.style);
        int total = (majorTicks - 1) * minorPerMajor;
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int tick = 0; tick <= total; tick++) {
            boolean major = tick % minorPerMajor == 0;
            float angle = gaugeStart(element.style) + gaugeSweep(element.style) * tick / total;
            double radians = Math.toRadians(angle);
            float outer = radius * .90f;
            float inner = radius * (major ? .76f : .83f);
            paint.setStrokeWidth(Math.max(1.5f, radius * (major ? .018f : .009f)));
            boolean warning = warningTick(element.type, tick / (float) total);
            paint.setColor(withAlpha(warning ? 0xFFFF3B30 : element.style.primaryColor, alpha));
            canvas.drawLine(cx + (float) Math.cos(radians) * inner,
                    cy + (float) Math.sin(radians) * inner,
                    cx + (float) Math.cos(radians) * outer,
                    cy + (float) Math.sin(radians) * outer, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        drawGaugeLabels(canvas, element, cx, cy, radius, alpha, majorTicks);
    }

    private void drawGaugeLabels(@NonNull Canvas canvas,
                                 @NonNull InstrumentElementConfig element,
                                 float cx, float cy, float radius, int alpha, int majorTicks) {
        int labelEvery = element.type == InstrumentElementType.ANALOG_SPEEDOMETER ? 2 : 1;
        if (element.style == InstrumentStyleFamily.MINIMAL_PANORAMA) labelEvery *= 2;
        float minimum = gaugeMinimum(element.type);
        float maximum = gaugeMaximum(element.type);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(digitalTypeface(element.style, false));
        paint.setTextSize(Math.max(7f, radius * .083f));
        for (int index = 0; index < majorTicks; index += labelEvery) {
            float fraction = index / (float) (majorTicks - 1);
            float angle = gaugeStart(element.style) + gaugeSweep(element.style) * fraction;
            double radians = Math.toRadians(angle);
            float value = minimum + (maximum - minimum) * fraction;
            String label = element.type == InstrumentElementType.ANALOG_TACHOMETER
                    ? Integer.toString(Math.round(value / 1_000f))
                    : Integer.toString(Math.round(value));
            paint.setColor(withAlpha(warningTick(element.type, fraction)
                    ? 0xFFFF3B30 : element.style.primaryColor, Math.min(alpha, 205)));
            canvas.drawText(label,
                    cx + (float) Math.cos(radians) * radius * .66f,
                    cy + (float) Math.sin(radians) * radius * .66f
                            - (paint.ascent() + paint.descent()) * .5f,
                    paint);
        }
    }

    private void drawDynamicElement(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                                    @NonNull RectF bounds) {
        InstrumentElementConfig element = runtime.config;
        switch (element.type) {
            case ANALOG_SPEEDOMETER:
            case ANALOG_TACHOMETER:
            case ANALOG_FUEL_GAUGE:
            case ANALOG_BATTERY_GAUGE:
            case ANALOG_COOLANT_TEMPERATURE:
            case ANALOG_INSTANT_CONSUMPTION:
                drawAnalogGauge(canvas, runtime, bounds, valueFor(element.type));
                break;
            case DIGITAL_SPEEDOMETER:
                drawDigital(canvas, runtime, bounds, frame.speed, "км/ч", 0);
                break;
            case DIGITAL_TACHOMETER:
                drawDigital(canvas, runtime, bounds, frame.rpm, "об/мин", 0);
                break;
            case GEAR:
                drawStyledText(canvas, runtime, bounds, runtime.gearText(frame.gear), .58f);
                break;
            case ODOMETER:
                drawDigital(canvas, runtime, bounds, frame.odometer, "км", 0);
                break;
            case FUEL_GAUGE:
                drawDigital(canvas, runtime, bounds, frame.fuel, "л", 1);
                break;
            case BATTERY_GAUGE:
                drawDigital(canvas, runtime, bounds, frame.battery, "%", 0);
                break;
            case RANGE:
                float range = Float.isFinite(frame.totalRange)
                        ? frame.totalRange : frame.fuelRange;
                drawDigital(canvas, runtime, bounds, range, "км", 0);
                break;
            case AMBIENT_TEMPERATURE:
                drawDigital(canvas, runtime, bounds, frame.ambientTemperature, "°C", 0);
                break;
            case COOLANT_TEMPERATURE:
                drawDigital(canvas, runtime, bounds, frame.coolantTemperature, "°C", 0);
                break;
            case INSTANT_CONSUMPTION:
                drawDigital(canvas, runtime, bounds, frame.instantConsumption, "л/100", 1);
                break;
            case AVERAGE_CONSUMPTION:
                drawDigital(canvas, runtime, bounds, frame.averageConsumption, "л/100", 1);
                break;
            case TRIP_CONSUMPTION:
                drawDigital(canvas, runtime, bounds, frame.tripConsumption, "л/100", 1);
                break;
            case CLOCK:
                drawStyledText(canvas, runtime, bounds, clockText, .53f);
                break;
            default:
                break;
        }
    }

    private void drawAnalogGauge(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                                 @NonNull RectF bounds, float rawValue) {
        InstrumentElementConfig element = runtime.config;
        float minimum = gaugeMinimum(element.type);
        float maximum = gaugeMaximum(element.type);
        String unit = gaugeUnit(element.type);
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float radius = Math.min(bounds.width(), bounds.height()) * .46f;
        float value = runtime.initialized ? runtime.smoothedValue : minimum;
        float fraction = Math.max(0f, Math.min(1f,
                (value - minimum) / Math.max(1f, maximum - minimum)));
        float start = gaugeStart(element.style);
        float sweep = gaugeSweep(element.style);
        float angle = start + sweep * fraction;
        double radians = Math.toRadians(angle);
        float needleLength = radius * .69f;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (element.style == InstrumentStyleFamily.EV_FLOW) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(5f, radius * .045f));
            paint.setColor(withAlpha(element.style.accentColor, alpha));
            gaugeArc.set(cx - radius * .68f, cy - radius * .68f,
                    cx + radius * .68f, cy + radius * .68f);
            canvas.drawArc(gaugeArc, start, sweep * fraction, false, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx + (float) Math.cos(radians) * radius * .68f,
                    cy + (float) Math.sin(radians) * radius * .68f,
                    Math.max(3f, radius * .045f), paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            float needleWidth = element.style == InstrumentStyleFamily.SUPERSPORT
                    || element.style == InstrumentStyleFamily.M_SPORT_ARCS ? .036f
                    : element.style == InstrumentStyleFamily.MINIMAL_PANORAMA ? .014f : .025f;
            paint.setStrokeWidth(Math.max(2f, radius * needleWidth));
            paint.setColor(withAlpha(element.style.accentColor, alpha));
            canvas.drawLine(cx - (float) Math.cos(radians) * radius * .12f,
                    cy - (float) Math.sin(radians) * radius * .12f,
                    cx + (float) Math.cos(radians) * needleLength,
                    cy + (float) Math.sin(radians) * needleLength, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius * .055f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);

        String valueText = gaugeDecimals(element.type) == 0
                ? runtime.integerText(rawValue) : runtime.oneDecimalText(rawValue);
        paint.setTypeface(digitalTypeface(element.style, true));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * .28f);
        paint.setColor(withAlpha(element.style.primaryColor, alpha));
        canvas.drawText(valueText, cx, cy + radius * .35f, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(radius * .095f);
        paint.setColor(withAlpha(element.style.primaryColor, Math.min(alpha, 190)));
        canvas.drawText(unit, cx, cy + radius * .52f, paint);
    }

    private void drawDigital(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                             @NonNull RectF bounds, float value, @NonNull String unit,
                             int decimals) {
        String number = decimals == 0
                ? runtime.integerText(value) : runtime.oneDecimalText(value);
        InstrumentStyleFamily style = runtime.config.style;
        int alpha = Math.round(255f * runtime.config.opacityPercent / 100f);
        boolean sideBySide = style == InstrumentStyleFamily.M_SPORT_ARCS
                || style == InstrumentStyleFamily.VIRTUAL_CLASSIC
                || style == InstrumentStyleFamily.SUPERSPORT;
        float textSize = bounds.height() * (style == InstrumentStyleFamily.MINIMAL_PANORAMA
                ? .52f : sideBySide ? .47f : .43f);
        paint.setTypeface(digitalTypeface(style, true));
        paint.setTextSize(textSize);
        float maximumTextWidth = bounds.width() * (sideBySide ? .62f : .82f);
        float measured = paint.measureText(number);
        if (measured > maximumTextWidth && measured > 0f) {
            paint.setTextSize(textSize * maximumTextWidth / measured);
        }
        paint.getFontMetrics(fontMetrics);
        float baseline = bounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) * .5f
                - (sideBySide ? 0f : bounds.height() * .07f);
        paint.setColor(withAlpha(style.primaryColor, alpha));
        if (sideBySide) {
            paint.setTextAlign(Paint.Align.LEFT);
            float leftInset = style == InstrumentStyleFamily.M_SPORT_ARCS ? .16f : .10f;
            canvas.drawText(number, bounds.left + bounds.width() * leftInset, baseline, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTypeface(digitalTypeface(style, false));
            paint.setTextSize(bounds.height() * .15f);
            paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 220)));
            canvas.drawText(unit, bounds.right - bounds.width() * .08f,
                    baseline + bounds.height() * .03f, paint);
        } else {
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(number, bounds.centerX(), baseline, paint);
            paint.setTypeface(digitalTypeface(style, false));
            paint.setTextSize(bounds.height() * .15f);
            paint.setColor(withAlpha(style.primaryColor, Math.min(alpha, 190)));
            canvas.drawText(unit, bounds.centerX(), baseline + bounds.height() * .25f, paint);
        }
        drawDigitalProgress(canvas, runtime.config, bounds, value, alpha);
    }

    private void drawStyledText(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                                @NonNull RectF bounds, @NonNull String text,
                                float heightFraction) {
        InstrumentElementConfig element = runtime.config;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(digitalTypeface(element.style, true));
        paint.setTextSize(bounds.height() * heightFraction);
        float maximumWidth = bounds.width() * .82f;
        float measured = paint.measureText(text);
        if (measured > maximumWidth && measured > 0f) {
            paint.setTextSize(paint.getTextSize() * maximumWidth / measured);
        }
        paint.setColor(withAlpha(element.style.primaryColor, alpha));
        paint.getFontMetrics(fontMetrics);
        float baseline = bounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) * .5f;
        canvas.drawText(text, bounds.centerX(), baseline, paint);
    }

    private void drawDigitalProgress(@NonNull Canvas canvas,
                                     @NonNull InstrumentElementConfig element,
                                     @NonNull RectF bounds, float value, int alpha) {
        if (element.style != InstrumentStyleFamily.EV_FLOW || !Float.isFinite(value)) return;
        float maximum;
        float minimum = 0f;
        switch (element.type) {
            case DIGITAL_SPEEDOMETER: maximum = SPEED_MAX; break;
            case DIGITAL_TACHOMETER: maximum = RPM_MAX; break;
            case FUEL_GAUGE: maximum = 70f; break;
            case BATTERY_GAUGE: maximum = 100f; break;
            case COOLANT_TEMPERATURE: minimum = 40f; maximum = 140f; break;
            case INSTANT_CONSUMPTION:
            case AVERAGE_CONSUMPTION:
            case TRIP_CONSUMPTION: maximum = 30f; break;
            default: return;
        }
        float fraction = Math.max(0f, Math.min(1f,
                (value - minimum) / Math.max(1f, maximum - minimum)));
        float left = bounds.left + bounds.width() * .10f;
        float right = bounds.right - bounds.width() * .10f;
        float y = bounds.bottom - bounds.height() * .08f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(3f, bounds.height() * .04f));
        paint.setColor(withAlpha(element.style.accentColor, alpha));
        canvas.drawLine(left, y, left + (right - left) * fraction, y, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawEditor(@NonNull Canvas canvas) {
        InstrumentElementConfig selected = find(selectedId);
        if (selected == null) return;
        bounds(selected, rect);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 2f));
        paint.setColor(0xFF3B9DFF);
        canvas.drawRect(rect, paint);
        paint.setStyle(Paint.Style.FILL);
        float handle = Math.max(18f, getResources().getDisplayMetrics().density * 10f);
        canvas.drawRect(rect.right - handle, rect.bottom - handle, rect.right, rect.bottom, paint);
    }

    @Override public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (!editorMode) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                InstrumentElementConfig hit = hitTest(event.getX(), event.getY());
                dragging = hit;
                if (hit == null) {
                    select(null);
                    return true;
                }
                select(hit.id);
                bounds(hit, rect);
                float threshold = Math.max(30f,
                        18f * getResources().getDisplayMetrics().density);
                resizing = event.getX() >= rect.right - threshold
                        && event.getY() >= rect.bottom - threshold;
                touchStartX = event.getX();
                touchStartY = event.getY();
                originalX = hit.x;
                originalY = hit.y;
                originalWidth = hit.width;
                originalHeight = hit.height;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == null) return true;
                int dx = Math.round((event.getX() - touchStartX) * config.columns / getWidth());
                int dy = Math.round((event.getY() - touchStartY) * config.rows / getHeight());
                if (resizing) {
                    dragging.width = originalWidth + dx;
                    dragging.height = originalHeight + dy;
                } else {
                    dragging.x = originalX + dx;
                    dragging.y = originalY + dy;
                }
                dragging.normalize(config.columns, config.rows);
                staticLayerDirty = true;
                invalidate();
                if (editorListener != null) editorListener.onGeometryChanged(dragging, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                InstrumentElementConfig finished = dragging;
                dragging = null;
                resizing = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (finished != null && editorListener != null) {
                    editorListener.onGeometryChanged(finished, true);
                }
                return true;
            default:
                return true;
        }
    }

    @Nullable
    private InstrumentElementConfig hitTest(float px, float py) {
        for (int index = config.elements.size() - 1; index >= 0; index--) {
            InstrumentElementConfig element = config.elements.get(index);
            if (!element.enabled) continue;
            bounds(element, rect);
            if (rect.contains(px, py)) return element;
        }
        return null;
    }

    @Nullable
    private InstrumentElementConfig find(@Nullable String id) {
        if (id == null) return null;
        for (InstrumentElementConfig element : config.elements) {
            if (id.equals(element.id)) return element;
        }
        return null;
    }

    private float valueFor(@NonNull InstrumentElementType type) {
        if (type == InstrumentElementType.ANALOG_SPEEDOMETER) return frame.speed;
        if (type == InstrumentElementType.ANALOG_TACHOMETER) return frame.rpm;
        if (type == InstrumentElementType.ANALOG_FUEL_GAUGE) return frame.fuel;
        if (type == InstrumentElementType.ANALOG_BATTERY_GAUGE) return frame.battery;
        if (type == InstrumentElementType.ANALOG_COOLANT_TEMPERATURE) {
            return frame.coolantTemperature;
        }
        if (type == InstrumentElementType.ANALOG_INSTANT_CONSUMPTION) {
            return frame.instantConsumption;
        }
        return Float.NaN;
    }

    private void bounds(@NonNull InstrumentElementConfig element, @NonNull RectF out) {
        float cellWidth = getWidth() / (float) config.columns;
        float cellHeight = getHeight() / (float) config.rows;
        out.set(element.x * cellWidth, element.y * cellHeight,
                (element.x + element.width) * cellWidth,
                (element.y + element.height) * cellHeight);
    }

    private void drawStyleGaugeFace(@NonNull Canvas canvas,
                                    @NonNull InstrumentStyleFamily style,
                                    float cx, float cy, float radius, int alpha) {
        gaugeArc.set(cx - radius * .92f, cy - radius * .92f,
                cx + radius * .92f, cy + radius * .92f);
        paint.setStyle(Paint.Style.STROKE);
        if (style == InstrumentStyleFamily.EXECUTIVE_GLASS) {
            paint.setStrokeWidth(Math.max(1f, radius * .009f));
            paint.setColor(withAlpha(style.accentColor, alpha / 2));
            canvas.drawCircle(cx, cy, radius * .84f, paint);
        } else if (style == InstrumentStyleFamily.M_SPORT_ARCS) {
            paint.setStrokeWidth(Math.max(4f, radius * .032f));
            paint.setColor(withAlpha(0xFF43A8FF, alpha));
            canvas.drawArc(gaugeArc, 205f, 38f, false, paint);
            paint.setColor(withAlpha(0xFFE84B55, alpha));
            canvas.drawArc(gaugeArc, 243f, 38f, false, paint);
        } else if (style == InstrumentStyleFamily.SUPERSPORT) {
            paint.setStrokeWidth(Math.max(4f, radius * .04f));
            paint.setColor(withAlpha(style.accentColor, alpha));
            canvas.drawArc(gaugeArc, 335f, 64f, false, paint);
        } else if (style == InstrumentStyleFamily.FIVE_DIAL_HERITAGE) {
            paint.setStrokeWidth(Math.max(2f, radius * .014f));
            paint.setColor(withAlpha(style.accentColor, alpha));
            canvas.drawCircle(cx, cy, radius * .17f, paint);
        } else if (style == InstrumentStyleFamily.NAVIGATION_FIRST
                || style == InstrumentStyleFamily.MINIMAL_PANORAMA) {
            paint.setStrokeWidth(Math.max(2f, radius * .018f));
            paint.setColor(withAlpha(style.accentColor, alpha / 2));
            canvas.drawArc(gaugeArc, gaugeStart(style), gaugeSweep(style), false, paint);
        } else if (style == InstrumentStyleFamily.EV_FLOW) {
            paint.setStrokeWidth(Math.max(3f, radius * .024f));
            paint.setColor(withAlpha(style.secondaryColor, alpha));
            canvas.drawArc(gaugeArc, gaugeStart(style), gaugeSweep(style), false, paint);
        } else if (style == InstrumentStyleFamily.RETRO_MECHANICAL) {
            paint.setStrokeWidth(Math.max(2f, radius * .018f));
            paint.setColor(withAlpha(style.primaryColor, alpha / 2));
            canvas.drawCircle(cx, cy, radius * .82f, paint);
        }
    }

    private static float gaugeRingWidth(@NonNull InstrumentStyleFamily style) {
        if (style == InstrumentStyleFamily.SUPERSPORT) return .034f;
        if (style == InstrumentStyleFamily.MINIMAL_PANORAMA) return .009f;
        if (style == InstrumentStyleFamily.EXECUTIVE_GLASS) return .012f;
        return .018f;
    }

    private static float gaugeStart(@NonNull InstrumentStyleFamily style) {
        switch (style) {
            case FIVE_DIAL_HERITAGE: return 120f;
            case RETRO_MECHANICAL: return 125f;
            case EXECUTIVE_GLASS: return 130f;
            case SUPERSPORT: return 140f;
            case M_SPORT_ARCS: return 150f;
            case NAVIGATION_FIRST:
            case EV_FLOW: return 155f;
            case MINIMAL_PANORAMA: return 160f;
            default: return GAUGE_START_DEGREES;
        }
    }

    private static float gaugeSweep(@NonNull InstrumentStyleFamily style) {
        switch (style) {
            case FIVE_DIAL_HERITAGE: return 300f;
            case RETRO_MECHANICAL: return 290f;
            case EXECUTIVE_GLASS: return 280f;
            case SUPERSPORT: return 260f;
            case M_SPORT_ARCS: return 240f;
            case NAVIGATION_FIRST:
            case EV_FLOW: return 230f;
            case MINIMAL_PANORAMA: return 220f;
            default: return GAUGE_SWEEP_DEGREES;
        }
    }

    private static int gaugeMajorTicks(@NonNull InstrumentStyleFamily style,
                                       @NonNull InstrumentElementType type) {
        if (type == InstrumentElementType.ANALOG_SPEEDOMETER) return 14;
        if (type == InstrumentElementType.ANALOG_TACHOMETER) return 9;
        return 6;
    }

    private static boolean warningTick(@NonNull InstrumentElementType type, float fraction) {
        if (type == InstrumentElementType.ANALOG_FUEL_GAUGE
                || type == InstrumentElementType.ANALOG_BATTERY_GAUGE) {
            return fraction < .16f;
        }
        return (type == InstrumentElementType.ANALOG_TACHOMETER
                || type == InstrumentElementType.ANALOG_COOLANT_TEMPERATURE
                || type == InstrumentElementType.ANALOG_INSTANT_CONSUMPTION)
                && fraction > .80f;
    }

    private static float gaugeMinimum(@NonNull InstrumentElementType type) {
        return type == InstrumentElementType.ANALOG_COOLANT_TEMPERATURE ? 40f : 0f;
    }

    private static float gaugeMaximum(@NonNull InstrumentElementType type) {
        switch (type) {
            case ANALOG_SPEEDOMETER: return SPEED_MAX;
            case ANALOG_TACHOMETER: return RPM_MAX;
            case ANALOG_FUEL_GAUGE: return 70f;
            case ANALOG_BATTERY_GAUGE: return 100f;
            case ANALOG_COOLANT_TEMPERATURE: return 140f;
            case ANALOG_INSTANT_CONSUMPTION: return 30f;
            default: return 100f;
        }
    }

    @NonNull
    private static String gaugeUnit(@NonNull InstrumentElementType type) {
        switch (type) {
            case ANALOG_SPEEDOMETER: return "км/ч";
            case ANALOG_TACHOMETER: return "об/мин";
            case ANALOG_FUEL_GAUGE: return "л";
            case ANALOG_BATTERY_GAUGE: return "%";
            case ANALOG_COOLANT_TEMPERATURE: return "°C";
            case ANALOG_INSTANT_CONSUMPTION: return "л/100";
            default: return "";
        }
    }

    private static int gaugeDecimals(@NonNull InstrumentElementType type) {
        return type == InstrumentElementType.ANALOG_FUEL_GAUGE
                || type == InstrumentElementType.ANALOG_INSTANT_CONSUMPTION ? 1 : 0;
    }

    private static int gaugeMinorTicks(@NonNull InstrumentStyleFamily style) {
        if (style == InstrumentStyleFamily.MINIMAL_PANORAMA) return 1;
        if (style == InstrumentStyleFamily.NAVIGATION_FIRST
                || style == InstrumentStyleFamily.EV_FLOW) return 2;
        if (style == InstrumentStyleFamily.FIVE_DIAL_HERITAGE
                || style == InstrumentStyleFamily.RETRO_MECHANICAL) return 5;
        return 4;
    }

    @NonNull
    private static Typeface digitalTypeface(@NonNull InstrumentStyleFamily style,
                                            boolean bold) {
        if (style == InstrumentStyleFamily.RETRO_MECHANICAL
                || style == InstrumentStyleFamily.GRAND_TOURER
                || style == InstrumentStyleFamily.EXECUTIVE_GLASS) {
            return bold ? SERIF_BOLD : SERIF_REGULAR;
        }
        if (style == InstrumentStyleFamily.VIRTUAL_CLASSIC
                || style == InstrumentStyleFamily.MINIMAL_PANORAMA
                || style == InstrumentStyleFamily.EV_FLOW) {
            return bold ? MONO_BOLD : MONO_REGULAR;
        }
        return bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class RuntimeElement {
        @NonNull final InstrumentElementConfig config;
        float smoothedValue;
        boolean initialized;
        int cachedInteger = Integer.MIN_VALUE;
        @NonNull String cachedIntegerText = "—";
        int cachedTenths = Integer.MIN_VALUE;
        @NonNull String cachedTenthsText = "—";
        int cachedGear = Integer.MIN_VALUE;
        @NonNull String cachedGearText = "—";

        RuntimeElement(@NonNull InstrumentElementConfig config) {
            this.config = config;
        }

        @NonNull String integerText(float value) {
            if (!Float.isFinite(value)) return "—";
            int rounded = Math.round(value);
            if (rounded != cachedInteger) {
                cachedInteger = rounded;
                cachedIntegerText = Integer.toString(rounded);
            }
            return cachedIntegerText;
        }

        @NonNull String oneDecimalText(float value) {
            if (!Float.isFinite(value)) return "—";
            int tenths = Math.round(value * 10f);
            if (tenths != cachedTenths) {
                cachedTenths = tenths;
                int absolute = Math.abs(tenths);
                cachedTenthsText = (tenths < 0 ? "-" : "")
                        + (absolute / 10) + "." + (absolute % 10);
            }
            return cachedTenthsText;
        }

        @NonNull String gearText(float rawValue) {
            if (!Float.isFinite(rawValue)) return "—";
            int value = Math.round(rawValue);
            if (value == cachedGear) return cachedGearText;
            cachedGear = value;
            if (value == 2_097_680) cachedGearText = "N";
            else if (value == 2_097_696) cachedGearText = "D";
            else if (value == 2_097_712) cachedGearText = "P";
            else if (value == 2_097_728) cachedGearText = "R";
            else if (value >= 2_097_665 && value <= 2_097_674) {
                cachedGearText = "D" + (value - 2_097_664);
            } else if (value <= -10_001 && value >= -10_010) {
                cachedGearText = "M" + (-10_000 - value);
            } else cachedGearText = Integer.toString(value);
            return cachedGearText;
        }
    }
}
