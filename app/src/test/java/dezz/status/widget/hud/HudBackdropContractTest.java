/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class HudBackdropContractTest {
    @Test public void hudBackdropsHaveNoShadowAndWidgetsHaveNoImplicitSurface()
            throws Exception {
        String canvas = read("dezz/status/widget/hud/HudCanvasView.java");
        String settings = read("dezz/status/widget/HudPanelSettingsActivity.java");

        assertTrue(canvas.contains("item.type == HudElementType.BACKDROP"));
        assertTrue(canvas.contains("drawBackdrop(canvas, item, bounds, geometry)"));
        assertFalse(canvas.contains("setShadowLayer("));
        assertTrue(canvas.contains("drawStyledText(canvas, value, bounds"));
        assertTrue(settings.contains("Тень на HUD отключена"));
        assertFalse(settings.contains("Цвет фона\", item.backgroundColor"));
    }

    @Test public void removedStockCarControlCannotBeRestoredByThePanel()
            throws Exception {
        String settings = read("dezz/status/widget/HudPanelSettingsActivity.java");
        String service = read("dezz/status/widget/hud/HudPresentationService.java");
        String integration = read("dezz/status/widget/car/CarIntegration.java");

        assertFalse(settings.contains("maskStockHud"));
        assertFalse(settings.contains("Скрывать штатные машинку"));
        assertFalse(service.contains("setStockHudCarHidden"));
        assertFalse(integration.contains("setStockHudCarHidden"));
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
