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
    private int snapPx = 20;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private int downWidth;
    private int downHeight;
    private LauncherPanelResizeMath.Corner resizeCorner =
            LauncherPanelResizeMath.Corner.NONE;

    public LauncherElementFrame(@NonNull Context context, @NonNull String elementId,
                                @NonNull String label, @NonNull GeometryListener listener) {
        super(context);
        this.elementId = elementId;
        this.listener = listener;

        setRadius(dp(24));
        setCardElevation(dp(5));
        setCardBackgroundColor(Color.argb(150, 18, 18, 24));
        setUseCompatPadding(false);
        setPreventCornerOverlap(true);

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
        editMode = enabled;
        this.snapPx = Math.max(1, snapPx);
        editBadge.setVisibility(enabled ? VISIBLE : GONE);
        for (ImageView handle : resizeHandles) {
            if (handle != null) handle.setVisibility(enabled ? VISIBLE : GONE);
        }
        setStrokeWidth(enabled ? dp(3) : 0);
        setStrokeColor(Color.rgb(55, 135, 245));
        setCardElevation(enabled ? dp(10) : dp(5));
        setClickable(enabled);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return editMode || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editMode) return super.onTouchEvent(event);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = lp.leftMargin;
                downY = lp.topMargin;
                downWidth = getWidth();
                downHeight = getHeight();
                resizeCorner = LauncherPanelResizeMath.cornerAt(
                        event.getX(), event.getY(), getWidth(), getHeight(), dp(64));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.round(event.getRawX() - downRawX);
                int dy = Math.round(event.getRawY() - downRawY);
                if (resizeCorner != LauncherPanelResizeMath.Corner.NONE) {
                    View parent = (View) getParent();
                    LauncherPanelResizeMath.Rect resized = LauncherPanelResizeMath.resize(
                            resizeCorner,
                            new LauncherPanelResizeMath.Rect(
                                    downX, downY, downX + downWidth, downY + downHeight),
                            dx, dy, parent.getWidth(), parent.getHeight(),
                            dp(160), dp(96), snapPx);
                    lp.leftMargin = resized.left;
                    lp.topMargin = resized.top;
                    lp.width = resized.width();
                    lp.height = resized.height();
                } else {
                    int maxX = Math.max(0, ((View) getParent()).getWidth() - getWidth());
                    int maxY = Math.max(0, ((View) getParent()).getHeight() - getHeight());
                    lp.leftMargin = Math.max(0, Math.min(snap(downX + dx), maxX));
                    lp.topMargin = Math.max(0, Math.min(snap(downY + dy), maxY));
                }
                setLayoutParams(lp);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                listener.onGeometryChanged(elementId, lp.leftMargin, lp.topMargin,
                        Math.max(1, lp.width), Math.max(1, lp.height));
                resizeCorner = LauncherPanelResizeMath.Corner.NONE;
                performClick();
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

    private int snap(int value) {
        return Math.round(value / (float) snapPx) * snapPx;
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
            default: return "Изменить размер панели";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
