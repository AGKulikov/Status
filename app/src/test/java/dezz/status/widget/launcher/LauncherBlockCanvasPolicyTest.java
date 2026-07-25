/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LauncherBlockCanvasPolicyTest {
    @Test public void independentlyPlacedBlocksUseWholeHomeCanvas() {
        assertTrue(LauncherBlockCanvasPolicy.usesWholeHome(LauncherLayoutStore.MEDIA, true));
        assertTrue(LauncherBlockCanvasPolicy.usesWholeHome(
                LauncherLayoutStore.NAVIGATION, true));
        assertTrue(LauncherBlockCanvasPolicy.usesWholeHome(LauncherLayoutStore.ACTIONS, true));
        assertTrue(LauncherBlockCanvasPolicy.usesWholeHome(
                LauncherLayoutStore.INFORMATION, true));
    }

    @Test public void compatibilityModeAndLinearBlocksKeepTheirStoredRectangle() {
        assertFalse(LauncherBlockCanvasPolicy.usesWholeHome(LauncherLayoutStore.MEDIA, false));
        assertFalse(LauncherBlockCanvasPolicy.supports(LauncherLayoutStore.APPS));
        assertFalse(LauncherBlockCanvasPolicy.supports(LauncherLayoutStore.CLOCK));
        assertFalse(LauncherBlockCanvasPolicy.supports(LauncherLayoutStore.CLIMATE));
        assertFalse(LauncherBlockCanvasPolicy.supports(LauncherLayoutStore.VEHICLE_INFO));
    }
}
