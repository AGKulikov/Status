/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Pure throttling and demand rules shared by the notification and Accessibility collectors. */
public final class NavigationCollectionPolicy {
    /** Coalesces the burst of callbacks produced by one visual Navigator update. */
    public static final long EVENT_DEBOUNCE_MS = 300L;
    /** A changing notification/tree is expensive; five seconds is ample for HOME route text. */
    public static final long MIN_EVENT_SCAN_INTERVAL_MS = 5_000L;
    /** Safety reconciliation for OEM firmware which occasionally drops callbacks. */
    public static final long ACTIVE_WATCHDOG_MS = 30_000L;
    /** No route was found. Keep only a very cheap, infrequent recovery check. */
    public static final long IDLE_WATCHDOG_MS = 120_000L;

    private NavigationCollectionPolicy() {}

    public static boolean shouldCollect(boolean navigationPanelEnabled,
            boolean favoriteRoutesEnabled, boolean vehiclePanelEnabled,
            boolean speedLimitWarningEnabled) {
        return navigationPanelEnabled || favoriteRoutesEnabled
                || (vehiclePanelEnabled && speedLimitWarningEnabled);
    }

    /** Delay for an event-driven scan without allowing callback storms to starve the trailing run. */
    public static long eventDelay(long nowElapsed, long lastScanElapsed) {
        if (lastScanElapsed <= 0L || nowElapsed < lastScanElapsed) return EVENT_DEBOUNCE_MS;
        long untilAllowed = MIN_EVENT_SCAN_INTERVAL_MS - (nowElapsed - lastScanElapsed);
        return Math.max(EVENT_DEBOUNCE_MS, untilAllowed);
    }

    public static long watchdogDelay(boolean navigationSurfaceObserved) {
        return navigationSurfaceObserved ? ACTIVE_WATCHDOG_MS : IDLE_WATCHDOG_MS;
    }
}
