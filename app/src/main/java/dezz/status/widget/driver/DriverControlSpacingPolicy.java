/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;

/** Resolves driver-rail control insets without hidden weighted button slots. */
public final class DriverControlSpacingPolicy {
    public static final class Layout {
        @NonNull public final int[] topPadding;
        @NonNull public final int[] bottomPadding;

        Layout(@NonNull int[] topPadding, @NonNull int[] bottomPadding) {
            this.topPadding = topPadding;
            this.bottomPadding = bottomPadding;
        }
    }

    private static final int AUTO = -1;

    private DriverControlSpacingPolicy() {
    }

    /**
     * Explicit values are exact internal padding. Remaining height is shared only by untouched
     * {@code -1} sides. At an adjacent boundary an explicit side suppresses the neighbour's auto
     * side, so setting a Back button's top inset to zero really removes the whole Home→Back gap.
     */
    @NonNull
    public static Layout resolve(int availableHeight, @NonNull int[] naturalHeights,
                                 @NonNull int[] requestedTop,
                                 @NonNull int[] requestedBottom) {
        int count = naturalHeights.length;
        if (requestedTop.length != count || requestedBottom.length != count) {
            throw new IllegalArgumentException("Mismatched driver-control arrays");
        }
        int[] top = new int[count];
        int[] bottom = new int[count];
        for (int index = 0; index < count; index++) {
            top[index] = normalizedRequest(requestedTop[index]);
            bottom[index] = normalizedRequest(requestedBottom[index]);
        }

        for (int index = 1; index < count; index++) {
            if (top[index] >= 0 && bottom[index - 1] == AUTO) bottom[index - 1] = 0;
            if (bottom[index - 1] >= 0 && top[index] == AUTO) top[index] = 0;
        }

        long fixed = 0L;
        int autoSides = 0;
        for (int index = 0; index < count; index++) {
            fixed += Math.max(0, naturalHeights[index]);
            if (top[index] == AUTO) autoSides++;
            else if (!DriverButtonHeightPolicy.isFixedAutoSpacingRequest(top[index])) {
                fixed += top[index];
            }
            if (bottom[index] == AUTO) autoSides++;
            else if (!DriverButtonHeightPolicy.isFixedAutoSpacingRequest(bottom[index])) {
                fixed += bottom[index];
            }
        }
        int remaining = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                (long) Math.max(0, availableHeight) - fixed));
        int each = autoSides == 0 ? 0 : remaining / autoSides;
        int remainder = autoSides == 0 ? 0 : remaining % autoSides;
        for (int index = 0; index < count; index++) {
            if (top[index] == AUTO) {
                top[index] = each;
                if (remainder > 0) {
                    top[index]++;
                    remainder--;
                }
            }
            if (bottom[index] == AUTO) {
                bottom[index] = each;
                if (remainder > 0) {
                    bottom[index]++;
                    remainder--;
                }
            }
            if (DriverButtonHeightPolicy.isFixedAutoSpacingRequest(top[index])) {
                top[index] = 0;
            }
            if (DriverButtonHeightPolicy.isFixedAutoSpacingRequest(bottom[index])) {
                bottom[index] = 0;
            }
        }
        return new Layout(top, bottom);
    }

    private static int normalizedRequest(int requested) {
        if (DriverButtonHeightPolicy.isFixedAutoSpacingRequest(requested)) return requested;
        return requested < 0 ? AUTO : requested;
    }
}
