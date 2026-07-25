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

/** HOME and driver rail must use one All Apps layout and leave the rail interactive. */
public final class SharedAllAppsSurfaceContractTest {
    @Test public void bothCallTheSameSurfaceFactory() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        String driver = source(
                "dezz/status/widget/driver/DriverPanelOverlayController.java");
        assertTrue(launcher.contains("LauncherAllAppsSurface.create(this, preferences)"));
        assertTrue(driver.contains("LauncherAllAppsSurface.create(context, preferences)"));
    }

    @Test public void driverRailIsRaisedAboveDrawer() throws IOException {
        String driver = source(
                "dezz/status/widget/driver/DriverPanelOverlayController.java");
        int attach = driver.indexOf("drawerWindow = new AttachedWindow(root, params, manager)");
        int raise = driver.indexOf("applyPreferences();", attach);
        assertTrue(attach >= 0 && raise > attach);
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
