/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
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

import dezz.status.widget.navigation.NavigationBridgeStateStore;
import dezz.status.widget.navigation.NavigationSnapshotV2;

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
    private static final long NAVIGATION_FRESH_MS = 2_500L;
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
    @NonNull private final AtomicBoolean navigationWakePosted = new AtomicBoolean();
    @NonNull private final NavigationBridgeStateStore.Listener navigationListener =
            this::onNavigationChanged;
    @NonNull private final Runnable navigationWake = () -> {
        navigationWakePosted.set(false);
        refreshNavigationSnapshot();
    };
    @NonNull private final Runnable navigationExpiry = () -> {
        navigationSnapshot = null;
        invalidate();
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
    private boolean navigationAcquired;
    private boolean frameCallbackPosted;
    private long lastFrameNanos;
    private long lastGeneration = Long.MIN_VALUE;
    @NonNull private String clockText = "--:--";
    @Nullable private String selectedId;
    @Nullable private InstrumentElementConfig dragging;
    @Nullable private NavigationSnapshotV2 navigationSnapshot;
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
        reconcileNavigationDemand();
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
        reconcileNavigationDemand();
    }

    private void reconcileNavigationDemand() {
        boolean needed = isRenderingActive() && config.hasVisibleNavigationInfo();
        if (needed && !navigationAcquired) {
            navigationAcquired = true;
            NavigationBridgeStateStore.addListener(navigationListener);
            refreshNavigationSnapshot();
        } else if (!needed && navigationAcquired) {
            navigationAcquired = false;
            NavigationBridgeStateStore.removeListener(navigationListener);
            navigationSnapshot = null;
            removeCallbacks(navigationWake);
            removeCallbacks(navigationExpiry);
            navigationWakePosted.set(false);
        }
    }

    private void onNavigationChanged() {
        if (!navigationAcquired || !navigationWakePosted.compareAndSet(false, true)) return;
        if (!post(navigationWake)) navigationWakePosted.set(false);
    }

    private void refreshNavigationSnapshot() {
        removeCallbacks(navigationExpiry);
        NavigationSnapshotV2 value = NavigationBridgeStateStore.snapshot();
        long now = System.currentTimeMillis();
        navigationSnapshot = value != null && value.routeActive
                && value.isFreshAt(now, NAVIGATION_FRESH_MS) ? value : null;
        NavigationSnapshotV2 accepted = navigationSnapshot;
        if (accepted != null) {
            long delay = accepted.sourceTimestampMs + NAVIGATION_FRESH_MS - now;
            postDelayed(navigationExpiry, Math.max(1L, delay));
        }
        invalidate();
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
            int save = canvas.save();
            canvas.clipRect(rect);
            drawDynamicElement(canvas, runtime, rect);
            canvas.restoreToCount(save);
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
            float blackEnd = config.blackZonePercent / 100f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(),
                    new int[]{Color.BLACK, Color.BLACK,
                            Color.parseColor(config.backgroundBottomColor)},
                    new float[]{0f, blackEnd, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
            paint.setShader(null);
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
        if (element.type == InstrumentElementType.NAVIGATION_INFO) return;
        InstrumentStyleFamily style = element.style;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        if (element.type.isAnalogGauge()) {
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            float radius = Math.min(bounds.width(), bounds.height()) * .46f;
            if (option(element, "showFace", true)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(style.backgroundColor, Math.min(alpha, 118)));
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, radius * gaugeRingWidth(style)));
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 190)));
                canvas.drawArc(new RectF(cx - radius, cy - radius,
                        cx + radius, cy + radius), gaugeStart(style),
                        gaugeSweep(style), false, paint);
                drawStyleGaugeFace(canvas, style, cx, cy, radius, alpha);
            }
            if (option(element, "showScale", true)) {
                drawGaugeTicks(canvas, element, cx, cy, radius, alpha);
            }
            return;
        }
        boolean faceByDefault = element.type != InstrumentElementType.INFO_BLOCK;
        if (option(element, "showFace", faceByDefault)) {
            drawDigitalFace(canvas, element, bounds, alpha);
        }
    }

    private void drawDigitalFace(@NonNull Canvas canvas,
                                 @NonNull InstrumentElementConfig element,
                                 @NonNull RectF bounds, int alpha) {
        InstrumentStyleFamily style = element.style;
        float shortSide = Math.min(bounds.width(), bounds.height());
        float corner = shortSide * .10f;
        int backgroundAlpha = Math.min(alpha, 76);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(style.backgroundColor, backgroundAlpha));
        canvas.drawRoundRect(bounds, corner, corner, paint);

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        switch (style) {
            case SLATE_HORIZON:
                paint.setStrokeWidth(Math.max(1f, bounds.height() * .012f));
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 170)));
                canvas.drawLine(bounds.left + bounds.width() * .08f,
                        bounds.bottom - bounds.height() * .10f,
                        bounds.right - bounds.width() * .08f,
                        bounds.bottom - bounds.height() * .10f, paint);
                break;
            case GLACIER_MAP:
                paint.setStrokeWidth(Math.max(2f, bounds.height() * .022f));
                paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 205)));
                canvas.drawLine(bounds.left + bounds.width() * .05f,
                        bounds.top + bounds.height() * .16f,
                        bounds.left + bounds.width() * .05f,
                        bounds.bottom - bounds.height() * .16f, paint);
                break;
            case AEROWAVE:
                paint.setStrokeWidth(Math.max(1.5f, bounds.height() * .018f));
                paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 190)));
                canvas.drawLine(bounds.left + bounds.width() * .12f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.right - bounds.width() * .12f,
                        bounds.bottom - bounds.height() * .08f, paint);
                break;
            case STEEL_VECTOR:
                paint.setStrokeWidth(Math.max(1f, bounds.height() * .012f));
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 150)));
                canvas.drawLine(bounds.left + bounds.width() * .04f,
                        bounds.top + bounds.height() * .08f,
                        bounds.right - bounds.width() * .04f,
                        bounds.top + bounds.height() * .08f, paint);
                canvas.drawLine(bounds.left + bounds.width() * .04f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.right - bounds.width() * .04f,
                        bounds.bottom - bounds.height() * .08f, paint);
                break;
            case CONTINUUM:
                paint.setStrokeWidth(Math.max(2f, bounds.height() * .025f));
                paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 220)));
                canvas.drawLine(bounds.left + bounds.width() * .10f,
                        bounds.bottom - bounds.height() * .08f,
                        bounds.left + bounds.width() * .34f,
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
        if (option(element, "showScaleLabels", true)) {
            drawGaugeLabels(canvas, element, cx, cy, radius, alpha, majorTicks);
        }
    }

    private void drawGaugeLabels(@NonNull Canvas canvas,
                                 @NonNull InstrumentElementConfig element,
                                 float cx, float cy, float radius, int alpha, int majorTicks) {
        int labelEvery = element.type == InstrumentElementType.ANALOG_SPEEDOMETER ? 2 : 1;
        if (element.style == InstrumentStyleFamily.STEEL_VECTOR) labelEvery *= 2;
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
            case INFO_BLOCK:
                drawInfoBlock(canvas, runtime, bounds);
                break;
            case NAVIGATION_INFO:
                drawNavigationInfo(canvas, runtime, bounds, navigationSnapshot);
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
        if (option(element, "showNeedle", true)) {
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            float needleWidth = element.style == InstrumentStyleFamily.AEROWAVE ? .030f
                    : element.style == InstrumentStyleFamily.STEEL_VECTOR ? .014f : .022f;
            paint.setStrokeWidth(Math.max(2f, radius * needleWidth));
            paint.setColor(withAlpha(element.style.accentColor, alpha));
            canvas.drawLine(cx - (float) Math.cos(radians) * radius * .12f,
                    cy - (float) Math.sin(radians) * radius * .12f,
                    cx + (float) Math.cos(radians) * needleLength,
                    cy + (float) Math.sin(radians) * needleLength, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius * .055f, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
        if (element.style == InstrumentStyleFamily.CONTINUUM) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(2f, radius * .018f));
            paint.setColor(withAlpha(element.style.accentColor, Math.min(alpha, 210)));
            gaugeArc.set(cx - radius * .91f, cy - radius * .91f,
                    cx + radius * .91f, cy + radius * .91f);
            canvas.drawArc(gaugeArc, start, sweep * fraction, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        if (option(element, "showValue", true)) {
            String valueText = gaugeDecimals(element.type) == 0
                    ? runtime.integerText(rawValue) : runtime.oneDecimalText(rawValue);
            paint.setTypeface(digitalTypeface(element.style, true));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * .28f);
            paint.setColor(withAlpha(element.style.primaryColor, alpha));
            canvas.drawText(valueText, cx, cy + radius * .35f, paint);
        }
        if (option(element, "showUnit", true)) {
            paint.setTypeface(digitalTypeface(element.style, false));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * .095f);
            paint.setColor(withAlpha(element.style.primaryColor, Math.min(alpha, 190)));
            canvas.drawText(unit, cx, cy + radius * .52f, paint);
        }
    }

    private void drawDigital(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                             @NonNull RectF bounds, float value, @NonNull String unit,
                             int decimals) {
        String number = decimals == 0
                ? runtime.integerText(value) : runtime.oneDecimalText(value);
        InstrumentStyleFamily style = runtime.config.style;
        int alpha = Math.round(255f * runtime.config.opacityPercent / 100f);
        boolean sideBySide = style == InstrumentStyleFamily.AEROWAVE
                || style == InstrumentStyleFamily.CONTINUUM;
        float textSize = bounds.height() * (style == InstrumentStyleFamily.STEEL_VECTOR
                ? .54f : sideBySide ? .47f : .43f);
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
            float leftInset = style == InstrumentStyleFamily.AEROWAVE ? .14f : .10f;
            canvas.drawText(number, bounds.left + bounds.width() * leftInset, baseline, paint);
            if (option(runtime.config, "showUnit", true)) {
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setTypeface(digitalTypeface(style, false));
                paint.setTextSize(bounds.height() * .15f);
                paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 220)));
                canvas.drawText(unit, bounds.right - bounds.width() * .08f,
                        baseline + bounds.height() * .03f, paint);
            }
        } else {
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(number, bounds.centerX(), baseline, paint);
            if (option(runtime.config, "showUnit", true)) {
                paint.setTypeface(digitalTypeface(style, false));
                paint.setTextSize(bounds.height() * .15f);
                paint.setColor(withAlpha(style.primaryColor, Math.min(alpha, 190)));
                canvas.drawText(unit, bounds.centerX(), baseline + bounds.height() * .25f, paint);
            }
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
        if (!option(element, "showProgress", true) || !Float.isFinite(value)) return;
        if (element.style != InstrumentStyleFamily.GLACIER_MAP
                && element.style != InstrumentStyleFamily.STEEL_VECTOR
                && element.style != InstrumentStyleFamily.CONTINUUM) return;
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

    private void drawInfoBlock(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                               @NonNull RectF bounds) {
        InstrumentElementConfig element = runtime.config;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        float rowHeight = bounds.height() / 3f;
        for (int index = 0; index < runtime.infoMetrics.length; index++) {
            InstrumentInfoMetric metric = runtime.infoMetrics[index];
            if (metric == InstrumentInfoMetric.NONE) continue;
            float centerY = bounds.top + rowHeight * (index + .5f);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(digitalTypeface(element.style, false));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(Math.max(8f, rowHeight * .24f));
            paint.setColor(withAlpha(element.style.secondaryColor, Math.min(alpha, 220)));
            canvas.drawText(metric.label, bounds.left + bounds.width() * .08f,
                    centerY - rowHeight * .08f, paint);

            float value = infoValue(metric);
            String valueText = metric.decimals == 0
                    ? runtime.rowIntegerText(index, value)
                    : runtime.rowDecimalText(index, value);
            paint.setTypeface(digitalTypeface(element.style, true));
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(Math.max(10f, rowHeight * .38f));
            paint.setColor(withAlpha(element.style.primaryColor, alpha));
            canvas.drawText(valueText, bounds.right - bounds.width() * .23f,
                    centerY + rowHeight * .18f, paint);
            paint.setTypeface(digitalTypeface(element.style, false));
            paint.setTextSize(Math.max(7f, rowHeight * .20f));
            paint.setColor(withAlpha(element.style.accentColor, Math.min(alpha, 220)));
            canvas.drawText(metric.unit, bounds.right - bounds.width() * .06f,
                    centerY + rowHeight * .18f, paint);
        }
    }

    private void drawNavigationInfo(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                                    @NonNull RectF bounds,
                                    @Nullable NavigationSnapshotV2 navigation) {
        // Route-bound information must disappear completely when the publisher is stale.
        if (navigation == null) return;
        runtime.updateNavigation(navigation);
        InstrumentElementConfig element = runtime.config;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        if (option(element, "showFace", false)) drawDigitalFace(canvas, element, bounds, alpha);

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(digitalTypeface(element.style, true));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(bounds.height() * .42f);
        paint.setColor(withAlpha(element.style.primaryColor, alpha));
        canvas.drawText(runtime.navigationArrow,
                bounds.left + bounds.width() * .07f, bounds.top + bounds.height() * .51f, paint);

        paint.setTextSize(bounds.height() * .30f);
        canvas.drawText(runtime.navigationDistance,
                bounds.left + bounds.width() * .27f, bounds.top + bounds.height() * .48f, paint);
        paint.setTypeface(digitalTypeface(element.style, false));
        paint.setTextSize(bounds.height() * .15f);
        paint.setColor(withAlpha(element.style.primaryColor, Math.min(alpha, 220)));
        canvas.drawText(runtime.navigationTitle,
                bounds.left + bounds.width() * .27f, bounds.top + bounds.height() * .70f, paint);

        if (option(element, "showStreet", true) && !runtime.navigationStreet.isEmpty()) {
            paint.setTextSize(bounds.height() * .12f);
            paint.setColor(withAlpha(element.style.secondaryColor, Math.min(alpha, 210)));
            canvas.drawText(runtime.navigationStreet,
                    bounds.left + bounds.width() * .27f, bounds.top + bounds.height() * .88f, paint);
        }
        if (option(element, "showArrival", true) && !runtime.navigationRemaining.isEmpty()) {
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(bounds.height() * .12f);
            paint.setColor(withAlpha(element.style.accentColor, Math.min(alpha, 220)));
            canvas.drawText(runtime.navigationRemaining,
                    bounds.right - bounds.width() * .06f, bounds.top + bounds.height() * .18f, paint);
        }
    }

    private float infoValue(@NonNull InstrumentInfoMetric metric) {
        switch (metric) {
            case RANGE: return Float.isFinite(frame.totalRange) ? frame.totalRange : frame.fuelRange;
            case FUEL: return frame.fuel;
            case BATTERY: return frame.battery;
            case AMBIENT_TEMPERATURE: return frame.ambientTemperature;
            case COOLANT_TEMPERATURE: return frame.coolantTemperature;
            case INSTANT_CONSUMPTION: return frame.instantConsumption;
            case AVERAGE_CONSUMPTION: return frame.averageConsumption;
            case TRIP_CONSUMPTION: return frame.tripConsumption;
            case ODOMETER: return frame.odometer;
            case RPM: return frame.rpm;
            case SPEED: return frame.speed;
            case NONE:
            default: return Float.NaN;
        }
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
        switch (style) {
            case SLATE_HORIZON:
                paint.setStrokeWidth(Math.max(1f, radius * .010f));
                paint.setColor(withAlpha(style.accentColor, alpha / 2));
                canvas.drawCircle(cx, cy, radius * .83f, paint);
                break;
            case GLACIER_MAP:
                paint.setStrokeWidth(Math.max(2f, radius * .018f));
                paint.setColor(withAlpha(style.accentColor, alpha / 2));
                canvas.drawArc(gaugeArc, gaugeStart(style), gaugeSweep(style), false, paint);
                break;
            case AEROWAVE:
                paint.setStrokeWidth(Math.max(3f, radius * .030f));
                paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 220)));
                canvas.drawArc(gaugeArc, 207f, 34f, false, paint);
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 180)));
                canvas.drawArc(gaugeArc, 242f, 34f, false, paint);
                break;
            case STEEL_VECTOR:
                paint.setStrokeWidth(Math.max(1f, radius * .009f));
                paint.setColor(withAlpha(style.secondaryColor, alpha / 2));
                canvas.drawArc(gaugeArc, gaugeStart(style), gaugeSweep(style), false, paint);
                break;
            case CONTINUUM:
                paint.setStrokeWidth(Math.max(2f, radius * .020f));
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 175)));
                canvas.drawArc(gaugeArc, gaugeStart(style), gaugeSweep(style), false, paint);
                break;
            default:
                break;
        }
    }

    private static float gaugeRingWidth(@NonNull InstrumentStyleFamily style) {
        if (style == InstrumentStyleFamily.AEROWAVE) return .028f;
        if (style == InstrumentStyleFamily.STEEL_VECTOR) return .009f;
        if (style == InstrumentStyleFamily.SLATE_HORIZON) return .012f;
        return .018f;
    }

    private static float gaugeStart(@NonNull InstrumentStyleFamily style) {
        switch (style) {
            case SLATE_HORIZON: return 130f;
            case GLACIER_MAP: return 150f;
            case AEROWAVE: return 145f;
            case STEEL_VECTOR: return 160f;
            case CONTINUUM: return 155f;
            default: return GAUGE_START_DEGREES;
        }
    }

    private static float gaugeSweep(@NonNull InstrumentStyleFamily style) {
        switch (style) {
            case SLATE_HORIZON: return 280f;
            case GLACIER_MAP: return 240f;
            case AEROWAVE: return 250f;
            case STEEL_VECTOR: return 220f;
            case CONTINUUM: return 230f;
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
        if (style == InstrumentStyleFamily.STEEL_VECTOR) return 1;
        if (style == InstrumentStyleFamily.GLACIER_MAP
                || style == InstrumentStyleFamily.CONTINUUM) return 2;
        if (style == InstrumentStyleFamily.SLATE_HORIZON) return 5;
        return 4;
    }

    @NonNull
    private static Typeface digitalTypeface(@NonNull InstrumentStyleFamily style,
                                            boolean bold) {
        if (style == InstrumentStyleFamily.GLACIER_MAP
                || style == InstrumentStyleFamily.STEEL_VECTOR
                || style == InstrumentStyleFamily.CONTINUUM) {
            return bold ? MONO_BOLD : MONO_REGULAR;
        }
        return bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    private static boolean option(@NonNull InstrumentElementConfig element,
                                  @NonNull String key, boolean fallback) {
        return element.options.optBoolean(key, fallback);
    }

    @NonNull
    private static String navigationArrow(@NonNull String raw) {
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.contains("UTURN") || value.contains("U_TURN")) return "↶";
        if (value.contains("LEFT")) return "↰";
        if (value.contains("RIGHT")) return "↱";
        if (value.contains("ROUNDABOUT")) return "↻";
        if (value.contains("FINISH")) return "⚑";
        return "↑";
    }

    @NonNull
    private static String distanceText(int meters) {
        if (meters < 0) return "";
        if (meters < 1_000) return meters + " м";
        int tenths = Math.round(meters / 100f);
        return tenths < 100 ? (tenths / 10) + "." + (tenths % 10) + " км"
                : Math.round(meters / 1_000f) + " км";
    }

    @NonNull
    private static String durationText(int seconds) {
        if (seconds < 0) return "";
        int minutes = Math.max(0, (seconds + 30) / 60);
        if (minutes < 60) return minutes + " мин";
        int hours = minutes / 60;
        int remainder = minutes % 60;
        return remainder == 0 ? hours + " ч" : hours + " ч " + remainder + " мин";
    }

    @NonNull
    private static String firstText(@NonNull String first, @NonNull String second,
                                    @NonNull String third) {
        if (!first.isEmpty()) return first;
        if (!second.isEmpty()) return second;
        return third;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class RuntimeElement {
        @NonNull final InstrumentElementConfig config;
        @NonNull final InstrumentInfoMetric[] infoMetrics = new InstrumentInfoMetric[3];
        @NonNull final int[] cachedRowIntegers = {
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        @NonNull final String[] cachedRowIntegerText = {"—", "—", "—"};
        @NonNull final int[] cachedRowTenths = {
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        @NonNull final String[] cachedRowTenthsText = {"—", "—", "—"};
        float smoothedValue;
        boolean initialized;
        int cachedInteger = Integer.MIN_VALUE;
        @NonNull String cachedIntegerText = "—";
        int cachedTenths = Integer.MIN_VALUE;
        @NonNull String cachedTenthsText = "—";
        int cachedGear = Integer.MIN_VALUE;
        @NonNull String cachedGearText = "—";
        long cachedNavigationSequence = Long.MIN_VALUE;
        @NonNull String navigationArrow = "↑";
        @NonNull String navigationDistance = "";
        @NonNull String navigationTitle = "";
        @NonNull String navigationStreet = "";
        @NonNull String navigationRemaining = "";

        RuntimeElement(@NonNull InstrumentElementConfig config) {
            this.config = config;
            infoMetrics[0] = InstrumentInfoMetric.fromName(
                    config.options.optString("row1", null), InstrumentInfoMetric.RANGE);
            infoMetrics[1] = InstrumentInfoMetric.fromName(
                    config.options.optString("row2", null),
                    InstrumentInfoMetric.AVERAGE_CONSUMPTION);
            infoMetrics[2] = InstrumentInfoMetric.fromName(
                    config.options.optString("row3", null),
                    InstrumentInfoMetric.AMBIENT_TEMPERATURE);
        }

        @NonNull String rowIntegerText(int row, float value) {
            if (!Float.isFinite(value)) return "—";
            int rounded = Math.round(value);
            if (rounded != cachedRowIntegers[row]) {
                cachedRowIntegers[row] = rounded;
                cachedRowIntegerText[row] = Integer.toString(rounded);
            }
            return cachedRowIntegerText[row];
        }

        @NonNull String rowDecimalText(int row, float value) {
            if (!Float.isFinite(value)) return "—";
            int tenths = Math.round(value * 10f);
            if (tenths != cachedRowTenths[row]) {
                cachedRowTenths[row] = tenths;
                int absolute = Math.abs(tenths);
                cachedRowTenthsText[row] = (tenths < 0 ? "-" : "")
                        + (absolute / 10) + "." + (absolute % 10);
            }
            return cachedRowTenthsText[row];
        }

        void updateNavigation(@NonNull NavigationSnapshotV2 value) {
            if (cachedNavigationSequence == value.sequence) return;
            cachedNavigationSequence = value.sequence;
            navigationArrow = InstrumentClusterView.navigationArrow(value.maneuverType);
            navigationDistance = distanceText(value.maneuverDistanceMeters);
            navigationTitle = firstText(value.maneuverTitle, value.street, value.destination);
            navigationStreet = value.street.equals(navigationTitle) ? "" : value.street;
            String remainingDistance = distanceText(value.remainingDistanceMeters);
            String remainingTime = durationText(value.remainingDurationSeconds);
            navigationRemaining = remainingDistance.isEmpty() ? remainingTime
                    : remainingTime.isEmpty() ? remainingDistance
                    : remainingDistance + " · " + remainingTime;
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
