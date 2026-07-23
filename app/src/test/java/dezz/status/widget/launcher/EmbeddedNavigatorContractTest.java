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

/** Regression coverage for the one-package Status + Navigator integration contract. */
public final class EmbeddedNavigatorContractTest {
    @Test
    public void defaultPanelIsLargeEnoughForTheModsResizeHandle() {
        LauncherLayoutStore.Geometry geometry = LauncherLayoutStore.defaults(1920, 720)
                .get(LauncherLayoutStore.EMBEDDED_NAVIGATOR);

        assertTrue(geometry.width >= 200);
        assertTrue(geometry.height >= 200);
        assertTrue(geometry.x >= 0);
        assertTrue(geometry.y >= 0);
    }

    @Test
    public void panelUsesSameUidPreferencesAndBundledComponent() throws IOException {
        String contract = source("dezz/status/widget/launcher/EmbeddedNavigatorContract.java");

        assertTrue(contract.contains("context.getPackageName(), MAP_ACTIVITY"));
        assertTrue(contract.contains("\"overlay_prefs\""));
        assertTrue(contract.contains("\"overlay_width\""));
        assertTrue(contract.contains("\"overlay_height\""));
        assertTrue(contract.contains("\"ddnavforcewinfull\""));
    }

    @Test
    public void editingFinishesTheLiveNavigatorBeforeMovingItsFrame() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");

        assertTrue(launcher.contains("EmbeddedNavigatorRuntime.finishNavigatorActivity()"));
        assertTrue(launcher.contains("EmbeddedNavigatorContract.savePanelBounds(this, bounds)"));
        assertTrue(launcher.contains("controls.showExpand(bounds, displayId)"));
    }

    @Test
    public void mergerPinsTheExactReviewedNavigatorBase() throws IOException {
        Path rootScript = Paths.get("tools", "navigator-mod", "build-merged-mod.sh");
        Path appScript = Paths.get("..", "tools", "navigator-mod", "build-merged-mod.sh");
        Path script = Files.isRegularFile(rootScript) ? rootScript : appScript;
        String text = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);

        assertTrue(text.contains(
                "a529f4f180ce42e29b1b7b7d801d21428ed8801d65271c46b9a29cb5769b4c3b"));
        assertTrue(text.contains("status_widget_resources.apk"));
        assertTrue(text.contains("patch_navi_application.py"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
