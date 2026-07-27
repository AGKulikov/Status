/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DriverInformationGroupContractTest {
    @Test public void namedInformationRowsExposeCompleteHorizontalLayoutControls()
            throws Exception {
        String settings = read("dezz/status/widget/DriverPanelSettingsActivity.java");
        String overlay = read(
                "dezz/status/widget/driver/DriverPanelOverlayController.java");
        String store = read("dezz/status/widget/launcher/LauncherShortcutStore.java");

        assertTrue(settings.contains("Объединить в горизонтальный ряд"));
        assertTrue(settings.contains("Распределение в ряду"));
        assertTrue(settings.contains("Внешние отступы ряда"));
        assertTrue(settings.contains("Внутренние отступы ряда"));
        assertTrue(settings.contains("addPreviewInformationRows"));
        assertTrue(overlay.contains("row.setOrientation(LinearLayout.HORIZONTAL)"));
        assertTrue(overlay.contains("informationGroupDistribution == 1"));
        assertTrue(overlay.contains("scroll.setVerticalScrollBarEnabled(rows.size() > 3)"));
        assertTrue(store.contains("informationGroupMarginLeftPx"));
        assertTrue(store.contains("informationGroupPaddingBottomPx"));
        assertTrue(store.contains("moveInformationGroupItem"));
        assertTrue(store.contains("moveInformationGroup("));
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++, current = current.getParent()) {
            Path candidate = current.resolve("app/src/main/java").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Source not found: " + relative);
    }
}
