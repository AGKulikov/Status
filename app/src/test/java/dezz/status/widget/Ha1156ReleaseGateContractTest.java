/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Retained driver-rail regression coverage. */
public final class Ha1156ReleaseGateContractTest {
    @Test public void oneExplicitInsetCannotSwitchTheWholeDriverRailToCompactMode()
            throws Exception {
        String overlay = source("driver/DriverPanelOverlayController.java");
        String settings = source("DriverPanelSettingsActivity.java");

        assertTrue(overlay.contains("DriverControlSpacingPolicy.resolve("));
        assertTrue(settings.contains("DriverControlSpacingPolicy.resolve("));
        assertFalse(overlay.contains("boolean compactSpacing"));
        assertFalse(settings.contains("boolean compactSpacing"));
        assertTrue(overlay.contains("requestedTop[controlIndex] = DriverButtonHeightPolicy.spacingRequest("));
        assertTrue(overlay.contains("requestedBottom[controlIndex] = DriverButtonHeightPolicy.spacingRequest("));
        assertTrue(settings.contains("DriverButtonHeightPolicy.internalPadding("));
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
