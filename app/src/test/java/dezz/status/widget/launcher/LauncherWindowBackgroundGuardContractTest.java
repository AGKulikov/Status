/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps Status Widget HOME directly underneath ECARX windowed navigation. */
public final class LauncherWindowBackgroundGuardContractTest {
    @Test public void windowedLaunchRaisesLauncherBeforeYandex() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        String guard = source("dezz/status/widget/launcher/StatusHomeBackgroundGuard.java");
        int method = launcher.indexOf("private void launchYandex(");
        int end = launcher.indexOf("private void dismissAllAppsDialog()", method);
        String block = launcher.substring(method, end);
        assertTrue(block.contains("ensureLauncherTaskForeground()"));
        assertTrue(block.contains("moveTaskToFront(getTaskId()"));
        assertTrue(guard.contains("FLAG_ACTIVITY_REORDER_TO_FRONT"));
        assertTrue(guard.contains("LauncherActivity.class"));
        assertTrue(block.indexOf("ensureLauncherTaskForeground()")
                < block.indexOf("launchYandexNow(product, false"));
    }

    @Test public void manifestGrantsNormalTaskReorderPermission() throws IOException {
        String manifest = sourceManifest();
        assertTrue(manifest.contains("android.permission.REORDER_TASKS"));
    }

    @Test public void driverAndFavoriteWindowsUseTheSameHomeGuard() throws IOException {
        String window = source("dezz/status/widget/launcher/YandexWindowLauncher.java");
        String route = source(
                "dezz/status/widget/launcher/routes/YandexRouteLauncher.java");
        String driver = source(
                "dezz/status/widget/driver/DriverPanelActionExecutor.java");
        assertTrue(window.contains("launchOverStatusHome"));
        assertTrue(window.contains("StatusHomeBackgroundGuard.raise(context)"));
        assertTrue(window.contains("if (!StatusHomeBackgroundGuard.raise(context))"));
        assertTrue(route.contains("launchOverStatusHome"));
        assertTrue(route.contains("if (!scheduled)"));
        assertTrue(driver.contains("launchOverStatusHome"));
        assertTrue(driver.contains("if (!scheduled)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String sourceManifest() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "AndroidManifest.xml");
        Path fromApp = Paths.get("src", "main", "AndroidManifest.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
