/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

/** Allocation-light fixed grid used for both HOME and the direct-manipulation preview. */
final class MediaElementGridLayout extends ViewGroup {
    static final class ElementLayoutParams extends ViewGroup.LayoutParams {
        int column;
        int row;
        int columnSpan;
        int rowSpan;

        ElementLayoutParams(int column, int row, int columnSpan, int rowSpan) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }
    }

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int spacing;
    private int columns = MediaPanelConfig.DEFAULT_GRID_COLUMNS;
    private int rows = MediaPanelConfig.DEFAULT_GRID_ROWS;
    private boolean gridVisible;

    MediaElementGridLayout(@NonNull Context context) {
        super(context);
        setClipChildren(true);
        setWillNotDraw(false);
        gridPaint.setColor(Color.argb(52, 150, 195, 255));
        gridPaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
    }

    void setSpacing(int value) {
        spacing = Math.max(0, value);
        requestLayout();
        invalidate();
    }

    void setGridVisible(boolean visible) {
        gridVisible = visible;
        invalidate();
    }

    void setGridSize(int columns, int rows) {
        this.columns = Math.max(MediaPanelConfig.MIN_GRID_COLUMNS,
                Math.min(MediaPanelConfig.MAX_GRID_COLUMNS, columns));
        this.rows = Math.max(MediaPanelConfig.MIN_GRID_ROWS,
                Math.min(MediaPanelConfig.MAX_GRID_ROWS, rows));
        requestLayout();
        invalidate();
    }

    boolean moveToPixel(@NonNull View child, int left, int top) {
        ViewGroup.LayoutParams raw = child.getLayoutParams();
        if (!(raw instanceof ElementLayoutParams)) return false;
        ElementLayoutParams lp = (ElementLayoutParams) raw;
        int width = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int height = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        int column = MediaGridLayoutMath.startForPx(left - getPaddingLeft(), width, spacing,
                columns, lp.columnSpan);
        int row = MediaGridLayoutMath.startForPx(top - getPaddingTop(), height, spacing,
                rows, lp.rowSpan);
        if (column == lp.column && row == lp.row) return false;
        lp.column = column;
        lp.row = row;
        child.setLayoutParams(lp);
        return true;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int measuredWidth = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int measuredHeight = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
        int availableWidth = Math.max(0,
                measuredWidth - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(0,
                measuredHeight - getPaddingTop() - getPaddingBottom());
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            ElementLayoutParams lp = (ElementLayoutParams) child.getLayoutParams();
            normalize(lp);
            int width = MediaGridLayoutMath.spanPx(availableWidth, spacing,
                    columns, lp.column, lp.columnSpan);
            int height = MediaGridLayoutMath.spanPx(availableHeight, spacing,
                    rows, lp.row, lp.rowSpan);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int availableWidth = Math.max(0,
                right - left - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(0,
                bottom - top - getPaddingTop() - getPaddingBottom());
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            ElementLayoutParams lp = (ElementLayoutParams) child.getLayoutParams();
            normalize(lp);
            int childLeft = getPaddingLeft() + MediaGridLayoutMath.startPx(availableWidth,
                    spacing, columns, lp.column);
            int childTop = getPaddingTop() + MediaGridLayoutMath.startPx(availableHeight,
                    spacing, rows, lp.row);
            child.layout(childLeft, childTop, childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight());
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!gridVisible) return;
        int width = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int height = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        for (int column = 1; column < columns; column++) {
            int x = getPaddingLeft() + MediaGridLayoutMath.startPx(width, spacing,
                    columns, column) - spacing / 2;
            canvas.drawLine(x, getPaddingTop(), x, getHeight() - getPaddingBottom(), gridPaint);
        }
        for (int row = 1; row < rows; row++) {
            int y = getPaddingTop() + MediaGridLayoutMath.startPx(height, spacing,
                    rows, row) - spacing / 2;
            canvas.drawLine(getPaddingLeft(), y, getWidth() - getPaddingRight(), y, gridPaint);
        }
    }

    private void normalize(@NonNull ElementLayoutParams lp) {
        lp.columnSpan = MediaGridLayoutMath.clampSpan(lp.columnSpan,
                columns);
        lp.rowSpan = MediaGridLayoutMath.clampSpan(lp.rowSpan, rows);
        lp.column = MediaGridLayoutMath.clampStart(lp.column, lp.columnSpan,
                columns);
        lp.row = MediaGridLayoutMath.clampStart(lp.row, lp.rowSpan,
                rows);
    }

    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new ElementLayoutParams(0, 0, 1, 1);
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof ElementLayoutParams;
    }
}
