/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Immutable render state shared by the status bar and read-only driver information tiles. */
public final class StatusBrickSnapshot {
    @NonNull public final String text;
    public final int iconResource;
    public final int iconTint;
    /** Drawable level (0..10000), used by signal bars and clipped battery fill. */
    public final int iconLevel;
    /** Live number rendered inside the iPhone battery body; null for every other icon. */
    @Nullable public final Integer batteryPercent;
    /** Fresh helper-reported external power; controls the lightning overlay. */
    public final boolean batteryCharging;
    /** Raw iPhone cellular fields retained for independently configurable secondary surfaces. */
    @Nullable public final Integer cellularSignalPercent;
    @NonNull public final String cellularOperator;
    @NonNull public final String cellularNetworkType;
    public final int outlineColor;
    public final int outlineWidth;
    @Nullable public final String badgeText;
    public final int badgeBackground;
    public final int badgeForeground;
    public final int badgeDrawableResource;
    public final boolean known;
    public final boolean active;

    public StatusBrickSnapshot(@NonNull String text,
                               int iconResource,
                               int iconTint,
                               int iconLevel,
                               @Nullable Integer batteryPercent,
                               boolean batteryCharging,
                               @Nullable Integer cellularSignalPercent,
                               @NonNull String cellularOperator,
                               @NonNull String cellularNetworkType,
                               int outlineColor,
                               int outlineWidth,
                               @Nullable String badgeText,
                               int badgeBackground,
                               int badgeForeground,
                               int badgeDrawableResource,
                               boolean known,
                               boolean active) {
        this.text = text;
        this.iconResource = iconResource;
        this.iconTint = iconTint;
        this.iconLevel = iconLevel;
        this.batteryPercent = batteryPercent;
        this.batteryCharging = batteryCharging;
        this.cellularSignalPercent = cellularSignalPercent;
        this.cellularOperator = cellularOperator;
        this.cellularNetworkType = cellularNetworkType;
        this.outlineColor = outlineColor;
        this.outlineWidth = outlineWidth;
        this.badgeText = badgeText;
        this.badgeBackground = badgeBackground;
        this.badgeForeground = badgeForeground;
        this.badgeDrawableResource = badgeDrawableResource;
        this.known = known;
        this.active = active;
    }
}
