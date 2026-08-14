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
 * <p>Status Widget's HOME and its settings use the same package, while full-screen and floating
 * Yandex Navigator share another package. Persisting either package alone collapses two distinct
 * places. Their lifecycle/class identity is published here instead; stable synthetic targets can
 * live beside package names in existing hide lists without another preference schema.</p>
 */
public final class StatusBarSurfaceContext {
    /** Stable hide-list id shown as "our launcher · HOME only" in the app picker. */
    public static final String LAUNCHER_HOME = "@surface/launcher_home";
    /** Stable hide-list id for the ECARX floating Navigator window above our HOME. */
    public static final String NAVIGATOR_WINDOW = "@surface/navigator_window";

    private static volatile boolean launcherHomeForeground;
    private static volatile boolean navigatorWindowForeground;

    private StatusBarSurfaceContext() {
    }

    public static boolean isLauncherHomeForeground() {
        return launcherHomeForeground;
    }

    public static boolean isNavigatorWindowForeground() {
        return navigatorWindowForeground;
    }

    /** Called only from {@link LauncherActivity}'s resumed/paused lifecycle on the main thread. */
    public static void setLauncherHomeForeground(boolean foreground) {
        boolean navigatorChanged = foreground && navigatorWindowForeground;
        if (launcherHomeForeground == foreground && !navigatorChanged) return;
        launcherHomeForeground = foreground;
        // Returning to the resumed HOME Activity is the authoritative close event for the
        // freeform Yandex window that Natro launched above it.
        if (foreground) navigatorWindowForeground = false;
        notifySurfaceChanged();
    }

    /**
     * Publishes the actual floating-window surface independently of the Yandex package.
     * Package identity alone cannot distinguish TransparentSplashActivity from full Navigator.
     */
    public static void setNavigatorWindowForeground(boolean foreground) {
        if (navigatorWindowForeground == foreground) return;
        navigatorWindowForeground = foreground;
        if (foreground) launcherHomeForeground = false;
        notifySurfaceChanged();
    }

    private static void notifySurfaceChanged() {
        WidgetService service = WidgetService.getInstance();
        if (service != null) service.onForegroundSurfaceContextChanged();
    }

    /** Matches a real package or the two independently published synthetic surfaces. */
    static boolean matches(@Nullable Set<String> targets,
                           @Nullable String foregroundPackage,
                           boolean launcherForeground) {
        return matches(targets, foregroundPackage, launcherForeground,
                navigatorWindowForeground);
    }

    /** Matches real packages and both lifecycle-owned display surfaces independently. */
    static boolean matches(@Nullable Set<String> targets,
                           @Nullable String foregroundPackage,
                           boolean launcherForeground,
                           boolean navigatorWindowForeground) {
        if (targets == null || targets.isEmpty()) return false;
        // Synthetic surfaces are disjoint from their hosting package. In particular, choosing
        // the normal Yandex Navigator application must not silently include its ECARX floating
        // window, and choosing the floating window must not hide the full-screen application.
        if (launcherForeground) return targets.contains(LAUNCHER_HOME);
        if (navigatorWindowForeground) return targets.contains(NAVIGATOR_WINDOW);
        return foregroundPackage != null && targets.contains(foregroundPackage);
    }

    /** Synthetic-only lists do not justify UsageStats/accessibility package tracking. */
    public static boolean requiresPackageTracking(@Nullable Set<String> targets) {
        if (targets == null || targets.isEmpty()) return false;
        for (String target : targets) {
            if (!isSyntheticTarget(target)) return true;
        }
        return false;
    }

    /** Known surface ids are allowed beside package names in existing persisted string sets. */
    public static boolean isSyntheticTarget(@Nullable String target) {
        return LAUNCHER_HOME.equals(target) || NAVIGATOR_WINDOW.equals(target);
    }

    /** Exact classifier shared by the safe Android-9 event path and tests. */
    static boolean isNavigatorWindow(@Nullable String packageName,
                                     @Nullable String className) {
        if (packageName == null || className == null) return false;
        String pkg = packageName.trim();
        String cls = className.trim();
        return isYandexPackage(pkg) && ("TransparentSplashActivity".equals(cls)
                || cls.endsWith(".TransparentSplashActivity"));
    }

    static boolean isFullscreenYandex(@Nullable String packageName,
                                      @Nullable String className) {
        if (!isYandexPackage(packageName) || className == null) return false;
        String cls = className.trim();
        return cls.endsWith(".NavigatorActivity") || cls.endsWith(".MapActivity");
    }

    static boolean isYandexPackage(@Nullable String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.trim();
        return "ru.yandex.yandexnavi".equals(pkg)
                || "ru.yandex.yandexmaps".equals(pkg)
                || "com.yandex.yango".equals(pkg);
    }
}
