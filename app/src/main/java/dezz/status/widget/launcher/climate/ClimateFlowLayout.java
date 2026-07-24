/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

/** Small dependency-free wrapping layout for user-ordered climate controls of different sizes. */
final class ClimateFlowLayout extends ViewGroup {
    private int horizontalGap;
    private int verticalGap;

    ClimateFlowLayout(@NonNull Context context) {
        super(context);
        setClipChildren(false);
    }

    void setGaps(int horizontal, int vertical) {
        horizontalGap = Math.max(0, horizontal);
        verticalGap = Math.max(0, vertical);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthLimit = Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight());
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            widthLimit = Integer.MAX_VALUE / 4;
        }
        int lineWidth = 0;
        int lineHeight = 0;
        int usedWidth = 0;
        int usedHeight = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int maximumChildWidth = Math.max(1, widthLimit - lp.leftMargin - lp.rightMargin);
            if (child.getMeasuredWidth() > maximumChildWidth) {
                child.measure(MeasureSpec.makeMeasureSpec(maximumChildWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            int addition = lineWidth == 0 ? childWidth : horizontalGap + childWidth;
            if (lineWidth > 0 && lineWidth + addition > widthLimit) {
                usedWidth = Math.max(usedWidth, lineWidth);
                usedHeight += lineHeight + verticalGap;
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth += addition;
                lineHeight = Math.max(lineHeight, childHeight);
            }
        }
        usedWidth = Math.max(usedWidth, lineWidth);
        usedHeight += lineHeight;
        int desiredWidth = usedWidth + getPaddingLeft() + getPaddingRight();
        int desiredHeight = usedHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int widthLimit = Math.max(0, right - left - getPaddingLeft() - getPaddingRight());
        int x = 0;
        int y = getPaddingTop();
        int lineHeight = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int occupiedWidth = childWidth + lp.leftMargin + lp.rightMargin;
            int occupiedHeight = childHeight + lp.topMargin + lp.bottomMargin;
            int addition = x == 0 ? occupiedWidth : horizontalGap + occupiedWidth;
            if (x > 0 && x + addition > widthLimit) {
                x = 0;
                y += lineHeight + verticalGap;
                lineHeight = 0;
                addition = occupiedWidth;
            }
            if (x > 0) x += horizontalGap;
            int childLeft = getPaddingLeft() + x + lp.leftMargin;
            int childTop = y + lp.topMargin;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            x += occupiedWidth;
            lineHeight = Math.max(lineHeight, occupiedHeight);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams source) {
        return new MarginLayoutParams(source);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }
}
