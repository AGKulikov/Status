/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * Process-local foreground surfaces that cannot be represented by an Android package name.
 *
 * <p>Status Widget's HOME and its settings use the same package. Persisting the package in a
 * hide list would therefore also hide elements throughout the settings UI. HOME publishes its
 * resumed lifecycle here instead; the stable synthetic target can live beside package names in
 * existing hide lists without another polling path or preference schema.</p>
 */
public final class StatusBarSurfaceContext {
    /** Stable hide-list id shown as "our launcher · HOME only" in the app picker. */
    public static final String LAUNCHER_HOME = "@surface/launcher_home";

    private static volatile boolean launcherHomeForeground;

    private StatusBarSurfaceContext() {
    }

    public static boolean isLauncherHomeForeground() {
        return launcherHomeForeground;
    }

    /** Called only from {@link LauncherActivity}'s resumed/paused lifecycle on the main thread. */
    public static void setLauncherHomeForeground(boolean foreground) {
        if (launcherHomeForeground == foreground) return;
        launcherHomeForeground = foreground;
        WidgetService service = WidgetService.getInstance();
        if (service != null) service.onForegroundSurfaceContextChanged();
    }

    /** Matches either a real foreground package or the caller-confirmed top HOME surface. */
    static boolean matches(@Nullable Set<String> targets,
                           @Nullable String foregroundPackage,
                           boolean launcherForeground) {
        if (targets == null || targets.isEmpty()) return false;
        if (launcherForeground && targets.contains(LAUNCHER_HOME)) return true;
        return foregroundPackage != null && targets.contains(foregroundPackage);
    }

    /** Synthetic-only lists do not justify UsageStats/accessibility package tracking. */
    public static boolean requiresPackageTracking(@Nullable Set<String> targets) {
        if (targets == null || targets.isEmpty()) return false;
        for (String target : targets) {
            if (!LAUNCHER_HOME.equals(target)) return true;
        }
        return false;
    }
}
