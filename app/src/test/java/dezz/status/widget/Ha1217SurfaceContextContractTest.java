/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Regression boundary for HOME/windowed-app status-row visibility. */
public final class Ha1217SurfaceContextContractTest {
    @Test public void launcherHomeTargetNeverMatchesSettingsPackageByPackageAlone() {
        Set<String> targets = Collections.singleton(StatusBarSurfaceContext.LAUNCHER_HOME);

        assertFalse(StatusBarSurfaceContext.matches(
                targets, "ru.natro.statuswidget", false));
        assertTrue(StatusBarSurfaceContext.matches(
                targets, "ru.natro.statuswidget", true));
        assertFalse(StatusBarSurfaceContext.requiresPackageTracking(targets));
    }

    @Test public void realPackagesStillMatchAndRequireEventOrUsageTracking() {
        Set<String> targets = new HashSet<>();
        targets.add(StatusBarSurfaceContext.LAUNCHER_HOME);
        targets.add("ru.yandex.yandexnavi");

        assertTrue(StatusBarSurfaceContext.matches(
                targets, "ru.yandex.yandexnavi", false));
        assertTrue(StatusBarSurfaceContext.requiresPackageTracking(targets));
    }

    @Test public void pickerAndLauncherUseAnExplicitLifecycleSurface() throws Exception {
        String picker = source("AppSelectionActivity.java");
        String launcher = source("LauncherActivity.java");
        String widget = source("WidgetService.java");

        assertTrue(picker.contains("addLauncherHomeEntry(pm, seen, result)"));
        assertTrue(picker.contains("StatusBarSurfaceContext.LAUNCHER_HOME"));
        assertTrue(launcher.contains("StatusBarSurfaceContext.setLauncherHomeForeground(true)"));
        assertTrue(launcher.contains("StatusBarSurfaceContext.setLauncherHomeForeground(false)"));
        assertTrue(launcher.indexOf("setLauncherHomeForeground(true)")
                > launcher.indexOf("protected void onResume()"));
        int pause = launcher.indexOf("protected void onPause()");
        assertTrue(launcher.indexOf("setLauncherHomeForeground(false)", pause) > pause);
        int topSurface = widget.indexOf("private boolean isLauncherHomeTopSurface()");
        int matcher = widget.indexOf("private boolean matchesForegroundContext", topSurface);
        String lifecycleGate = widget.substring(topSurface, matcher);
        assertTrue(lifecycleGate.contains(
                "return StatusBarSurfaceContext.isLauncherHomeForeground();"));
        assertFalse(lifecycleGate.contains("lastForegroundPackage"));
        assertTrue(widget.contains(
                "if (Looper.myLooper() == Looper.getMainLooper()) update.run()"));
    }

    @Test public void oldLauncherToggleOnlyHidesTimeAndBluetoothViews() throws Exception {
        String widget = source("WidgetService.java");
        String settings = source("LauncherSettingsActivity.java");
        String legacyCleanup = source("launcher/EcarxSystemStatusBarPolicy.java");

        assertTrue(widget.contains("type == BrickType.TIME || type == BrickType.BLUETOOTH"));
        assertTrue(widget.contains("StatusBarSurfaceContext.isLauncherHomeForeground()"));
        assertTrue(settings.contains(
                "Скрывать часы и Bluetooth нашей строки только на HOME"));
        assertFalse(settings.contains("EcarxSystemStatusBarPolicy.apply(this, checked"));
        assertFalse(legacyCleanup.contains("immersive.status=*"));
        assertFalse(legacyCleanup.contains("launcherHideSystemStatusBar.set(enabled)"));
        assertTrue(legacyCleanup.contains("launcherSystemStatusBarOriginalPolicy.set(UNSET)"));
    }

    @Test public void androidNineAcceptsFreeformWindowLifecycleWithoutTreeTraversal()
            throws Exception {
        String accessibility = source("WidgetAccessibilityService.java");
        int method = accessibility.indexOf("publishAndroidNineForegroundEvent(");
        int end = accessibility.indexOf("supportsSafeWindowTraversal()", method);
        String windowPublishing = accessibility.substring(method, end);

        assertTrue(windowPublishing.contains("AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED"));
        assertTrue(windowPublishing.contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED"));
        assertTrue(windowPublishing.contains("NavigationDataRepository.isYandexPackage"));
        assertFalse(windowPublishing.contains("getWindows()"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }
}
