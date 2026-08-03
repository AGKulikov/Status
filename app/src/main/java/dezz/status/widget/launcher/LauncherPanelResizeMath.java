/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/**
 * Pure geometry for resizing a HOME panel from any corner.
 *
 * <p>The dragged corner follows a snapped gesture delta while the diagonally opposite corner
 * remains fixed. Snapping the delta rather than the stored edge is important for layouts created
 * with a previous grid size: the panel never jumps on the first MOVE event.</p>
 */
final class LauncherPanelResizeMath {
    enum Corner {
        NONE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    static final class Rect {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }
    }

    private LauncherPanelResizeMath() {
    }

    /**
     * Returns the closest corner when the pointer is inside both an horizontal and a vertical
     * handle zone. Choosing the closest edge makes small panels unambiguous even when two handle
     * zones overlap.
     */
    static Corner cornerAt(float x, float y, int width, int height, int handleSize) {
        if (width <= 0 || height <= 0 || handleSize <= 0) return Corner.NONE;
        float leftDistance = Math.abs(x);
        float rightDistance = Math.abs(width - x);
        float topDistance = Math.abs(y);
        float bottomDistance = Math.abs(height - y);
        if (Math.min(leftDistance, rightDistance) > handleSize
                || Math.min(topDistance, bottomDistance) > handleSize) {
            return Corner.NONE;
        }
        boolean left = leftDistance <= rightDistance;
        boolean top = topDistance <= bottomDistance;
        if (top) return left ? Corner.TOP_LEFT : Corner.TOP_RIGHT;
        return left ? Corner.BOTTOM_LEFT : Corner.BOTTOM_RIGHT;
    }

    /**
     * Resizes {@code start} inside the parent. Width/height minimums are relaxed only when the
     * fixed corner itself leaves less room than the requested minimum.
     */
    static Rect resize(Corner corner, Rect start,
                       int rawDx, int rawDy, int parentWidth, int parentHeight,
                       int minWidth, int minHeight, int snapPx) {
        if (corner == Corner.NONE) return start;
        int safeParentWidth = Math.max(1, parentWidth);
        int safeParentHeight = Math.max(1, parentHeight);
        int dx = snapDelta(rawDx, snapPx);
        int dy = snapDelta(rawDy, snapPx);

        int left = start.left;
        int top = start.top;
        int right = start.right;
        int bottom = start.bottom;

        if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) {
            int effectiveMin = Math.min(Math.max(1, minWidth), Math.max(1, start.right));
            left = clamp(start.left + dx, 0, Math.max(0, start.right - effectiveMin));
        } else {
            int room = Math.max(1, safeParentWidth - start.left);
            int effectiveMin = Math.min(Math.max(1, minWidth), room);
            right = clamp(start.right + dx, start.left + effectiveMin, safeParentWidth);
        }

        if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) {
            int effectiveMin = Math.min(Math.max(1, minHeight), Math.max(1, start.bottom));
            top = clamp(start.top + dy, 0, Math.max(0, start.bottom - effectiveMin));
        } else {
            int room = Math.max(1, safeParentHeight - start.top);
            int effectiveMin = Math.min(Math.max(1, minHeight), room);
            bottom = clamp(start.bottom + dy, start.top + effectiveMin, safeParentHeight);
        }
        return new Rect(left, top, right, bottom);
    }

    /**
     * Resizes from the same fixed opposite corner while preserving the supplied width/height
     * ratio. The gesture axis with the larger relative change leads, so diagonal handles feel
     * natural on both very wide media artwork and narrow buttons.
     */
    static Rect resizeKeepingAspect(Corner corner, Rect start,
                                    int rawDx, int rawDy,
                                    int parentWidth, int parentHeight,
                                    int minWidth, int minHeight, int snapPx,
                                    float aspectRatio) {
        if (corner == Corner.NONE) return start;
        float ratio = Float.isFinite(aspectRatio) && aspectRatio > 0f
                ? aspectRatio
                : start.width() / (float) Math.max(1, start.height());
        Rect free = resize(corner, start, rawDx, rawDy,
                parentWidth, parentHeight, minWidth, minHeight, snapPx);
        int maxWidth = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT
                ? Math.max(1, start.right)
                : Math.max(1, parentWidth - start.left);
        int maxHeight = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT
                ? Math.max(1, start.bottom)
                : Math.max(1, parentHeight - start.top);
        int minimumWidth = Math.max(Math.max(1, minWidth),
                (int) Math.ceil(Math.max(1, minHeight) * ratio));
        int maximumWidth = Math.max(1,
                Math.min(maxWidth, (int) Math.floor(maxHeight * ratio)));
        if (minimumWidth > maximumWidth) minimumWidth = maximumWidth;

        float relativeWidthChange = Math.abs(free.width() - start.width())
                / (float) Math.max(1, start.width());
        float relativeHeightChange = Math.abs(free.height() - start.height())
                / (float) Math.max(1, start.height());
        int targetWidth = relativeWidthChange >= relativeHeightChange
                ? free.width() : Math.round(free.height() * ratio);
        int width = clamp(targetWidth, minimumWidth, maximumWidth);
        int height = Math.max(1, Math.round(width / ratio));
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.max(1, Math.min(maxWidth, Math.round(height * ratio)));
        }

        boolean fromLeft = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT;
        boolean fromTop = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT;
        int left = fromLeft ? start.right - width : start.left;
        int right = fromLeft ? start.right : start.left + width;
        int top = fromTop ? start.bottom - height : start.top;
        int bottom = fromTop ? start.bottom : start.top + height;
        return new Rect(Math.max(0, left), Math.max(0, top),
                Math.min(parentWidth, right), Math.min(parentHeight, bottom));
    }

    static int snapDelta(int delta, int snapPx) {
        int step = Math.max(1, snapPx);
        return Math.round(delta / (float) step) * step;
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        return Math.max(minimum, Math.min(value, maximum));
    }
}
