/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClimatePanelConfigTest {
    @Test
    public void defaultsExposeEveryClimateFunction() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            assertTrue(element.id, config.isElementEnabled(element.id));
        }
    }

    @Test
    public void copyKeepsIndependentElementSelection() {
        ClimatePanelConfig original = new ClimatePanelConfig();
        original.setElementEnabled(ClimatePanelConfig.WHEEL_HEAT, false);
        ClimatePanelConfig copy = original.copy();
        copy.setElementEnabled(ClimatePanelConfig.WHEEL_HEAT, true);
        assertFalse(original.isElementEnabled(ClimatePanelConfig.WHEEL_HEAT));
        assertTrue(copy.isElementEnabled(ClimatePanelConfig.WHEEL_HEAT));
    }

    @Test
    public void normalizeClampsVisualValuesAndRepairsColors() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        config.backgroundAlpha = 900;
        config.cornerRadiusPx = -3;
        config.scalePercent = 20;
        config.accentColor = "wrong";
        config.normalize();
        assertEquals(255, config.backgroundAlpha);
        assertEquals(0, config.cornerRadiusPx);
        assertEquals(60, config.scalePercent);
        assertEquals("#35B7FF", config.accentColor);
    }
}
