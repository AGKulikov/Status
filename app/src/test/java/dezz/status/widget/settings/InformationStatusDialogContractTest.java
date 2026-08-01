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

/** Keeps the tall Information-status editor usable on the landscape ECARX display. */
public final class InformationStatusDialogContractTest {
    @Test
    public void saveActionsStayOutsideTheScrollableForm() throws IOException {
        String source = source();

        assertTrue(source.contains("MaterialButton cancel = button(\"Отмена\")"));
        assertTrue(source.contains("MaterialButton save = button(\"Сохранить\")"));
        assertTrue(source.contains("dialogBody.addView(scroll"));
        assertTrue(source.contains("dialogBody.addView(actions"));
        assertTrue(source.contains(".setView(dialogBody)"));
        assertTrue(source.contains("save.setOnClickListener"));
        assertFalse(source.contains(".setView(scroll)"));
    }

    @Test
    public void formHeightIsBoundedByThePhysicalDisplay() throws IOException {
        String source = source();

        assertTrue(source.contains("getDisplayMetrics().heightPixels"));
        assertTrue(source.contains("screenHeight - dp(240)"));
        assertTrue(source.contains("scroll.setFillViewport(true)"));
    }

    private static String source() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "InformationPanelSettingsActivity.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status",
                "widget", "InformationPanelSettingsActivity.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
