/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

/**
 * Small exact-cell layout shared by editable HOME panel contents.
 *
 * <p>Unlike {@code GridLayout}, every cell is derived directly from this view's measured width
 * and height. The WYSIWYG editor therefore manipulates the same rectangles that HOME renders on
 * Android 9 vendor builds, without depending on weighted GridLayout implementation details.</p>
 */
public final class PanelGridLayout extends ViewGroup {
    public static final class LayoutParams extends MarginLayoutParams {
        public int column;
        public int row;
        public int columnSpan;
        public int rowSpan;

        public LayoutParams(int column, int row, int columnSpan, int rowSpan) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        public LayoutParams(@NonNull ViewGroup.LayoutParams source) {
            super(source);
            column = 0;
            row = 0;
            columnSpan = 1;
            rowSpan = 1;
        }
    }

    private int columns = 1;
    private int rows = 1;
    private int gapPx;

    public PanelGridLayout(@NonNull Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setGridSize(int columns, int rows) {
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        requestLayout();
    }

    public void setCellGapPx(int gapPx) {
        this.gapPx = Math.max(0, gapPx);
        requestLayout();
    }

    public void updatePlacement(@NonNull String elementId, int column, int row,
                                int columnSpan, int rowSpan) {
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (!elementId.equals(child.getTag())) continue;
            LayoutParams params = asCellParams(child.getLayoutParams());
            params.column = column;
            params.row = row;
            params.columnSpan = columnSpan;
            params.rowSpan = rowSpan;
            child.setLayoutParams(params);
            return;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);
        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = normalized(asCellParams(child.getLayoutParams()));
            int childWidth = cellEnd(params.column + params.columnSpan, contentWidth, columns)
                    - cellStart(params.column, contentWidth, columns)
                    - params.leftMargin - params.rightMargin - gapPx;
            int childHeight = cellEnd(params.row + params.rowSpan, contentHeight, rows)
                    - cellStart(params.row, contentHeight, rows)
                    - params.topMargin - params.bottomMargin - gapPx;
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, childWidth),
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, childHeight),
                            MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int contentWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        int halfGap = gapPx / 2;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = normalized(asCellParams(child.getLayoutParams()));
            int childLeft = getPaddingLeft()
                    + cellStart(params.column, contentWidth, columns)
                    + params.leftMargin + halfGap;
            int childTop = getPaddingTop()
                    + cellStart(params.row, contentHeight, rows)
                    + params.topMargin + halfGap;
            child.layout(childLeft, childTop,
                    childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight());
        }
    }

    @Override
    protected boolean checkLayoutParams(@NonNull ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(0, 0, 1, 1);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(@NonNull ViewGroup.LayoutParams source) {
        return new LayoutParams(source);
    }

    @NonNull
    private LayoutParams asCellParams(@NonNull ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams
                ? (LayoutParams) params : new LayoutParams(params);
    }

    @NonNull
    private LayoutParams normalized(@NonNull LayoutParams params) {
        params.columnSpan = clamp(params.columnSpan, 1, columns);
        params.rowSpan = clamp(params.rowSpan, 1, rows);
        params.column = clamp(params.column, 0, columns - params.columnSpan);
        params.row = clamp(params.row, 0, rows - params.rowSpan);
        return params;
    }

    private static int cellStart(int cell, int pixels, int count) {
        return Math.round(cell * pixels / (float) count);
    }

    private static int cellEnd(int cell, int pixels, int count) {
        return Math.round(cell * pixels / (float) count);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
