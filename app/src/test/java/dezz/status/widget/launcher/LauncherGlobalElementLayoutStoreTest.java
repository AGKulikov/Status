/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LauncherGlobalElementLayoutStoreTest {
    @Test
    public void geometryIsClampedOnlyByTheWholeLauncher() {
        LauncherGlobalElementLayoutStore.Geometry value =
                LauncherGlobalElementLayoutStore.clamp(
                        new LauncherGlobalElementLayoutStore.Geometry(
                                1_700, 900, 420, 220),
                        1_920, 1_080);

        assertEquals(1_500, value.x);
        assertEquals(860, value.y);
        assertEquals(420, value.width);
        assertEquals(220, value.height);
    }

    @Test
    public void smallElementsAreNotForcedToOldPanelMinimums() {
        LauncherGlobalElementLayoutStore.Geometry value =
                LauncherGlobalElementLayoutStore.clamp(
                        new LauncherGlobalElementLayoutStore.Geometry(
                                40, 50, 12, 9),
                        1_920, 1_080);

        assertEquals(40, value.x);
        assertEquals(50, value.y);
        assertEquals(36, value.width);
        assertEquals(28, value.height);
    }

    @Test
    public void deepAppearanceDefaultsToNonDistortingFitAndFreeFrame() {
        LauncherGlobalElementLayoutStore.Appearance value =
                new LauncherGlobalElementLayoutStore.Appearance();

        assertEquals(LauncherGlobalElementLayoutStore.ScaleMode.FIT, value.scaleMode);
        org.junit.Assert.assertFalse(value.preserveAspectRatio);
        assertEquals(LauncherGlobalElementLayoutStore.TapAction.INHERIT,
                value.tapAction);
    }
}
