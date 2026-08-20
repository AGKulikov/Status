/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import dezz.status.widget.Fonts;
import dezz.status.widget.MarqueeOutlineTextView;
import dezz.status.widget.OutlineTextView;
import dezz.status.widget.launcher.media.MediaPanelConfig;

/**
 * Draws one live launcher widget in screen coordinates without changing its native proportions.
 *
 * <p>The hidden panel hierarchy remains the data/action source. This view supplies a separate
 * viewport, padding, typography and action policy for each atomic widget. FIT is deliberately the
 * default for bitmap-like content; the media progress and volume controls are responsive and
 * always consume the complete independently resized frame.</p>
 */
public final class LauncherGlobalElementProxyView extends View {
    public interface Source {
        @Nullable View resolve();
    }

    @NonNull private final Source source;
    @NonNull private LauncherGlobalElementLayoutStore.Appearance appearance;
    @Nullable private View styledSource;
    @Nullable private View gestureSource;
    @Nullable private DrawTransform gestureTransform;
    private boolean ignoreGestureUntilUp;
    private final Map<TextView, TextSnapshot> originalTexts = new IdentityHashMap<>();
    private final Map<ImageView, ColorFilter> originalImageFilters = new IdentityHashMap<>();
    private final Map<TextView, MarqueeState> marqueeStates = new IdentityHashMap<>();
    private long lastVisualSignature = Long.MIN_VALUE;

    public LauncherGlobalElementProxyView(
            @NonNull Context context,
            @NonNull Source source,
            @NonNull LauncherGlobalElementLayoutStore.Appearance appearance) {
        super(context);
        this.source = source;
        this.appearance = appearance.copy();
        setClickable(true);
        // Settings are opened by tapping the frame while explicit layout mode is active.
        // Outside that mode a long press belongs to the live widget/application.
        setLongClickable(false);
        setFocusable(true);
        setWillNotDraw(false);
        applySurface();
    }

    public void setAppearance(
            @NonNull LauncherGlobalElementLayoutStore.Appearance value) {
        cancelForwardedGesture();
        restoreOriginalStyles();
        appearance = value.copy();
        styledSource = null;
        lastVisualSignature = Long.MIN_VALUE;
        marqueeStates.clear();
        applySurface();
        invalidate();
    }

    /**
     * Polls cheap source state without redrawing an unchanged tile. The old unconditional
     * 500-ms invalidation made static launcher icons visibly blink on the KX11 display.
     */
    public void refreshFromSource() {
        long signature = visualSignature(sourceView());
        if (signature == lastVisualSignature) return;
        lastVisualSignature = signature;
        invalidate();
    }

    @Nullable
    public View sourceView() {
        return source.resolve();
    }

    public boolean sourceIsShown() {
        View value = sourceView();
        return value != null && value.isShown() && value.getWidth() > 0 && value.getHeight() > 0;
    }

    /** Drops style/gesture ownership when a rebuilt panel reuses the same stable element ID. */
    public void onSourceRebound() {
        View current = sourceView();
        if (styledSource == null || styledSource == current) return;
        cancelForwardedGesture();
        restoreOriginalStyles();
        marqueeStates.clear();
        lastVisualSignature = Long.MIN_VALUE;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        View value = sourceView();
        if (value == null || value.getWidth() <= 0 || value.getHeight() <= 0) return;
        ensureSourceAppearance(value);
        int checkpoint = canvas.save();
        if (appearance.cornerRadiusPx > 0) {
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()),
                    appearance.cornerRadiusPx, appearance.cornerRadiusPx,
                    Path.Direction.CW);
            canvas.clipPath(clip);
        }
        if (isPlainText(value)) {
            drawTextFrame(canvas, (TextView) value);
        } else {
            DrawTransform transform = transform(value);
            if (transform.drawable()) {
                canvas.translate(transform.offsetX, transform.offsetY);
                canvas.scale(transform.scaleX, transform.scaleY);
                canvas.translate(-transform.sourceLeft, -transform.sourceTop);
                drawWithoutAutomaticSurface(value, canvas,
                        transform.scaleX, transform.scaleY);
            }
        }
        canvas.restoreToCount(checkpoint);
        lastVisualSignature = visualSignature(value);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        View actionSource = action == MotionEvent.ACTION_DOWN ? sourceView() : gestureSource;
        LauncherGlobalElementLayoutStore.TapAction tapAction = appearance.tapAction;
        // These two elements are controls, even if an older saved layout still contains a
        // custom tap action from before direct manipulation was introduced.
        if (actionSource != null && isResponsiveMediaControl(actionSource)) {
            tapAction = LauncherGlobalElementLayoutStore.TapAction.INHERIT;
        }
        if (tapAction != LauncherGlobalElementLayoutStore.TapAction.INHERIT) {
            cancelForwardedGesture();
            if (action == MotionEvent.ACTION_UP
                    && tapAction == LauncherGlobalElementLayoutStore.TapAction.APP) {
                launchConfiguredApp();
                performClick();
            }
            return true;
        }

        if (ignoreGestureUntilUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoreGestureUntilUp = false;
                finishForwardedGesture();
            }
            return true;
        }

        View value;
        DrawTransform transform;
        if (action == MotionEvent.ACTION_DOWN) {
            cancelForwardedGesture();
            value = sourceView();
            if (value == null || value.getWidth() <= 0 || value.getHeight() <= 0
                    || getWidth() <= 0 || getHeight() <= 0) return false;
            transform = transform(value);
            if (!transform.drawable()) return false;
        } else {
            value = gestureSource;
            transform = gestureTransform;
            if (value == null || transform == null) return true;
        }

        float mappedX = transform.sourceLeft
                + (event.getX() - transform.offsetX) / transform.scaleX;
        float mappedY = transform.sourceTop
                + (event.getY() - transform.offsetY) / transform.scaleY;
        if (action == MotionEvent.ACTION_DOWN
                && appearance.scaleMode == LauncherGlobalElementLayoutStore.ScaleMode.FIT
                && (mappedX < transform.sourceLeft
                || mappedX > transform.sourceRight
                || mappedY < transform.sourceTop
                || mappedY > transform.sourceBottom)) {
            ignoreGestureUntilUp = true;
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            gestureSource = value;
            gestureTransform = transform;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        }

        MotionEvent forwarded = MotionEvent.obtain(event);
        forwarded.setLocation(clamp(mappedX, 0f, value.getWidth()),
                clamp(mappedY, 0f, value.getHeight()));
        boolean handled;
        try {
            handled = value.dispatchTouchEvent(forwarded);
        } finally {
            forwarded.recycle();
        }
        if (!handled && action == MotionEvent.ACTION_UP) {
            performClickableAncestor(value);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishForwardedGesture();
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        dispose();
        super.onDetachedFromWindow();
    }

    /** Releases every source-side style and gesture owned by this proxy before it is discarded. */
    public void dispose() {
        cancelForwardedGesture();
        restoreOriginalStyles();
        marqueeStates.clear();
        lastVisualSignature = Long.MIN_VALUE;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void cancelForwardedGesture() {
        View value = gestureSource;
        if (value != null) {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL,
                    0f, 0f, 0);
            try {
                value.dispatchTouchEvent(cancel);
            } catch (RuntimeException ignored) {
            } finally {
                cancel.recycle();
            }
        }
        finishForwardedGesture();
        ignoreGestureUntilUp = false;
    }

    private void finishForwardedGesture() {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        gestureSource = null;
        gestureTransform = null;
    }

    private void launchConfiguredApp() {
        ComponentName component = ComponentName.unflattenFromString(
                appearance.appComponent);
        if (component == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getContext().startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(getContext(), "Не удалось открыть приложение",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    private DrawTransform transform(@NonNull View source) {
        float left = Math.min(getWidth(), Math.max(0, appearance.paddingLeftPx));
        float top = Math.min(getHeight(), Math.max(0, appearance.paddingTopPx));
        float right = Math.max(left, getWidth()
                - Math.max(0, appearance.paddingRightPx));
        float bottom = Math.max(top, getHeight()
                - Math.max(0, appearance.paddingBottomPx));
        float viewportWidth = Math.max(0f, right - left);
        float viewportHeight = Math.max(0f, bottom - top);
        if (viewportWidth <= 0f || viewportHeight <= 0f) return DrawTransform.EMPTY;

        RectF content = visualContentBounds(source);
        float sourceLeft = content.left;
        float sourceTop = content.top;
        float sourceRight = content.right;
        float sourceBottom = content.bottom;
        float sourceWidth = Math.max(1f, sourceRight - sourceLeft);
        float sourceHeight = Math.max(1f, sourceBottom - sourceTop);
        // Progress and volume are responsive controls rather than a bitmap-like panel cell.
        // Their outer frame may grow independently on either axis; the controls themselves keep
        // circles, glyphs and rounded ends proportional while using all of the new space.
        if (isResponsiveMediaControl(source)
                || appearance.scaleMode == LauncherGlobalElementLayoutStore.ScaleMode.STRETCH) {
            return new DrawTransform(left, top,
                    viewportWidth / sourceWidth, viewportHeight / sourceHeight,
                    sourceLeft, sourceTop, sourceRight, sourceBottom);
        }
        float widthScale = viewportWidth / sourceWidth;
        float heightScale = viewportHeight / sourceHeight;
        float scale = appearance.scaleMode == LauncherGlobalElementLayoutStore.ScaleMode.CROP
                ? Math.max(widthScale, heightScale) : Math.min(widthScale, heightScale);
        float drawnWidth = sourceWidth * scale;
        float drawnHeight = sourceHeight * scale;
        float x = alignedOffset(left, viewportWidth, drawnWidth,
                appearance.horizontalAlignment);
        float y = alignedOffset(top, viewportHeight, drawnHeight,
                appearance.verticalAlignment);
        return new DrawTransform(x, y, scale, scale,
                sourceLeft, sourceTop, sourceRight, sourceBottom);
    }

    private static float alignedOffset(float start, float available, float used, int alignment) {
        if (alignment == 0) return start;
        if (alignment == 2) return start + available - used;
        return start + (available - used) / 2f;
    }

    private static boolean isResponsiveMediaControl(@NonNull View source) {
        LauncherGlobalElementTag tag = LauncherGlobalElementTag.from(source);
        if (tag == null) return false;
        String progress = LauncherLayoutStore.MEDIA + "/" + MediaPanelConfig.PROGRESS;
        String volume = LauncherLayoutStore.MEDIA + "/" + MediaPanelConfig.VOLUME;
        return progress.equals(tag.id) || volume.equals(tag.id);
    }

    private static boolean isPlainText(@NonNull View value) {
        if (!(value instanceof TextView)) return false;
        TextView text = (TextView) value;
        for (Drawable drawable : text.getCompoundDrawables()) {
            if (drawable != null) return false;
        }
        for (Drawable drawable : text.getCompoundDrawablesRelative()) {
            if (drawable != null) return false;
        }
        return true;
    }

    /**
     * Reflows a text widget in the actual editor frame. Resizing therefore gives the text more
     * room without scaling its font, and no source-grid padding leaks into the free frame.
     */
    private void drawTextFrame(@NonNull Canvas canvas, @NonNull TextView source) {
        float left = Math.min(getWidth(), Math.max(0, appearance.paddingLeftPx));
        float top = Math.min(getHeight(), Math.max(0, appearance.paddingTopPx));
        float right = Math.max(left,
                getWidth() - Math.max(0, appearance.paddingRightPx));
        float bottom = Math.max(top,
                getHeight() - Math.max(0, appearance.paddingBottomPx));
        int viewportWidth = Math.max(0, Math.round(right - left));
        int viewportHeight = Math.max(0, Math.round(bottom - top));
        if (viewportWidth <= 0 || viewportHeight <= 0) return;

        CharSequence text = source instanceof MarqueeOutlineTextView
                ? ((MarqueeOutlineTextView) source).getMarqueeSourceText()
                : source.getText();
        if (text == null || text.length() == 0) return;
        TextPaint paint = new TextPaint(source.getPaint());
        TextSnapshot original = originalTexts.get(source);
        float configuredPx = appearance.textSizeSp > 0
                ? TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                appearance.textSizeSp, getResources().getDisplayMetrics()) : 0f;
        paint.setTextSize(configuredPx > 0f
                ? configuredPx
                : original == null ? source.getTextSize() : original.textSizePx);
        paint.setColor(source.getCurrentTextColor());
        paint.setAlpha(Math.max(0, Math.min(255,
                Math.round(Color.alpha(source.getCurrentTextColor()) * source.getAlpha()))));

        boolean singleLine = source.getMaxLines() == 1
                || source instanceof MarqueeOutlineTextView;
        float desiredWidth = singleLine ? Layout.getDesiredWidth(text, paint) : 0f;
        if (singleLine && desiredWidth > viewportWidth + .5f) {
            int layoutWidth = Math.max(1, (int) Math.ceil(desiredWidth + 2f));
            StaticLayout layout = textLayout(source, text, paint, layoutWidth, 1,
                    Layout.Alignment.ALIGN_NORMAL);
            float y = verticalTextOffset(source, top, viewportHeight, layout.getHeight());
            float gap = Math.max(24f, getResources().getDisplayMetrics().density * 48f);
            float loopWidth = Math.max(1f, desiredWidth + gap);
            long key = mix(mix(text.hashCode(), viewportWidth),
                    Float.floatToIntBits(paint.getTextSize()));
            MarqueeState state = marqueeState(source, key);
            long now = SystemClock.uptimeMillis();
            float speedPxPerSecond =
                    75f * getResources().getDisplayMetrics().density;
            float scroll = ((now - state.startedAtMs) * speedPxPerSecond / 1_000f)
                    % loopWidth;
            int checkpoint = canvas.save();
            canvas.clipRect(left, top, right, bottom);
            drawTextLayout(canvas, source, layout, paint, left - scroll, y, 1f);
            drawTextLayout(canvas, source, layout, paint,
                    left - scroll + loopWidth, y, 1f);
            canvas.restoreToCount(checkpoint);
            postInvalidateOnAnimation();
            return;
        }

        marqueeStates.remove(source);
        Layout.Alignment alignment = textAlignment(source.getGravity());
        StaticLayout layout = textLayout(source, text, paint, viewportWidth,
                singleLine ? 1 : Math.max(1, source.getMaxLines()), alignment);
        float y = verticalTextOffset(source, top, viewportHeight, layout.getHeight());
        int checkpoint = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        drawTextLayout(canvas, source, layout, paint, left, y, 1f);
        canvas.restoreToCount(checkpoint);
    }

    @NonNull
    private static StaticLayout textLayout(
            @NonNull TextView source,
            @NonNull CharSequence text,
            @NonNull TextPaint paint,
            int width,
            int maxLines,
            @NonNull Layout.Alignment alignment) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(
                        text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(source.getLineSpacingExtra(),
                        source.getLineSpacingMultiplier())
                .setMaxLines(Math.max(1, maxLines));
        TextUtils.TruncateAt ellipsize = source.getEllipsize();
        if (ellipsize != null && ellipsize != TextUtils.TruncateAt.MARQUEE) {
            builder.setEllipsize(ellipsize).setEllipsizedWidth(Math.max(1, width));
        }
        return builder.build();
    }

    private static float verticalTextOffset(
            @NonNull TextView source, float top, int availableHeight, int textHeight) {
        int gravity = source.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        float remaining = availableHeight - textHeight;
        if (gravity == Gravity.BOTTOM) return top + remaining;
        if (gravity == Gravity.CENTER_VERTICAL) return top + remaining / 2f;
        return top;
    }

    @NonNull
    private static Layout.Alignment textAlignment(int gravity) {
        int horizontal = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (horizontal == Gravity.CENTER_HORIZONTAL) return Layout.Alignment.ALIGN_CENTER;
        if (horizontal == Gravity.RIGHT || horizontal == Gravity.END) {
            return Layout.Alignment.ALIGN_OPPOSITE;
        }
        return Layout.Alignment.ALIGN_NORMAL;
    }

    private static void drawTextLayout(
            @NonNull Canvas canvas,
            @NonNull TextView source,
            @NonNull StaticLayout layout,
            @NonNull TextPaint paint,
            float x,
            float y,
            float sourceToScreenScale) {
        int checkpoint = canvas.save();
        canvas.translate(x, y);
        Paint.Style originalStyle = paint.getStyle();
        float originalStroke = paint.getStrokeWidth();
        int originalColor = paint.getColor();
        try {
            if (source instanceof OutlineTextView
                    && ((OutlineTextView) source).getOutlineWidth() > 0f) {
                OutlineTextView outlined = (OutlineTextView) source;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(outlined.getOutlineWidth()
                        / Math.max(.01f, Math.abs(sourceToScreenScale)));
                paint.setColor(outlined.getOutlineColor());
                layout.draw(canvas);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(source.getCurrentTextColor());
            layout.draw(canvas);
        } finally {
            paint.setStyle(originalStyle);
            paint.setStrokeWidth(originalStroke);
            paint.setColor(originalColor);
            canvas.restoreToCount(checkpoint);
        }
    }

    private void ensureSourceAppearance(@NonNull View value) {
        if (styledSource != value) {
            restoreOriginalStyles();
            styledSource = value;
            captureOriginalStyles(value);
            applyCustomStyles(value);
        }
    }

    private void captureOriginalStyles(@NonNull View value) {
        if (value instanceof TextView) {
            TextView text = (TextView) value;
            originalTexts.put(text, new TextSnapshot(text));
        }
        if (value instanceof ImageView) {
            ImageView image = (ImageView) value;
            originalImageFilters.put(image, image.getColorFilter());
        }
        if (!(value instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) value;
        for (int index = 0; index < group.getChildCount(); index++) {
            captureOriginalStyles(group.getChildAt(index));
        }
    }

    private void restoreOriginalStyles() {
        for (Map.Entry<TextView, TextSnapshot> entry : originalTexts.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        for (Map.Entry<ImageView, ColorFilter> entry : originalImageFilters.entrySet()) {
            entry.getKey().setColorFilter(entry.getValue());
        }
        originalTexts.clear();
        originalImageFilters.clear();
        styledSource = null;
    }

    private void applyCustomStyles(@NonNull View value) {
        if (value instanceof TextView) {
            TextView text = (TextView) value;
            TextSnapshot original = originalTexts.get(text);
            if (appearance.textSizeSp > 0) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp);
            } else if (original != null) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, original.textSizePx);
            }
            Integer textColor = parseOptionalColor(appearance.textColor);
            if (textColor != null) text.setTextColor(textColor);
            else if (original != null) text.setTextColor(original.textColor);
            if (!appearance.fontFamily.isEmpty()
                    || appearance.textBold || appearance.textItalic) {
                if (appearance.fontFamily.isEmpty() && original != null) {
                    int style = (appearance.textBold ? Typeface.BOLD : 0)
                            | (appearance.textItalic ? Typeface.ITALIC : 0);
                    text.setTypeface(Typeface.create(original.typeface, style));
                } else {
                    text.setTypeface(Fonts.resolve(getContext(), appearance.fontFamily,
                            appearance.textBold, appearance.textItalic));
                }
            } else if (original != null) {
                text.setTypeface(original.typeface);
            }
            int horizontal = appearance.horizontalAlignment;
            int vertical = appearance.verticalAlignment;
            if ((horizontal >= 0 || vertical >= 0) && original != null) {
                int originalHorizontal = original.gravity
                        & Gravity.HORIZONTAL_GRAVITY_MASK;
                int originalVertical = original.gravity
                        & Gravity.VERTICAL_GRAVITY_MASK;
                text.setGravity((horizontal < 0 ? originalHorizontal
                        : horizontal == 0 ? Gravity.START
                        : horizontal == 1 ? Gravity.CENTER_HORIZONTAL : Gravity.END)
                        | (vertical < 0 ? originalVertical
                        : vertical == 0 ? Gravity.TOP
                        : vertical == 1 ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM));
            } else if (original != null) {
                text.setGravity(original.gravity);
            }
            // Atomic HOME widgets start flush with their frame. Optional outer padding remains an
            // explicit per-widget setting in Appearance instead of a hidden source-view inset.
            text.setPadding(0, 0, 0, 0);
        }
        if (value instanceof ImageView) {
            ImageView image = (ImageView) value;
            Integer iconColor = parseOptionalColor(appearance.iconColor);
            if (iconColor != null) image.setColorFilter(iconColor);
            else if (originalImageFilters.containsKey(image)) {
                image.setColorFilter(originalImageFilters.get(image));
            }
        }
        if (!(value instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) value;
        for (int index = 0; index < group.getChildCount(); index++) {
            applyCustomStyles(group.getChildAt(index));
        }
    }

    private void applySurface() {
        setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Paints the live source without mutating it. In particular, never call setTextSize while a
     * resize gesture is in progress: that schedules a layout of the hidden source hierarchy and
     * was the reason both the text and editor handles jumped in the hardware recording.
     *
     * <p>Text is laid out with an inverse canvas scale, so its on-screen size stays fixed while a
     * larger frame gives it more usable width. Container and text backgrounds are deliberately
     * skipped; decorative surfaces are independent backdrop layers.</p>
     */
    private void drawWithoutAutomaticSurface(
            @NonNull View source,
            @NonNull Canvas canvas,
            float sourceToScreenScaleX,
            float sourceToScreenScaleY) {
        drawViewTree(source, canvas,
                Math.max(.01f, Math.abs(sourceToScreenScaleX)),
                Math.max(.01f, Math.abs(sourceToScreenScaleY)));
    }

    private void drawViewTree(
            @NonNull View value,
            @NonNull Canvas canvas,
            float sourceToScreenScaleX,
            float sourceToScreenScaleY) {
        if (value.getVisibility() != View.VISIBLE) return;
        if (value instanceof TextView && isPlainText(value)) {
            drawNestedText(canvas, (TextView) value,
                    sourceToScreenScaleX, sourceToScreenScaleY);
            return;
        }
        if (value instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) value;
            int groupCheckpoint = canvas.save();
            if (group.getClipChildren()) {
                canvas.clipRect(0, 0, group.getWidth(), group.getHeight());
            }
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE) continue;
                int checkpoint = canvas.save();
                canvas.translate(child.getLeft() - group.getScrollX(),
                        child.getTop() - group.getScrollY());
                drawViewTree(child, canvas,
                        sourceToScreenScaleX, sourceToScreenScaleY);
                canvas.restoreToCount(checkpoint);
            }
            canvas.restoreToCount(groupCheckpoint);
            return;
        }

        // Leaf visual content (dividers, climate scales and other custom drawings) keeps its
        // normal rendering. Automatic surfaces live on container/text/image nodes and were
        // already skipped above, so no attached source property has to be toggled here.
        if (value instanceof ImageView) {
            drawImageContent(canvas, (ImageView) value,
                    sourceToScreenScaleX, sourceToScreenScaleY);
            return;
        }
        value.draw(canvas);
    }

    private void drawNestedText(
            @NonNull Canvas canvas,
            @NonNull TextView source,
            float sourceToScreenScaleX,
            float sourceToScreenScaleY) {
        int viewportWidth = Math.max(0, source.getWidth());
        int viewportHeight = Math.max(0, source.getHeight());
        if (viewportWidth <= 0 || viewportHeight <= 0) return;

        CharSequence text = source instanceof MarqueeOutlineTextView
                ? ((MarqueeOutlineTextView) source).getMarqueeSourceText()
                : source.getText();
        if (text == null || text.length() == 0) return;
        TextPaint paint = new TextPaint(source.getPaint());
        TextSnapshot original = originalTexts.get(source);
        float configuredPx = appearance.textSizeSp > 0
                ? TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                appearance.textSizeSp, getResources().getDisplayMetrics()) : 0f;
        float desiredScreenPx = configuredPx > 0f
                ? configuredPx
                : original == null ? source.getTextSize() : original.textSizePx;
        paint.setTextSize(desiredScreenPx / sourceToScreenScaleY);
        // A responsive control can have different X/Y frame scales. Counter the outer X scale
        // in the glyph metrics so labels stay typographically proportional.
        paint.setTextScaleX(paint.getTextScaleX()
                * sourceToScreenScaleY / sourceToScreenScaleX);
        paint.setColor(source.getCurrentTextColor());
        paint.setAlpha(Math.max(0, Math.min(255,
                Math.round(Color.alpha(source.getCurrentTextColor()) * source.getAlpha()))));

        boolean singleLine = source.getMaxLines() == 1
                || source instanceof MarqueeOutlineTextView;
        float desiredWidth = singleLine ? Layout.getDesiredWidth(text, paint) : 0f;
        if (singleLine && desiredWidth > viewportWidth + .5f) {
            int layoutWidth = Math.max(1, (int) Math.ceil(desiredWidth + 2f));
            StaticLayout layout = textLayout(source, text, paint, layoutWidth, 1,
                    Layout.Alignment.ALIGN_NORMAL);
            float y = verticalTextOffset(source, 0f, viewportHeight, layout.getHeight());
            float gap = Math.max(24f / sourceToScreenScaleY,
                    getResources().getDisplayMetrics().density * 48f
                            / sourceToScreenScaleY);
            float loopWidth = Math.max(1f, desiredWidth + gap);
            long key = mix(mix(System.identityHashCode(source), text.hashCode()),
                    Float.floatToIntBits(paint.getTextSize()));
            MarqueeState state = marqueeState(source, key);
            long now = SystemClock.uptimeMillis();
            float speedInSourcePxPerSecond =
                    75f * getResources().getDisplayMetrics().density
                            / sourceToScreenScaleY;
            float scroll = ((now - state.startedAtMs)
                    * speedInSourcePxPerSecond / 1_000f) % loopWidth;
            int checkpoint = canvas.save();
            canvas.clipRect(0, 0, viewportWidth, viewportHeight);
            drawTextLayout(canvas, source, layout, paint,
                    -scroll, y, sourceToScreenScaleY);
            drawTextLayout(canvas, source, layout, paint,
                    -scroll + loopWidth, y, sourceToScreenScaleY);
            canvas.restoreToCount(checkpoint);
            postInvalidateOnAnimation();
            return;
        }

        Layout.Alignment alignment = textAlignment(source.getGravity());
        StaticLayout layout = textLayout(source, text, paint, viewportWidth,
                singleLine ? 1 : Math.max(1, source.getMaxLines()), alignment);
        float y = verticalTextOffset(source, 0f, viewportHeight, layout.getHeight());
        int checkpoint = canvas.save();
        canvas.clipRect(0, 0, viewportWidth, viewportHeight);
        drawTextLayout(canvas, source, layout, paint,
                0f, y, sourceToScreenScaleY);
        canvas.restoreToCount(checkpoint);
    }

    private static void drawImageContent(
            @NonNull Canvas canvas, @NonNull ImageView image,
            float sourceToScreenScaleX, float sourceToScreenScaleY) {
        Drawable drawable = image.getDrawable();
        if (drawable == null) return;
        int checkpoint = canvas.save();
        float uniformScale = Math.min(
                Math.max(.01f, Math.abs(sourceToScreenScaleX)),
                Math.max(.01f, Math.abs(sourceToScreenScaleY)));
        canvas.scale(uniformScale / Math.max(.01f, Math.abs(sourceToScreenScaleX)),
                uniformScale / Math.max(.01f, Math.abs(sourceToScreenScaleY)),
                image.getWidth() / 2f, image.getHeight() / 2f);
        RectF viewport = imageViewport(image);
        canvas.clipRect(viewport);
        clipMediaArtworkDrawable(canvas, image, drawable, viewport);
        canvas.translate(image.getPaddingLeft(), image.getPaddingTop());
        canvas.concat(image.getImageMatrix());
        drawable.draw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    /**
     * The live media panel is alpha-suppressed on HOME, so ImageView#clipToOutline is never part
     * of the pixels users see. Mirror its round outline in the proxy, but clip the transformed
     * drawable rather than the whole ImageView: FIT_CENTER letterboxing must not move the corner
     * arcs away from the actual cover. Canvas/Path only; no bitmap allocation or timer.
     */
    private static void clipMediaArtworkDrawable(
            @NonNull Canvas canvas,
            @NonNull ImageView image,
            @NonNull Drawable drawable,
            @NonNull RectF viewport) {
        if (!isMediaArtwork(image) || !image.getClipToOutline()
                || image.getOutlineProvider() == null) return;
        android.graphics.Outline outline = new android.graphics.Outline();
        image.getOutlineProvider().getOutline(image, outline);
        float sourceRadius = Math.max(0f, outline.getRadius());
        if (sourceRadius <= 0f) return;

        Rect bounds = drawable.getBounds();
        if (bounds.isEmpty()) return;
        RectF drawn = new RectF(bounds);
        image.getImageMatrix().mapRect(drawn);
        drawn.offset(image.getPaddingLeft(), image.getPaddingTop());
        if (!drawn.intersect(viewport) || drawn.isEmpty()) return;

        // sourceRadius and drawn are both in ImageView coordinates after mapRect. Scaling the
        // radius again by intrinsic bitmap size would turn (for example) a 24 px corner on a
        // 1000 px cover fitted to 100 px into an incorrect 2.4 px corner.
        float radius = Math.min(sourceRadius,
                Math.min(drawn.width(), drawn.height()) / 2f);
        Path clip = new Path();
        clip.addRoundRect(drawn, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
    }

    private static boolean isMediaArtwork(@NonNull ImageView image) {
        LauncherGlobalElementTag tag = LauncherGlobalElementTag.from(image);
        return tag != null && (LauncherLayoutStore.MEDIA + "/" + MediaPanelConfig.ARTWORK)
                .equals(tag.id);
    }

    @NonNull
    private static RectF imageViewport(@NonNull ImageView image) {
        return new RectF(image.getPaddingLeft(), image.getPaddingTop(),
                Math.max(image.getPaddingLeft(),
                        image.getWidth() - image.getPaddingRight()),
                Math.max(image.getPaddingTop(),
                        image.getHeight() - image.getPaddingBottom()));
    }

    /**
     * Resolves the union of actually rendered descendants in source coordinates. Legacy panel
     * cells commonly fill a complete grid row even when their content uses only a small area;
     * importing the cell bounds made that empty bottom/right space part of the free HOME frame.
     */
    @NonNull
    private static RectF visualContentBounds(@NonNull View source) {
        RectF resolved = new RectF();
        if (!appendVisualBounds(source, -source.getLeft(), -source.getTop(), resolved)) {
            resolved.set(0f, 0f, Math.max(1, source.getWidth()),
                    Math.max(1, source.getHeight()));
        }
        return resolved;
    }

    private static boolean appendVisualBounds(
            @NonNull View value, float parentX, float parentY, @NonNull RectF result) {
        if (value.getVisibility() != View.VISIBLE || value.getWidth() <= 0
                || value.getHeight() <= 0) return false;
        float left = parentX + value.getLeft();
        float top = parentY + value.getTop();
        if (value instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) value;
            boolean found = false;
            for (int index = 0; index < group.getChildCount(); index++) {
                found |= appendVisualBounds(group.getChildAt(index),
                        left - group.getScrollX(), top - group.getScrollY(), result);
            }
            if (found) return true;
        }
        RectF own = new RectF(left, top, left + value.getWidth(), top + value.getHeight());
        if (result.isEmpty()) result.set(own); else result.union(own);
        return true;
    }

    @NonNull
    private MarqueeState marqueeState(@NonNull TextView source, long key) {
        MarqueeState state = marqueeStates.get(source);
        if (state == null || state.key != key) {
            state = new MarqueeState(key, SystemClock.uptimeMillis());
            marqueeStates.put(source, state);
        }
        return state;
    }

    private static long visualSignature(@Nullable View value) {
        if (value == null) return 0L;
        long result = value.getClass().getName().hashCode();
        result = mix(result, System.identityHashCode(value));
        result = mix(result, value.getVisibility());
        result = mix(result, value.isShown() ? 1 : 0);
        result = mix(result, value.getWidth());
        result = mix(result, value.getHeight());
        result = mix(result, Float.floatToIntBits(value.getAlpha()));
        result = mix(result, value.getScrollX());
        result = mix(result, value.getScrollY());
        result = drawableSignature(result, value.getBackground());
        if (value instanceof TextView) {
            TextView text = (TextView) value;
            CharSequence displayed = text instanceof MarqueeOutlineTextView
                    ? ((MarqueeOutlineTextView) text).getMarqueeSourceText()
                    : text.getText();
            result = mix(result, displayed == null ? 0 : displayed.hashCode());
            result = mix(result, text.getCurrentTextColor());
            result = mix(result, text.getGravity());
            result = mix(result, text.getMaxLines());
            for (Drawable drawable : text.getCompoundDrawablesRelative()) {
                result = drawableSignature(result, drawable);
            }
        }
        if (value instanceof ImageView) {
            result = drawableSignature(result, ((ImageView) value).getDrawable());
        }
        if (value instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) value;
            result = mix(result, group.getChildCount());
            for (int index = 0; index < group.getChildCount(); index++) {
                result = mix(result, visualSignature(group.getChildAt(index)));
            }
        }
        return result;
    }

    private static long drawableSignature(long seed, @Nullable Drawable drawable) {
        if (drawable == null) return mix(seed, 0);
        long result = mix(seed, System.identityHashCode(drawable));
        result = mix(result, drawable.getLevel());
        result = mix(result, drawable.getAlpha());
        result = mix(result, Arrays.hashCode(drawable.getState()));
        return result;
    }

    private static long mix(long seed, long value) {
        return (seed ^ value) * 0x100000001b3L;
    }

    @Nullable
    private static Integer parseOptionalColor(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Color.parseColor(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void performClickableAncestor(@NonNull View source) {
        View current = source;
        while (!(current instanceof LauncherElementFrame)) {
            if (current.isClickable()) {
                current.performClick();
                return;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return;
            current = (View) parent;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class DrawTransform {
        static final DrawTransform EMPTY =
                new DrawTransform(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        final float offsetX;
        final float offsetY;
        final float scaleX;
        final float scaleY;
        final float sourceLeft;
        final float sourceTop;
        final float sourceRight;
        final float sourceBottom;

        DrawTransform(float offsetX, float offsetY, float scaleX, float scaleY,
                      float sourceLeft, float sourceTop,
                      float sourceRight, float sourceBottom) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.sourceLeft = sourceLeft;
            this.sourceTop = sourceTop;
            this.sourceRight = sourceRight;
            this.sourceBottom = sourceBottom;
        }

        boolean drawable() {
            return scaleX > 0f && scaleY > 0f;
        }
    }

    private static final class TextSnapshot {
        final float textSizePx;
        final int textColor;
        @Nullable final Typeface typeface;
        final int gravity;
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;

        TextSnapshot(@NonNull TextView source) {
            textSizePx = source.getTextSize();
            textColor = source.getCurrentTextColor();
            typeface = source.getTypeface();
            gravity = source.getGravity();
            paddingLeft = source.getPaddingLeft();
            paddingTop = source.getPaddingTop();
            paddingRight = source.getPaddingRight();
            paddingBottom = source.getPaddingBottom();
        }

        void restore(@NonNull TextView target) {
            target.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            target.setTextColor(textColor);
            target.setTypeface(typeface);
            target.setGravity(gravity);
            target.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        }
    }

    private static final class MarqueeState {
        final long key;
        final long startedAtMs;

        MarqueeState(long key, long startedAtMs) {
            this.key = key;
            this.startedAtMs = startedAtMs;
        }
    }
}
