/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;

/**
 * Geometry shared by the real overlay, settings preview and stock-climate tap proxy.
 *
 * <p>The old Monjaro panel divides the display into four vertical slots. Its stock climate
 * control is centred at one and a half slots (37.5% of the screen). We deliberately preserve
 * that coordinate instead of exposing a misleading slider. The production rail is continuous;
 * its movable climate proxy temporarily makes the rail input-transparent and taps this point.</p>
 */
public final class DriverPanelLayoutPolicy {
    public static final int MAX_BUTTONS = 10;
    public static final float STOCK_CLIMATE_CENTER_FRACTION = 0.375f;
    public static final float STOCK_CLIMATE_SLOT_HEIGHT_FRACTION = 0.55f;
    public static final int STOCK_CLIMATE_MIN_HEIGHT_PX = 88;
    private static final int REFERENCE_SCREEN_WIDTH = 1920;
    private static final int REFERENCE_OLD_PANEL_WIDTH = 120;

    public static final class TapTarget {
        public final int x;
        public final int y;

        TapTarget(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class Layout {
        public final int contentTop;
        public final int contentBottom;
        public final int holeTop;
        public final int holeBottom;
        public final int beforeCount;
        public final int afterCount;

        Layout(int contentTop, int contentBottom, int holeTop, int holeBottom,
               int beforeCount, int afterCount) {
            this.contentTop = contentTop;
            this.contentBottom = contentBottom;
            this.holeTop = holeTop;
            this.holeBottom = holeBottom;
            this.beforeCount = beforeCount;
            this.afterCount = afterCount;
        }

        public boolean hasHole() {
            return holeBottom > holeTop;
        }

        public int beforeHeight() {
            return Math.max(0, holeTop - contentTop);
        }

        public int afterHeight() {
            return Math.max(0, contentBottom - holeBottom);
        }
    }

    private DriverPanelLayoutPolicy() {
    }

    /**
     * Centre of the covered OEM climate button in the old driver panel.
     * Horizontal sizing follows the reference panel's 120/1920 scale with its 80 px minimum.
     */
    @NonNull
    public static TapTarget stockClimateTapTarget(int screenWidth, int screenHeight,
                                                  boolean panelOnRight) {
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        int oldPanelWidth = Math.max(Math.round(REFERENCE_OLD_PANEL_WIDTH
                * width / (float) REFERENCE_SCREEN_WIDTH), 80);
        int x = panelOnRight ? width - oldPanelWidth / 2 : oldPanelWidth / 2;
        int quarter = Math.max(height / 4, 1);
        int y = quarter / 2 + quarter;
        return new TapTarget(clamp(x, 0, width - 1), clamp(y, 0, height - 1));
    }

    @NonNull
    public static Layout calculate(int screenHeight, int topPadding, int bottomPadding,
                                   int requestedButtons, boolean stockClimateEnabled) {
        int height = Math.max(1, screenHeight);
        int contentTop = clamp(topPadding, 0, height - 1);
        int contentBottom = clamp(height - Math.max(0, bottomPadding),
                contentTop + 1, height);
        int buttonCount = clamp(requestedButtons, 0, MAX_BUTTONS);
        if (!stockClimateEnabled) {
            return new Layout(contentTop, contentBottom, contentBottom, contentBottom,
                    buttonCount, 0);
        }

        // Retained as a tested emergency physical-gap policy. Production currently passes false
        // because the input-transparent proxy keeps the rail continuous.
        int quarter = Math.max(height / 4, 1);
        int holeHeight = Math.max(Math.round(quarter
                * STOCK_CLIMATE_SLOT_HEIGHT_FRACTION), STOCK_CLIMATE_MIN_HEIGHT_PX);
        int center = quarter / 2 + quarter;
        int holeTop = Math.max(contentTop, center - holeHeight / 2);
        int holeBottom = Math.min(contentBottom, holeTop + holeHeight);
        if (holeBottom <= holeTop) {
            return new Layout(contentTop, contentBottom, contentBottom, contentBottom,
                    buttonCount, 0);
        }

        int beforeHeight = Math.max(0, holeTop - contentTop);
        int afterHeight = Math.max(0, contentBottom - holeBottom);
        int freeHeight = beforeHeight + afterHeight;
        int beforeCount;
        if (buttonCount == 0 || freeHeight == 0) {
            beforeCount = 0;
        } else {
            beforeCount = Math.round(buttonCount * (beforeHeight / (float) freeHeight));
            beforeCount = clamp(beforeCount, 0, buttonCount);
            if (beforeHeight == 0) beforeCount = 0;
            if (afterHeight == 0) beforeCount = buttonCount;
        }
        return new Layout(contentTop, contentBottom, holeTop, holeBottom,
                beforeCount, buttonCount - beforeCount);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
