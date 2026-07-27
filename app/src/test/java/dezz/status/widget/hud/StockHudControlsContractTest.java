/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StockHudControlsContractTest {
    @Test public void primarySettingsExposeProfileModesAndAllFiveCategories() throws Exception {
        String settings = read("dezz/status/widget/HudPanelSettingsActivity.java");
        assertTrue(settings.contains("0 · Guide"));
        assertTrue(settings.contains("3 · Simple"));
        assertTrue(settings.contains("DRIVE_ENVIRONMENT"));
        assertTrue(settings.contains("SAFETY"));
        assertTrue(settings.contains("MEDIA"));
        assertTrue(settings.contains("NAVIGATION"));
        assertTrue(settings.contains("PHONE"));
        assertTrue(settings.contains("setStockHudProfileMode"));
    }

    @Test public void fallbackWritesOnlyProfileTransferMode() throws Exception {
        String service = readGeely("dezz/status/widget/car/HudModeFallbackService.java");
        assertTrue(service.contains("CB_HudDispModSetgReq"));
        assertFalse(service.contains("setHudVisFctSetgReq("));
        assertFalse(service.contains("ManagerId_cbphudvisfctsetgreq"));
        assertFalse(service.contains("setIntProperty(33278"));
        assertFalse(service.contains("SignalId_PSET_SaveReq"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relative),
                StandardCharsets.UTF_8);
    }

    private static String readGeely(String relative) throws Exception {
        return Files.readString(Path.of("src/geely/java").resolve(relative),
                StandardCharsets.UTF_8);
    }
}
