/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Small dependency-free flow layout: ordered media elements wrap when the panel narrows. */
final class MediaElementFlowLayout extends ViewGroup {
    private int spacing;

    MediaElementFlowLayout(@NonNull Context context) {
        super(context);
        setClipChildren(false);
    }

    void setSpacing(int value) {
        spacing = Math.max(0, value);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int rowWidth = 0;
        int rowHeight = 0;
        int usedHeight = getPaddingTop() + getPaddingBottom();
        int widest = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int addition = rowWidth == 0 ? childWidth : spacing + childWidth;
            if (rowWidth > 0 && rowWidth + addition > available) {
                widest = Math.max(widest, rowWidth);
                usedHeight += rowHeight + spacing;
                rowWidth = childWidth;
                rowHeight = childHeight;
            } else {
                rowWidth += addition;
                rowHeight = Math.max(rowHeight, childHeight);
            }
        }
        widest = Math.max(widest, rowWidth);
        usedHeight += rowHeight;
        int measuredWidth = resolveSize(widest + getPaddingLeft() + getPaddingRight(),
                widthMeasureSpec);
        int measuredHeight = resolveSize(usedHeight, heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, right - left - getPaddingLeft() - getPaddingRight());
        List<List<View>> rows = new ArrayList<>();
        List<Integer> rowWidths = new ArrayList<>();
        List<Integer> rowHeights = new ArrayList<>();
        ArrayList<View> row = new ArrayList<>();
        int rowWidth = 0;
        int rowHeight = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int addition = row.isEmpty() ? width : spacing + width;
            if (!row.isEmpty() && rowWidth + addition > available) {
                rows.add(row);
                rowWidths.add(rowWidth);
                rowHeights.add(rowHeight);
                row = new ArrayList<>();
                rowWidth = 0;
                rowHeight = 0;
                addition = width;
            }
            row.add(child);
            rowWidth += addition;
            rowHeight = Math.max(rowHeight, height);
        }
        if (!row.isEmpty()) {
            rows.add(row);
            rowWidths.add(rowWidth);
            rowHeights.add(rowHeight);
        }

        int totalHeight = 0;
        for (int height : rowHeights) totalHeight += height;
        if (rows.size() > 1) totalHeight += spacing * (rows.size() - 1);
        int availableHeight = Math.max(0,
                bottom - top - getPaddingTop() - getPaddingBottom());
        int y = getPaddingTop() + Math.max(0, (availableHeight - totalHeight) / 2);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            int height = rowHeights.get(rowIndex);
            int x = getPaddingLeft() + Math.max(0,
                    (available - rowWidths.get(rowIndex)) / 2);
            for (View child : rows.get(rowIndex)) {
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();
                int childTop = y + Math.max(0, (height - childHeight) / 2);
                child.layout(x, childTop, x + childWidth, childTop + childHeight);
                x += childWidth + spacing;
            }
            y += height + spacing;
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
