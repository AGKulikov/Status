/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetService;
import dezz.status.widget.climate.ClimatePanelService;

/**
 * Same-process bridge from the running Status Widget overlay to any HOME content editor.
 * Media can consume this resolver later without depending on navigation classes.
 */
public final class LauncherSafeAreaResolver {
    private LauncherSafeAreaResolver() {}

    public static int resolveTopInset(@NonNull Preferences preferences, int systemTopInset) {
        return resolveInsets(preferences, 0, systemTopInset, 0, 0, 0).top;
    }

    @NonNull
    public static LauncherSafeAreaPolicy.Insets resolveInsets(
            @NonNull Preferences preferences,
            int systemLeftInset, int systemTopInset,
            int systemRightInset, int systemBottomInset,
            int launcherDisplayId) {
        WidgetService service = WidgetService.getInstance();
        boolean running = service != null && WidgetService.isRunning();
        int measuredHeight = running ? service.getStatusBarOverlayHeight() : 0;
        String climateStatus = ClimatePanelService.getRuntimeStatus();
        return LauncherSafeAreaPolicy.insets(
                systemLeftInset, systemTopInset, systemRightInset, systemBottomInset,
                preferences.widgetEnabled.get(),
                preferences.widgetMode.get() == 1,
                running,
                measuredHeight,
                preferences.climatePanelEnabled.get()
                        && preferences.climatePanelMode.get() == 1
                        && LauncherSafeAreaPolicy.isLocalClimateReservation(
                                climateStatus,
                                ClimatePanelService.getRuntimeDisplayId(),
                                launcherDisplayId),
                preferences.climatePanelEdge.get(),
                preferences.climatePanelExtent.get());
    }
}
