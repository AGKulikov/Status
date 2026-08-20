/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import androidx.annotation.NonNull;

import java.util.UUID;

/** One independently styled, one-tap destination in the HOME routes panel. */
public final class FavoriteRouteConfig {
    public enum Product { NAVIGATOR, MAPS }

    @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String title = "Новый маршрут";
    @NonNull public String address = "";
    @NonNull public String coordinates = "";
    @NonNull public Product product = Product.NAVIGATOR;
    public boolean floating = true;
    @NonNull public String icon = "navigation";
    public boolean enabled = true;
    public int iconSizePx = 56;
    public int labelSizeSp = 14;
    @NonNull public String backgroundColor = "#CC222A38";
    @NonNull public String textColor = "#FFFFFF";
    @NonNull public String iconColor = "#4EA5FF";

    @NonNull
    public FavoriteRouteConfig copy() {
        FavoriteRouteConfig value = new FavoriteRouteConfig();
        value.id = id;
        value.title = title;
        value.address = address;
        value.coordinates = coordinates;
        value.product = product;
        value.floating = floating;
        value.icon = icon;
        value.enabled = enabled;
        value.iconSizePx = iconSizePx;
        value.labelSizeSp = labelSizeSp;
        value.backgroundColor = backgroundColor;
        value.textColor = textColor;
        value.iconColor = iconColor;
        value.normalize();
        return value;
    }

    public void normalize() {
        id = bounded(id, UUID.randomUUID().toString(), 80);
        title = bounded(title, "Маршрут", 80);
        address = bounded(address, "", 500);
        coordinates = bounded(coordinates, "", 1200);
        icon = bounded(icon, "navigation", 80);
        if (product == null) product = Product.NAVIGATOR;
        iconSizePx = clamp(iconSizePx, 24, 180);
        labelSizeSp = clamp(labelSizeSp, 8, 36);
        if (!isColor(backgroundColor)) backgroundColor = "#CC222A38";
        if (!isColor(textColor)) textColor = "#FFFFFF";
        if (!isColor(iconColor)) iconColor = "#4EA5FF";
    }

    @NonNull
    private static String bounded(String value, @NonNull String fallback, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) normalized = fallback;
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static boolean isColor(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
