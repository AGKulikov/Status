/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract ensuring settings do not drift back to unrelated HEX/list pickers. */
public final class AppleColorPickerIntegrationContractTest {
    private static final String[] COLOR_SCREENS = {
            "MediaPanelSettingsActivity.java",
            "ClimatePanelSettingsActivity.java",
            "VehicleInfoPanelSettingsActivity.java",
            "PopupSettingsActivity.java",
            "VisualBrickEditorActivity.java",
            "ScenarioSettingsActivity.java",
            "FavoriteRoutesSettingsActivity.java",
            "InformationPanelSettingsActivity.java",
            "LauncherShortcutSettingsActivity.java",
            "LauncherSettingsActivity.java"
    };

    @Test
    public void everyAuditedScreenUsesTheSharedVisualPicker() throws IOException {
        for (String screen : COLOR_SCREENS) {
            String source = source("dezz/status/widget/" + screen);
            assertTrue(screen + " must use the shared visual picker",
                    source.contains("AppleColorPickerDialog.show("));
            assertFalse(screen + " must not expose a raw custom-color dialog",
                    source.contains("Свой HEX-цвет"));
            assertFalse(screen + " must not expose a raw custom-color list item",
                    source.contains("Свой цвет…"));
        }
    }

    @Test
    public void semanticNoneAndInheritStatesHaveDedicatedModes() throws IOException {
        String shortcuts = source("dezz/status/widget/LauncherShortcutSettingsActivity.java");
        String bricks = source("dezz/status/widget/VisualBrickEditorActivity.java");

        assertTrue(shortcuts.contains("AppleColorPickerDialog.Options.noTint()"));
        assertTrue(bricks.contains("AppleColorPickerDialog.Options.inheritable()"));
    }

    @Test
    public void informationPanelUsesVisualColorControlsOnly() throws IOException {
        String information = source(
                "dezz/status/widget/InformationPanelSettingsActivity.java");

        assertFalse(information.contains("Цвет значения, HEX"));
        assertFalse(information.contains("Цвет подписи, HEX"));
        assertFalse(information.contains("Цвет иконки, HEX"));
        assertFalse(information.contains("requireColor("));
        assertTrue(information.contains("AppleColorPickerDialog.Options.opaque()"));
    }

    @Test
    public void pickerContainsPalettePrecisionAndAccessibilityControls() throws IOException {
        String source = source("dezz/status/widget/settings/AppleColorPickerDialog.java");

        assertTrue(source.contains("class PreviewView"));
        assertTrue(source.contains("GridLayout palette"));
        assertTrue(source.contains("\"Оттенок\""));
        assertTrue(source.contains("\"Насыщенность\""));
        assertTrue(source.contains("\"Яркость\""));
        assertTrue(source.contains("\"Непрозрачность\""));
        assertTrue(source.contains("\"ТОЧНОЕ ЗНАЧЕНИЕ\""));
        assertTrue(source.contains("setContentDescription"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
