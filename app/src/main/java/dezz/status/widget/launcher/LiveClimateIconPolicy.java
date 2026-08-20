/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Keeps the live-climate presentation independent from the action assigned to a button. */
public final class LiveClimateIconPolicy {
    private LiveClimateIconPolicy() {}

    /**
     * A new stock-climate button gets the useful live default. Editing an existing button never
     * changes the user's current presentation choice, regardless of the newly selected action.
     */
    public static boolean afterPrimaryActionChange(
            boolean existingButton,
            boolean currentLiveClimate,
            LauncherShortcutStore.Kind selectedKind,
            String selectedTarget) {
        boolean selectedStockClimate = selectedKind == LauncherShortcutStore.Kind.BUILTIN
                && LauncherShortcutStore.Builtin.STOCK_CLIMATE.key.equals(selectedTarget);
        return existingButton ? currentLiveClimate : selectedStockClimate;
    }
}
