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
