/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.navigation;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the real-HOME editing and stale-bitmap lifecycle contract. */
public final class NavigationPanelEditorContractTest {
    @Test public void launcherUsesActualNavigationRectangleAndGenericOverlay()
            throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(launcher.contains("new PanelGridLayout(this)"));
        assertTrue(launcher.contains("new PanelContentEditOverlay(this)"));
        assertTrue(launcher.contains("EXTRA_EDIT_NAVIGATION_CONTENT"));
        assertTrue(launcher.contains("navigationContentEditOverlay.setEditing(enabled)"));
        assertTrue(launcher.contains(
                "if (enabled && editMode) setEditMode(false)"));
        assertTrue(launcher.contains("if (!navigationContentEditMode)"));
    }

    @Test public void maneuverAndLaneBitmapsAreRenderedAndClearedSeparately()
            throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(launcher.contains("showNavigationImage(navigationManeuverImage"));
        assertTrue(launcher.contains("showNavigationImage(navigationLanesImage"));
        assertTrue(launcher.contains("hideNavigationImage(navigationManeuverImage)"));
        assertTrue(launcher.contains("hideNavigationImage(navigationLanesImage)"));
        assertTrue(launcher.contains("clearNavigationRouteViews()"));
    }

    @Test public void bothHomeEditorsShareAllLiveSafeEdges() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(launcher.contains("LauncherSafeAreaResolver.resolveInsets"));
        assertTrue(launcher.contains("applySafeMargins(workspace, safe)"));
        assertTrue(launcher.contains("applySafeMargins(editorGrid, safe)"));
        assertTrue(launcher.contains("doneParams.topMargin = safe.top + dp(12)"));
        assertTrue(launcher.contains("navigationUiHandler.post(safeAreaRefresh)"));
    }

    @Test public void preciseSettingsLeadWithRealHomeEditor() throws IOException {
        String settings = source("dezz/status/widget/NavigationPanelSettingsActivity.java");
        assertTrue(settings.contains("Редактировать прямо на HOME"));
        assertTrue(settings.contains("EXTRA_EDIT_NAVIGATION_CONTENT"));
        assertTrue(settings.contains("\"Столбец\""));
        assertTrue(settings.contains("\"Ширина\""));
        assertTrue(settings.contains("\"Высота\""));
        assertTrue(settings.contains("\"Масштаб\""));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
