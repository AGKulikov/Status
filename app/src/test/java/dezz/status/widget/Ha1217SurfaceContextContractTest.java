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
import java.util.Arrays;
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

    @Test public void navigatorWindowAndFullscreenPackageAreIndependentTargets() {
        Set<String> windowOnly = Collections.singleton(
                StatusBarSurfaceContext.NAVIGATOR_WINDOW);
        Set<String> fullscreenOnly = Collections.singleton("ru.yandex.yandexnavi");
        Set<String> both = new HashSet<>(Arrays.asList(
                StatusBarSurfaceContext.NAVIGATOR_WINDOW, "ru.yandex.yandexnavi"));

        assertTrue(StatusBarSurfaceContext.matches(
                windowOnly, "ru.yandex.yandexnavi", false, true));
        assertFalse(StatusBarSurfaceContext.matches(
                windowOnly, "ru.yandex.yandexnavi", false, false));
        assertFalse(StatusBarSurfaceContext.matches(
                windowOnly, "ru.natro.statuswidget", true, false));
        assertFalse(StatusBarSurfaceContext.matches(
                windowOnly, "com.example.music", false, false));
        assertFalse(StatusBarSurfaceContext.matches(
                fullscreenOnly, "ru.yandex.yandexnavi", false, true));
        assertTrue(StatusBarSurfaceContext.matches(
                fullscreenOnly, "ru.yandex.yandexnavi", false, false));
        assertTrue(StatusBarSurfaceContext.matches(
                both, "ru.yandex.yandexnavi", false, true));
        assertTrue(StatusBarSurfaceContext.matches(
                both, "ru.yandex.yandexnavi", false, false));
        assertFalse(StatusBarSurfaceContext.requiresPackageTracking(windowOnly));
    }

    @Test public void onlyKnownYandexTransparentSplashIsTheWindowSurface() {
        assertTrue(StatusBarSurfaceContext.isNavigatorWindow(
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexmaps.app.TransparentSplashActivity"));
        assertTrue(StatusBarSurfaceContext.isNavigatorWindow(
                "ru.yandex.yandexmaps",
                "ru.yandex.yandexmaps.TransparentSplashActivity"));
        assertFalse(StatusBarSurfaceContext.isNavigatorWindow(
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexnavi.core.NavigatorActivity"));
        assertFalse(StatusBarSurfaceContext.isNavigatorWindow(
                "com.example.other",
                "com.example.other.TransparentSplashActivity"));
    }

    @Test public void yandexContentActivityDoesNotCancelConfirmedFreeformTask() {
        assertTrue(StatusBarSurfaceContext.navigatorWindowAfterStateChange(
                true,
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexnavi.core.NavigatorActivity"));
        assertTrue(StatusBarSurfaceContext.navigatorWindowAfterStateChange(
                true,
                "ru.yandex.yandexmaps",
                "ru.yandex.yandexmaps.app.MapActivity"));
        assertFalse(StatusBarSurfaceContext.navigatorWindowAfterStateChange(
                false,
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexnavi.core.NavigatorActivity"));
        assertTrue(StatusBarSurfaceContext.navigatorWindowAfterStateChange(
                false,
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexmaps.app.TransparentSplashActivity"));
        assertFalse(StatusBarSurfaceContext.navigatorWindowAfterStateChange(
                true,
                "com.example.music",
                "com.example.music.PlayerActivity"));
    }

    @Test public void windowTokenSurvivesUnderlyingHomeUntilHomeRegainsFocus() {
        Set<String> windowOnly = Collections.singleton(
                StatusBarSurfaceContext.NAVIGATOR_WINDOW);
        try {
            // Start from an unambiguous HOME-not-foreground state.
            StatusBarSurfaceContext.setLauncherHomeForeground(true);
            StatusBarSurfaceContext.setLauncherHomeForeground(false);

            // Window launch and the subsequent HOME focus-loss. ECARX may spuriously call HOME
            // onResume between these transitions; LauncherActivity deliberately publishes no
            // surface state from that callback.
            StatusBarSurfaceContext.setNavigatorWindowForeground(true);
            StatusBarSurfaceContext.setLauncherHomeForeground(false);
            assertTrue(StatusBarSurfaceContext.isNavigatorWindowForeground());
            assertTrue(StatusBarSurfaceContext.matches(
                    windowOnly, "ru.yandex.yandexnavi", false));

            // Closing the freeform task returns actual window focus to HOME and clears the token.
            StatusBarSurfaceContext.setLauncherHomeForeground(true);
            assertFalse(StatusBarSurfaceContext.isNavigatorWindowForeground());
            assertFalse(StatusBarSurfaceContext.matches(
                    windowOnly, "ru.natro.statuswidget", true));
        } finally {
            StatusBarSurfaceContext.setLauncherHomeForeground(true);
            StatusBarSurfaceContext.setLauncherHomeForeground(false);
        }
    }

    @Test public void pickerAndLauncherUseAnExplicitLifecycleSurface() throws Exception {
        String picker = source("AppSelectionActivity.java");
        String brickPicker = source("BrickListAdapter.java");
        String launcher = source("LauncherActivity.java");
        String widget = source("WidgetService.java");
        String windowLauncher = source("launcher/YandexWindowLauncher.java");

        assertTrue(picker.contains("addLauncherHomeEntry(pm, seen, result)"));
        assertTrue(picker.contains("StatusBarSurfaceContext.LAUNCHER_HOME"));
        assertTrue(picker.contains("StatusBarSurfaceContext.NAVIGATOR_WINDOW"));
        assertTrue(picker.contains("app_selection_navigator_window"));
        int openPicker = brickPicker.indexOf("private void openHideInApps(BrickType type)");
        int nextMethod = brickPicker.indexOf("private String brickTitleString", openPicker);
        String hidePicker = brickPicker.substring(openPicker, nextMethod);
        assertFalse(hidePicker.contains("Permissions.isUsageAccessGranted"));
        assertTrue(launcher.contains("StatusBarSurfaceContext.setLauncherHomeForeground(false)"));
        int resume = launcher.indexOf("protected void onResume()");
        int pause = launcher.indexOf("protected void onPause()", resume);
        String resumeBody = launcher.substring(resume, pause);
        assertFalse(resumeBody.contains("setLauncherHomeForeground("));
        int focus = launcher.indexOf("public void onWindowFocusChanged(boolean hasFocus)");
        assertTrue(focus > resume);
        assertTrue(launcher.indexOf("setLauncherHomeForeground(hasFocus)", focus) > focus);
        assertTrue(launcher.indexOf("setLauncherHomeForeground(false)", pause) > pause);
        int topSurface = widget.indexOf("private boolean isLauncherHomeTopSurface()");
        int matcher = widget.indexOf("private boolean matchesForegroundContext", topSurface);
        String lifecycleGate = widget.substring(topSurface, matcher);
        assertTrue(lifecycleGate.contains(
                "return StatusBarSurfaceContext.isLauncherHomeForeground();"));
        assertFalse(lifecycleGate.contains("lastForegroundPackage"));
        assertTrue(widget.contains(
                "if (Looper.myLooper() == Looper.getMainLooper()) update.run()"));
        assertTrue(widget.contains(
                "!StatusBarSurfaceContext.isYandexPackage(latestPackage)"));
        assertTrue(windowLauncher.contains(
                "StatusBarSurfaceContext.setNavigatorWindowForeground(windowed)"));
    }

    @Test public void routeDeepLinksPublishOnlyTheirExplicitSurface() throws Exception {
        String routeLauncher = source("launcher/routes/YandexRouteLauncher.java");
        String windowLauncher = source("launcher/YandexWindowLauncher.java");

        // A normal route and the fallback after a failed floating-window handoff are both
        // full-screen. Only the delayed deep link delivered into an already-open floating task
        // may identify itself as the independent "Navigator in window" surface.
        assertTrue(occurrences(routeLauncher, "alternateDeepLink, false)") == 2);
        assertTrue(occurrences(routeLauncher, "alternateDeepLink, true)") == 1);
        assertTrue(routeLauncher.contains("alternateDeepLink, windowed)"));
        assertTrue(occurrences(windowLauncher,
                "public static boolean launchDeepLink(") == 1);
        assertTrue(windowLauncher.contains(
                "@NonNull Uri deepLink,\n                                         boolean windowed)"));
        assertTrue(windowLauncher.contains(".putExtra(\"ddnavwin\", windowed)"));
        assertTrue(windowLauncher.contains(
                "if (!windowed) intent.putExtra(\"ddnavforcewinfull\", true)"));
    }

    @Test public void customMainElementsOpenTheSameSurfaceTargetPicker() throws Exception {
        String picker = source("AppSelectionActivity.java");
        String editor = source("VisualBrickEditorActivity.java");
        String preferences = source("Preferences.java");

        assertTrue(picker.contains("EXTRA_MAIN_BRICK_ID"));
        assertTrue(picker.contains("config.hideInPackages.addAll(targets)"));
        assertTrue(editor.contains("AppSelectionActivity.EXTRA_MAIN_BRICK_ID"));
        assertTrue(editor.contains("«Навигатор в окне»"));
        assertTrue(preferences.contains("migrateNavigatorWindowSurfaceIfNeeded()"));
        assertTrue(preferences.contains("navigatorWindowSurfaceHa1219"));
        assertTrue(preferences.contains(
                "hidden.put(StatusBarSurfaceContext.NAVIGATOR_WINDOW)"));
    }

    @Test public void oldLauncherToggleIsInertAndGlobalSystemUiHasItsOwnControl()
            throws Exception {
        String widget = source("WidgetService.java");
        String settings = source("LauncherSettingsActivity.java");
        String main = source("MainActivity.java");
        String legacyCleanup = source("launcher/EcarxSystemStatusBarPolicy.java");

        assertFalse(widget.contains("prefs.launcherHideSystemStatusBar.get()"));
        assertFalse(settings.contains("launcherHideSystemStatusBar"));
        assertTrue(main.contains("setupStockSystemContentSwitch()"));
        assertTrue(main.contains("SystemStatusBarContentPolicy.apply(this, enabled"));
        assertFalse(settings.contains("EcarxSystemStatusBarPolicy.apply(this, checked"));
        assertFalse(legacyCleanup.contains("immersive.status=*"));
        assertFalse(legacyCleanup.contains("launcherHideSystemStatusBar.set(enabled)"));
        assertTrue(legacyCleanup.contains("launcherSystemStatusBarOriginalPolicy.set(UNSET)"));
    }

    @Test public void androidNineAcceptsFreeformWindowLifecycleWithoutTreeTraversal()
            throws Exception {
        String accessibility = source("WidgetAccessibilityService.java");
        int method = accessibility.indexOf(
                "private boolean publishAndroidNineForegroundEvent(");
        int end = accessibility.indexOf(
                "private void publishNavigatorWindowSurfaceEvent(", method);
        String windowPublishing = accessibility.substring(method, end);

        assertTrue(windowPublishing.contains("AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED"));
        assertTrue(windowPublishing.contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED"));
        assertTrue(windowPublishing.contains("NavigationDataRepository.isYandexPackage"));
        assertFalse(windowPublishing.contains("getWindows()"));
        assertTrue(accessibility.contains("publishNavigatorWindowSurfaceEvent("));
        assertTrue(accessibility.contains(
                "StatusBarSurfaceContext.isNavigatorWindow(packageName, className)"));
        assertTrue(accessibility.contains(
                "StatusBarSurfaceContext.navigatorWindowAfterStateChange("));
        assertFalse(accessibility.contains(
                "StatusBarSurfaceContext.isFullscreenYandex(packageName, className)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
