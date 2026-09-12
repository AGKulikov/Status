/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Shared, pixel-space joint for a balloon and its geographic pointer. No screen displacement. */
final class BalloonGeometry {
    private BalloonGeometry() {}

    /** Both base vertices are INSIDE the rounded body, including for diagonal/circular cards. */
    static float[] tailBase(float left, float top, float right, float bottom,
                            float radius, float tipX, float tipY, float halfWidth) {
        float width = right - left, height = bottom - top;
        float cx = (left + right) * .5f, cy = (top + bottom) * .5f;
        float dx = tipX - cx, dy = tipY - cy;
        float length = Math.max(.001f, (float) Math.hypot(dx, dy));
        dx /= length; dy /= length;
        float half = Math.min(Math.max(0f, halfWidth), Math.min(width, height) * .24f);
        float corner = Math.min(Math.max(0f, radius), Math.min(width, height) * .5f);
        float edge = Math.min(Math.abs(dx) < .0001f ? Float.MAX_VALUE : width / (2f * Math.abs(dx)),
                Math.abs(dy) < .0001f ? Float.MAX_VALUE : height / (2f * Math.abs(dy)));
        // Find the nearest joint whose TWO vertices are inside the real rounded silhouette.
        // A fixed inset fails on long narrow cards, where the diagonal ray is almost horizontal.
        float low = 0f, high = edge;
        for (int step = 0; step < 24; step++) {
            float middle = low + (high - low) * .5f;
            float x = cx + dx * middle, y = cy + dy * middle;
            if (inside(left, top, right, bottom, corner, x - dy * half, y + dx * half)
                    && inside(left, top, right, bottom, corner, x + dy * half, y - dx * half)) low = middle;
            else high = middle;
        }
        float reach = Math.max(0f, low - 1f);
        float bx = cx + dx * reach, by = cy + dy * reach;
        return new float[]{bx - dy * half, by + dx * half,
                bx + dy * half, by - dx * half};
    }

    private static boolean inside(float left, float top, float right, float bottom,
                                  float radius, float x, float y) {
        if (x <= left || x >= right || y <= top || y >= bottom) return false;
        if (radius <= 0f) return true;
        float cx = Math.max(left + radius, Math.min(right - radius, x));
        float cy = Math.max(top + radius, Math.min(bottom - radius, y));
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy < radius * radius;
    }
}
