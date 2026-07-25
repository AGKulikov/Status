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
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.GestureDetector;
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

import java.util.IdentityHashMap;
import java.util.Map;

import dezz.status.widget.Fonts;

/**
 * Draws one live launcher widget in screen coordinates without changing its native proportions.
 *
 * <p>The hidden panel hierarchy remains the data/action source. This view supplies a separate
 * viewport, padding, typography and action policy for each atomic widget. FIT is deliberately the
 * default; STRETCH exists only as an explicit compatibility choice.</p>
 */
public final class LauncherGlobalElementProxyView extends View {
    public interface Source {
        @Nullable View resolve();
    }

    public interface ConfigurationListener {
        void onConfigure();
    }

    @NonNull private final Source source;
    @NonNull private final ConfigurationListener configurationListener;
    @NonNull private final GestureDetector gestures;
    @NonNull private LauncherGlobalElementLayoutStore.Appearance appearance;
    @Nullable private View styledSource;
    private final Map<TextView, TextSnapshot> originalTexts = new IdentityHashMap<>();
    private final Map<ImageView, ColorFilter> originalImageFilters = new IdentityHashMap<>();
    private boolean longPressTriggered;
    private boolean sourceCancelled;

    public LauncherGlobalElementProxyView(
            @NonNull Context context,
            @NonNull Source source,
            @NonNull LauncherGlobalElementLayoutStore.Appearance appearance,
            @NonNull ConfigurationListener configurationListener) {
        super(context);
        this.source = source;
        this.configurationListener = configurationListener;
        this.appearance = appearance.copy();
        gestures = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(@NonNull MotionEvent event) {
                        return true;
                    }

                    @Override public void onLongPress(@NonNull MotionEvent event) {
                        longPressTriggered = true;
                        cancelSourceGesture();
                        LauncherGlobalElementProxyView.this.configurationListener.onConfigure();
                        performHapticFeedback(
                                android.view.HapticFeedbackConstants.LONG_PRESS);
                    }
                });
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        applySurface();
    }

    public void setAppearance(
            @NonNull LauncherGlobalElementLayoutStore.Appearance value) {
        restoreOriginalStyles();
        appearance = value.copy();
        styledSource = null;
        applySurface();
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

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        View value = sourceView();
        if (value == null || value.getWidth() <= 0 || value.getHeight() <= 0) return;
        ensureSourceAppearance(value);
        DrawTransform transform = transform(value);
        if (!transform.drawable()) return;
        int checkpoint = canvas.save();
        if (appearance.cornerRadiusPx > 0) {
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()),
                    appearance.cornerRadiusPx, appearance.cornerRadiusPx,
                    Path.Direction.CW);
            canvas.clipPath(clip);
        }
        canvas.translate(transform.offsetX, transform.offsetY);
        canvas.scale(transform.scaleX, transform.scaleY);
        value.draw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        View value = sourceView();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            longPressTriggered = false;
            sourceCancelled = false;
        }
        gestures.onTouchEvent(event);
        if (longPressTriggered) return true;

        LauncherGlobalElementLayoutStore.TapAction tapAction = appearance.tapAction;
        if (tapAction != LauncherGlobalElementLayoutStore.TapAction.INHERIT) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && tapAction == LauncherGlobalElementLayoutStore.TapAction.APP) {
                launchConfiguredApp();
                performClick();
            }
            return true;
        }
        if (value == null || value.getWidth() <= 0 || value.getHeight() <= 0
                || getWidth() <= 0 || getHeight() <= 0) return false;
        DrawTransform transform = transform(value);
        if (!transform.drawable()) return false;
        float mappedX = (event.getX() - transform.offsetX) / transform.scaleX;
        float mappedY = (event.getY() - transform.offsetY) / transform.scaleY;
        if (appearance.scaleMode == LauncherGlobalElementLayoutStore.ScaleMode.FIT
                && (mappedX < 0f || mappedX > value.getWidth()
                || mappedY < 0f || mappedY > value.getHeight())) {
            return true;
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
        if (!handled && event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClickableAncestor(value);
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void cancelSourceGesture() {
        if (sourceCancelled) return;
        sourceCancelled = true;
        View value = sourceView();
        if (value == null) return;
        MotionEvent cancel = MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try {
            value.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
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

        float sourceWidth = Math.max(1, source.getWidth());
        float sourceHeight = Math.max(1, source.getHeight());
        if (appearance.scaleMode == LauncherGlobalElementLayoutStore.ScaleMode.STRETCH) {
            return new DrawTransform(left, top,
                    viewportWidth / sourceWidth, viewportHeight / sourceHeight);
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
        return new DrawTransform(x, y, scale, scale);
    }

    private static float alignedOffset(float start, float available, float used, int alignment) {
        if (alignment == 0) return start;
        if (alignment == 2) return start + available - used;
        return start + (available - used) / 2f;
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
        GradientDrawable background = new GradientDrawable();
        Integer color = parseOptionalColor(appearance.backgroundColor);
        background.setColor(color == null ? Color.TRANSPARENT : color);
        background.setCornerRadius(Math.max(0, appearance.cornerRadiusPx));
        setBackground(background);
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
        static final DrawTransform EMPTY = new DrawTransform(0f, 0f, 0f, 0f);
        final float offsetX;
        final float offsetY;
        final float scaleX;
        final float scaleY;

        DrawTransform(float offsetX, float offsetY, float scaleX, float scaleY) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
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

        TextSnapshot(@NonNull TextView source) {
            textSizePx = source.getTextSize();
            textColor = source.getCurrentTextColor();
            typeface = source.getTypeface();
            gravity = source.getGravity();
        }

        void restore(@NonNull TextView target) {
            target.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            target.setTextColor(textColor);
            target.setTypeface(typeface);
            target.setGravity(gravity);
        }
    }
}
