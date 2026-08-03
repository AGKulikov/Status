/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract for keeping HOME climate and the floating panel independently editable. */
public final class ClimateSurfaceIsolationContractTest {
    @Test
    public void floatingControllerUsesItsOwnMigratedDocument() throws Exception {
        String preferences = source("Preferences.java");
        String overlay = source("climate/ClimatePanelOverlayController.java");
        String settings = source("ClimatePanelSettingsActivity.java");
        String store = source("launcher/climate/ClimatePanelConfigStore.java");

        assertTrue(preferences.contains("floatingClimateConfigJson"));
        assertTrue(preferences.contains("if (!prefs.contains(floatingClimateConfigJson.key))"));
        assertTrue(overlay.contains("preferences.floatingClimateConfigJson"));
        assertTrue(store.contains("Preferences.Str storage"));
        assertTrue(settings.contains("EXTRA_LAUNCHER_ONLY"));
        assertTrue(settings.contains("launcherOnly\n"
                + "                ? new ClimatePanelConfigStore(preferences)"));
        assertTrue(settings.contains("if (launcherOnly) return;"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }
}
