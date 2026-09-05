/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.launcher.NavigationDataRepository;
import dezz.status.widget.navigation.NavigationBridgeStateStore;
import dezz.status.widget.navigation.NavigationIntegrationConfig;
import dezz.status.widget.navigation.NavigationRouteGeometryV2;
import dezz.status.widget.navigation.NavigationSnapshotV2;
import dezz.status.widget.navigation.StockManeuverCardState;
import dezz.status.widget.hud.StockManeuverCardRenderer;

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
    @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    @NonNull private final Paint clearPaint = new Paint();
    @NonNull private final RectF rect = new RectF();
    @NonNull private final RectF gaugeArc = new RectF();
    @NonNull private final RectF routeBarRect = new RectF();
    @NonNull private final Path routeProgressPath = new Path();
    @NonNull private final Calendar calendar = Calendar.getInstance();
    @NonNull private final char[] clockBuffer = {'0', '0', ':', '0', '0'};
    @NonNull private final List<RuntimeElement> runtimeElements = new ArrayList<>();
    @NonNull private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private final boolean editorMode;
    @Nullable private final EditorListener editorListener;
    @NonNull private InstrumentPanelConfig config;
    @NonNull private NavigationIntegrationConfig.MapProfile navigationProfile =
            NavigationIntegrationConfig.MapProfile.defaults(
                    NavigationIntegrationConfig.Target.CLUSTER);
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
    @NonNull private final BroadcastReceiver navigationGraphicReceiver =
            new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (NavigationDataRepository.ACTION_UPDATED.equals(intent.getAction())) {
                        onNavigationChanged();
                    }
                }
            };
    @NonNull private final Runnable navigationWake = () -> {
        navigationWakePosted.set(false);
        refreshNavigationSnapshot();
    };
    @NonNull private final Runnable navigationExpiry = () -> {
        navigationSnapshot = null;
        navigationGeometry = null;
        navigationManeuverImage = null;
        commandCard = StockManeuverCardState.HIDDEN;
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
    private boolean navigationGraphicReceiverRegistered;
    private boolean frameCallbackPosted;
    private long lastFrameNanos;
    private long lastGeneration = Long.MIN_VALUE;
    @NonNull private String clockText = "--:--";
    @Nullable private String selectedId;
    @Nullable private InstrumentElementConfig dragging;
    @Nullable private NavigationSnapshotV2 navigationSnapshot;
    @Nullable private NavigationRouteGeometryV2 navigationGeometry;
    /** Reserved only for keyed source art; direct snapshots intentionally leave it null. */
    @Nullable private Bitmap navigationManeuverImage;
    private StockManeuverCardState commandCard = StockManeuverCardState.LEGACY;
    private StockManeuverCardRenderer commandCardRenderer;
    private StockManeuverCardRenderer commandCardRenderer() {
        if (commandCardRenderer == null) commandCardRenderer = new StockManeuverCardRenderer(getContext());
        return commandCardRenderer;
    }
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

    /** Keeps the route card on the same user-selected congestion palette as the cluster map. */
    public void setNavigationProfile(
            @NonNull NavigationIntegrationConfig.MapProfile value) {
        navigationProfile = value;
        invalidate();
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
            try {
                ContextCompat.registerReceiver(getContext(), navigationGraphicReceiver,
                        new IntentFilter(NavigationDataRepository.ACTION_UPDATED),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                navigationGraphicReceiverRegistered = true;
            } catch (RuntimeException ignored) {
                navigationGraphicReceiverRegistered = false;
            }
            refreshNavigationSnapshot();
        } else if (!needed && navigationAcquired) {
            navigationAcquired = false;
            NavigationBridgeStateStore.removeListener(navigationListener);
            if (navigationGraphicReceiverRegistered) {
                try { getContext().unregisterReceiver(navigationGraphicReceiver); }
                catch (RuntimeException ignored) { }
                navigationGraphicReceiverRegistered = false;
            }
            navigationSnapshot = null;
            navigationGeometry = null;
            navigationManeuverImage = null;
        commandCard = StockManeuverCardState.HIDDEN;
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
        commandCard = accepted == null ? StockManeuverCardState.HIDDEN : StockManeuverCardState.parse(
                accepted.maneuverCardJson, accepted.routeEpoch, accepted.routeActive);
        NavigationRouteGeometryV2 geometry = NavigationBridgeStateStore.routeGeometry();
        navigationGeometry = accepted != null && geometry != null
                && geometry.routeEpoch == accepted.routeEpoch ? geometry : null;
        // Artwork is joined only by the bridge store after matching this exact maneuver identity.
        navigationManeuverImage = accepted == null
                ? null : NavigationBridgeStateStore.maneuverArtworkFor(accepted);
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
                drawMapVignette(canvas, element, rect);
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
        if (element.type == InstrumentElementType.NAVIGATION_INFO
                || element.type == InstrumentElementType.NAVIGATION_ROUTE_SUMMARY
                || element.type == InstrumentElementType.TRAFFIC_JAM) return;
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
            }
            // The approved Aerowave and Continuum faces are open arcs over the map. The scale
            // therefore owns the ring; hiding the optional dial background must not erase it.
            if (option(element, "showScale", true)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, radius * gaugeRingWidth(style)));
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 190)));
                canvas.drawArc(new RectF(cx - radius, cy - radius,
                        cx + radius, cy + radius), gaugeStart(element),
                        gaugeSweep(element), false, paint);
                drawStyleGaugeFace(canvas, element, cx, cy, radius, alpha);
                drawGaugeTicks(canvas, element, cx, cy, radius, alpha);
            }
            return;
        }
        String presentation = presentation(element);
        if ("HORIZONTAL_RULER".equals(presentation)) {
            drawHorizontalRulerFace(canvas, element, bounds, alpha);
            return;
        }
        if ("VERTICAL_RULER".equals(presentation)) {
            drawVerticalRulerFace(canvas, element, bounds, alpha);
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
            float angle = gaugeStart(element) + gaugeSweep(element) * tick / total;
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
            float angle = gaugeStart(element) + gaugeSweep(element) * fraction;
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
                if ("HORIZONTAL_RULER".equals(presentation(element))) {
                    drawHorizontalRulerValue(canvas, runtime, bounds, frame.rpm);
                } else if ("VERTICAL_RULER".equals(presentation(element))) {
                    drawVerticalRulerValue(canvas, runtime, bounds, frame.rpm);
                } else {
                    drawDigital(canvas, runtime, bounds, frame.rpm / 1_000f,
                            "x1000 об/мин", 1);
                }
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
            case NAVIGATION_ROUTE_SUMMARY:
                drawNavigationInfo(canvas, runtime, bounds, navigationSnapshot);
                break;
            case TRAFFIC_JAM:
                drawTrafficJamForecast(canvas, runtime, bounds, navigationSnapshot);
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
        float start = gaugeStart(element);
        float sweep = gaugeSweep(element);
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
        if (element.style == InstrumentStyleFamily.AEROWAVE
                || element.style == InstrumentStyleFamily.CONTINUUM) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(2f, radius
                    * (element.style == InstrumentStyleFamily.AEROWAVE ? .038f : .018f)));
            paint.setColor(withAlpha(element.style.accentColor, Math.min(alpha, 210)));
            gaugeArc.set(cx - radius * .91f, cy - radius * .91f,
                    cx + radius * .91f, cy + radius * .91f);
            canvas.drawArc(gaugeArc, start, sweep * fraction, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        if (option(element, "showValue", true)) {
            float displayValue = element.type == InstrumentElementType.ANALOG_TACHOMETER
                    ? rawValue / 1_000f : rawValue;
            String valueText = gaugeDecimals(element.type) == 0
                    ? runtime.integerText(displayValue) : runtime.oneDecimalText(displayValue);
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

    /** Feather the live TextureView into the approved black/blue instrument artwork. */
    private void drawMapVignette(@NonNull Canvas canvas,
                                 @NonNull InstrumentElementConfig element,
                                 @NonNull RectF bounds) {
        if (!option(element, "fadeEdges", true)) return;
        float fraction = Math.max(.04f, Math.min(.35f,
                element.options.optInt("fadePercent", 16) / 100f));
        float horizontal = bounds.width() * fraction;
        float vertical = bounds.height() * fraction;
        int edge = withAlpha(Color.BLACK,
                Math.round(245f * element.opacityPercent / 100f));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(bounds.left, 0f,
                bounds.left + horizontal, 0f,
                edge, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.left, bounds.top, bounds.left + horizontal, bounds.bottom, paint);
        paint.setShader(new LinearGradient(bounds.right, 0f,
                bounds.right - horizontal, 0f,
                edge, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.right - horizontal, bounds.top, bounds.right, bounds.bottom, paint);
        paint.setShader(new LinearGradient(0f, bounds.top, 0f,
                bounds.top + vertical, edge, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + vertical, paint);
        int bottomColor = withAlpha(Color.parseColor(config.backgroundBottomColor),
                Math.round(235f * element.opacityPercent / 100f));
        paint.setShader(new LinearGradient(0f, bounds.bottom, 0f,
                bounds.bottom - vertical, bottomColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.left, bounds.bottom - vertical,
                bounds.right, bounds.bottom, paint);
        paint.setShader(null);
    }

    private void drawHorizontalRulerFace(@NonNull Canvas canvas,
                                         @NonNull InstrumentElementConfig element,
                                         @NonNull RectF bounds, int alpha) {
        float left = bounds.left + bounds.width() * .08f;
        float right = bounds.right - bounds.width() * .08f;
        float y = bounds.top + bounds.height() * .62f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(Math.max(1f, bounds.height() * .012f));
        paint.setColor(withAlpha(element.style.secondaryColor, Math.min(alpha, 180)));
        canvas.drawLine(left, y, right, y, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(digitalTypeface(element.style, false));
        paint.setTextSize(Math.max(8f, bounds.height() * .17f));
        for (int index = 0; index <= 8; index++) {
            float x = left + (right - left) * index / 8f;
            paint.setColor(withAlpha(index >= 7 ? 0xFFFF3B30
                    : element.style.primaryColor, Math.min(alpha, 210)));
            canvas.drawRect(x - 1f, y - bounds.height() * .10f,
                    x + 1f, y + bounds.height() * .06f, paint);
            canvas.drawText(Integer.toString(index), x,
                    y - bounds.height() * .15f, paint);
        }
    }

    private void drawHorizontalRulerValue(@NonNull Canvas canvas,
                                          @NonNull RuntimeElement runtime,
                                          @NonNull RectF bounds, float rpm) {
        InstrumentElementConfig element = runtime.config;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        float fraction = Float.isFinite(rpm)
                ? Math.max(0f, Math.min(1f, rpm / RPM_MAX)) : 0f;
        float left = bounds.left + bounds.width() * .08f;
        float right = bounds.right - bounds.width() * .08f;
        float y = bounds.top + bounds.height() * .72f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStrokeWidth(Math.max(4f, bounds.height() * .055f));
        paint.setColor(withAlpha(element.style.accentColor, alpha));
        canvas.drawLine(left, y, left + (right - left) * fraction, y, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(digitalTypeface(element.style, true));
        paint.setTextSize(bounds.height() * .28f);
        paint.setColor(withAlpha(element.style.primaryColor, alpha));
        canvas.drawText(runtime.oneDecimalText(rpm / 1_000f), right,
                bounds.bottom - bounds.height() * .03f, paint);
    }

    private void drawVerticalRulerFace(@NonNull Canvas canvas,
                                       @NonNull InstrumentElementConfig element,
                                       @NonNull RectF bounds, int alpha) {
        float x = bounds.centerX();
        float top = bounds.top + bounds.height() * .08f;
        float bottom = bounds.bottom - bounds.height() * .08f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, bounds.width() * .015f));
        paint.setColor(withAlpha(element.style.secondaryColor, Math.min(alpha, 185)));
        canvas.drawLine(x, top, x, bottom, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(digitalTypeface(element.style, false));
        paint.setTextSize(Math.max(8f, bounds.width() * .16f));
        for (int index = 0; index <= 8; index++) {
            float y = bottom - (bottom - top) * index / 8f;
            float length = bounds.width() * (index % 2 == 0 ? .15f : .09f);
            paint.setColor(withAlpha(index >= 7 ? 0xFFFF3B30
                    : element.style.primaryColor, Math.min(alpha, 210)));
            canvas.drawRect(x - length, y - 1f, x + bounds.width() * .04f, y + 1f, paint);
            if (index % 2 == 0 && index > 0) {
                canvas.drawText(Integer.toString(index), x - length - bounds.width() * .05f,
                        y - (paint.ascent() + paint.descent()) * .5f, paint);
            }
        }
    }

    private void drawVerticalRulerValue(@NonNull Canvas canvas,
                                        @NonNull RuntimeElement runtime,
                                        @NonNull RectF bounds, float rpm) {
        InstrumentElementConfig element = runtime.config;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        float fraction = Float.isFinite(rpm)
                ? Math.max(0f, Math.min(1f, rpm / RPM_MAX)) : 0f;
        float x = bounds.centerX() + bounds.width() * .08f;
        float top = bounds.top + bounds.height() * .08f;
        float bottom = bounds.bottom - bounds.height() * .08f;
        float y = bottom - (bottom - top) * fraction;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF71D94B, alpha));
        canvas.drawRoundRect(x, y - bounds.height() * .025f,
                x + bounds.width() * .10f, bottom,
                bounds.width() * .03f, bounds.width() * .03f, paint);
        canvas.drawCircle(x + bounds.width() * .05f, y,
                Math.max(3f, bounds.width() * .075f), paint);
    }

    private void drawNavigationInfo(@NonNull Canvas canvas, @NonNull RuntimeElement runtime,
                                    @NonNull RectF bounds,
                                    @Nullable NavigationSnapshotV2 navigation) {
        // Route-bound information must disappear completely when the publisher is stale.
        if (navigation == null) return;
        runtime.updateNavigation(navigation, navigationGeometry);
        InstrumentElementConfig element = runtime.config;
        if (element.type == InstrumentElementType.NAVIGATION_ROUTE_SUMMARY
                && (runtime.navigationRemainingDistance.isEmpty()
                || runtime.navigationArrival.isEmpty()
                || runtime.navigationDuration.isEmpty())) return;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        if (option(element, "showFace", true)) {
            int faceColor = navigationColor(element.options.optString(
                    "faceColor", "#FF15171B"), 0xFF15171B);
            int faceOpacity = Math.max(0, Math.min(100,
                    element.options.optInt("faceOpacityPercent", 93)));
            float corner = Math.min(Math.min(bounds.width(), bounds.height()) * .5f,
                    Math.max(0f, element.options.optInt("faceCornerRadiusPx", 18)));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(faceColor, Math.round(
                    Color.alpha(faceColor) * faceOpacity / 100f * alpha / 255f)));
            canvas.drawRoundRect(bounds, corner, corner, paint);
            float borderWidth = Math.max(0f,
                    element.options.optInt("faceBorderWidthPx", 0));
            if (borderWidth > 0f) {
                int borderColor = navigationColor(element.options.optString(
                        "faceBorderColor", "#00000000"), 0x00000000);
                rect.set(bounds);
                rect.inset(borderWidth * .5f, borderWidth * .5f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(borderWidth);
                paint.setColor(withAlpha(borderColor, Math.round(
                        Color.alpha(borderColor) * alpha / 255f)));
                canvas.drawRoundRect(rect, corner, corner, paint);
            }
        }

        boolean showDistance = option(element, "showDistance", true);
        boolean showEta = option(element, "showEta", true);
        boolean showDuration = option(element, "showDuration", true);
        boolean showProgress = option(element, "showRouteProgress", true);
        RectF content = insetSides(bounds,
                element.options.optInt("contentPaddingLeftPx", 14),
                element.options.optInt("contentPaddingTopPx", 10),
                element.options.optInt("contentPaddingRightPx", 14),
                element.options.optInt("contentPaddingBottomPx", 10));
        if (content.isEmpty()) return;

        boolean showIcon = option(element, "showManeuverIcon", true);
        boolean sourceIconAvailable = !commandCard.enabled && navigationManeuverImage != null
                && !navigationManeuverImage.isRecycled();
        boolean reserveIcon = !commandCard.enabled && option(element, "reserveManeuverIconSpace", true);
        RectF metricsArea = new RectF(content);
        if (showIcon && (sourceIconAvailable || reserveIcon)) {
            float iconWidth = content.width() * Math.max(5, Math.min(40,
                    element.options.optInt("maneuverIconAreaPercent", 15))) / 100f;
            RectF iconArea = new RectF(content.left, content.top,
                    Math.min(content.right, content.left + iconWidth), content.bottom);
            int iconBackground = navigationColor(element.options.optString(
                    "maneuverIconBackgroundColor", "#FF2B2E35"), 0xFF2B2E35);
            int iconBackgroundOpacity = Math.max(0, Math.min(100,
                    element.options.optInt("maneuverIconBackgroundOpacityPercent", 100)));
            float iconCorner = Math.min(Math.min(iconArea.width(), iconArea.height()) * .5f,
                    Math.max(0f, element.options.optInt("maneuverIconCornerRadiusPx", 12)));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(iconBackground, Math.round(
                    Color.alpha(iconBackground) * iconBackgroundOpacity / 100f
                            * alpha / 255f)));
            canvas.drawRoundRect(iconArea, iconCorner, iconCorner, paint);
            if (sourceIconAvailable) {
                RectF iconTarget = insetSides(iconArea,
                        element.options.optInt("maneuverIconPaddingLeftPx", 5),
                        element.options.optInt("maneuverIconPaddingTopPx", 5),
                        element.options.optInt("maneuverIconPaddingRightPx", 5),
                        element.options.optInt("maneuverIconPaddingBottomPx", 5));
                float iconScale = Math.max(25, Math.min(250,
                        element.options.optInt("maneuverIconScalePercent", 100))) / 100f;
                iconTarget = scaleAroundCenter(iconTarget, iconScale, iconArea);
                drawSourceBitmap(canvas, navigationManeuverImage, iconTarget, alpha);
            }
            metricsArea.left = Math.min(metricsArea.right,
                    iconArea.right + Math.max(0,
                            element.options.optInt("maneuverIconGapPx", 10)));
        }

        RectF valuesArea = new RectF(metricsArea);
        if (option(element, "showManeuverDetails", true)
                && (commandCard.enabled ? commandCardRenderer().available(commandCard)
                    : runtime.hasNavigationManeuverDetails()) && !metricsArea.isEmpty()) {
            float detailsFraction = Math.max(20, Math.min(65,
                    element.options.optInt("maneuverDetailsHeightPercent", 42))) / 100f;
            float detailsHeight = Math.min(metricsArea.height() * .65f,
                    Math.max(18f, metricsArea.height() * detailsFraction));
            RectF detailsArea = new RectF(metricsArea.left, metricsArea.top,
                    metricsArea.right, metricsArea.top + detailsHeight);
            drawNavigationManeuverDetails(canvas, runtime, detailsArea, element, alpha);
            valuesArea.top = Math.min(valuesArea.bottom, detailsArea.bottom + Math.max(0f,
                    element.options.optInt("maneuverDetailsGapPx", 4)));
        }
        if (showProgress) {
            float barHeight = Math.max(2f, Math.min(metricsArea.height(),
                    element.options.optInt("progressBarHeightPx", 14)));
            float barGap = Math.max(0f,
                    element.options.optInt("progressBarTopGapPx", 9));
            routeBarRect.set(metricsArea.left,
                    Math.max(metricsArea.top, metricsArea.bottom - barHeight),
                    metricsArea.right, metricsArea.bottom);
            valuesArea.bottom = Math.max(valuesArea.top, routeBarRect.top - barGap);
        }

        int metricCount = (showDistance ? 1 : 0) + (showEta ? 1 : 0)
                + (showDuration ? 1 : 0);
        if (metricCount > 0 && !valuesArea.isEmpty()) {
            float metricGap = Math.max(0f, element.options.optInt("metricGapPx", 10));
            float totalGap = metricGap * Math.max(0, metricCount - 1);
            float cellWidth = Math.max(1f, (valuesArea.width() - totalGap) / metricCount);
            float vertical = Math.max(0, Math.min(100,
                    element.options.optInt("metricsVerticalPercent", 44))) / 100f;
            float baselineCenter = valuesArea.top + valuesArea.height() * vertical;
            float x = valuesArea.left + cellWidth * .5f;
            if (showDistance) {
                drawNavigationMetric(canvas, runtime.navigationRemainingDistance,
                        x, baselineCenter, cellWidth,
                        element.options.optInt("distanceTextSizeSp", 25),
                        valuesArea.height(),
                        element, alpha);
                x += cellWidth + metricGap;
            }
            if (showEta) {
                drawNavigationMetric(canvas, runtime.navigationArrival,
                        x, baselineCenter, cellWidth,
                        element.options.optInt("arrivalTextSizeSp", 25),
                        valuesArea.height(),
                        element, alpha);
                x += cellWidth + metricGap;
            }
            if (showDuration) {
                drawNavigationMetric(canvas, runtime.navigationDuration,
                        x, baselineCenter, cellWidth,
                        element.options.optInt("durationTextSizeSp", 25),
                        valuesArea.height(),
                        element, alpha);
            }
        }
        if (showProgress) {
            drawNavigationRouteProgress(canvas, runtime, routeBarRect, alpha, element);
        }
    }

    /**
     * Stock ManeuverView content is structured: colored direction signs and the auxiliary row are
     * not ordinary street text. Draw each region directly at its final size so a resized cluster
     * element stays sharp and an absent region collapses without leaving an empty placeholder.
     */
    private void drawNavigationManeuverDetails(@NonNull Canvas canvas,
                                                @NonNull RuntimeElement runtime,
                                                @NonNull RectF bounds,
                                                @NonNull InstrumentElementConfig element,
                                                int alpha) {
        if (bounds.isEmpty()) return;
        if (commandCard.enabled) {
            int textColor = navigationColor(element.options.optString(
                    "maneuverDetailTextColor", "#FFFFFFFF"), Color.WHITE);
            commandCardRenderer().draw(canvas, commandCard, bounds, element.options,
                    getResources().getDisplayMetrics().scaledDensity,
                    element.options.optInt("maneuverDetailTextSizeSp", 18), 600,
                    withAlpha(textColor, alpha), withAlpha(textColor, alpha));
            return;
        }
        RectF primary = new RectF(bounds);
        RectF auxiliary = null;
        if (!runtime.navigationAuxiliaryText.isEmpty()) {
            float auxiliaryHeight = Math.min(bounds.height() * .40f,
                    Math.max(12f, bounds.height() * .30f));
            auxiliary = new RectF(bounds.left,
                    Math.max(bounds.top, bounds.bottom - auxiliaryHeight),
                    bounds.right, bounds.bottom);
            primary.bottom = Math.max(primary.top, auxiliary.top - Math.max(1f,
                    element.options.optInt("maneuverDetailRowGapPx", 2)));
        }

        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float requested = Math.max(8f, element.options.optInt(
                "maneuverDetailTextSizeSp", 18) * scaledDensity);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(digitalTypeface(element.style, true));
        paint.setTextSize(Math.min(requested, Math.max(7f, primary.height() * .68f)));
        int primaryColor = navigationColor(element.options.optString(
                "maneuverDetailTextColor", "#FFFFFFFF"), Color.WHITE);

        float cursor = primary.left;
        if (!runtime.navigationTurnDistance.isEmpty()) {
            float distancePadding = Math.max(3f, primary.height() * .12f);
            float distanceWidth = Math.min(primary.width() * .34f,
                    paint.measureText(runtime.navigationTurnDistance) + distancePadding * 2f);
            RectF distanceBounds = new RectF(cursor, primary.top,
                    Math.min(primary.right, cursor + distanceWidth), primary.bottom);
            drawNavigationTextFit(canvas, runtime.navigationTurnDistance, distanceBounds,
                    primaryColor, alpha, Paint.Align.LEFT, element.style, true,
                    element.options.optInt("maneuverDetailTextSizeSp", 18));
            cursor = Math.min(primary.right, distanceBounds.right + distancePadding);
        }

        float badgeGap = Math.max(2f, primary.height() * .08f);
        for (ManeuverDirectionSign sign : runtime.navigationDirectionSigns) {
            if (cursor >= primary.right) break;
            float horizontalPadding = Math.max(3f, primary.height() * .13f);
            float available = primary.right - cursor;
            float badgeWidth = Math.min(available,
                    paint.measureText(sign.text) + horizontalPadding * 2f);
            if (badgeWidth <= horizontalPadding * 2f) break;
            float verticalInset = Math.max(0f, primary.height() * .08f);
            RectF badge = new RectF(cursor, primary.top + verticalInset,
                    cursor + badgeWidth, primary.bottom - verticalInset);
            int background = navigationColor(sign.backgroundColor, 0xFF1478FF);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(background, Math.round(
                    Color.alpha(background) * alpha / 255f)));
            float radius = Math.max(2f, Math.min(badge.height() * .22f, 6f));
            canvas.drawRoundRect(badge, radius, radius, paint);
            int foreground = navigationColor(sign.textColor, Color.WHITE);
            drawNavigationTextFit(canvas, sign.text,
                    insetSides(badge, horizontalPadding, 0f, horizontalPadding, 0f),
                    foreground, alpha, Paint.Align.CENTER, element.style, true,
                    element.options.optInt("maneuverDetailTextSizeSp", 18));
            cursor = Math.min(primary.right, badge.right + badgeGap);
        }

        String detail = runtime.navigationCardText;
        if (!detail.isEmpty() && !runtime.directionSignsContain(detail)
                && cursor < primary.right) {
            drawNavigationTextFit(canvas, detail,
                    new RectF(cursor, primary.top, primary.right, primary.bottom),
                    primaryColor, alpha, Paint.Align.LEFT, element.style, false,
                    element.options.optInt("maneuverDetailTextSizeSp", 18));
        }

        if (auxiliary != null && !auxiliary.isEmpty()) {
            int auxiliaryColor = navigationColor(element.options.optString(
                    "maneuverAuxiliaryColor", "#E60B4DB5"), 0xE60B4DB5);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(auxiliaryColor, Math.round(
                    Color.alpha(auxiliaryColor) * alpha / 255f)));
            float radius = Math.max(2f, Math.min(auxiliary.height() * .28f, 7f));
            canvas.drawRoundRect(auxiliary, radius, radius, paint);
            drawNavigationTextFit(canvas, runtime.navigationAuxiliaryText,
                    insetSides(auxiliary, Math.max(3f, auxiliary.height() * .18f), 0f,
                            Math.max(3f, auxiliary.height() * .18f), 0f),
                    primaryColor, alpha, Paint.Align.LEFT, element.style, true,
                    element.options.optInt("maneuverAuxiliaryTextSizeSp", 14));
        }
    }

    private void drawNavigationTextFit(@NonNull Canvas canvas, @NonNull String value,
                                       @NonNull RectF bounds, int color, int alpha,
                                       @NonNull Paint.Align align,
                                       @NonNull InstrumentStyleFamily style, boolean bold,
                                       int requestedTextSizeSp) {
        if (value.isEmpty() || bounds.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(digitalTypeface(style, bold));
        paint.setTextAlign(align);
        float requested = Math.max(7f, requestedTextSizeSp
                * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextSize(Math.min(requested, Math.max(7f, bounds.height() * .72f)));
        float measured = paint.measureText(value);
        if (measured > bounds.width() && measured > 0f) {
            paint.setTextSize(Math.max(7f, paint.getTextSize() * bounds.width() / measured));
        }
        paint.setColor(withAlpha(color, Math.round(Color.alpha(color) * alpha / 255f)));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float x = align == Paint.Align.CENTER ? bounds.centerX()
                : align == Paint.Align.RIGHT ? bounds.right : bounds.left;
        int saved = canvas.save();
        canvas.clipRect(bounds);
        canvas.drawText(value, x,
                bounds.centerY() - (metrics.ascent + metrics.descent) * .5f, paint);
        canvas.restoreToCount(saved);
    }

    private static boolean hasManeuverAction(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return !value.isEmpty() && !"UNKNOWN".equals(value) && !"NONE".equals(value);
    }

    /** Vector comes from the same atomic semantic action as the text, never from legacy cache. */
    private void drawSemanticManeuver(@NonNull Canvas canvas, @NonNull String raw,
                                      @NonNull RectF bounds, int color) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        int direction = value.contains("LEFT") ? -1 : value.contains("RIGHT") ? 1 : 0;
        float stroke = Math.max(3f, Math.min(bounds.width(), bounds.height()) * .105f);
        float cx = bounds.centerX();
        float top = bounds.top + bounds.height() * .12f;
        float bottom = bounds.bottom - bounds.height() * .10f;
        float half = bounds.width() * .32f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        routeProgressPath.reset();
        if ("FINISH".equals(value)) {
            paint.setStyle(Paint.Style.FILL);
            float square = Math.min(bounds.width(), bounds.height()) * .18f;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    if ((row + column) % 2 == 0) {
                        canvas.drawRect(cx - square * 1.5f + column * square,
                                top + row * square,
                                cx - square * .5f + column * square,
                                top + (row + 1) * square, paint);
                    }
                }
            }
            canvas.drawLine(cx - square * 1.5f, top, cx - square * 1.5f, bottom, paint);
            return;
        }
        if (value.contains("UTURN")) {
            routeProgressPath.moveTo(cx, bottom);
            routeProgressPath.lineTo(cx, bounds.centerY());
            routeProgressPath.cubicTo(cx, top, cx + direction * half, top,
                    cx + direction * half, bounds.centerY());
            canvas.drawPath(routeProgressPath, paint);
            drawManeuverArrowHead(canvas, cx + direction * half, bounds.centerY(),
                    0f, 1f, stroke, color);
            return;
        }
        if (value.contains("ROUNDABOUT")) {
            float radius = Math.min(bounds.width(), bounds.height()) * .25f;
            rect.set(cx - radius, bounds.centerY() - radius,
                    cx + radius, bounds.centerY() + radius);
            canvas.drawArc(rect, 85f, direction < 0 ? -285f : 285f, false, paint);
            float endX = cx + (direction < 0 ? -radius : radius);
            drawManeuverArrowHead(canvas, endX, bounds.centerY(),
                    direction < 0 ? -1f : 1f, 0f, stroke, color);
            return;
        }
        if (value.contains("FORK")) {
            canvas.drawLine(cx, bottom, cx, bounds.centerY(), paint);
            float endX = cx + (direction == 0 ? half : direction * half);
            canvas.drawLine(cx, bounds.centerY(), endX, top, paint);
            drawManeuverArrowHead(canvas, endX, top,
                    direction == 0 ? 1f : direction, -1f, stroke, color);
            return;
        }
        if (direction == 0) {
            canvas.drawLine(cx, bottom, cx, top, paint);
            drawManeuverArrowHead(canvas, cx, top, 0f, -1f, stroke, color);
            return;
        }
        float turnY = bounds.top + bounds.height() * (value.contains("SLIGHT") ? .48f : .55f);
        routeProgressPath.moveTo(cx, bottom);
        routeProgressPath.lineTo(cx, turnY);
        float endX = cx + direction * half;
        if (value.contains("SLIGHT")) {
            routeProgressPath.lineTo(endX, top);
            canvas.drawPath(routeProgressPath, paint);
            drawManeuverArrowHead(canvas, endX, top, direction, -1f, stroke, color);
        } else {
            routeProgressPath.lineTo(endX, turnY);
            canvas.drawPath(routeProgressPath, paint);
            drawManeuverArrowHead(canvas, endX, turnY, direction, 0f, stroke, color);
        }
    }

    private void drawManeuverArrowHead(@NonNull Canvas canvas, float x, float y,
                                       float dx, float dy, float stroke, int color) {
        float length = stroke * 2.1f;
        float magnitude = (float) Math.sqrt(dx * dx + dy * dy);
        if (magnitude <= 0f) return;
        dx /= magnitude;
        dy /= magnitude;
        float px = -dy;
        float py = dx;
        routeProgressPath.reset();
        routeProgressPath.moveTo(x, y);
        routeProgressPath.lineTo(x - dx * length + px * length * .55f,
                y - dy * length + py * length * .55f);
        routeProgressPath.moveTo(x, y);
        routeProgressPath.lineTo(x - dx * length - px * length * .55f,
                y - dy * length - py * length * .55f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        canvas.drawPath(routeProgressPath, paint);
    }

    private void drawNavigationMetric(@NonNull Canvas canvas, @NonNull String raw,
                                      float centerX, float centerY, float maximumWidth,
                                      int requestedTextSizeSp, float maximumHeight,
                                      @NonNull InstrumentElementConfig element, int alpha) {
        String value = raw.isEmpty() ? "—" : raw;
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(digitalTypeface(element.style, true));
        paint.setTextAlign(Paint.Align.CENTER);
        float requested = Math.max(8f, requestedTextSizeSp
                * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextSize(Math.min(requested, Math.max(8f, maximumHeight * .82f)));
        float measured = paint.measureText(value);
        if (measured > maximumWidth && measured > 0f) {
            paint.setTextSize(Math.max(8f, paint.getTextSize() * maximumWidth / measured));
        }
        paint.setColor(withAlpha(element.style.primaryColor, alpha));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, centerX,
                centerY - (metrics.ascent + metrics.descent) * .5f, paint);
    }

    /** Independent forecast card. Canvas re-renders vectors and glyphs for every live frame. */
    private void drawTrafficJamForecast(@NonNull Canvas canvas,
                                        @NonNull RuntimeElement runtime,
                                        @NonNull RectF bounds,
                                        @Nullable NavigationSnapshotV2 navigation) {
        boolean available = navigation != null
                && navigation.trafficJamDurationSeconds >= 0
                && navigation.trafficJamDistanceMeters >= 0;
        if (!available && !editorMode) return;
        InstrumentElementConfig element = runtime.config;
        int alpha = Math.round(255f * element.opacityPercent / 100f);
        if (option(element, "showFace", true)) {
            int faceColor = navigationColor(element.options.optString(
                    "faceColor", "#F21B1F24"), 0xF21B1F24);
            int faceOpacity = Math.max(0, Math.min(100,
                    element.options.optInt("faceOpacityPercent", 100)));
            float corner = Math.min(Math.min(bounds.width(), bounds.height()) * .5f,
                    Math.max(0f, element.options.optInt("faceCornerRadiusPx", 16)));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(faceColor, Math.round(
                    Color.alpha(faceColor) * faceOpacity / 100f * alpha / 255f)));
            canvas.drawRoundRect(bounds, corner, corner, paint);
            float borderWidth = Math.max(0f,
                    element.options.optInt("faceBorderWidthPx", 0));
            if (borderWidth > 0f) {
                int borderColor = navigationColor(element.options.optString(
                        "faceBorderColor", "#00000000"), 0x00000000);
                RectF border = new RectF(bounds);
                border.inset(borderWidth * .5f, borderWidth * .5f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(borderWidth);
                paint.setColor(withAlpha(borderColor, Math.round(
                        Color.alpha(borderColor) * alpha / 255f)));
                canvas.drawRoundRect(border, corner, corner, paint);
            }
        }
        RectF content = insetSides(bounds,
                element.options.optInt("contentPaddingLeftPx", 12),
                element.options.optInt("contentPaddingTopPx", 5),
                element.options.optInt("contentPaddingRightPx", 12),
                element.options.optInt("contentPaddingBottomPx", 5));
        if (content.isEmpty()) return;
        String value = available
                ? "Пробка на " + durationText(navigation.trafficJamDurationSeconds)
                + " (" + distanceText(navigation.trafficJamDistanceMeters) + ")"
                : "Пробка на 10 мин (1,2 км)";
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(digitalTypeface(element.style, true));
        float requested = Math.max(8f, element.options.optInt("textSizeSp", 30)
                * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextSize(Math.min(requested, Math.max(8f, content.height() * .82f)));
        float measured = paint.measureText(value);
        if (measured > content.width() && measured > 0f) {
            paint.setTextSize(Math.max(8f,
                    paint.getTextSize() * content.width() / measured));
        }
        int textColor = navigationColor(element.options.optString(
                "textColor", "#FFFFFFFF"), Color.WHITE);
        paint.setColor(withAlpha(textColor, Math.round(
                Color.alpha(textColor) * alpha / 255f)));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, content.centerX(),
                content.centerY() - (metrics.ascent + metrics.descent) * .5f, paint);
    }

    private void drawNavigationRouteProgress(@NonNull Canvas canvas,
                                             @NonNull RuntimeElement runtime,
                                             @NonNull RectF bounds, int alpha,
                                             @NonNull InstrumentElementConfig element) {
        if (bounds.isEmpty()) return;
        routeBarRect.set(bounds);
        float radius = Math.min(routeBarRect.height() * .5f, Math.max(0f,
                element.options.optInt("progressBarCornerRadiusPx", 7)));
        int unknownColor = routeTrafficColor("UNKNOWN", navigationProfile, element);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(unknownColor, Math.min(alpha, 185)));
        canvas.drawRoundRect(routeBarRect, radius, radius, paint);

        routeProgressPath.reset();
        routeProgressPath.addRoundRect(routeBarRect, radius, radius, Path.Direction.CW);
        int clipped = canvas.save();
        canvas.clipPath(routeProgressPath);
        float maximumMeters = Math.max(runtime.navigationTotalDistanceMeters,
                runtime.trafficTotalMeters);
        if (maximumMeters > 0f) {
            for (RouteTrafficRun run : runtime.navigationTrafficRuns) {
                float from = Math.max(0f, Math.min(1f, run.fromMeters / maximumMeters));
                float to = Math.max(from, Math.min(1f, run.toMeters / maximumMeters));
                if (to <= from) continue;
                paint.setColor(withAlpha(routeTrafficColor(
                        run.type, navigationProfile, element), alpha));
                canvas.drawRect(routeBarRect.left + routeBarRect.width() * from,
                        routeBarRect.top,
                        routeBarRect.left + routeBarRect.width() * to,
                        routeBarRect.bottom, paint);
            }
        }
        float progress = Double.isFinite(runtime.navigationProgress)
                ? (float) Math.max(0d, Math.min(1d, runtime.navigationProgress)) : Float.NaN;
        if (Float.isFinite(progress) && progress > 0f) {
            paint.setColor(withAlpha(0xFF0A0B0D, Math.min(alpha, 145)));
            canvas.drawRect(routeBarRect.left, routeBarRect.top,
                    routeBarRect.left + routeBarRect.width() * progress,
                    routeBarRect.bottom, paint);
        }
        canvas.restoreToCount(clipped);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, routeBarRect.height() * .09f));
        paint.setColor(withAlpha(0xFF08090B, Math.min(alpha, 210)));
        canvas.drawRoundRect(routeBarRect, radius, radius, paint);

        if (!Float.isFinite(progress)) return;
        float markerX = routeBarRect.left + routeBarRect.width() * progress;
        float markerScale = Math.max(25, Math.min(250,
                element.options.optInt("progressMarkerScalePercent", 100))) / 100f;
        float markerWidth = Math.max(8f, routeBarRect.height() * 1.05f) * markerScale;
        float markerHeight = Math.max(10f, routeBarRect.height() * 1.7f) * markerScale;
        markerX = Math.max(routeBarRect.left + markerWidth * .45f,
                Math.min(routeBarRect.right - markerWidth * .55f, markerX));
        float markerY = routeBarRect.centerY();
        routeProgressPath.reset();
        routeProgressPath.moveTo(markerX + markerWidth * .55f, markerY);
        routeProgressPath.lineTo(markerX - markerWidth * .45f,
                markerY - markerHeight * .5f);
        routeProgressPath.lineTo(markerX - markerWidth * .20f, markerY);
        routeProgressPath.lineTo(markerX - markerWidth * .45f,
                markerY + markerHeight * .5f);
        routeProgressPath.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(2f, markerWidth * .13f));
        paint.setColor(withAlpha(0xFF111318, alpha));
        canvas.drawPath(routeProgressPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(navigationColor(element.options.optString(
                "markerColor", navigationProfile.cursorColor), 0xFFFFC400), alpha));
        canvas.drawPath(routeProgressPath, paint);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawSourceBitmap(@NonNull Canvas canvas, @NonNull Bitmap bitmap,
                                  @NonNull RectF bounds, int alpha) {
        if (bounds.isEmpty() || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        float scale = Math.min(bounds.width() / bitmap.getWidth(),
                bounds.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        RectF target = new RectF(bounds.centerX() - width * .5f,
                bounds.centerY() - height * .5f,
                bounds.centerX() + width * .5f,
                bounds.centerY() + height * .5f);
        paint.setAlpha(alpha);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(bitmap, null, target, paint);
        paint.setAlpha(255);
    }

    @NonNull
    private static RectF insetSides(@NonNull RectF source, float left, float top,
                                    float right, float bottom) {
        return new RectF(source.left + Math.max(0f, left),
                source.top + Math.max(0f, top),
                source.right - Math.max(0f, right),
                source.bottom - Math.max(0f, bottom));
    }

    @NonNull
    private static RectF scaleAroundCenter(@NonNull RectF source, float factor,
                                           @NonNull RectF limit) {
        if (source.isEmpty()) return new RectF(source);
        float safe = Math.max(.05f, factor);
        float halfWidth = source.width() * safe * .5f;
        float halfHeight = source.height() * safe * .5f;
        RectF result = new RectF(source.centerX() - halfWidth,
                source.centerY() - halfHeight,
                source.centerX() + halfWidth,
                source.centerY() + halfHeight);
        if (!result.intersect(limit)) return new RectF();
        return result;
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
                                    @NonNull InstrumentElementConfig element,
                                    float cx, float cy, float radius, int alpha) {
        InstrumentStyleFamily style = element.style;
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
                canvas.drawArc(gaugeArc, gaugeStart(element),
                        gaugeSweep(element), false, paint);
                break;
            case AEROWAVE:
                paint.setStrokeWidth(Math.max(3f, radius * .030f));
                paint.setColor(withAlpha(style.accentColor, Math.min(alpha, 220)));
                float start = gaugeStart(element);
                float sweep = gaugeSweep(element);
                canvas.drawArc(gaugeArc, start + sweep * .10f,
                        sweep * .22f, false, paint);
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 180)));
                canvas.drawArc(gaugeArc, start + sweep * .33f,
                        sweep * .24f, false, paint);
                break;
            case STEEL_VECTOR:
                paint.setStrokeWidth(Math.max(1f, radius * .009f));
                paint.setColor(withAlpha(style.secondaryColor, alpha / 2));
                canvas.drawArc(gaugeArc, gaugeStart(element),
                        gaugeSweep(element), false, paint);
                break;
            case CONTINUUM:
                paint.setStrokeWidth(Math.max(2f, radius * .020f));
                paint.setColor(withAlpha(style.secondaryColor, Math.min(alpha, 175)));
                canvas.drawArc(gaugeArc, gaugeStart(element),
                        gaugeSweep(element), false, paint);
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

    private static float gaugeStart(@NonNull InstrumentElementConfig element) {
        return element.options.has("arcStartDegrees")
                ? (float) element.options.optDouble(
                        "arcStartDegrees", gaugeStart(element.style))
                : gaugeStart(element.style);
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

    private static float gaugeSweep(@NonNull InstrumentElementConfig element) {
        return element.options.has("arcSweepDegrees")
                ? (float) element.options.optDouble(
                        "arcSweepDegrees", gaugeSweep(element.style))
                : gaugeSweep(element.style);
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
            case ANALOG_TACHOMETER: return "x1000 об/мин";
            case ANALOG_FUEL_GAUGE: return "л";
            case ANALOG_BATTERY_GAUGE: return "%";
            case ANALOG_COOLANT_TEMPERATURE: return "°C";
            case ANALOG_INSTANT_CONSUMPTION: return "л/100";
            default: return "";
        }
    }

    private static int gaugeDecimals(@NonNull InstrumentElementType type) {
        return type == InstrumentElementType.ANALOG_TACHOMETER
                || type == InstrumentElementType.ANALOG_FUEL_GAUGE
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
    private static String presentation(@NonNull InstrumentElementConfig element) {
        return element.options.optString("presentation", "").trim().toUpperCase(
                java.util.Locale.ROOT);
    }

    @NonNull
    private static String distanceText(int meters) {
        if (meters < 0) return "";
        if (meters < 1_000) return meters + " м";
        return meters < 10_000
                ? String.format(Locale.getDefault(), "%.1f км", meters / 1_000d)
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
    private static String arrivalText(long epochMs) {
        if (epochMs <= 0L) return "";
        Calendar value = Calendar.getInstance();
        value.setTimeInMillis(epochMs);
        return String.format(Locale.getDefault(), "%02d:%02d",
                value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE));
    }

    @NonNull
    private static List<RouteTrafficRun> parseRouteTrafficRuns(@NonNull String raw) {
        if (raw.isEmpty()) return Collections.emptyList();
        ArrayList<RouteTrafficRun> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < Math.min(2_048, values.length()); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                int from = Math.max(0, value.has("fromMeters")
                        ? value.optInt("fromMeters", 0) : value.optInt("from", 0));
                int to = Math.max(from, value.has("toMeters")
                        ? value.optInt("toMeters", from) : value.optInt("to", from));
                if (to <= from) continue;
                result.add(new RouteTrafficRun(from, to,
                        value.optString("type", "UNKNOWN").trim()
                                .toUpperCase(Locale.ROOT)));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return result.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }

    @NonNull
    private static List<ManeuverDirectionSign> parseManeuverDirectionSigns(
            @NonNull String raw) {
        if (raw.isEmpty()) return Collections.emptyList();
        ArrayList<ManeuverDirectionSign> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < Math.min(8, values.length()); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String text = value.optString("text", "").trim();
                if (text.isEmpty()) continue;
                result.add(new ManeuverDirectionSign(text,
                        value.optString("bgColor", "#FF1478FF"),
                        value.optString("textColor", "#FFFFFFFF")));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return result.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }

    @NonNull
    private static String firstNavigationCardText(@Nullable String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"—".equals(value.trim())) {
                return value.trim();
            }
        }
        return "";
    }

    private static int trafficColor(@NonNull String type,
                                    @NonNull NavigationIntegrationConfig.MapProfile profile) {
        if ("FREE".equals(type)) {
            return navigationColor(profile.trafficFreeColor, 0xFF39B54A);
        }
        if ("LIGHT".equals(type)) {
            return navigationColor(profile.trafficLightColor, 0xFFFFD54F);
        }
        if ("HARD".equals(type)) {
            return navigationColor(profile.trafficHardColor, 0xFFFF8A3D);
        }
        if ("VERY_HARD".equals(type)) {
            return navigationColor(profile.trafficVeryHardColor, 0xFFF04444);
        }
        if ("BLOCKED".equals(type)) {
            return navigationColor(profile.trafficBlockedColor, 0xFF7E1D2D);
        }
        return navigationColor(profile.trafficUnknownColor, 0xFF8A9099);
    }

    private static int routeTrafficColor(
            @NonNull String type,
            @NonNull NavigationIntegrationConfig.MapProfile profile,
            @NonNull InstrumentElementConfig element) {
        String key = "UNKNOWN".equals(type) ? "unknownColor"
                : "FREE".equals(type) ? "freeColor"
                : "LIGHT".equals(type) ? "lightColor"
                : "HARD".equals(type) ? "hardColor"
                : "VERY_HARD".equals(type) ? "veryHardColor"
                : "BLOCKED".equals(type) ? "blockedColor" : "unknownColor";
        int fallback = trafficColor(type, profile);
        return navigationColor(element.options.optString(key, ""), fallback);
    }

    private static int navigationColor(@Nullable String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class RouteTrafficRun {
        final int fromMeters;
        final int toMeters;
        @NonNull final String type;

        RouteTrafficRun(int fromMeters, int toMeters, @NonNull String type) {
            this.fromMeters = Math.max(0, fromMeters);
            this.toMeters = Math.max(this.fromMeters, toMeters);
            this.type = type;
        }
    }

    private static final class ManeuverDirectionSign {
        @NonNull final String text;
        @NonNull final String backgroundColor;
        @NonNull final String textColor;

        ManeuverDirectionSign(@NonNull String text, @NonNull String backgroundColor,
                              @NonNull String textColor) {
            this.text = text;
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;
        }
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
        long cachedNavigationRouteEpoch = Long.MIN_VALUE;
        @NonNull String cachedNavigationTrafficJson = "";
        @NonNull String navigationRemainingDistance = "";
        @NonNull String navigationArrival = "";
        @NonNull String navigationDuration = "";
        @NonNull String navigationTurnDistance = "";
        @NonNull String navigationCardText = "";
        @NonNull String navigationAuxiliaryText = "";
        @NonNull List<ManeuverDirectionSign> navigationDirectionSigns =
                Collections.emptyList();
        @NonNull List<RouteTrafficRun> navigationTrafficRuns = Collections.emptyList();
        int navigationTotalDistanceMeters = -1;
        int trafficTotalMeters;
        double navigationProgress = Double.NaN;

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

        void updateNavigation(@NonNull NavigationSnapshotV2 value,
                              @Nullable NavigationRouteGeometryV2 geometry) {
            if (cachedNavigationSequence != value.sequence) {
                cachedNavigationSequence = value.sequence;
                navigationRemainingDistance = distanceText(value.remainingDistanceMeters);
                navigationArrival = arrivalText(value.arrivalEpochMs);
                navigationDuration = durationText(value.remainingDurationSeconds);
                navigationTurnDistance = value.maneuverDisplayDistance.trim().isEmpty()
                        ? distanceText(value.maneuverDistanceMeters)
                        : value.maneuverDisplayDistance;
                navigationCardText = value.maneuverNextRoad;
                navigationDirectionSigns = parseManeuverDirectionSigns(
                        value.maneuverDirectionSignsJson);
                navigationAuxiliaryText = value.maneuverAuxiliaryText;
                if ("NEXT_MANEUVER".equals(value.maneuverAuxiliaryType)
                        && value.maneuverAuxiliaryDistanceMeters >= 0) {
                    String auxiliaryDistance = distanceText(
                            value.maneuverAuxiliaryDistanceMeters);
                    navigationAuxiliaryText = navigationAuxiliaryText.isEmpty()
                            ? auxiliaryDistance
                            : navigationAuxiliaryText + " · " + auxiliaryDistance;
                }
                navigationTotalDistanceMeters = value.routeTotalDistanceMeters;
                if (value.routeTotalDistanceMeters > 0
                        && value.remainingDistanceMeters >= 0) {
                    navigationProgress = 1d - value.remainingDistanceMeters
                            / (double) value.routeTotalDistanceMeters;
                    navigationProgress = Math.max(0d, Math.min(1d, navigationProgress));
                } else {
                    navigationProgress = Double.NaN;
                }
            }
            String trafficJson = geometry == null || geometry.routeEpoch != value.routeEpoch
                    ? "" : geometry.trafficSegmentsJson;
            if (cachedNavigationRouteEpoch == value.routeEpoch
                    && cachedNavigationTrafficJson.equals(trafficJson)) return;
            cachedNavigationRouteEpoch = value.routeEpoch;
            cachedNavigationTrafficJson = trafficJson;
            navigationTrafficRuns = parseRouteTrafficRuns(trafficJson);
            trafficTotalMeters = navigationTrafficRuns.isEmpty() ? 0
                    : navigationTrafficRuns.get(navigationTrafficRuns.size() - 1).toMeters;
        }

        boolean hasNavigationManeuverDetails() {
            return !navigationTurnDistance.isEmpty() || !navigationCardText.isEmpty()
                    || !navigationDirectionSigns.isEmpty()
                    || !navigationAuxiliaryText.isEmpty();
        }

        boolean directionSignsContain(@NonNull String value) {
            for (ManeuverDirectionSign sign : navigationDirectionSigns) {
                if (sign.text.equalsIgnoreCase(value)) return true;
            }
            return false;
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
