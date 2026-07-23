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

/** Keeps the application stack above ECARX/Yandex freeform application windows. */
public final class LauncherAllAppsOverlayContractTest {
    @Test public void allAppsUsesInteractiveApplicationOverlayWhenPermissionExists()
            throws IOException {
        String launcher = source();
        int start = launcher.indexOf("private void showAllApps()");
        int end = launcher.indexOf("private void refreshFavorites()", start);
        String method = launcher.substring(start, end);
        assertTrue(method.contains("Permissions.checkOverlayPermission(this)"));
        assertTrue(method.contains("TYPE_APPLICATION_OVERLAY"));
        assertTrue(method.indexOf("dialogWindow.setType(") < method.indexOf("dialog.show()"));
        assertTrue(method.contains("dismissAllAppsDialog()"));
    }

    @Test public void overlayDialogIsReleasedWhenLauncherStops() throws IOException {
        String launcher = source();
        int start = launcher.indexOf("protected void onStop()");
        int end = launcher.indexOf("protected void onDestroy()", start);
        assertTrue(launcher.substring(start, end).contains("dismissAllAppsDialog()"));
    }

    private static String source() throws IOException {
        String relative = "dezz/status/widget/LauncherActivity.java";
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
