/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.hud;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the display-2 mask and overlay behavior verified from the ECARX device dump. */
public final class HudOverlayContractTest {
    @Test
    public void hardwareViewportCannotCoverOtherCompositeDisplayRegions() throws IOException {
        String viewport = source("dezz/status/widget/hud/HudViewportPolicy.java");
        String canvas = source("dezz/status/widget/hud/HudCanvasView.java");
        assertTrue(viewport.contains("SAFE_TOP = 720"));
        assertTrue(viewport.contains("SAFE_WIDTH = 728"));
        assertTrue(viewport.contains("SAFE_HEIGHT = 190"));
        assertTrue(viewport.contains("STOCK_MASK_WIDTH = 808"));
        assertTrue(viewport.contains("STOCK_MASK_HEIGHT = 266"));
        assertTrue(canvas.contains("canvas.clipRect(geometry.safeClip)"));
        assertTrue(canvas.contains("config.maskStockHud && !editor"));
        assertTrue(canvas.contains("paint.setColor(Color.BLACK)"));
    }

    @Test
    public void outputUsesTheDumpVerifiedCompactOverlayAndFallsBackToPresentation()
            throws IOException {
        String service = source("dezz/status/widget/hud/HudPresentationService.java");
        String overlay = source("dezz/status/widget/hud/HudOverlayWindow.java");
        String canvas = source("dezz/status/widget/hud/HudCanvasView.java");
        assertTrue(service.contains("Settings.canDrawOverlays(this)"));
        assertTrue(service.contains("HudOverlayWindow.show(this, display, config, data)"));
        assertTrue(service.contains("createPresentation(display)"));
        assertTrue(service.contains("currentOverlay.invalidateHud()"));
        assertTrue(overlay.contains("HudViewportPolicy.SAFE_WIDTH"));
        assertTrue(overlay.contains("HudViewportPolicy.SAFE_HEIGHT"));
        assertTrue(overlay.contains("params.x = HudViewportPolicy.SAFE_LEFT"));
        assertTrue(overlay.contains("params.y = HudViewportPolicy.SAFE_TOP"));
        assertTrue(overlay.contains(
                "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"));
        assertTrue(canvas.contains("if (localHudViewport)"));
    }

    @Test
    public void oldLayoutsMigrateToAnEnabledStockHudMask() throws IOException {
        String config = source("dezz/status/widget/hud/HudPanelConfig.java");
        assertTrue(config.contains("SCHEMA_VERSION = 4"));
        assertTrue(config.contains(
                "public int displayId = HudViewportPolicy.VERIFIED_DISPLAY_ID"));
        assertTrue(config.contains(
                "displayId = HudViewportPolicy.VERIFIED_DISPLAY_ID"));
        assertTrue(config.contains("public boolean maskStockHud = true"));
        assertTrue(config.contains(
                "schema < 4 || source.optBoolean(\"maskStockHud\", true)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
