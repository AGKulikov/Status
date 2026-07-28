/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.card.MaterialCardView;

import dezz.status.widget.R;

/** A HOME panel that can be moved and resized directly on the dashboard while edit mode is on. */
public final class LauncherElementFrame extends MaterialCardView {
    public interface GeometryListener {
        void onGeometryChanged(@NonNull String id, int x, int y, int width, int height);
    }

    private final String elementId;
    private final FrameLayout rootHost;
    private final FrameLayout contentHost;
    private final TextView editBadge;
    private final ImageView[] resizeHandles = new ImageView[4];
    private final GeometryListener listener;
    private boolean editMode;
    private boolean contentTouchBlocked;
    private boolean preserveAspectRatio;
    private boolean stayBehindSiblings;
    private int snapPx = 20;
    private int minimumWidthPx;
    private int minimumHeightPx;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private int downWidth;
    private int downHeight;
    private final int touchSlop;
    private boolean movedSinceDown;
    private LauncherPanelResizeMath.Corner resizeCorner =
            LauncherPanelResizeMath.Corner.NONE;

    public LauncherElementFrame(@NonNull Context context, @NonNull String elementId,
                                @NonNull String label, @NonNull GeometryListener listener) {
        super(context);
        this.elementId = elementId;
        this.listener = listener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // Atomic widgets are free rectangles. The editor handles may visually overlap on a very
        // small item, but they must never impose an invisible 160x96 minimum on saved geometry.
        minimumWidthPx = 1;
        minimumHeightPx = 1;

        // The frame is geometry/editor chrome only. Visual underlays are independent
        // LauncherBackdropView layers explicitly added by the user.
        setRadius(0);
        setCardElevation(0);
        setCardBackgroundColor(Color.TRANSPARENT);
        setUseCompatPadding(false);
        setPreventCornerOverlap(false);

        rootHost = new FrameLayout(context);
        super.addView(rootHost, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        contentHost = new FrameLayout(context);
        rootHost.addView(contentHost, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        editBadge = new TextView(context);
        editBadge.setText(label + "   ✥");
        editBadge.setTextColor(Color.WHITE);
        editBadge.setTextSize(13);
        editBadge.setGravity(Gravity.CENTER);
        editBadge.setPadding(dp(10), 0, dp(10), 0);
        editBadge.setBackgroundColor(Color.argb(210, 30, 110, 220));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rootHost.addView(editBadge, badgeParams);
        editBadge.setVisibility(GONE);

        addResizeHandle(LauncherPanelResizeMath.Corner.TOP_LEFT,
                Gravity.TOP | Gravity.START, 180f, 0);
        addResizeHandle(LauncherPanelResizeMath.Corner.TOP_RIGHT,
                Gravity.TOP | Gravity.END, 270f, 1);
        addResizeHandle(LauncherPanelResizeMath.Corner.BOTTOM_LEFT,
                Gravity.BOTTOM | Gravity.START, 90f, 2);
        addResizeHandle(LauncherPanelResizeMath.Corner.BOTTOM_RIGHT,
                Gravity.BOTTOM | Gravity.END, 0f, 3);
    }

    public void setContent(@NonNull View view) {
        // Editor chrome lives in rootHost, so replacing live panel content can never remove or
        // duplicate the four resize handles.
        contentHost.removeAllViews();
        contentHost.addView(view, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setEditMode(boolean enabled, int snapPx) {
        int normalizedSnap = Math.max(1, snapPx);
        boolean modeChanged = editMode != enabled;
        editMode = enabled;
        this.snapPx = normalizedSnap;
        if (!modeChanged) return;
        editBadge.setVisibility(enabled ? VISIBLE : GONE);
        for (ImageView handle : resizeHandles) {
            if (handle != null) handle.setVisibility(enabled ? VISIBLE : GONE);
        }
        // The four handles and the badge are sufficient editor chrome. Drawing a rectangle around
        // every item made those technical contours look like real launcher underlays.
        setStrokeWidth(0);
        setCardElevation(0);
        setClickable(enabled);
    }

    /** Prevents the invisible legacy source panel from receiving touches behind global proxies. */
    public void setContentTouchBlocked(boolean blocked) {
        contentTouchBlocked = blocked;
        if (blocked) setClickable(false);
    }

    /** Individual labels and icons may be much smaller than the old whole-panel minimum. */
    public void setMinimumGeometryPx(int width, int height) {
        minimumWidthPx = Math.max(1, width);
        minimumHeightPx = Math.max(1, height);
    }

    public void setPreserveAspectRatio(boolean preserve) {
        preserveAspectRatio = preserve;
    }

    /** Decorative layers must remain below all live widgets even while they are edited. */
    public void setStayBehindSiblings(boolean stayBehind) {
        stayBehindSiblings = stayBehind;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (contentTouchBlocked) return true;
        return editMode || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (contentTouchBlocked) return false;
        if (!editMode) return super.onTouchEvent(event);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!stayBehindSiblings) bringToFront();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = lp.leftMargin;
                downY = lp.topMargin;
                downWidth = getWidth();
                downHeight = getHeight();
                movedSinceDown = false;
                resizeCorner = LauncherPanelResizeMath.cornerAt(
                        event.getX(), event.getY(), getWidth(), getHeight(), dp(64));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.round(event.getRawX() - downRawX);
                int dy = Math.round(event.getRawY() - downRawY);
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    movedSinceDown = true;
                }
                // Follow the finger pixel-for-pixel. Grid snapping during every MOVE was the
                // source of the visible jumps in the hardware video; snap once on release.
                applyGeometry(lp, dx, dy, 1);
                setLayoutParams(lp);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP && movedSinceDown) {
                    int finalDx = Math.round(event.getRawX() - downRawX);
                    int finalDy = Math.round(event.getRawY() - downRawY);
                    applyGeometry(lp, finalDx, finalDy, snapPx);
                    setLayoutParams(lp);
                }
                listener.onGeometryChanged(elementId, lp.leftMargin, lp.topMargin,
                        Math.max(1, lp.width), Math.max(1, lp.height));
                resizeCorner = LauncherPanelResizeMath.Corner.NONE;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !movedSinceDown) {
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void applyGeometry(@NonNull FrameLayout.LayoutParams lp,
                               int dx, int dy, int gridSnapPx) {
        View parent = (View) getParent();
        if (resizeCorner != LauncherPanelResizeMath.Corner.NONE) {
            LauncherPanelResizeMath.Rect start =
                    new LauncherPanelResizeMath.Rect(
                            downX, downY, downX + downWidth, downY + downHeight);
            LauncherPanelResizeMath.Rect resized = preserveAspectRatio
                    ? LauncherPanelResizeMath.resizeKeepingAspect(
                            resizeCorner, start, dx, dy,
                            parent.getWidth(), parent.getHeight(),
                            minimumWidthPx, minimumHeightPx, gridSnapPx,
                            downWidth / (float) Math.max(1, downHeight))
                    : LauncherPanelResizeMath.resize(
                            resizeCorner, start, dx, dy,
                            parent.getWidth(), parent.getHeight(),
                            minimumWidthPx, minimumHeightPx, gridSnapPx);
            lp.leftMargin = resized.left;
            lp.topMargin = resized.top;
            lp.width = resized.width();
            lp.height = resized.height();
            return;
        }
        int maxX = Math.max(0, parent.getWidth() - downWidth);
        int maxY = Math.max(0, parent.getHeight() - downHeight);
        lp.leftMargin = Math.max(0,
                Math.min(snap(downX + dx, gridSnapPx), maxX));
        lp.topMargin = Math.max(0,
                Math.min(snap(downY + dy, gridSnapPx), maxY));
    }

    private static int snap(int value, int gridSnapPx) {
        int safeSnap = Math.max(1, gridSnapPx);
        return Math.round(value / (float) safeSnap) * safeSnap;
    }

    private void addResizeHandle(@NonNull LauncherPanelResizeMath.Corner corner,
                                 int gravity, float rotation, int index) {
        ImageView handle = new ImageView(getContext());
        handle.setImageResource(R.drawable.ic_resize_corner);
        handle.setRotation(rotation);
        handle.setPadding(dp(8), dp(8), dp(8), dp(8));
        handle.setBackground(resizeHandleBackground());
        handle.setContentDescription(resizeHandleDescription(corner));
        handle.setVisibility(GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(42), dp(42), gravity);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        rootHost.addView(handle, params);
        resizeHandles[index] = handle;
    }

    @NonNull
    private GradientDrawable resizeHandleBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(30, 110, 220));
        background.setStroke(dp(2), Color.WHITE);
        return background;
    }

    @NonNull
    private String resizeHandleDescription(@NonNull LauncherPanelResizeMath.Corner corner) {
        switch (corner) {
            case TOP_LEFT: return "Изменить размер за левый верхний угол";
            case TOP_RIGHT: return "Изменить размер за правый верхний угол";
            case BOTTOM_LEFT: return "Изменить размер за левый нижний угол";
            case BOTTOM_RIGHT: return "Изменить размер за правый нижний угол";
            default: return "Изменить размер блока";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
