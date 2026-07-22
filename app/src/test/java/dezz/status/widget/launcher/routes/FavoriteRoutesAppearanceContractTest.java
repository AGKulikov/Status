/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the visible Home/Work icon-size slider on the head-unit settings screen. */
public final class FavoriteRoutesAppearanceContractTest {
    @Test
    public void sliderRepaintsPreviewImmediatelyAndRendererUsesExactPixels() throws IOException {
        String settings = source("dezz/status/widget/FavoriteRoutesSettingsActivity.java");
        String panel = source("dezz/status/widget/launcher/routes/FavoriteRoutesPanelView.java");

        assertTrue(settings.contains("route.iconSizePx = value;\n            previewAndPersist();"));
        assertTrue(settings.contains("private void previewAndPersist()"));
        assertTrue(panel.contains("new LinearLayout.LayoutParams(iconSize, iconSize)"));
        assertTrue(panel.contains("icon.setMaxWidth(iconSize)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
