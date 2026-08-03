/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the shared media/navigation/actions editor integration around pure resize math. */
public final class PanelContentEditOverlayContractTest {
    @Test public void overlayUsesFourCornerMathAndStillDelegatesAtomicPlacement() throws IOException {
        String source = source();

        assertTrue(source.contains("PanelContentResizeMath.hitCorner"));
        assertTrue(source.contains("PanelContentResizeMath.resize("));
        assertTrue(source.contains("drawResizeHandles(canvas, scratch)"));
        assertTrue(source.contains("current.setPlacement(selectedId, column, row, columnSpan, rowSpan)"));
        assertTrue(source.contains("column = startColumn + deltaColumn"));
        assertTrue(source.contains("row = startRow + deltaRow"));
    }

    @Test public void acceptedChangesKeepPreviewAndFinalPersistenceCallbacks() throws IOException {
        String source = source();

        assertTrue(source.contains("callback.onPlacementChanged(selectedId, false)"));
        assertTrue(source.contains("callback.onPlacementChanged(selectedId, true)"));
        assertTrue(source.contains("callback.onItemClicked(selectedId)"));
        assertTrue(source.contains("requestDisallowInterceptTouchEvent(true)"));
        assertTrue(source.contains("requestDisallowInterceptTouchEvent(false)"));
    }

    private static String source() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "launcher", "panels", "PanelContentEditOverlay.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "launcher", "panels", "PanelContentEditOverlay.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
