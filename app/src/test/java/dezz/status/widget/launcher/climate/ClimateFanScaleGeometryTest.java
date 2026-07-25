/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ClimateFanScaleGeometryTest {
    @Test
    public void manualUsesEveryFixedSlot() {
        int[] slots = new int[9];
        for (int index = 0; index < slots.length; index++) {
            slots[index] = ClimateFanScaleGeometry.physicalSlot(index, 9);
        }
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, slots);
    }

    @Test
    public void autoKeepsTheSameEdgesAndBarGeometry() {
        int[] slots = new int[5];
        for (int index = 0; index < slots.length; index++) {
            slots[index] = ClimateFanScaleGeometry.physicalSlot(index, 5);
        }
        assertArrayEquals(new int[]{0, 2, 4, 6, 8}, slots);
        assertEquals(ClimateFanIndicatorPolicy.MANUAL_SEGMENTS,
                ClimateFanScaleGeometry.PHYSICAL_SLOTS);
    }
}
