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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.card.MaterialCardView;

/** A HOME panel that can be moved and resized directly on the dashboard while edit mode is on. */
public final class LauncherElementFrame extends MaterialCardView {
    public interface GeometryListener {
        void onGeometryChanged(@NonNull String id, int x, int y, int width, int height);
    }

    private final String elementId;
    private final FrameLayout contentHost;
    private final TextView editBadge;
    private final GeometryListener listener;
    private boolean editMode;
    private int snapPx = 20;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private int downWidth;
    private int downHeight;
    private boolean resizing;

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

        contentHost = new FrameLayout(context);
        super.addView(contentHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        editBadge = new TextView(context);
        editBadge.setText(label + "   ✥   ↘");
        editBadge.setTextColor(Color.WHITE);
        editBadge.setTextSize(13);
        editBadge.setGravity(Gravity.CENTER_VERTICAL);
        editBadge.setPadding(dp(10), 0, dp(10), 0);
        editBadge.setBackgroundColor(Color.argb(210, 30, 110, 220));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(36), Gravity.TOP | Gravity.START);
        contentHost.addView(editBadge, badgeParams);
        editBadge.setVisibility(GONE);
    }

    public void setContent(@NonNull View view) {
        contentHost.addView(view, 0, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setEditMode(boolean enabled, int snapPx) {
        editMode = enabled;
        this.snapPx = Math.max(1, snapPx);
        editBadge.setVisibility(enabled ? VISIBLE : GONE);
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
                int resizeZone = dp(72);
                resizing = event.getX() >= Math.max(0, getWidth() - resizeZone)
                        && event.getY() >= Math.max(0, getHeight() - resizeZone);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.round(event.getRawX() - downRawX);
                int dy = Math.round(event.getRawY() - downRawY);
                if (resizing) {
                    int parentWidth = ((View) getParent()).getWidth();
                    int parentHeight = ((View) getParent()).getHeight();
                    int maxWidth = Math.max(dp(160), parentWidth - lp.leftMargin);
                    int maxHeight = Math.max(dp(96), parentHeight - lp.topMargin);
                    lp.width = Math.min(maxWidth, snap(Math.max(dp(160),
                            Math.min(downWidth + dx, maxWidth))));
                    lp.height = Math.min(maxHeight, snap(Math.max(dp(96),
                            Math.min(downHeight + dy, maxHeight))));
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
