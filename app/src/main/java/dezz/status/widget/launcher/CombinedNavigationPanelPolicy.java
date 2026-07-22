/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Pure switching and migration rules for the combined favorite/navigation HOME panel. */
public final class CombinedNavigationPanelPolicy {
    private CombinedNavigationPanelPolicy() {}

    public static boolean isEnabled(boolean legacyNavigationEnabled,
            boolean legacyFavoritesEnabled) {
        return legacyNavigationEnabled || legacyFavoritesEnabled;
    }

    public static boolean showFavorites(boolean routeActive, boolean hasEnabledFavorites) {
        return !routeActive && hasEnabledFavorites;
    }

    public static boolean hasVisibleContent(boolean routeActive, boolean hasEnabledFavorites,
            boolean hasLiveNavigationElements, boolean hasIdleFallback) {
        return routeActive ? hasLiveNavigationElements
                : hasEnabledFavorites || hasIdleFallback;
    }

    public static boolean shouldUseLegacyFavoriteGeometry(boolean migrationCompleted,
            boolean legacyNavigationEnabled, boolean legacyFavoritesEnabled) {
        return !migrationCompleted && !legacyNavigationEnabled && legacyFavoritesEnabled;
    }
}
