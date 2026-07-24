/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Built-in appearance presets must never erase unrelated HOME, climate or automation keys. */
public final class PresetMergeContractTest {
    @Test
    public void bundledThemesMergeWhileBackupsStillReplace() throws IOException {
        String preferences = source("Preferences.java");
        String presets = source("PresetsActivity.java");

        assertTrue(preferences.contains("applyPatchFromJson(String json)"));
        assertTrue(preferences.contains("applyJson(json, false)"));
        assertTrue(preferences.contains("applyJson(json, true)"));
        assertTrue(preferences.contains("if (clearExisting) editor.clear();"));
        assertTrue(presets.contains("if (entry.bundled != null)"));
        assertTrue(presets.contains("prefs.applyPatchFromJson(json)"));
        assertTrue(presets.contains("prefs.importFromJson(json)"));
        assertTrue(presets.contains("ClimatePanelService.apply(this)"));
    }

    private static String source(String name) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java",
                "dezz", "status", "widget", name);
        Path fromApp = Paths.get("src", "main", "java",
                "dezz", "status", "widget", name);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
