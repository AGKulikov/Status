/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.launcher.NavigationDataRepository;

/**
 * Resolution-independent renderer shared byte-for-byte by the live editor and HUD Presentation.
 * All built-in arrows, lane marks and traffic lights are original Canvas vectors; no mHUD assets
 * or implementation code are bundled.
 */
public final class HudCanvasView extends View {
    public interface EditorListener {
        void onSelectionChanged(@Nullable HudElementConfig selected);
        void onGeometryChanged(@NonNull HudElementConfig item, boolean committed);
    }

    private static final float HANDLE_SIZE = 34f;
    @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    @NonNull private final Path path = new Path();
    @NonNull private HudPanelConfig config;
    @NonNull private final HudRuntimeData data;
    private final boolean editor;
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
    @Nullable private String loadedFontUri;
    @Nullable private Typeface loadedFont;

    public HudCanvasView(@NonNull Context context, @NonNull HudPanelConfig config,
                         @NonNull HudRuntimeData data, boolean editor,
                         @Nullable EditorListener editorListener) {
        super(context);
        this.config = config;
        this.data = data;
        this.editor = editor;
        this.editorListener = editorListener;
        // A black full-screen View would overwrite neighbouring planes of the composite display.
        setBackgroundColor(editor ? Color.BLACK : Color.TRANSPARENT);
        setFocusable(editor);
        setClickable(editor);
    }

    public void updateConfig(@NonNull HudPanelConfig next) {
        config = next;
        if (selectedId != null && find(selectedId) == null) selectedId = null;
        invalidate();
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

        int layer = canvas.saveLayerAlpha(geometry.safeClip,
                Math.round(255f * config.globalBrightness / 100f));
        boolean animated = false;
        for (HudElementConfig item : config.drawingOrder()) {
            if (!shouldDraw(item)) continue;
            RectF bounds = bounds(item, geometry);
            drawElement(canvas, item, bounds, geometry.scale);
            if ((item.type == HudElementType.TURN_SIGNAL_LEFT
                    || item.type == HudElementType.TURN_SIGNAL_RIGHT
                    || item.type == HudElementType.NAV_TRAFFIC_LIGHTS)
                    && item.options.optBoolean("animated",
                    item.options.optBoolean("arrowAnimation", false))) {
                animated = true;
            }
        }
        canvas.restoreToCount(layer);
        if (editor && config.showGrid) drawGrid(canvas, geometry);
        if (editor && selectedId != null) {
            HudElementConfig selected = find(selectedId);
            if (selected != null) drawSelection(canvas, bounds(selected, geometry));
        }
        canvas.restoreToCount(safety);
        if (animated || config.snowMode) postInvalidateDelayed(250L);
    }

    private boolean shouldDraw(HudElementConfig item) {
        if (!item.enabled) return false;
        AutomationState state = data.automation(item);
        if (state.present && !state.visible) return false;
        if (item.options.optBoolean("hideWhenInactive", false)) {
            if (isNavigation(item.type)) {
                NavigationDataRepository.Snapshot nav = data.navigation();
                if (nav == null || (!nav.routeActive && !nav.laneAvailable
                        && !nav.trafficAvailable)) return false;
            } else if (!data.active(item)) {
                return false;
            }
        }
        if ((item.type == HudElementType.NAV_SPEED_LIMIT)
                && item.options.optBoolean("routeOnly", false)) {
            NavigationDataRepository.Snapshot nav = data.navigation();
            if (nav == null || !nav.routeActive) return false;
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
            NavigationDataRepository.Snapshot nav = data.navigation();
            int threshold = item.options.optInt("laneThresholdMeters",
                    config.navigationDisplayThresholdMeters);
            if (nav != null && Double.isFinite(nav.laneDistanceMeters)
                    && threshold > 0 && nav.laneDistanceMeters > threshold) return false;
        }
        return true;
    }

    private void drawPanelBackground(Canvas canvas, Geometry geometry) {
        if ("TRANSPARENT".equals(config.backgroundMode) && !editor) {
            // Canvas.drawColor observes the hard clip established by onDraw.
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            return;
        }
        int color = "BLACK".equals(config.backgroundMode) ? Color.BLACK
                : "DIM".equals(config.backgroundMode) ? 0xDD101218 : 0xFF090B10;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(geometry.content, paint);
    }

    private void drawElement(Canvas canvas, HudElementConfig item, RectF bounds, float scale) {
        AutomationState automation = data.automation(item);
        int textColor = parseColor(automation.color,
                config.syncElementColors ? config.globalTextColor : item.textColor,
                Color.WHITE);
        textColor = warningColor(item, textColor);
        int unitColor = parseColor(null,
                config.syncElementColors ? config.globalUnitColor : item.unitColor,
                0xCCFFFFFF);
        int background = parseColor(automation.backgroundColor, item.backgroundColor,
                Color.TRANSPARENT);
        int alpha = Math.round(255f * item.brightness / 100f);
        if (background != Color.TRANSPARENT) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(background,
                    Math.round(Color.alpha(background) * alpha / 255f)));
            float radius = Math.min(bounds.width(), bounds.height()) * .12f;
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
        textColor = withAlpha(textColor, Math.round(Color.alpha(textColor) * alpha / 255f));
        unitColor = withAlpha(unitColor, Math.round(Color.alpha(unitColor) * alpha / 255f));

        switch (item.type) {
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
            case NAV_JAM_PROGRESS:
                drawProgress(canvas, item, bounds, textColor, scale);
                return;
            case NAV_ROUTE_GRAPHIC:
                drawRouteGraphic(canvas, bounds);
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

    private void drawManeuver(Canvas canvas, HudElementConfig item, RectF bounds, int color) {
        NavigationDataRepository.Snapshot nav = data.navigation();
        if (nav != null && nav.maneuverImage != null) {
            drawBitmap(canvas, nav.maneuverImage, bounds);
            return;
        }
        String hint = nav == null ? "" : (nav.maneuverTitle + " " + nav.maneuverText)
                .toLowerCase(Locale.ROOT);
        boolean right = hint.contains("направ") || hint.contains("right");
        boolean left = hint.contains("налев") || hint.contains("left");
        boolean uturn = hint.contains("развор") || hint.contains("u-turn");
        float stroke = Math.max(5f, Math.min(bounds.width(), bounds.height()) * .095f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float cx = bounds.centerX();
        float bottom = bounds.bottom - stroke;
        float top = bounds.top + stroke;
        path.reset();
        if (uturn) {
            path.moveTo(cx + bounds.width() * .22f, bottom);
            path.lineTo(cx + bounds.width() * .22f, bounds.centerY());
            path.cubicTo(cx + bounds.width() * .22f, top, cx - bounds.width() * .22f, top,
                    cx - bounds.width() * .22f, bounds.centerY());
            canvas.drawPath(path, paint);
            drawArrowHead(canvas, cx - bounds.width() * .22f, bounds.centerY(),
                    false, color, stroke);
        } else if (left || right) {
            float direction = right ? 1f : -1f;
            path.moveTo(cx, bottom);
            path.lineTo(cx, bounds.centerY());
            path.lineTo(cx + direction * bounds.width() * .28f, bounds.centerY());
            canvas.drawPath(path, paint);
            drawArrowHead(canvas, cx + direction * bounds.width() * .28f, bounds.centerY(),
                    right, color, stroke);
        } else {
            path.moveTo(cx, bottom);
            path.lineTo(cx, top + bounds.height() * .12f);
            canvas.drawPath(path, paint);
            drawUpArrowHead(canvas, cx, top, color, stroke);
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
        drawText(canvas, item, data.textFor(item), inset(text, scale * 5f), unitColor, scale);
    }

    private void drawLanes(Canvas canvas, HudElementConfig item, RectF bounds, int color) {
        NavigationDataRepository.Snapshot nav = data.navigation();
        if (nav != null && nav.lanesImage != null) {
            drawBitmap(canvas, nav.lanesImage, bounds);
            return;
        }
        String lanes = nav == null ? "" : nav.lanes;
        int count = lanes.isEmpty() ? 3 : Math.max(1,
                Math.min(8, lanes.split("[,;| ]+").length));
        float cell = bounds.width() / count;
        float stroke = Math.max(3f, Math.min(cell, bounds.height()) * .09f);
        for (int index = 0; index < count; index++) {
            float cx = bounds.left + cell * (index + .5f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
            canvas.drawLine(cx, bounds.bottom - stroke, cx,
                    bounds.top + bounds.height() * .24f, paint);
            drawUpArrowHead(canvas, cx, bounds.top + stroke, color, stroke);
        }
        if (nav != null && !"OFF".equals(item.options.optString(
                "laneDistancePosition", "BOTTOM")) && !nav.laneDistance.isEmpty()) {
            RectF label = new RectF(bounds.left, bounds.bottom - bounds.height() * .26f,
                    bounds.right, bounds.bottom);
            drawSimpleText(canvas, nav.laneDistance, label, color,
                    Math.max(10f, label.height() * .65f), Layout.Alignment.ALIGN_CENTER);
        }
    }

    private void drawSpeedLimit(Canvas canvas, HudElementConfig item, RectF bounds,
                                int color, float scale) {
        String speed = data.textFor(item).replaceAll("[^0-9]", "");
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
        NavigationDataRepository.Snapshot nav = data.navigation();
        List<NavigationDataRepository.TrafficLight> lights =
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
            NavigationDataRepository.TrafficLight light = lights.get(index);
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
        int active = trafficColor(state);
        float diameter = Math.min(bounds.width() * .55f, bounds.height() * .34f);
        float cx = bounds.centerX();
        float cy = bounds.top + bounds.height() * .32f;
        String lower = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if (lower.contains("red") || lower.contains("крас")) active = 0xFFFF3B30;
        else if (lower.contains("yellow") || lower.contains("жел")) active = 0xFFFFCC00;
        else if (lower.contains("green") || lower.contains("зел")) active = 0xFF34C759;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active);
        canvas.drawCircle(cx, cy, diameter * .45f, paint);
        String line = countdown == null || countdown.trim().isEmpty() ? "—" : countdown.trim();
        if (arrow != null && !arrow.trim().isEmpty()) line += " " + arrow.trim();
        RectF text = new RectF(bounds.left, bounds.top + bounds.height() * .56f,
                bounds.right, bounds.bottom);
        drawSimpleText(canvas, line, text, fallbackColor,
                Math.max(9f, Math.min(text.height() * .62f, text.width() * .28f)),
                Layout.Alignment.ALIGN_CENTER);
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
            NavigationDataRepository.Snapshot nav = data.navigation();
            progress = nav != null && nav.routeActive ? .55d : 0d;
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

    private void drawRouteGraphic(Canvas canvas, RectF bounds) {
        NavigationDataRepository.Snapshot nav = data.navigation();
        Bitmap bitmap = nav == null ? null
                : nav.rainbowImage != null ? nav.rainbowImage : nav.jamImage;
        if (bitmap != null) drawBitmap(canvas, bitmap, bounds);
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
        drawStyledText(canvas, value, inset(bounds, 4f * scale), color, size,
                alignment, item.wrapText, item.fontWeight);
    }

    private void drawStyledText(Canvas canvas, String value, RectF bounds, int color,
                                float size, Layout.Alignment alignment, boolean wrap,
                                int weight) {
        textPaint.setColor(color);
        textPaint.setTextSize(size);
        textPaint.setTypeface(typeface(weight));
        textPaint.setTextAlign(Paint.Align.LEFT);
        int width = Math.max(1, Math.round(bounds.width()));
        String text = value == null ? "" : value;
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
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == null) return true;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
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
                if (dragging != null && editorListener != null) {
                    editorListener.onGeometryChanged(dragging, true);
                }
                dragging = null;
                resizing = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
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
        for (HudElementConfig item : order) {
            if (item.enabled && bounds(item, geometry).contains(x, y)) return item;
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
        float fineX = (float) item.options.optDouble("fineX", 0d) * geometry.scale;
        float fineY = (float) item.options.optDouble("fineY", 0d) * geometry.scale;
        float left = geometry.content.left + item.x * geometry.cellWidth + fineX;
        float top = geometry.content.top + item.y * geometry.cellHeight + fineY;
        return new RectF(left, top, left + item.width * geometry.cellWidth,
                top + item.height * geometry.cellHeight);
    }

    private Geometry geometry() {
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
            return new Geometry(scale, content, new RectF(content),
                    content.width() / config.gridColumns,
                    content.height() / config.gridRows);
        }

        // Presentation coordinates are physical pixels, exactly as in mHUD 6.1. Never scale or
        // center this plane: doing so could make it overlap another panel in the virtual display.
        RectF content = new RectF(HudViewportPolicy.SAFE_LEFT, HudViewportPolicy.SAFE_TOP,
                HudViewportPolicy.SAFE_RIGHT, HudViewportPolicy.SAFE_BOTTOM);
        HudViewportPolicy.Bounds clipped = HudViewportPolicy.clipToSurface(
                Math.round(width), Math.round(height));
        RectF safeClip = new RectF(clipped.left, clipped.top, clipped.right, clipped.bottom);
        return new Geometry(1f, content, safeClip,
                HudViewportPolicy.SAFE_WIDTH / (float) config.gridColumns,
                HudViewportPolicy.SAFE_HEIGHT / (float) config.gridRows);
    }

    @NonNull
    private Typeface typeface(int itemWeight) {
        int weight = clamp(itemWeight > 0 ? itemWeight : config.globalFontWeight, 100, 900);
        String uri = config.customFontUri;
        if (!uri.isEmpty() && !uri.equals(loadedFontUri)) {
            loadedFontUri = uri;
            loadedFont = null;
            try (ParcelFileDescriptor descriptor = getContext().getContentResolver()
                    .openFileDescriptor(Uri.parse(uri), "r")) {
                if (descriptor != null) {
                    loadedFont = new Typeface.Builder(descriptor.getFileDescriptor())
                            .setWeight(weight).build();
                }
            } catch (Exception ignored) {
                loadedFont = null;
            }
        }
        Typeface base = loadedFont == null ? Typeface.DEFAULT : loadedFont;
        try { return Typeface.create(base, weight, false); }
        catch (RuntimeException ignored) { return Typeface.DEFAULT_BOLD; }
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

    private static RectF fitCenter(float sourceWidth, float sourceHeight, RectF target) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new RectF(target);
        float scale = Math.min(target.width() / sourceWidth, target.height() / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        return new RectF(target.centerX() - width / 2f, target.centerY() - height / 2f,
                target.centerX() + width / 2f, target.centerY() + height / 2f);
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

    private static int trafficColor(@Nullable String state) {
        String value = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if (value.contains("red") || value.contains("крас")) return 0xFFFF3B30;
        if (value.contains("yellow") || value.contains("жел")) return 0xFFFFCC00;
        if (value.contains("green") || value.contains("зел")) return 0xFF34C759;
        return 0xFF6B7280;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(clamp(alpha, 0, 255), Color.red(color),
                Color.green(color), Color.blue(color));
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
