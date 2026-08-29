/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.launcher.HorizontalGroupLayout;

/**
 * Resolution-independent renderer shared byte-for-byte by the live editor and HUD Presentation.
 * All built-in arrows, lane marks and traffic lights are original Canvas vectors; no mHUD assets
 * or implementation code are bundled.
 */
public final class HudCanvasView extends View {
    public interface EditorListener {
        void onSelectionChanged(@Nullable HudElementConfig selected);
        void onGeometryChanged(@NonNull HudElementConfig item, boolean committed);
        void onConfigure(@NonNull HudElementConfig item);
    }

    private static final float HANDLE_SIZE = 34f;
    @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final Paint.FontMetrics textFontMetrics = new Paint.FontMetrics();
    @NonNull private final Path path = new Path();
    @NonNull private HudPanelConfig config;
    @NonNull private List<HudElementConfig> drawingOrder;
    @Nullable private HudElementConfig directMap;
    @Nullable private Geometry cachedGeometry;
    /** Live geometry changes only on config/size updates, never on telemetry frames. */
    @NonNull private final Map<HudElementConfig, RectF> cachedElementBounds =
            new IdentityHashMap<>();
    @NonNull private final HudRuntimeData data;
    private final boolean editor;
    /** True when WindowManager already cropped this View to the physical 728x190 HUD plane. */
    private final boolean localHudViewport;
    @Nullable private final EditorListener editorListener;
    @Nullable private String selectedId;
    @Nullable private HudElementConfig dragging;
    private boolean resizing;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private int startWidth;
    private int startHeight;
    private float startFineX;
    private float startFineY;
    private final int touchSlop;
    private boolean movedSinceDown;
    @Nullable private String loadedFontUri;
    @Nullable private Typeface loadedFont;
    @NonNull private final SparseArray<Typeface> typefaceCache = new SparseArray<>(9);
    private boolean animationWakePosted;
    @NonNull private final Runnable animationWake = () -> {
        animationWakePosted = false;
        if (isAttachedToWindow()) invalidate();
    };

    public HudCanvasView(@NonNull Context context, @NonNull HudPanelConfig config,
                         @NonNull HudRuntimeData data, boolean editor,
                         @Nullable EditorListener editorListener) {
        this(context, config, data, editor, editorListener, false);
    }

    HudCanvasView(@NonNull Context context, @NonNull HudPanelConfig config,
                  @NonNull HudRuntimeData data, boolean editor,
                  @Nullable EditorListener editorListener,
                  boolean localHudViewport) {
        super(context);
        this.config = config;
        drawingOrder = config.drawingOrder();
        directMap = HudDirectMapGeometry.find(config);
        this.data = data;
        this.editor = editor;
        this.localHudViewport = localHudViewport;
        this.editorListener = editorListener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // A black full-screen View would overwrite neighbouring planes of the composite display.
        setBackgroundColor(editor ? Color.BLACK : Color.TRANSPARENT);
        setFocusable(editor);
        setClickable(editor);
    }

    public void updateConfig(@NonNull HudPanelConfig next) {
        config = next;
        drawingOrder = next.drawingOrder();
        directMap = HudDirectMapGeometry.find(next);
        cachedGeometry = null;
        cachedElementBounds.clear();
        if (selectedId != null && find(selectedId) == null) selectedId = null;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cachedGeometry = null;
        cachedElementBounds.clear();
    }

    @Override protected void onDetachedFromWindow() {
        cancelAnimationWake();
        super.onDetachedFromWindow();
    }

    public void select(@Nullable String id) {
        selectedId = id;
        invalidate();
        if (editorListener != null) editorListener.onSelectionChanged(find(id));
    }

    @Nullable public String selectedId() { return selectedId; }
    @Nullable public HudElementConfig selected() { return find(selectedId); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Geometry geometry = geometry();
        if (geometry.safeClip.isEmpty()) return;
        int safety = canvas.save();
        if (!canvas.clipRect(geometry.safeClip)) {
            canvas.restoreToCount(safety);
            return;
        }
        drawPanelBackground(canvas, geometry);
        if (config.snowMode) drawSnow(canvas, geometry);

        int brightnessAlpha = Math.round(255f * config.globalBrightness / 100f);
        // The default 100% brightness needs no offscreen composition buffer. Avoiding this layer
        // removes one full HUD-plane GPU blend from every telemetry refresh.
        int layer = brightnessAlpha < 255
                ? canvas.saveLayerAlpha(geometry.safeClip, brightnessAlpha) : -1;
        boolean animated = false;
        for (HudElementConfig item : drawingOrder) {
            if (!shouldDraw(item)) continue;
            RectF bounds = bounds(item, geometry);
            drawElement(canvas, item, bounds, geometry.scale, geometry);
            if ((item.type == HudElementType.TURN_SIGNAL_LEFT
                    || item.type == HudElementType.TURN_SIGNAL_RIGHT
                    || item.type == HudElementType.NAV_TRAFFIC_LIGHTS)
                    && item.options.optBoolean("animated",
                    item.options.optBoolean("arrowAnimation", false))) {
                animated = true;
            }
        }
        if (layer >= 0) canvas.restoreToCount(layer);
        if (editor && config.showGrid) drawGrid(canvas, geometry);
        if (editor && selectedId != null) {
            HudElementConfig selected = find(selectedId);
            if (selected != null) drawSelection(canvas, bounds(selected, geometry));
        }
        canvas.restoreToCount(safety);
        if (animated || config.snowMode) scheduleAnimationWake();
        else cancelAnimationWake();
    }

    private void scheduleAnimationWake() {
        if (animationWakePosted) return;
        animationWakePosted = postDelayed(animationWake, 250L);
    }

    private void cancelAnimationWake() {
        if (!animationWakePosted) return;
        removeCallbacks(animationWake);
        animationWakePosted = false;
    }

    private boolean shouldDraw(HudElementConfig item) {
        if (!item.enabled) return false;
        AutomationState state = data.automation(item);
        if (state.present && !state.visible) return false;
        if (item.options.optBoolean("hideWhenInactive", false)) {
            if (isNavigation(item.type)) {
                HudNavigationState nav = data.navigation();
                if (nav == null || (!nav.routeActive && !nav.laneAvailable
                        && !nav.trafficAvailable)) return false;
            } else if (!data.active(item)) {
                return false;
            }
        }
        if (item.options.optBoolean("hideWhenEmpty", false)) {
            String value = data.textFor(item).trim();
            if (value.isEmpty() || "—".equals(value)
                    || "Маршрут не активен".equals(value)) {
                return false;
            }
        }
        if ((item.type == HudElementType.NAV_SPEED_LIMIT)
                && item.options.optBoolean("routeOnly", false)) {
            HudNavigationState nav = data.navigation();
            if (nav == null || !nav.routeActive) return false;
        }
        if (item.type == HudElementType.NAV_SPEED_LIMIT
                && item.options.optBoolean("onlyWhenExceeded", false)) {
            HudNavigationState nav = data.navigation();
            double current = data.numericValue(item);
            double limit = nav == null ? Double.NaN : parseNumber(nav.speedLimit);
            double delta = item.options.optDouble("overspeedDelta", 0d);
            if (!Double.isFinite(current) || !Double.isFinite(limit)
                    || current <= limit + delta) {
                return false;
            }
        }
        if (item.type == HudElementType.FUEL_REFILL
                && item.options.optBoolean("onlyInPark", true) && !data.inPark()) {
            return false;
        }
        double numeric = data.numericValue(item);
        if (Double.isFinite(numeric) && item.options.optBoolean("hideAboveThreshold", false)) {
            double threshold = isTirePressure(item.type)
                    ? item.options.optDouble("lowThreshold", 2d)
                    : item.options.optDouble("yellowThreshold", 20d);
            if (numeric > threshold) return false;
        }
        if (Double.isFinite(numeric) && isTirePressure(item.type)
                && numeric < item.options.optDouble("lowThreshold", 2d)
                && item.options.optBoolean("blinkBelowThreshold", true)
                && (SystemClock.uptimeMillis() / 500L) % 2L == 0L) {
            return false;
        }
        if (item.type == HudElementType.NAV_LANES) {
            HudNavigationState nav = data.navigation();
            int threshold = item.options.optInt("laneThresholdMeters",
                    config.navigationDisplayThresholdMeters);
            if (nav != null && Double.isFinite(nav.laneDistanceMeters)
                    && threshold > 0 && nav.laneDistanceMeters > threshold) return false;
        }
        return true;
    }

    private void drawPanelBackground(Canvas canvas, Geometry geometry) {
        // The live Natro panel never owns a substrate. In particular, do not issue CLEAR here:
        // HudCanvasView is the top child of the same translucent window as the TextureView map,
        // and KX11 applies that blend operation to the complete window buffer. It therefore
        // erased the already rendered map below this Canvas in 2.4.5. An empty display list is
        // transparent by construction; only explicit widgets/backdrops contribute pixels.
    }

    private void drawElement(Canvas canvas, HudElementConfig item, RectF bounds, float scale,
                             Geometry geometry) {
        if (item.type == HudElementType.BACKDROP) {
            drawBackdrop(canvas, item, bounds, geometry);
            return;
        }
        AutomationState automation = data.automation(item);
        int textColor = parseColor(automation.color,
                config.syncElementColors ? config.globalTextColor : item.textColor,
                Color.WHITE);
        textColor = warningColor(item, textColor);
        int unitColor = parseColor(null,
                config.syncElementColors ? config.globalUnitColor : item.unitColor,
                0xCCFFFFFF);
        int alpha = Math.round(255f * item.brightness / 100f);
        textColor = withAlpha(textColor, Math.round(Color.alpha(textColor) * alpha / 255f));
        unitColor = withAlpha(unitColor, Math.round(Color.alpha(unitColor) * alpha / 255f));

        switch (item.type) {
            case HORIZONTAL_GROUP:
                // Geometry-only container. A background is always an independent BACKDROP.
                return;
            case NAV_MAP:
                if (editor) drawMapPlaceholder(canvas, item, bounds, scale);
                return;
            case MEDIA_ARTWORK:
                drawBitmap(canvas, data.media() == null ? null : data.media().artwork, bounds);
                return;
            case NAV_MANEUVER_ARROW:
                drawManeuver(canvas, item, bounds, textColor);
                return;
            case NAV_LANES:
                drawLanes(canvas, item, bounds, textColor);
                return;
            case NAV_SPEED_LIMIT:
                drawSpeedLimit(canvas, item, bounds, textColor, scale);
                return;
            case NAV_TRAFFIC_LIGHTS:
                drawTrafficLights(canvas, item, bounds, textColor, scale);
                return;
            case NAV_TRIP_PROGRESS:
                drawProgress(canvas, item, bounds, textColor, scale);
                return;
            case NAV_JAM_PROGRESS:
                drawJamProgress(canvas, item, bounds, textColor, scale);
                return;
            case NAV_ROUTE_GRAPHIC:
                drawRouteGraphic(canvas, item, bounds);
                return;
            case TURN_SIGNAL_LEFT:
                drawTurnSignal(canvas, item, bounds, textColor, true);
                return;
            case TURN_SIGNAL_RIGHT:
                drawTurnSignal(canvas, item, bounds, textColor, false);
                return;
            case HIGH_BEAM:
                drawHighBeam(canvas, bounds, textColor);
                return;
            case AUTO_HOLD:
                drawAutoHold(canvas, bounds, textColor, scale);
                return;
            case NAV_COMBINED:
                drawCombinedNavigation(canvas, item, bounds, textColor, unitColor, scale);
                return;
            default:
                drawText(canvas, item, data.textFor(item), bounds, textColor, scale);
        }
    }

    private void drawBackdrop(Canvas canvas, HudElementConfig item, RectF bounds,
                              Geometry geometry) {
        int clipped = canvas.save();
        if (!editor) {
            HudElementConfig map = directMap;
            if (map != null) {
                RectF mapBounds = bounds(map, geometry);
                float mapRadius = Math.max(0f, Math.min(
                        map.options.optInt("cornerRadiusPx", 0),
                        Math.min(mapBounds.width(), mapBounds.height()) / 2f));
                path.reset();
                path.addRoundRect(mapBounds, mapRadius, mapRadius, Path.Direction.CW);
                canvas.clipOutPath(path);
            }
        }
        float radius = Math.max(0f, Math.min(item.cornerRadiusPx,
                Math.min(bounds.width(), bounds.height()) / 2f));
        int fill = parseColor(null, item.backgroundColor, 0xFF121923);
        int fillAlpha = Math.round(Color.alpha(fill)
                * item.backgroundOpacityPercent / 100f);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(fill, fillAlpha));
        canvas.drawRoundRect(bounds, radius, radius, paint);

        if (item.borderWidthPx > 0 && item.borderOpacityPercent > 0) {
            int border = parseColor(null, item.borderColor, Color.WHITE);
            int borderAlpha = Math.round(Color.alpha(border)
                    * item.borderOpacityPercent / 100f);
            float half = item.borderWidthPx / 2f;
            RectF borderBounds = new RectF(bounds);
            borderBounds.inset(half, half);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(item.borderWidthPx);
            paint.setColor(withAlpha(border, borderAlpha));
            canvas.drawRoundRect(borderBounds, Math.max(0f, radius - half),
                    Math.max(0f, radius - half), paint);
        }
        canvas.restoreToCount(clipped);
    }

    private void drawMapPlaceholder(Canvas canvas, HudElementConfig item,
                                    RectF bounds, float scale) {
        float radius = Math.max(0f, Math.min(
                item.options.optInt("cornerRadiusPx", 0) * scale,
                Math.min(bounds.width(), bounds.height()) / 2f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF162333);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 2f * scale));
        paint.setColor(0xFF3D7FC4);
        float step = Math.max(14f * scale, Math.min(bounds.width(), bounds.height()) / 5f);
        for (float x = bounds.left + step; x < bounds.right; x += step) {
            canvas.drawLine(x, bounds.top, x, bounds.bottom, paint);
        }
        for (float y = bounds.top + step; y < bounds.bottom; y += step) {
            canvas.drawLine(bounds.left, y, bounds.right, y, paint);
        }
        paint.setStrokeWidth(Math.max(3f, 5f * scale));
        paint.setColor(0xFFFFC400);
        path.reset();
        path.moveTo(bounds.left + bounds.width() * .08f, bounds.bottom - bounds.height() * .2f);
        path.cubicTo(bounds.centerX(), bounds.top + bounds.height() * .25f,
                bounds.centerX(), bounds.bottom - bounds.height() * .15f,
                bounds.right - bounds.width() * .08f, bounds.top + bounds.height() * .22f);
        canvas.drawPath(path, paint);
        drawSimpleText(canvas, "КАРТА HUD · DIRECT SURFACE",
                new RectF(bounds.left, bounds.top, bounds.right,
                        Math.min(bounds.bottom, bounds.top + Math.max(22f, 30f * scale))),
                Color.WHITE, Math.max(9f, 13f * scale), Layout.Alignment.ALIGN_CENTER);
    }

    private void drawManeuver(Canvas canvas, HudElementConfig item, RectF bounds, int color) {
        HudNavigationState nav = data.navigation();
        if (item.options.optBoolean("preferSourceImage", true)
                && nav != null && nav.maneuverImage != null) {
            drawBitmap(canvas, nav.maneuverImage, bounds);
            return;
        }
        String hint = nav == null ? "" : (nav.maneuverTitle + " " + nav.maneuverText)
                .toLowerCase(Locale.ROOT);
        HudNavigationVisuals.Maneuver visual = HudNavigationVisuals.maneuver(
                nav == null ? "" : nav.maneuverType);
        if (visual.shape == HudNavigationVisuals.ManeuverShape.UNKNOWN) {
            int direction = hint.contains("направ") || hint.contains("right") ? 1
                    : hint.contains("налев") || hint.contains("left") ? -1 : 0;
            visual = new HudNavigationVisuals.Maneuver(
                    hint.contains("развор") || hint.contains("u-turn")
                            ? HudNavigationVisuals.ManeuverShape.UTURN
                            : direction == 0 ? HudNavigationVisuals.ManeuverShape.STRAIGHT
                            : HudNavigationVisuals.ManeuverShape.TURN,
                    direction);
        }
        float stroke = Math.max(5f, Math.min(bounds.width(), bounds.height()) * .095f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (visual.shape) {
            case TURN:
            case SLIGHT:
            case HARD:
            case EXIT:
                drawDirectionalManeuver(canvas, bounds, color, stroke, visual);
                break;
            case FORK:
                drawForkManeuver(canvas, bounds, color, stroke, visual.direction);
                break;
            case UTURN:
                drawUturnManeuver(canvas, bounds, color, stroke, visual.direction);
                break;
            case ROUNDABOUT:
                drawRoundaboutManeuver(canvas, bounds, color, stroke);
                break;
            case FINISH:
                drawFinishManeuver(canvas, bounds, color, stroke);
                break;
            case WAYPOINT:
                drawWaypointManeuver(canvas, bounds, color, stroke);
                break;
            case FERRY:
                drawFerryManeuver(canvas, bounds, color, stroke);
                break;
            case STRAIGHT:
            case UNKNOWN:
            default:
                float cx = bounds.centerX();
                float top = bounds.top + stroke;
                canvas.drawLine(cx, bounds.bottom - stroke, cx,
                        top + bounds.height() * .12f, paint);
                drawVectorArrowHead(canvas, cx, top, 0f, -1f, color, stroke);
                break;
        }
    }

    private void drawDirectionalManeuver(Canvas canvas, RectF bounds, int color, float stroke,
                                         HudNavigationVisuals.Maneuver visual) {
        float direction = visual.direction < 0 ? -1f : 1f;
        float cx = bounds.centerX();
        float bottom = bounds.bottom - stroke;
        float top = bounds.top + stroke;
        float endX;
        float endY;
        path.reset();
        path.moveTo(cx, bottom);
        if (visual.shape == HudNavigationVisuals.ManeuverShape.SLIGHT) {
            endX = cx + direction * bounds.width() * .28f;
            endY = top + bounds.height() * .08f;
            path.cubicTo(cx, bounds.centerY(), endX, bounds.centerY(), endX, endY);
            canvas.drawPath(path, paint);
            drawVectorArrowHead(canvas, endX, endY, direction * .45f, -1f,
                    color, stroke);
            return;
        }
        if (visual.shape == HudNavigationVisuals.ManeuverShape.HARD) {
            endX = cx + direction * bounds.width() * .34f;
            endY = bounds.centerY() + bounds.height() * .17f;
            path.lineTo(cx, bounds.centerY() - bounds.height() * .08f);
            path.cubicTo(cx, top, endX, top, endX, endY);
            canvas.drawPath(path, paint);
            drawVectorArrowHead(canvas, endX, endY, 0f, 1f, color, stroke);
            return;
        }
        endX = cx + direction * bounds.width() * .34f;
        endY = bounds.centerY();
        path.lineTo(cx, endY);
        path.lineTo(endX, endY);
        canvas.drawPath(path, paint);
        if (visual.shape == HudNavigationVisuals.ManeuverShape.EXIT) {
            paint.setColor(withAlpha(color, 75));
            canvas.drawLine(cx, endY, cx, top, paint);
            paint.setColor(color);
        }
        drawVectorArrowHead(canvas, endX, endY, direction, 0f, color, stroke);
    }

    private void drawForkManeuver(Canvas canvas, RectF bounds, int color, float stroke,
                                  int selectedDirection) {
        float cx = bounds.centerX();
        float joint = bounds.centerY() + bounds.height() * .08f;
        float top = bounds.top + stroke;
        float dx = bounds.width() * .27f;
        canvas.drawLine(cx, bounds.bottom - stroke, cx, joint, paint);
        for (int branch = 0; branch < 2; branch++) {
            int direction = branch == 0 ? -1 : 1;
            int branchColor = direction == selectedDirection ? color : withAlpha(color, 75);
            paint.setColor(branchColor);
            path.reset();
            path.moveTo(cx, joint);
            path.cubicTo(cx, joint - bounds.height() * .14f,
                    cx + direction * dx, joint - bounds.height() * .18f,
                    cx + direction * dx, top);
            canvas.drawPath(path, paint);
            drawVectorArrowHead(canvas, cx + direction * dx, top,
                    direction * .25f, -1f, branchColor, stroke);
        }
    }

    private void drawUturnManeuver(Canvas canvas, RectF bounds, int color, float stroke,
                                   int selectedDirection) {
        float direction = selectedDirection < 0 ? -1f : 1f;
        float cx = bounds.centerX() - direction * bounds.width() * .16f;
        float otherX = cx + direction * bounds.width() * .34f;
        float top = bounds.top + stroke;
        float endY = bounds.centerY() + bounds.height() * .13f;
        path.reset();
        path.moveTo(otherX, bounds.bottom - stroke);
        path.lineTo(otherX, bounds.centerY());
        path.cubicTo(otherX, top, cx, top, cx, bounds.centerY());
        path.lineTo(cx, endY);
        canvas.drawPath(path, paint);
        drawVectorArrowHead(canvas, cx, endY, 0f, 1f, color, stroke);
    }

    private void drawRoundaboutManeuver(Canvas canvas, RectF bounds, int color, float stroke) {
        float radius = Math.min(bounds.width(), bounds.height()) * .25f;
        float cx = bounds.centerX();
        float cy = bounds.centerY() - bounds.height() * .06f;
        RectF circle = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        path.reset();
        path.addArc(circle, 75f, 300f);
        canvas.drawPath(path, paint);
        canvas.drawLine(cx, bounds.bottom - stroke, cx, cy + radius, paint);
        float angle = (float) Math.toRadians(15d);
        float x = cx + (float) Math.cos(angle) * radius;
        float y = cy + (float) Math.sin(angle) * radius;
        drawVectorArrowHead(canvas, x, y, .35f, -1f, color, stroke);
    }

    private void drawFinishManeuver(Canvas canvas, RectF bounds, int color, float stroke) {
        float poleX = bounds.left + bounds.width() * .34f;
        float top = bounds.top + stroke;
        float flagWidth = bounds.width() * .38f;
        float flagHeight = bounds.height() * .34f;
        canvas.drawLine(poleX, top, poleX, bounds.bottom - stroke, paint);
        paint.setStyle(Paint.Style.FILL);
        float cellW = flagWidth / 3f;
        float cellH = flagHeight / 2f;
        for (int row = 0; row < 2; row++) for (int column = 0; column < 3; column++) {
            paint.setColor((row + column) % 2 == 0 ? color : withAlpha(color, 45));
            canvas.drawRect(poleX + column * cellW, top + row * cellH,
                    poleX + (column + 1) * cellW, top + (row + 1) * cellH, paint);
        }
    }

    private void drawWaypointManeuver(Canvas canvas, RectF bounds, int color, float stroke) {
        float radius = Math.min(bounds.width(), bounds.height()) * .22f;
        float cx = bounds.centerX();
        float cy = bounds.centerY() - radius * .35f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, radius, paint);
        path.reset();
        path.moveTo(cx - radius * .55f, cy + radius * .82f);
        path.lineTo(cx, bounds.bottom - stroke);
        path.lineTo(cx + radius * .55f, cy + radius * .82f);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, Math.max(2f, radius * .28f), paint);
    }

    private void drawFerryManeuver(Canvas canvas, RectF bounds, int color, float stroke) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        float top = bounds.top + bounds.height() * .24f;
        RectF cabin = new RectF(bounds.left + bounds.width() * .34f, top,
                bounds.right - bounds.width() * .34f, bounds.centerY());
        canvas.drawRect(cabin, paint);
        path.reset();
        path.moveTo(bounds.left + bounds.width() * .18f, bounds.centerY());
        path.lineTo(bounds.right - bounds.width() * .18f, bounds.centerY());
        path.lineTo(bounds.right - bounds.width() * .3f,
                bounds.centerY() + bounds.height() * .25f);
        path.lineTo(bounds.left + bounds.width() * .3f,
                bounds.centerY() + bounds.height() * .25f);
        path.close();
        canvas.drawPath(path, paint);
        for (int wave = 0; wave < 2; wave++) {
            float y = bounds.centerY() + bounds.height() * (.34f + wave * .14f);
            canvas.drawLine(bounds.left + bounds.width() * .2f, y,
                    bounds.right - bounds.width() * .2f, y, paint);
        }
    }

    private void drawCombinedNavigation(Canvas canvas, HudElementConfig item, RectF bounds,
                                        int color, int unitColor, float scale) {
        String layout = item.options.optString("arrowLayout", "LEFT");
        RectF arrow = new RectF(bounds);
        RectF text = new RectF(bounds);
        if ("TOP".equals(layout) || "BOTTOM".equals(layout)) {
            float split = bounds.height() * .58f;
            if ("TOP".equals(layout)) {
                arrow.bottom = arrow.top + split;
                text.top = arrow.bottom;
            } else {
                text.bottom = text.top + bounds.height() - split;
                arrow.top = text.bottom;
            }
        } else {
            float split = bounds.width() * .43f;
            if ("RIGHT".equals(layout)) {
                text.right = text.left + bounds.width() - split;
                arrow.left = text.right;
            } else {
                arrow.right = arrow.left + split;
                text.left = arrow.right;
            }
        }
        drawManeuver(canvas, item, inset(arrow, scale * 4f), color);
        drawText(canvas, item, data.textFor(item), text, unitColor, scale);
    }

    private void drawLanes(Canvas canvas, HudElementConfig item, RectF bounds, int color) {
        HudNavigationState nav = data.navigation();
        if (item.options.optBoolean("preferSourceImage", true)
                && nav != null && nav.lanesImage != null) {
            drawBitmap(canvas, nav.lanesImage, bounds);
            return;
        }
        String lanes = nav == null ? "" : nav.lanes;
        int count = nav != null && !nav.laneItems.isEmpty() ? nav.laneItems.size()
                : lanes.isEmpty() ? 3 : Math.max(1,
                Math.min(8, lanes.split("[,;| ]+").length));
        float cell = bounds.width() / count;
        float stroke = Math.max(3f, Math.min(cell, bounds.height()) * .09f);
        for (int index = 0; index < count; index++) {
            float cx = bounds.left + cell * (index + .5f);
            HudNavigationState.Lane lane = nav != null && index < nav.laneItems.size()
                    ? nav.laneItems.get(index) : null;
            if (lane == null || lane.directions.isEmpty()) {
                drawLaneDirection(canvas, cx, bounds, "STRAIGHT_AHEAD", color, stroke);
                continue;
            }
            int directionCount = Math.min(3, lane.directions.size());
            float spacing = Math.min(cell * .22f, stroke * 2.2f);
            for (int directionIndex = 0; directionIndex < directionCount; directionIndex++) {
                String direction = lane.directions.get(directionIndex);
                boolean highlighted = direction.equals(lane.highlightedDirection)
                        && !"UNKNOWN_DIRECTION".equals(direction);
                int laneColor = highlighted
                        ? optionColor(item, "highlightColor", 0xFF34C759) : color;
                float directionX = cx
                        + (directionIndex - (directionCount - 1) / 2f) * spacing;
                drawLaneDirection(canvas, directionX, bounds, direction, laneColor,
                        Math.max(2f, stroke * .8f));
            }
        }
        if (nav != null && !"OFF".equals(item.options.optString(
                "laneDistancePosition", "BOTTOM")) && !nav.laneDistance.isEmpty()) {
            RectF label = new RectF(bounds.left, bounds.bottom - bounds.height() * .26f,
                    bounds.right, bounds.bottom);
            drawSimpleText(canvas, nav.laneDistance, label, color,
                    Math.max(10f, label.height() * .65f), Layout.Alignment.ALIGN_CENTER);
        }
    }

    private void drawLaneDirection(Canvas canvas, float cx, RectF bounds, String direction,
                                   int color, float stroke) {
        HudNavigationVisuals.Lane visual = HudNavigationVisuals.lane(direction);
        float side = visual.direction < 0 ? -1f : 1f;
        float startY = bounds.bottom - stroke;
        float jointY = bounds.top + bounds.height() * .48f;
        float endX = cx;
        float endY = bounds.top + stroke;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        path.reset();
        path.moveTo(cx, startY);
        switch (visual.shape) {
            case TURN_45:
                endX = cx + side * bounds.width() * .055f;
                path.cubicTo(cx, jointY, endX, jointY, endX, endY);
                break;
            case TURN_90:
                endX = cx + side * bounds.width() * .075f;
                endY = jointY;
                path.lineTo(cx, jointY);
                path.lineTo(endX, endY);
                break;
            case TURN_135:
                endX = cx + side * bounds.width() * .07f;
                endY = jointY + bounds.height() * .14f;
                path.lineTo(cx, jointY - bounds.height() * .06f);
                path.cubicTo(cx, bounds.top + bounds.height() * .18f,
                        endX, bounds.top + bounds.height() * .18f, endX, endY);
                break;
            case UTURN:
                endX = cx - side * bounds.width() * .045f;
                endY = jointY + bounds.height() * .13f;
                float outerX = cx + side * bounds.width() * .045f;
                path.lineTo(outerX, jointY);
                path.cubicTo(outerX, bounds.top + bounds.height() * .12f,
                        endX, bounds.top + bounds.height() * .12f, endX, jointY);
                path.lineTo(endX, endY);
                break;
            case MERGE:
                endX = cx + side * bounds.width() * .05f;
                path.cubicTo(cx, jointY + bounds.height() * .12f,
                        endX, jointY, endX, endY);
                break;
            case SHIFT:
                endX = cx + side * bounds.width() * .055f;
                path.cubicTo(cx, jointY + bounds.height() * .15f,
                        endX, jointY + bounds.height() * .08f, endX, jointY);
                path.lineTo(endX, endY);
                break;
            case STRAIGHT:
            case UNKNOWN:
            default:
                path.lineTo(endX, endY);
                break;
        }
        canvas.drawPath(path, paint);
        float dx = endX - cx;
        float dy = endY - jointY;
        if (visual.shape == HudNavigationVisuals.LaneShape.TURN_90) dy = 0f;
        else if (visual.shape == HudNavigationVisuals.LaneShape.TURN_135
                || visual.shape == HudNavigationVisuals.LaneShape.UTURN) dy = 1f;
        else dy = -1f;
        drawVectorArrowHead(canvas, endX, endY, dx, dy, color, stroke);
    }

    private void drawSpeedLimit(Canvas canvas, HudElementConfig item, RectF bounds,
                                int color, float scale) {
        String speed = digitsOnly(data.textFor(item));
        boolean white = item.options.optBoolean("whiteSign", true);
        double current = data.numericValue(item);
        double limit;
        try { limit = Double.parseDouble(speed); }
        catch (NumberFormatException ignored) { limit = Double.NaN; }
        boolean overspeed = Double.isFinite(current) && Double.isFinite(limit)
                && current > limit + item.options.optInt("overspeedDelta", 10);
        boolean blinkOff = overspeed && item.options.optBoolean("overspeedBlink", true)
                && (SystemClock.uptimeMillis() / 500L) % 2L == 0L;
        if (blinkOff) return;
        float radius = Math.min(bounds.width(), bounds.height()) * .43f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(white ? Color.WHITE : Color.TRANSPARENT);
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(4f * scale, radius * .11f));
        paint.setColor(overspeed ? 0xFFFF2D2D : 0xFFFF3B30);
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
        drawSimpleText(canvas, speed.isEmpty() ? "—" : speed,
                new RectF(bounds.left, bounds.centerY() - radius * .55f,
                        bounds.right, bounds.centerY() + radius * .55f),
                white ? Color.BLACK : color, radius * .82f, Layout.Alignment.ALIGN_CENTER);
    }

    private void drawTrafficLights(Canvas canvas, HudElementConfig item, RectF bounds,
                                   int fallbackColor, float scale) {
        HudNavigationState nav = data.navigation();
        List<HudNavigationState.TrafficLight> lights =
                nav == null ? Collections.emptyList() : nav.trafficLights;
        if (lights.isEmpty() && nav != null && nav.trafficAvailable) {
            drawOneTrafficLight(canvas, item, bounds, nav.trafficColor,
                    nav.trafficCountdown, nav.trafficArrow, fallbackColor, scale);
            return;
        }
        if (lights.isEmpty()) {
            drawOneTrafficLight(canvas, item, bounds, "", "—", "",
                    fallbackColor, scale);
            return;
        }
        boolean horizontal = "HORIZONTAL".equals(item.options.optString(
                "orientation", "VERTICAL"));
        int count = Math.min(4, lights.size());
        for (int index = 0; index < count; index++) {
            RectF cell;
            if (horizontal) {
                float width = bounds.width() / count;
                cell = new RectF(bounds.left + width * index, bounds.top,
                        bounds.left + width * (index + 1), bounds.bottom);
            } else {
                float height = bounds.height() / count;
                cell = new RectF(bounds.left, bounds.top + height * index,
                        bounds.right, bounds.top + height * (index + 1));
            }
            HudNavigationState.TrafficLight light = lights.get(index);
            drawOneTrafficLight(canvas, item, inset(cell, 2f * scale), light.color,
                    light.countdown, light.arrow, fallbackColor, scale);
        }
    }

    private void drawOneTrafficLight(Canvas canvas, HudElementConfig item, RectF bounds,
                                     String state, String countdown, String arrow,
                                     int fallbackColor, float scale) {
        boolean capsule = !"CLASSIC".equals(item.options.optString("style", "CAPSULE"));
        if (item.options.optBoolean("showFrame", true)) {
            paint.setStyle(capsule ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, 2f * scale));
            paint.setColor(capsule ? 0xBB171A20 : fallbackColor);
            float radius = Math.min(bounds.width(), bounds.height()) * .23f;
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
        int active = optionColor(item, "unknownColor", 0xFF6B7280);
        String lower = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if (lower.contains("red") || lower.contains("крас")) {
            active = optionColor(item, "redColor", 0xFFFF3B30);
        } else if (lower.contains("yellow") || lower.contains("жел")) {
            active = optionColor(item, "yellowColor", 0xFFFFCC00);
        } else if (lower.contains("green") || lower.contains("зел")) {
            active = optionColor(item, "greenColor", 0xFF34C759);
        }
        String countdownSide = item.options.optString("countdownSide", "BOTTOM");
        RectF signalBounds = new RectF(bounds);
        RectF text = new RectF(bounds);
        if ("TOP".equals(countdownSide)) {
            text.bottom = bounds.top + bounds.height() * .36f;
            signalBounds.top = text.bottom;
        } else if ("LEFT".equals(countdownSide)) {
            text.right = bounds.left + bounds.width() * .42f;
            signalBounds.left = text.right;
        } else if ("RIGHT".equals(countdownSide)) {
            signalBounds.right = bounds.left + bounds.width() * .58f;
            text.left = signalBounds.right;
        } else {
            signalBounds.bottom = bounds.top + bounds.height() * .6f;
            text.top = signalBounds.bottom;
        }
        float diameter = Math.min(signalBounds.width(), signalBounds.height()) * .7f;
        float cx = signalBounds.centerX();
        float cy = signalBounds.centerY();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active);
        canvas.drawCircle(cx, cy, diameter * .45f, paint);
        drawTrafficArrow(canvas, cx, cy, diameter * .48f, arrow,
                lower.contains("red") ? Color.WHITE : Color.BLACK,
                Math.max(2f, 2.2f * scale));
        String line = countdown == null || countdown.trim().isEmpty() ? "—" : countdown.trim();
        drawSimpleText(canvas, line, text, fallbackColor,
                Math.max(9f, Math.min(text.height() * .62f, text.width() * .28f)),
                Layout.Alignment.ALIGN_CENTER);
    }

    private void drawTrafficArrow(Canvas canvas, float cx, float cy, float size,
                                  @Nullable String arrow, int color, float stroke) {
        String value = arrow == null ? "" : arrow.trim();
        if (!HudNavigationVisuals.isTrafficArrow(value)) return;
        if (HudNavigationVisuals.isTrafficUturn(value)) {
            RectF arc = new RectF(cx - size * .42f, cy - size * .42f,
                    cx + size * .42f, cy + size * .42f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(color);
            path.reset();
            path.addArc(arc, 40f, -250f);
            canvas.drawPath(path, paint);
            drawVectorArrowHead(canvas, arc.left, cy, 0f, 1f, color, stroke);
            return;
        }
        int direction = HudNavigationVisuals.trafficArrowDirection(value);
        float dx = direction * size * .32f;
        float startY = cy + size * .32f;
        float endY = direction == 0 ? cy - size * .36f : cy;
        float endX = cx + dx;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        path.reset();
        path.moveTo(cx, startY);
        path.lineTo(cx, cy);
        path.lineTo(endX, endY);
        canvas.drawPath(path, paint);
        drawVectorArrowHead(canvas, endX, endY,
                direction == 0 ? 0f : direction, direction == 0 ? -1f : 0f,
                color, stroke);
    }

    private void drawProgress(Canvas canvas, HudElementConfig item, RectF bounds,
                              int color, float scale) {
        boolean vertical = "VERTICAL".equals(item.options.optString(
                "orientation", "HORIZONTAL"));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x443F4653);
        float radius = Math.min(bounds.width(), bounds.height()) * .35f;
        canvas.drawRoundRect(bounds, radius, radius, paint);
        double progress = data.numericValue(item);
        if (!Double.isFinite(progress)) {
            progress = 0d;
        }
        progress = Math.max(0d, Math.min(1d, progress));
        RectF fill = new RectF(bounds);
        if (vertical) fill.top = fill.bottom - fill.height() * (float) progress;
        else fill.right = fill.left + fill.width() * (float) progress;
        paint.setColor(color);
        canvas.drawRoundRect(fill, radius, radius, paint);
        drawSimpleText(canvas, data.textFor(item), bounds, Color.WHITE,
                Math.max(9f, Math.min(bounds.height() * .55f, 26f * scale)),
                Layout.Alignment.ALIGN_CENTER);
    }

    private void drawJamProgress(Canvas canvas, HudElementConfig item, RectF bounds,
                                 int textColor, float scale) {
        HudNavigationState nav = data.navigation();
        List<HudNavigationState.TrafficRun> runs = nav == null
                ? Collections.emptyList() : nav.trafficRuns;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x443F4653);
        float radius = Math.min(bounds.width(), bounds.height()) * .35f;
        canvas.drawRoundRect(bounds, radius, radius, paint);
        if (!runs.isEmpty()) {
            int maximum = runs.get(runs.size() - 1).to;
            boolean vertical = "VERTICAL".equals(item.options.optString(
                    "orientation", "HORIZONTAL"));
            int clip = canvas.save();
            canvas.clipRect(bounds);
            for (HudNavigationState.TrafficRun run : runs) {
                if (maximum <= 0) break;
                float from = run.from / (float) maximum;
                float to = run.to / (float) maximum;
                RectF part = new RectF(bounds);
                if (vertical) {
                    part.bottom = bounds.bottom - bounds.height() * from;
                    part.top = bounds.bottom - bounds.height() * to;
                } else {
                    part.left = bounds.left + bounds.width() * from;
                    part.right = bounds.left + bounds.width() * to;
                }
                paint.setColor(jamColor(item, run.type));
                canvas.drawRect(part, paint);
            }
            canvas.restoreToCount(clip);
        }
        drawSimpleText(canvas, data.textFor(item), bounds, textColor,
                Math.max(9f, Math.min(bounds.height() * .55f, 26f * scale)),
                Layout.Alignment.ALIGN_CENTER);
    }

    private void drawRouteGraphic(Canvas canvas, HudElementConfig item, RectF bounds) {
        HudNavigationState nav = data.navigation();
        Bitmap bitmap = nav == null ? null
                : nav.rainbowImage != null ? nav.rainbowImage : nav.jamImage;
        if (bitmap != null) {
            drawBitmap(canvas, bitmap, bounds);
            return;
        }
        if (nav == null || nav.trafficRuns.isEmpty()) return;
        int maximum = nav.trafficRuns.get(nav.trafficRuns.size() - 1).to;
        if (maximum <= 0) return;
        float stroke = Math.max(4f, Math.min(bounds.width(), bounds.height()) * .11f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float x = bounds.centerX();
        for (HudNavigationState.TrafficRun run : nav.trafficRuns) {
            float top = bounds.bottom - bounds.height() * run.to / maximum;
            float bottom = bounds.bottom - bounds.height() * run.from / maximum;
            paint.setColor(jamColor(item, run.type));
            canvas.drawLine(x, bottom, x, top, paint);
        }
    }

    private static int jamColor(@Nullable HudElementConfig item, String type) {
        String key;
        int fallback;
        if ("BLOCKED".equals(type)) { key = "blockedColor"; fallback = 0xFF7A1FA2; }
        else if ("VERY_HARD".equals(type)) { key = "veryHardColor"; fallback = 0xFFB00020; }
        else if ("HARD".equals(type)) { key = "hardColor"; fallback = 0xFFFF3B30; }
        else if ("LIGHT".equals(type)) { key = "lightColor"; fallback = 0xFFFFCC00; }
        else if ("FREE".equals(type)) { key = "freeColor"; fallback = 0xFF34C759; }
        else { key = "unknownColor"; fallback = 0xFF8E8E93; }
        return item == null ? fallback : optionColor(item, key, fallback);
    }

    private void drawTurnSignal(Canvas canvas, HudElementConfig item, RectF bounds,
                                int color, boolean left) {
        if (!data.active(item)) {
            paint.setColor(withAlpha(color, 65));
        } else if (item.options.optBoolean("animated", true)) {
            int frequency = Math.max(150, item.options.optInt("blinkFrequencyMs", 500));
            if ((SystemClock.uptimeMillis() / frequency) % 2L == 0L) return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(data.active(item) ? color : withAlpha(color, 65));
        float direction = left ? -1f : 1f;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float halfW = bounds.width() * .42f;
        float halfH = bounds.height() * .38f;
        path.reset();
        path.moveTo(cx + direction * halfW, cy);
        path.lineTo(cx - direction * halfW * .05f, cy - halfH);
        path.lineTo(cx - direction * halfW * .05f, cy - halfH * .38f);
        path.lineTo(cx - direction * halfW, cy - halfH * .38f);
        path.lineTo(cx - direction * halfW, cy + halfH * .38f);
        path.lineTo(cx - direction * halfW * .05f, cy + halfH * .38f);
        path.lineTo(cx - direction * halfW * .05f, cy + halfH);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawHighBeam(Canvas canvas, RectF bounds, int color) {
        boolean active = data.active(findByType(HudElementType.HIGH_BEAM));
        paint.setColor(active ? color : withAlpha(color, 65));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, bounds.height() * .055f));
        RectF lamp = new RectF(bounds.left + bounds.width() * .12f,
                bounds.top + bounds.height() * .24f,
                bounds.left + bounds.width() * .48f,
                bounds.bottom - bounds.height() * .24f);
        canvas.drawOval(lamp, paint);
        for (int index = 0; index < 4; index++) {
            float y = bounds.top + bounds.height() * (.25f + index * .17f);
            canvas.drawLine(lamp.right + bounds.width() * .08f, y,
                    bounds.right - bounds.width() * .08f, y, paint);
        }
    }

    private void drawAutoHold(Canvas canvas, RectF bounds, int color, float scale) {
        int draw = data.active(findByType(HudElementType.AUTO_HOLD)) ? color : withAlpha(color, 65);
        paint.setColor(draw);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, 4f * scale));
        float radius = Math.min(bounds.width(), bounds.height()) * .34f;
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
        drawSimpleText(canvas, "A", bounds, draw, radius * 1.25f,
                Layout.Alignment.ALIGN_CENTER);
    }

    @Nullable
    private HudElementConfig findByType(HudElementType type) {
        for (HudElementConfig item : config.elements) if (item.type == type) return item;
        return null;
    }

    private void drawText(Canvas canvas, HudElementConfig item, String value, RectF bounds,
                          int color, float scale) {
        float size = Math.max(8f, item.fontSizeSp * scale);
        Layout.Alignment alignment = "LEFT".equals(item.alignment)
                ? Layout.Alignment.ALIGN_NORMAL : "RIGHT".equals(item.alignment)
                ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER;
        drawStyledText(canvas, value, bounds, color, size,
                alignment, item.wrapText, item.fontWeight);
    }

    private void drawStyledText(Canvas canvas, String value, RectF bounds, int color,
                                float size, Layout.Alignment alignment, boolean wrap,
                                int weight) {
        textPaint.setColor(color);
        textPaint.setTextSize(size);
        textPaint.setTypeface(typeface(weight));
        int width = Math.max(1, Math.round(bounds.width()));
        String text = value == null ? "" : value;
        if (!wrap && text.indexOf('\n') < 0) {
            CharSequence displayed = text;
            if (textPaint.measureText(text) > width) {
                displayed = android.text.TextUtils.ellipsize(text, textPaint, width,
                        android.text.TextUtils.TruncateAt.END);
            }
            float x;
            if (alignment == Layout.Alignment.ALIGN_NORMAL) {
                textPaint.setTextAlign(Paint.Align.LEFT);
                x = bounds.left;
            } else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
                x = bounds.right;
            } else {
                textPaint.setTextAlign(Paint.Align.CENTER);
                x = bounds.centerX();
            }
            textPaint.getFontMetrics(textFontMetrics);
            float baseline = bounds.centerY()
                    - (textFontMetrics.ascent + textFontMetrics.descent) * .5f;
            int save = canvas.save();
            canvas.clipRect(bounds);
            canvas.drawText(displayed, 0, displayed.length(), x, baseline, textPaint);
            canvas.restoreToCount(save);
            return;
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
        StaticLayout layout = new StaticLayout(text, textPaint, width, alignment,
                1f, 0f, false);
        if (!wrap && layout.getLineCount() > 1) {
            text = android.text.TextUtils.ellipsize(text, textPaint, width,
                    android.text.TextUtils.TruncateAt.END).toString();
            layout = new StaticLayout(text, textPaint, width, alignment,
                    1f, 0f, false);
        }
        float y = bounds.top + Math.max(0f, (bounds.height() - layout.getHeight()) / 2f);
        int save = canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(bounds.left, y);
        layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void drawSimpleText(Canvas canvas, String value, RectF bounds, int color,
                                float size, Layout.Alignment alignment) {
        drawStyledText(canvas, value, bounds, color, size, alignment, false, 700);
    }

    private void drawBitmap(Canvas canvas, @Nullable Bitmap bitmap, RectF bounds) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Rect source = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        RectF target = fitCenter(source.width(), source.height(), bounds);
        paint.setAlpha(255);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(bitmap, source, target, paint);
    }

    private void drawGrid(Canvas canvas, Geometry geometry) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        for (int column = 0; column <= config.gridColumns; column++) {
            paint.setColor(column % 5 == 0 ? 0x558BB8FF : 0x264B6480);
            float x = geometry.content.left + column * geometry.cellWidth;
            canvas.drawLine(x, geometry.content.top, x, geometry.content.bottom, paint);
        }
        for (int row = 0; row <= config.gridRows; row++) {
            paint.setColor(row % 5 == 0 ? 0x558BB8FF : 0x264B6480);
            float y = geometry.content.top + row * geometry.cellHeight;
            canvas.drawLine(geometry.content.left, y, geometry.content.right, y, paint);
        }
    }

    private void drawSelection(Canvas canvas, RectF bounds) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(0xFF0A84FF);
        canvas.drawRect(bounds, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(bounds.right, bounds.bottom, HANDLE_SIZE * .45f, paint);
    }

    private void drawSnow(Canvas canvas, Geometry geometry) {
        long tick = SystemClock.uptimeMillis() / 70L;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xBFFFFFFF);
        for (int index = 0; index < 42; index++) {
            float x = geometry.content.left
                    + ((index * 97 + tick * (1 + index % 3)) % 1_000) / 1_000f
                    * geometry.content.width();
            float y = geometry.content.top
                    + ((index * 53 + tick * (2 + index % 4)) % 1_000) / 1_000f
                    * geometry.content.height();
            canvas.drawCircle(x, y, 1.2f + index % 3, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editor) return false;
        Geometry geometry = geometry();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = hit(event.getX(), event.getY(), geometry);
                if (dragging == null) {
                    select(null);
                    return true;
                }
                select(dragging.id);
                RectF selectedBounds = bounds(dragging, geometry);
                resizing = Math.abs(event.getX() - selectedBounds.right) <= HANDLE_SIZE
                        && Math.abs(event.getY() - selectedBounds.bottom) <= HANDLE_SIZE;
                downX = event.getX();
                downY = event.getY();
                startX = dragging.x;
                startY = dragging.y;
                startWidth = dragging.width;
                startHeight = dragging.height;
                startFineX = (float) dragging.options.optDouble("fineX", 0d);
                startFineY = (float) dragging.options.optDouble("fineY", 0d);
                movedSinceDown = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == null) return true;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    movedSinceDown = true;
                }
                if (resizing) {
                    dragging.width = clamp(startWidth + Math.round(dx / geometry.cellWidth),
                            1, config.gridColumns - dragging.x);
                    dragging.height = clamp(startHeight + Math.round(dy / geometry.cellHeight),
                            1, config.gridRows - dragging.y);
                } else if (config.freeMovement) {
                    moveFreely(dragging, geometry, dx, dy);
                } else {
                    dragging.x = clamp(startX + Math.round(dx / geometry.cellWidth),
                            0, config.gridColumns - dragging.width);
                    dragging.y = clamp(startY + Math.round(dy / geometry.cellHeight),
                            0, config.gridRows - dragging.height);
                    putFine(dragging, 0f, 0f);
                }
                if (editorListener != null) editorListener.onGeometryChanged(dragging, false);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                HudElementConfig completed = dragging;
                boolean configure = event.getActionMasked() == MotionEvent.ACTION_UP
                        && completed != null && !movedSinceDown && !resizing;
                if (dragging != null && editorListener != null) {
                    editorListener.onGeometryChanged(dragging, true);
                }
                dragging = null;
                resizing = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                if (configure && editorListener != null) {
                    editorListener.onConfigure(completed);
                }
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void moveFreely(HudElementConfig item, Geometry geometry, float dx, float dy) {
        float logicalCellWidth = config.contentWidth / (float) config.gridColumns;
        float logicalCellHeight = config.contentHeight / (float) config.gridRows;
        float absoluteX = startX * logicalCellWidth + startFineX + dx / geometry.scale;
        float absoluteY = startY * logicalCellHeight + startFineY + dy / geometry.scale;
        float maxX = config.contentWidth - item.width * logicalCellWidth;
        float maxY = config.contentHeight - item.height * logicalCellHeight;
        absoluteX = Math.max(0f, Math.min(maxX, absoluteX));
        absoluteY = Math.max(0f, Math.min(maxY, absoluteY));
        item.x = clamp((int) Math.floor(absoluteX / logicalCellWidth),
                0, config.gridColumns - item.width);
        item.y = clamp((int) Math.floor(absoluteY / logicalCellHeight),
                0, config.gridRows - item.height);
        putFine(item, absoluteX - item.x * logicalCellWidth,
                absoluteY - item.y * logicalCellHeight);
    }

    private static void putFine(HudElementConfig item, float x, float y) {
        try {
            item.options.put("fineX", x);
            item.options.put("fineY", y);
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Nullable
    private HudElementConfig hit(float x, float y, Geometry geometry) {
        List<HudElementConfig> order = new ArrayList<>(config.drawingOrder());
        Collections.reverse(order);
        // A group is edited as one free frame. Its member settings remain available from the
        // group editor, so invisible children cannot steal the first tap from their container.
        for (HudElementConfig item : order) {
            if (item.type == HudElementType.HORIZONTAL_GROUP && item.enabled
                    && bounds(item, geometry).contains(x, y)) {
                return item;
            }
        }
        for (HudElementConfig item : order) {
            if (item.type != HudElementType.HORIZONTAL_GROUP && item.enabled
                    && bounds(item, geometry).contains(x, y)) {
                return item;
            }
        }
        return null;
    }

    @Nullable
    private HudElementConfig find(@Nullable String id) {
        if (id == null) return null;
        for (HudElementConfig item : config.elements) if (id.equals(item.id)) return item;
        return null;
    }

    private RectF bounds(HudElementConfig item, Geometry geometry) {
        if (!editor) {
            RectF cached = cachedElementBounds.get(item);
            if (cached != null) return cached;
            RectF resolved = resolveBounds(item, geometry);
            cachedElementBounds.put(item, resolved);
            return resolved;
        }
        return resolveBounds(item, geometry);
    }

    private RectF resolveBounds(HudElementConfig item, Geometry geometry) {
        if (item.type != HudElementType.HORIZONTAL_GROUP
                && item.type != HudElementType.BACKDROP) {
            RectF grouped = groupedBounds(item, geometry);
            if (grouped != null) return grouped;
        }
        return baseBounds(item, geometry);
    }

    @Nullable
    private RectF groupedBounds(@NonNull HudElementConfig member,
                                @NonNull Geometry geometry) {
        for (HudElementConfig group : config.elements) {
            if (!group.enabled || group.type != HudElementType.HORIZONTAL_GROUP) continue;
            List<String> ids = HudHorizontalGroup.memberIds(group);
            int memberIndex = ids.indexOf(member.id);
            if (memberIndex < 0 || ids.size() < 2) continue;

            RectF groupBounds = baseBounds(group, geometry);
            int marginLeft = Math.round(HudHorizontalGroup.marginLeftPx(group) * geometry.scale);
            int marginTop = Math.round(HudHorizontalGroup.marginTopPx(group) * geometry.scale);
            int marginRight = Math.round(
                    HudHorizontalGroup.marginRightPx(group) * geometry.scale);
            int marginBottom = Math.round(
                    HudHorizontalGroup.marginBottomPx(group) * geometry.scale);
            float left = Math.min(groupBounds.right - 1f, groupBounds.left + marginLeft);
            float top = Math.min(groupBounds.bottom - 1f, groupBounds.top + marginTop);
            float right = Math.max(left + 1f, groupBounds.right - marginRight);
            float bottom = Math.max(top + 1f, groupBounds.bottom - marginBottom);

            ArrayList<HorizontalGroupLayout.Size> desired = new ArrayList<>();
            ArrayList<String> presentIds = new ArrayList<>();
            for (String id : ids) {
                HudElementConfig value = find(id);
                if (value == null || !value.enabled
                        || value.type == HudElementType.HORIZONTAL_GROUP
                        || value.type == HudElementType.BACKDROP) {
                    continue;
                }
                RectF source = baseBounds(value, geometry);
                desired.add(new HorizontalGroupLayout.Size(
                        Math.max(1, Math.round(source.width())),
                        Math.max(1, Math.round(source.height()))));
                presentIds.add(id);
            }
            int presentIndex = presentIds.indexOf(member.id);
            if (presentIndex < 0 || presentIds.size() < 2) return null;
            List<HorizontalGroupLayout.Rect> placements = HorizontalGroupLayout.layout(
                    Math.round(left), Math.round(top),
                    Math.max(1, Math.round(right - left)),
                    Math.max(1, Math.round(bottom - top)),
                    Math.round(HudHorizontalGroup.paddingLeftPx(group) * geometry.scale),
                    Math.round(HudHorizontalGroup.paddingTopPx(group) * geometry.scale),
                    Math.round(HudHorizontalGroup.paddingRightPx(group) * geometry.scale),
                    Math.round(HudHorizontalGroup.paddingBottomPx(group) * geometry.scale),
                    Math.round(HudHorizontalGroup.gapPx(group) * geometry.scale),
                    HudHorizontalGroup.horizontalAlignment(group),
                    HudHorizontalGroup.verticalAlignment(group),
                    HudHorizontalGroup.distribution(group),
                    desired);
            if (presentIndex >= placements.size()) return null;
            HorizontalGroupLayout.Rect resolved = placements.get(presentIndex);
            return new RectF(resolved.x, resolved.y,
                    resolved.x + resolved.width, resolved.y + resolved.height);
        }
        return null;
    }

    private RectF baseBounds(HudElementConfig item, Geometry geometry) {
        float fineX = (float) item.options.optDouble("fineX", 0d) * geometry.scale;
        float fineY = (float) item.options.optDouble("fineY", 0d) * geometry.scale;
        float left = geometry.content.left + item.x * geometry.cellWidth + fineX;
        float top = geometry.content.top + item.y * geometry.cellHeight + fineY;
        return new RectF(left, top, left + item.width * geometry.cellWidth,
                top + item.height * geometry.cellHeight);
    }

    private Geometry geometry() {
        Geometry cached = cachedGeometry;
        if (cached != null) return cached;
        float width = Math.max(1f, getWidth());
        float height = Math.max(1f, getHeight());
        if (editor) {
            float scale = Math.min(width / HudViewportPolicy.SAFE_WIDTH,
                    height / HudViewportPolicy.SAFE_HEIGHT);
            float left = (width - HudViewportPolicy.SAFE_WIDTH * scale) / 2f;
            float top = (height - HudViewportPolicy.SAFE_HEIGHT * scale) / 2f;
            RectF content = new RectF(left, top,
                    left + HudViewportPolicy.SAFE_WIDTH * scale,
                    top + HudViewportPolicy.SAFE_HEIGHT * scale);
            cachedGeometry = new Geometry(scale, content, new RectF(content),
                    content.width() / config.gridColumns,
                    content.height() / config.gridRows);
            return cachedGeometry;
        }

        if (localHudViewport) {
            // WindowManager already enforces the dump-verified 728x190 surface. Coordinates are
            // local here, while the grid and every element keep exactly one physical pixel per
            // configured pixel.
            RectF content = new RectF(0f, 0f,
                    HudViewportPolicy.SAFE_WIDTH, HudViewportPolicy.SAFE_HEIGHT);
            RectF safeClip = new RectF(0f, 0f,
                    Math.min(width, HudViewportPolicy.SAFE_WIDTH),
                    Math.min(height, HudViewportPolicy.SAFE_HEIGHT));
            cachedGeometry = new Geometry(1f, content, safeClip,
                    HudViewportPolicy.SAFE_WIDTH / (float) config.gridColumns,
                    HudViewportPolicy.SAFE_HEIGHT / (float) config.gridRows);
            return cachedGeometry;
        }

        // Presentation fallback coordinates are physical pixels, exactly as in mHUD 6.1. Never
        // scale or center this plane: doing so could overlap another panel in the virtual display.
        RectF content = new RectF(HudViewportPolicy.SAFE_LEFT, HudViewportPolicy.SAFE_TOP,
                HudViewportPolicy.SAFE_RIGHT, HudViewportPolicy.SAFE_BOTTOM);
        HudViewportPolicy.Bounds clipped = HudViewportPolicy.clipToSurface(
                Math.round(width), Math.round(height));
        RectF safeClip = new RectF(clipped.left, clipped.top, clipped.right, clipped.bottom);
        cachedGeometry = new Geometry(1f, content, safeClip,
                HudViewportPolicy.SAFE_WIDTH / (float) config.gridColumns,
                HudViewportPolicy.SAFE_HEIGHT / (float) config.gridRows);
        return cachedGeometry;
    }

    @NonNull
    private Typeface typeface(int itemWeight) {
        int weight = clamp(itemWeight > 0 ? itemWeight : config.globalFontWeight, 100, 900);
        String uri = config.customFontUri;
        String previousUri = loadedFontUri == null ? "" : loadedFontUri;
        if (!uri.equals(previousUri)) {
            loadedFontUri = uri;
            loadedFont = null;
            typefaceCache.clear();
            if (!uri.isEmpty()) {
                try (ParcelFileDescriptor descriptor = getContext().getContentResolver()
                        .openFileDescriptor(Uri.parse(uri), "r")) {
                    if (descriptor != null) {
                        loadedFont = new Typeface.Builder(descriptor.getFileDescriptor()).build();
                    }
                } catch (Exception ignored) {
                    loadedFont = null;
                }
            }
        }
        Typeface cached = typefaceCache.get(weight);
        if (cached != null) return cached;
        Typeface base = loadedFont == null ? Typeface.DEFAULT : loadedFont;
        Typeface resolved;
        try { resolved = Typeface.create(base, weight, false); }
        catch (RuntimeException ignored) { resolved = Typeface.DEFAULT_BOLD; }
        typefaceCache.put(weight, resolved);
        return resolved;
    }

    private void drawArrowHead(Canvas canvas, float x, float y, boolean right,
                               int color, float stroke) {
        float direction = right ? 1f : -1f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        path.reset();
        path.moveTo(x - direction * stroke * 2.2f, y - stroke * 2.2f);
        path.lineTo(x, y);
        path.lineTo(x - direction * stroke * 2.2f, y + stroke * 2.2f);
        canvas.drawPath(path, paint);
    }

    private void drawUpArrowHead(Canvas canvas, float x, float y, int color, float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        path.reset();
        path.moveTo(x - stroke * 2.2f, y + stroke * 2.2f);
        path.lineTo(x, y);
        path.lineTo(x + stroke * 2.2f, y + stroke * 2.2f);
        canvas.drawPath(path, paint);
    }

    private void drawVectorArrowHead(Canvas canvas, float x, float y, float dx, float dy,
                                     int color, float stroke) {
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < .001f) return;
        float ux = dx / length;
        float uy = dy / length;
        float px = -uy;
        float py = ux;
        float back = stroke * 2.5f;
        float side = stroke * 1.65f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        path.reset();
        path.moveTo(x - ux * back + px * side, y - uy * back + py * side);
        path.lineTo(x, y);
        path.lineTo(x - ux * back - px * side, y - uy * back - py * side);
        canvas.drawPath(path, paint);
    }

    private static RectF fitCenter(float sourceWidth, float sourceHeight, RectF target) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new RectF(target);
        float scale = Math.min(target.width() / sourceWidth, target.height() / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        return new RectF(target.centerX() - width / 2f, target.centerY() - height / 2f,
                target.centerX() + width / 2f, target.centerY() + height / 2f);
    }

    private static double parseNumber(@Nullable String raw) {
        if (raw == null) return Double.NaN;
        boolean negative = false;
        boolean digits = false;
        boolean decimal = false;
        double value = 0d;
        double place = .1d;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '-' && !digits && !decimal) {
                negative = true;
            } else if ((character == '.' || character == ',') && !decimal) {
                decimal = true;
            } else if (character >= '0' && character <= '9') {
                digits = true;
                int digit = character - '0';
                if (decimal) {
                    value += digit * place;
                    place *= .1d;
                } else {
                    value = value * 10d + digit;
                }
            }
        }
        if (!digits) return Double.NaN;
        return negative ? -value : value;
    }

    private static RectF inset(RectF source, float amount) {
        RectF result = new RectF(source);
        result.inset(amount, amount);
        return result;
    }

    private static boolean isNavigation(HudElementType type) {
        return type.name().startsWith("NAV_");
    }

    private int warningColor(@NonNull HudElementConfig item, int fallback) {
        double value = data.numericValue(item);
        if (!Double.isFinite(value)) return fallback;
        if (isTirePressure(item.type)) {
            return value < item.options.optDouble("lowThreshold", 2d)
                    ? 0xFFFF453A : fallback;
        }
        switch (item.type) {
            case FUEL_LEVEL:
            case FUEL_RANGE:
                double red = item.options.optDouble("redThreshold", 10d);
                double yellow = item.options.optDouble("yellowThreshold", 20d);
                if (value <= red) return 0xFFFF453A;
                if (value <= yellow) return 0xFFFFCC00;
                return fallback;
            default:
                return fallback;
        }
    }

    private static boolean isTirePressure(HudElementType type) {
        return type == HudElementType.TIRE_PRESSURE_FRONT_LEFT
                || type == HudElementType.TIRE_PRESSURE_FRONT_RIGHT
                || type == HudElementType.TIRE_PRESSURE_REAR_LEFT
                || type == HudElementType.TIRE_PRESSURE_REAR_RIGHT;
    }

    @ColorInt
    private static int parseColor(@Nullable String primary, @Nullable String fallback,
                                  @ColorInt int defaultColor) {
        String value = primary == null || primary.trim().isEmpty() ? fallback : primary;
        if (value == null || value.trim().isEmpty()) return defaultColor;
        try { return Color.parseColor(value.trim()); }
        catch (IllegalArgumentException ignored) { return defaultColor; }
    }

    private static int optionColor(@NonNull HudElementConfig item, @NonNull String key,
                                   @ColorInt int fallback) {
        Object value = item.options.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        return parseColor(null, value == null ? "" : String.valueOf(value), fallback);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(clamp(alpha, 0, 255), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    @NonNull
    private static String digitsOnly(@NonNull String value) {
        boolean allDigits = !value.isEmpty();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return value;
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') result.append(character);
        }
        return result.toString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Geometry {
        final float scale;
        @NonNull final RectF content;
        @NonNull final RectF safeClip;
        final float cellWidth;
        final float cellHeight;

        Geometry(float scale, @NonNull RectF content, @NonNull RectF safeClip,
                 float cellWidth, float cellHeight) {
            this.scale = scale;
            this.content = content;
            this.safeClip = safeClip;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
        }
    }
}
