/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Single-line text view with seamless one-direction scrolling for overflowing strings.
 * <p>
 * Replaces Android's built-in {@code ellipsize="marquee"} which has two visual quirks the users
 * complained about on car head units:
 * <ul>
 *   <li>The text bounces back to the start with a brief pause every cycle — when the overflow
 *       is small, this reads as a "twitch" rather than smooth motion.</li>
 *   <li>It only animates while the view {@code isSelected()}, with subtly inconsistent
 *       timings across OEM ROMs.</li>
 * </ul>
 * Our approach: when the natural text width exceeds the available view width, render the
 * string twice with a separator in between and scroll {@code scrollX} continuously from
 * {@code 0} to {@code textWidth + separatorWidth}, wrapping back to {@code 0} on each loop.
 * Because the second copy is already on screen by the time the first scrolls off the left,
 * the wrap point is invisible — motion is uniform and one-directional. Short strings that
 * fit are shown statically; no animation kicks in.
 */
public class MarqueeOutlineTextView extends OutlineTextView {
    /** Separator between repetitions of the scrolling text. Bullet + flanking spaces — wide
     *  enough to read as a pause, and the dot gives an explicit "end of one cycle" marker. */
    private static final String SEPARATOR = "   •   ";

    /** Time-based speed keeps motion identical on 30/60/90 Hz displays. */
    private static final float DEFAULT_SPEED_PX_PER_SECOND = 75f;
    /** Do not jump after the UI thread was briefly blocked. */
    private static final long MAX_FRAME_DELTA_NANOS = 50_000_000L;

    @Nullable
    private CharSequence sourceText;
    private float scrollPx = 0f;
    private float loopWidthPx = 0f;
    private boolean scrolling = false;
    private boolean attached = false;
    private boolean marqueeEnabled = true;
    private float speedPxPerSecond = DEFAULT_SPEED_PX_PER_SECOND;
    private boolean frameScheduled;
    private long lastFrameTimeNanos;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (!scrolling || !attached) return;
            if (lastFrameTimeNanos != 0L) {
                long elapsed = Math.max(0L, Math.min(MAX_FRAME_DELTA_NANOS,
                        frameTimeNanos - lastFrameTimeNanos));
                scrollPx += speedPxPerSecond * elapsed / 1_000_000_000f;
            }
            lastFrameTimeNanos = frameTimeNanos;
            if (loopWidthPx > 0f && scrollPx >= loopWidthPx) {
                // Wrap: the second copy of the text is now at the position the first occupied,
                // so resetting scrollX is visually invisible.
                scrollPx %= loopWidthPx;
            }
            // Draw with a floating-point canvas translation. setScrollX(int) quantizes motion
            // into alternating 2/3 px steps on 30 Hz head units and also trips OEM parent-layout
            // animation hooks, which made neighbouring Settings/app icons twitch.
            postInvalidateOnAnimation();
            scheduleFrame();
        }
    };

    private void scheduleFrame() {
        if (frameScheduled || !scrolling || !attached) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void cancelFrame() {
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
        frameScheduled = false;
        lastFrameTimeNanos = 0L;
    }

    @Override
    public void scrollTo(int x, int y) {
        // {@link TextView#onPreDraw} unconditionally calls {@link TextView#bringTextIntoView}
        // on every frame for non-editable text views. With LEFT/START gravity (our default)
        // that routine resets {@code scrollX} to 0 — fighting our marquee tick and producing
        // periodic visual jumps back to the start of the text. While the marquee is actively
        // scrolling, keep the View's integer scroll at zero. onDraw applies our sub-pixel offset.
        if (scrolling) return;
        super.scrollTo(x, y);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas) {
        if (!scrolling) {
            super.onDraw(canvas);
            return;
        }
        Layout layout = getLayout();
        if (layout == null) return;

        // Do not call TextView.onDraw while moving. It couples clipping to the View's integer
        // scrollX and some Android 9 vendor implementations consequently invalidate/relayout the
        // parent. Draw the already-shaped TextView Layout directly inside one fixed viewport.
        // Only this canvas receives a floating-point translation; sibling views and their
        // geometry stay completely untouched.
        float contentLeft = getCompoundPaddingLeft();
        float contentTop = getCompoundPaddingTop();
        float contentRight = getWidth() - getCompoundPaddingRight();
        float contentBottom = getHeight() - getCompoundPaddingBottom();
        if (contentRight <= contentLeft || contentBottom <= contentTop) return;
        float layoutTop = contentTop;
        int verticalGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        float remaining = Math.max(0f, contentBottom - contentTop - layout.getHeight());
        if (verticalGravity == Gravity.BOTTOM) {
            layoutTop += remaining;
        } else if (verticalGravity == Gravity.CENTER_VERTICAL) {
            layoutTop += remaining / 2f;
        }

        int restore = canvas.save();
        canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom);
        canvas.translate(contentLeft - scrollPx, layoutTop);
        android.text.TextPaint paint = getPaint();
        Paint.Style originalStyle = paint.getStyle();
        float originalStrokeWidth = paint.getStrokeWidth();
        int originalColor = paint.getColor();
        try {
            if (getOutlineWidth() > 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(getOutlineWidth());
                paint.setColor(getOutlineColor());
                layout.draw(canvas);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getCurrentTextColor());
            layout.draw(canvas);
        } finally {
            paint.setStyle(originalStyle);
            paint.setStrokeWidth(originalStrokeWidth);
            paint.setColor(originalColor);
            canvas.restoreToCount(restore);
        }
    }

    public MarqueeOutlineTextView(@NonNull Context context) {
        super(context);
        init();
    }

    public MarqueeOutlineTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeOutlineTextView(@NonNull Context context, @Nullable AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setHorizontallyScrolling(true);
        // We do our own overflow handling — ellipsize would clip the second copy.
        setEllipsize(null);
    }

    /**
     * Set the user-visible text. If it fits within the current available width, the text is
     * shown statically. If it overflows, the view duplicates the text with a separator and
     * starts the continuous scroll loop.
     */
    public void setMarqueeText(@Nullable CharSequence text) {
        CharSequence next = text == null ? "" : text;
        // Skip re-evaluation when the text is unchanged — otherwise every PlaybackState callback
        // (which fires on play/pause/seek/buffer events with the same subtitle) would reset the
        // scroll offset to zero, making the marquee restart mid-track every time the user seeks.
        if (TextUtils.equals(next, sourceText)) return;
        sourceText = next;
        evaluateAndUpdate();
    }

    /**
     * Enable / disable the marquee scroll behavior. When disabled, overflowing text is rendered
     * statically up to {@link #setMaxWidth(int) maxWidth} and cut off with an end ellipsis;
     * when enabled (the default), overflow triggers the continuous scroll loop.
     */
    public void setMarqueeEnabled(boolean enabled) {
        if (marqueeEnabled == enabled) return;
        marqueeEnabled = enabled;
        evaluateAndUpdate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Available width changed → re-decide static vs. scrolling. The first call here also
        // covers the bootstrap case where setMarqueeText() ran before measurement.
        evaluateAndUpdate();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        evaluateAndUpdate();
    }

    @Override
    public void setTypeface(@Nullable Typeface tf) {
        super.setTypeface(tf);
        evaluateAndUpdate();
    }

    @Override
    public void setMaxWidth(int maxPixels) {
        super.setMaxWidth(maxPixels);
        evaluateAndUpdate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        cancelFrame();
        super.onDetachedFromWindow();
    }

    private void evaluateAndUpdate() {
        CharSequence text = sourceText == null ? "" : sourceText;

        int maxWidthPx = getMaxWidth();
        boolean hasMaxWidth = maxWidthPx > 0 && maxWidthPx < Integer.MAX_VALUE;
        int laidOutWidth = getWidth();
        int viewportWidth = laidOutWidth > 0
                ? (hasMaxWidth ? Math.min(laidOutWidth, maxWidthPx) : laidOutWidth)
                : maxWidthPx;
        boolean hasViewport = viewportWidth > 0 && viewportWidth < Integer.MAX_VALUE;
        float contentWidth = getPaint().measureText(text, 0, text.length());
        int paddings = getPaddingLeft() + getPaddingRight();
        float naturalTotalWidth = contentWidth + paddings;
        boolean overflowing = hasViewport && naturalTotalWidth > viewportWidth + 0.5f;

        Mode desiredMode = overflowing
                ? (marqueeEnabled ? Mode.MARQUEE : Mode.ELLIPSIZE)
                : Mode.FITS;

        // Idempotent fast path: if the desired mode and the rendered TextView text already
        // match what they would become below, do nothing. This avoids resetting scrollPx and
        // re-running super.setText on every onSizeChanged / onMeasure cycle when nothing has
        // actually changed — which previously made the marquee snap visually mid-scroll.
        CharSequence desiredRenderedText = (desiredMode == Mode.MARQUEE)
                ? TextUtils.concat(text, SEPARATOR, text)
                : text;
        if (desiredMode == currentMode && TextUtils.equals(getText(), desiredRenderedText)) {
            // Already in the right state. If we're supposed to be scrolling and the tick
            // happened to be removed (e.g. by setMarqueeEnabled re-entrancy), re-arm it.
            scheduleFrame();
            return;
        }

        // Real transition — only here do we reset scroll state.
        cancelFrame();
        scrolling = false;
        scrollPx = 0f;
        setScrollX(0);
        currentMode = desiredMode;

        if (desiredMode == Mode.MARQUEE) {
            // Overflow + marquee enabled: render "text + separator + text" so the wrap point
            // is hidden by the already-visible second copy. {@link #onMeasure} clamps the
            // measured width to {@code maxWidth} in this state — that's the only reliable
            // way to cap it, because {@code setHorizontallyScrolling(true)} makes the
            // underlying TextView report the full natural text width from {@code onMeasure}
            // and silently ignore {@code setMaxWidth}.
            setEllipsize(null);
            // Re-enable horizontal scrolling — the ellipsize branch may have turned it off on
            // a previous evaluate, and without it setScrollX is silently clamped to 0 and the
            // text just slides off the right edge instead of wrapping to the second copy.
            setHorizontallyScrolling(true);
            // Compute the exact X position of the first glyph of the second copy in the shaped
            // combined string. {@code Paint.getRunAdvance} with the whole string as the
            // shaping context honours kerning across the [last separator char, first text char]
            // boundary — using {@code measureText(combined, 0, prefixLen)} instead would treat
            // the prefix as its own shaping context and miss that boundary kerning, leaving
            // the marquee wrap off by 1–3 px each loop (visible as a small periodic jump).
            int combinedLen = desiredRenderedText.length();
            int prefixLen = text.length() + SEPARATOR.length();
            loopWidthPx = getPaint().getRunAdvance(desiredRenderedText, 0, combinedLen,
                    0, combinedLen, false, prefixLen);
            super.setText(desiredRenderedText);
            scrolling = true;
            requestLayout();
            scheduleFrame();
        } else if (desiredMode == Mode.ELLIPSIZE) {
            // Overflow + marquee disabled: static render, cap at maxWidth with end ellipsis.
            // setHorizontallyScrolling(false) plus ellipsize=END lets the TextView handle the
            // cutoff itself; onMeasure still clamps the measured width because the original
            // setHorizontallyScrolling(true) from init() would otherwise report the natural
            // width.
            setHorizontallyScrolling(false);
            setEllipsize(android.text.TextUtils.TruncateAt.END);
            super.setText(desiredRenderedText);
            requestLayout();
        } else {
            // Fits — single render, no animation, no ellipsis needed, view grows naturally.
            setEllipsize(null);
            setHorizontallyScrolling(true);
            super.setText(desiredRenderedText);
            requestLayout();
        }
    }

    private enum Mode { UNSET, FITS, ELLIPSIZE, MARQUEE }
    private Mode currentMode = Mode.UNSET;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // HOME media cells pass an exact grid-cell width. Respect it so a moving glyph never
        // changes the geometry of neighbouring Settings/app icons.
        if (android.view.View.MeasureSpec.getMode(widthMeasureSpec)
                == android.view.View.MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        // Re-measure under UNSPECIFIED so {@code setHorizontallyScrolling(true)} reports the
        // full natural text width without being squeezed by a competing-sibling AT_MOST cap
        // (overlay's LinearLayout horizontal with multiple bricks gives each child the
        // remaining-space cap, which would clip long media subtitles before our own logic
        // ever gets to choose between static render and scrolling).
        int unspecified = android.view.View.MeasureSpec.makeMeasureSpec(
                0, android.view.View.MeasureSpec.UNSPECIFIED);
        super.onMeasure(unspecified, heightMeasureSpec);
        int natural = getMeasuredWidth();
        int maxWidth = getMaxWidth();
        if (maxWidth > 0 && maxWidth < Integer.MAX_VALUE && natural > maxWidth) {
            natural = maxWidth;
        }
        setMeasuredDimension(natural, getMeasuredHeight());
    }
}
