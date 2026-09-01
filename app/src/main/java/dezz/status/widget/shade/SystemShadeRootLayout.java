/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Full-screen transparent host whose closed touch region is limited to the top gesture strip. */
public final class SystemShadeRootLayout extends FrameLayout {
    public interface Listener {
        void onOpenStateChanged(boolean open);
    }

    @NonNull private SystemShadeConfig config;
    @Nullable private Listener listener;
    @Nullable private View panel;
    @Nullable private ValueAnimator animator;
    @Nullable private VelocityTracker velocityTracker;
    private final int touchSlop;
    private float revealPx;
    private float downY;
    private boolean initiallyOpen;
    private boolean open;
    private boolean dragging;
    private boolean outsideTap;
    private boolean suppressed;

    private final ViewTreeObserver.OnComputeInternalInsetsListener insetsListener = info -> {
        info.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        Region region = info.touchableRegion;
        region.setEmpty();
        if (suppressed || getWidth() <= 0 || getHeight() <= 0) return;
        if (open || dragging || revealPx > 0f) {
            region.set(new Rect(0, 0, getWidth(), getHeight()));
        } else {
            region.set(new Rect(0, 0, getWidth(),
                    Math.min(getHeight(), config.gestureHandleHeightPx)));
        }
    };

    public SystemShadeRootLayout(@NonNull Context context,
                                 @NonNull SystemShadeConfig config) {
        super(context);
        this.config = config.copy();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    public void setPanel(@NonNull View value, @NonNull SystemShadeConfig next) {
        cancelAnimation();
        config = next.copy();
        if (panel != null) removeView(panel);
        panel = value;
        addView(value, 0, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, config.panelHeightPx));
        setReveal(open ? config.panelHeightPx : 0f);
        requestTouchableRegion();
    }

    public boolean isOpen() { return open; }

    public void setSuppressed(boolean value) {
        if (suppressed == value) return;
        suppressed = value;
        if (suppressed) settle(false, false);
        requestTouchableRegion();
    }

    public void close(boolean animate) { settle(false, animate); }

    public void open(boolean animate) {
        if (!suppressed) settle(true, animate);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(insetsListener);
        requestTouchableRegion();
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) {
            getViewTreeObserver().removeOnComputeInternalInsetsListener(insetsListener);
        }
        recycleVelocityTracker();
        cancelAnimation();
        super.onDetachedFromWindow();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (suppressed) return false;
        float y = event.getY();
        if (!open) return true;
        // Inside the open panel, reserve only its top strip for the closing gesture. Controls,
        // sliders and shortcut tiles keep their normal touch dispatch everywhere else.
        return y <= config.gestureHandleHeightPx || y > config.panelHeightPx;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (suppressed) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation();
                downY = event.getRawY();
                initiallyOpen = open;
                dragging = false;
                outsideTap = open && event.getY() > config.panelHeightPx;
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                requestTouchableRegion();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float delta = event.getRawY() - downY;
                if (!dragging && Math.abs(delta) >= touchSlop) {
                    dragging = true;
                    outsideTap = false;
                    requestTouchableRegion();
                }
                if (dragging) {
                    setReveal(SystemShadeGesturePolicy.revealForDrag(
                            initiallyOpen, delta, config.panelHeightPx));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float velocity = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1_000);
                    velocity = velocityTracker.getYVelocity();
                }
                float travel = event.getRawY() - downY;
                boolean target = event.getActionMasked() != MotionEvent.ACTION_CANCEL
                        && (outsideTap ? false : SystemShadeGesturePolicy.settleOpen(
                        initiallyOpen, travel, velocity,
                        config.openThresholdPx, config.closeThresholdPx));
                recycleVelocityTracker();
                dragging = false;
                settle(target, true);
                return true;
            default:
                return true;
        }
    }

    @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (open && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            close(true);
            return true;
        }
        return super.dispatchKeyEventPreIme(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (open && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            close(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void settle(boolean targetOpen, boolean animate) {
        if (targetOpen && suppressed) targetOpen = false;
        float target = targetOpen ? config.panelHeightPx : 0f;
        if (!animate || Math.abs(target - revealPx) < 1f) {
            setReveal(target);
            finishState(targetOpen);
            return;
        }
        cancelAnimation();
        animator = ValueAnimator.ofFloat(revealPx, target);
        animator.setDuration(config.animationDurationMs);
        final boolean finalOpen = targetOpen;
        animator.addUpdateListener(value -> setReveal((Float) value.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animator != animation) return;
                animator = null;
                finishState(finalOpen);
            }
        });
        animator.start();
    }

    private void finishState(boolean value) {
        boolean changed = open != value;
        open = value;
        setReveal(open ? config.panelHeightPx : 0f);
        if (open) requestFocus();
        else clearFocus();
        requestTouchableRegion();
        if (changed && listener != null) listener.onOpenStateChanged(open);
    }

    private void setReveal(float value) {
        revealPx = Math.max(0f, Math.min(config.panelHeightPx, value));
        float fraction = revealPx / Math.max(1f, config.panelHeightPx);
        View current = panel;
        if (current != null) current.setTranslationY(revealPx - config.panelHeightPx);
        int alpha = Math.round(255f * config.scrimOpacityPercent / 100f * fraction);
        setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        requestTouchableRegion();
    }

    private void requestTouchableRegion() {
        requestLayout();
        invalidate();
    }

    private void cancelAnimation() {
        ValueAnimator current = animator;
        animator = null;
        if (current != null) current.cancel();
    }

    private void recycleVelocityTracker() {
        VelocityTracker current = velocityTracker;
        velocityTracker = null;
        if (current != null) current.recycle();
    }
}
