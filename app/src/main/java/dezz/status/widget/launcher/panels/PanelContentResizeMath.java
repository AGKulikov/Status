/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

/**
 * Android-free geometry used by {@link PanelContentEditOverlay}.
 *
 * <p>Each resize keeps the diagonally opposite corner fixed. Returning a complete rectangle lets
 * the panel model accept or reject the operation atomically, so collision handling remains owned
 * by the same persisted grid model as dragging.</p>
 */
public final class PanelContentResizeMath {
    public enum Corner {
        NONE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public static final class Result {
        public final int column;
        public final int row;
        public final int columnSpan;
        public final int rowSpan;

        Result(int column, int row, int columnSpan, int rowSpan) {
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }
    }

    private PanelContentResizeMath() {
    }

    /**
     * Finds a corner handle while preserving a central drag area even for a one-cell tile.
     */
    public static Corner hitCorner(float x, float y, float left, float top,
                                   float right, float bottom, float maximumHandleSize) {
        if (right <= left || bottom <= top || x < left || x > right || y < top || y > bottom) {
            return Corner.NONE;
        }
        float horizontalHandle = handleExtent(right - left, maximumHandleSize);
        float verticalHandle = handleExtent(bottom - top, maximumHandleSize);
        if (horizontalHandle <= 0f || verticalHandle <= 0f) return Corner.NONE;

        float leftDistance = x - left;
        float rightDistance = right - x;
        float topDistance = y - top;
        float bottomDistance = bottom - y;
        boolean nearLeft = leftDistance <= horizontalHandle;
        boolean nearRight = rightDistance <= horizontalHandle;
        boolean nearTop = topDistance <= verticalHandle;
        boolean nearBottom = bottomDistance <= verticalHandle;
        if ((!nearLeft && !nearRight) || (!nearTop && !nearBottom)) return Corner.NONE;

        boolean useLeft = nearLeft && (!nearRight || leftDistance <= rightDistance);
        boolean useTop = nearTop && (!nearBottom || topDistance <= bottomDistance);
        if (useLeft) return useTop ? Corner.TOP_LEFT : Corner.BOTTOM_LEFT;
        return useTop ? Corner.TOP_RIGHT : Corner.BOTTOM_RIGHT;
    }

    public static float handleExtent(float length, float maximumHandleSize) {
        if (length <= 0f || maximumHandleSize <= 0f) return 0f;
        return Math.min(maximumHandleSize, length / 3f);
    }

    /**
     * Resizes a cell rectangle from {@code corner}, clamped to the grid and to a one-cell minimum.
     */
    public static Result resize(Corner corner, int startColumn, int startRow,
                                int startColumnSpan, int startRowSpan,
                                int deltaColumn, int deltaRow, int columns, int rows) {
        int gridColumns = Math.max(1, columns);
        int gridRows = Math.max(1, rows);
        int left = clamp(startColumn, 0, gridColumns - 1);
        int top = clamp(startRow, 0, gridRows - 1);
        int right = clamp(startColumn + Math.max(1, startColumnSpan),
                left + 1, gridColumns);
        int bottom = clamp(startRow + Math.max(1, startRowSpan),
                top + 1, gridRows);

        Corner selected = corner == null ? Corner.NONE : corner;
        switch (selected) {
            case TOP_LEFT:
                left = clamp(left + deltaColumn, 0, right - 1);
                top = clamp(top + deltaRow, 0, bottom - 1);
                break;
            case TOP_RIGHT:
                right = clamp(right + deltaColumn, left + 1, gridColumns);
                top = clamp(top + deltaRow, 0, bottom - 1);
                break;
            case BOTTOM_LEFT:
                left = clamp(left + deltaColumn, 0, right - 1);
                bottom = clamp(bottom + deltaRow, top + 1, gridRows);
                break;
            case BOTTOM_RIGHT:
                right = clamp(right + deltaColumn, left + 1, gridColumns);
                bottom = clamp(bottom + deltaRow, top + 1, gridRows);
                break;
            case NONE:
            default:
                break;
        }
        return new Result(left, top, right - left, bottom - top);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
