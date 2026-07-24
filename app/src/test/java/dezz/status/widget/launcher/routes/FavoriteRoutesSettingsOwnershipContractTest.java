/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** The favorites detail editor must not duplicate canonical navigation-panel settings. */
public final class FavoriteRoutesSettingsOwnershipContractTest {
    @Test
    public void favoritesOnlyOwnRoutesColumnsAppearanceAndHomeGeometry() throws IOException {
        String source = source("dezz/status/widget/FavoriteRoutesSettingsActivity.java");

        assertFalse(source.contains("launcherNavigationVisible"));
        assertFalse(source.contains("launcherFavoriteRoutesVisible"));
        assertFalse(source.contains("new Intent(this, NavigationPanelSettingsActivity.class)"));
        assertTrue(source.contains("launcherFavoriteRoutesColumns"));
        assertTrue(source.contains("Размер и положение объединённой плитки на HOME"));
        assertTrue(source.contains("addColorField"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
