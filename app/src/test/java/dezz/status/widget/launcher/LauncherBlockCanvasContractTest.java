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

/** Locks full-HOME placement without sacrificing the saved compact-block geometry. */
public final class LauncherBlockCanvasContractTest {
    @Test public void wholeHomeModeIsVisibleAndEnabledByDefault() throws IOException {
        String preferences = source("dezz/status/widget/Preferences.java");
        String settings = source("dezz/status/widget/LauncherSettingsActivity.java");
        assertTrue(preferences.contains("\"launcherFreeBlockCanvas\", true"));
        assertTrue(settings.contains("Свободно размещать элементы блоков по всему HOME"));
    }

    @Test public void runtimeCanvasDoesNotOverwriteCompactGeometry() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(launcher.contains("runtimeBlockGeometry"));
        assertTrue(launcher.contains("workspace.getWidth()"));
        assertTrue(launcher.contains("workspace.getHeight()"));
        assertTrue(launcher.contains("if (!usesWholeHomeCanvas(changedId))"));
        assertTrue(launcher.contains("bringBlockToFront(LauncherLayoutStore.ACTIONS)"));
        assertTrue(launcher.contains("bringBlockToFront(LauncherLayoutStore.INFORMATION)"));
    }

    @Test public void fullCanvasLeavesUnrelatedElementsTouchable() throws IOException {
        String media = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        String routes = source(
                "dezz/status/widget/launcher/routes/FavoriteRoutesPanelView.java");
        assertTrue(media.contains("layoutEditor != null || wholeHomeCanvas"));
        assertTrue(media.contains("setClickable(false)"));
        assertTrue(routes.contains("Avoids a full-screen ScrollView intercepting"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
