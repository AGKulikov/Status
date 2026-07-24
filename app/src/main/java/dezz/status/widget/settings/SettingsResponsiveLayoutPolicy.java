/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

/** Pure geometry policy shared by the settings hub and local JVM tests. */
public final class SettingsResponsiveLayoutPolicy {
    public static final int MIN_SPLIT_WIDTH_DP = 760;
    public static final int MIN_CONTENT_WIDTH_DP = 420;
    public static final int MIN_SIDEBAR_WIDTH_DP = 260;
    public static final int MAX_SIDEBAR_WIDTH_DP = 360;

    private SettingsResponsiveLayoutPolicy() {
    }

    public static boolean useSplitPane(int availableWidthDp) {
        return availableWidthDp >= MIN_SPLIT_WIDTH_DP;
    }

    public static int sidebarWidthDp(int availableWidthDp) {
        if (!useSplitPane(availableWidthDp)) return availableWidthDp;
        int preferred = Math.round(availableWidthDp * .30f);
        int maxByContent = Math.max(MIN_SIDEBAR_WIDTH_DP,
                availableWidthDp - MIN_CONTENT_WIDTH_DP);
        return clamp(preferred, MIN_SIDEBAR_WIDTH_DP,
                Math.min(MAX_SIDEBAR_WIDTH_DP, maxByContent));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
