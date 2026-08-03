/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure geometry used by HOME and, later, HUD horizontal groups. */
public final class HorizontalGroupLayout {
    public static final int DISTRIBUTION_COMPACT = 0;
    public static final int DISTRIBUTION_EQUAL = 1;

    public static final class Size {
        public final int width;
        public final int height;

        public Size(int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }
    }

    public static final class Rect {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }
    }

    private HorizontalGroupLayout() {
    }

    /**
     * Lays members left-to-right. Text size is deliberately absent from this calculation: a
     * larger group only gives its member frames more room.
     */
    @NonNull
    public static List<Rect> layout(
            int groupX, int groupY, int groupWidth, int groupHeight,
            int paddingLeft, int paddingTop, int paddingRight, int paddingBottom,
            int gap, int horizontalAlignment, int verticalAlignment, int distribution,
            @NonNull List<Size> desired) {
        if (desired.isEmpty()) return Collections.emptyList();
        int width = Math.max(1, groupWidth);
        int height = Math.max(1, groupHeight);
        int left = clamp(paddingLeft, 0, width - 1);
        int top = clamp(paddingTop, 0, height - 1);
        int right = clamp(paddingRight, 0, width - left - 1);
        int bottom = clamp(paddingBottom, 0, height - top - 1);
        int innerWidth = Math.max(1, width - left - right);
        int innerHeight = Math.max(1, height - top - bottom);
        int safeGap = Math.max(0, gap);
        int gapTotal = Math.min(innerWidth - 1,
                safeGap * Math.max(0, desired.size() - 1));
        int memberWidthBudget = Math.max(1, innerWidth - gapTotal);

        int[] widths = new int[desired.size()];
        if (distribution == DISTRIBUTION_EQUAL) {
            int used = 0;
            for (int index = 0; index < widths.length; index++) {
                int remainingItems = widths.length - index;
                widths[index] = Math.max(1,
                        (memberWidthBudget - used) / Math.max(1, remainingItems));
                used += widths[index];
            }
        } else {
            int desiredTotal = 0;
            for (Size size : desired) desiredTotal += Math.max(1, size.width);
            float scale = desiredTotal > memberWidthBudget
                    ? memberWidthBudget / (float) desiredTotal : 1f;
            int used = 0;
            for (int index = 0; index < widths.length; index++) {
                int remainingItems = widths.length - index;
                int maximum = Math.max(1, memberWidthBudget - used
                        - Math.max(0, remainingItems - 1));
                widths[index] = Math.min(maximum,
                        Math.max(1, Math.round(desired.get(index).width * scale)));
                used += widths[index];
            }
        }

        int contentWidth = gapTotal;
        for (int memberWidth : widths) contentWidth += memberWidth;
        int startX = left;
        if (distribution != DISTRIBUTION_EQUAL) {
            if (horizontalAlignment == 1) {
                startX += Math.max(0, innerWidth - contentWidth) / 2;
            } else if (horizontalAlignment == 2) {
                startX += Math.max(0, innerWidth - contentWidth);
            }
        }

        ArrayList<Rect> result = new ArrayList<>(desired.size());
        int x = groupX + startX;
        for (int index = 0; index < desired.size(); index++) {
            int itemHeight = Math.min(innerHeight, Math.max(1, desired.get(index).height));
            int y = groupY + top;
            if (verticalAlignment == 1) {
                y += Math.max(0, innerHeight - itemHeight) / 2;
            } else if (verticalAlignment == 2) {
                y += Math.max(0, innerHeight - itemHeight);
            }
            result.add(new Rect(x, y, widths[index], itemHeight));
            x += widths[index] + safeGap;
        }
        return Collections.unmodifiableList(result);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
