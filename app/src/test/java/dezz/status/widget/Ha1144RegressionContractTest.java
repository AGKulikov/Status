/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guard rails for the HA1144 fixes requested on the KX11 head unit. */
public final class Ha1144RegressionContractTest {
    @Test public void bothDriverDrawersUseOneDuplicateSafeTogglePath() throws Exception {
        String overlay = source("driver/DriverPanelOverlayController.java");
        String gate = source("driver/DriverPanelToggleGate.java");

        assertTrue(overlay.contains("panelToggleGate.accept(\"all_apps\", pressToken(anchor)"));
        assertTrue(overlay.contains(
                "panelToggleGate.accept(\"favorites:\" + panelId, pressToken(anchor)"));
        assertTrue(overlay.contains("allAppsRequestedOpen || drawerWindow != null"));
        assertTrue(overlay.contains("allAppsRequestedOpen = false"));
        assertTrue(gate.contains("DUPLICATE_WINDOW_MS = 450L"));
    }

    @Test public void driverSpacingCanReachRealZeroOnEverySide() throws Exception {
        String settings = source("DriverPanelSettingsActivity.java");
        String store = source("launcher/LauncherShortcutStore.java");
        String runtime = source("driver/DriverPanelOverlayController.java");

        assertTrue(settings.contains("spacingSlider(form, \"Слева\", 96"));
        assertTrue(settings.contains("spacingSlider(form, label, 120, current"));
        assertTrue(settings.contains("seek.setProgress(0)"));
        assertFalse(settings.contains("int rowHeight = dp(42)"));
        assertTrue(store.contains("return Math.max(0, Math.min(96, value))"));
        assertTrue(store.contains("return Math.max(0, Math.min(120, value))"));
        assertTrue(runtime.contains("row.setPadding(groupPaddingLeft, groupPaddingTop"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path app = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
