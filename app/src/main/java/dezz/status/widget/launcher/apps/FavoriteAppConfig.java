/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.apps;

import androidx.annotation.NonNull;

/** Appearance of one application in the HOME favourites panel. */
public final class FavoriteAppConfig {
    public static final int DEFAULT_ICON_SIZE_PX = 50;
    public static final int DEFAULT_LABEL_SIZE_SP = 12;
    public static final int MIN_ICON_SIZE_PX = 24;
    public static final int MAX_ICON_SIZE_PX = 180;
    public static final int MIN_LABEL_SIZE_SP = 8;
    public static final int MAX_LABEL_SIZE_SP = 36;

    @NonNull public final String packageName;
    public int iconSizePx = DEFAULT_ICON_SIZE_PX;
    public int labelSizeSp = DEFAULT_LABEL_SIZE_SP;
    public boolean showLabel = true;

    public FavoriteAppConfig(@NonNull String packageName) {
        this.packageName = normalizePackage(packageName);
    }

    @NonNull
    public FavoriteAppConfig copy() {
        FavoriteAppConfig value = new FavoriteAppConfig(packageName);
        value.iconSizePx = iconSizePx;
        value.labelSizeSp = labelSizeSp;
        value.showLabel = showLabel;
        value.normalize();
        return value;
    }

    public void normalize() {
        iconSizePx = clamp(iconSizePx, MIN_ICON_SIZE_PX, MAX_ICON_SIZE_PX);
        labelSizeSp = clamp(labelSizeSp, MIN_LABEL_SIZE_SP, MAX_LABEL_SIZE_SP);
    }

    @NonNull
    static String normalizePackage(@NonNull String value) {
        return value.trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
