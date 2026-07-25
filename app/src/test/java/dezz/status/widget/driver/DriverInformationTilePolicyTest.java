/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.launcher.information.InformationPanelConfig;

public final class DriverInformationTilePolicyTest {
    @Test public void enabledTilesBecomeOneOrderedColumnAboveButtons() {
        InformationPanelConfig source = new InformationPanelConfig();
        source.gapPx = 14;
        InformationPanelConfig.Item clock = InformationPanelConfig.Item.system(
                "system.time", "Время", "", "clock");
        InformationPanelConfig.Item hidden = InformationPanelConfig.Item.system(
                "system.date", "Дата", "", "calendar");
        hidden.enabled = false;
        InformationPanelConfig.Item wifi = InformationPanelConfig.Item.system(
                "system.wifi", "Wi‑Fi", "", "wifi");
        wifi.gapBeforePx = 31;
        source.mutableItems().add(clock);
        source.mutableItems().add(hidden);
        source.mutableItems().add(wifi);

        InformationPanelConfig vertical = DriverInformationTilePolicy.vertical(source);

        assertEquals(1, vertical.columns);
        assertEquals(2, vertical.rows);
        assertEquals(0, vertical.mutableItems().get(0).row);
        assertEquals(14, vertical.mutableItems().get(0).gapBeforePx);
        assertFalse(vertical.mutableItems().get(1).enabled);
        assertEquals(1, vertical.mutableItems().get(2).row);
        assertEquals(31, vertical.mutableItems().get(2).gapBeforePx);
        assertEquals(2, DriverInformationTilePolicy.enabledCount(vertical));
    }

    @Test public void itemCountIsNotCappedByDriverButtonLimit() {
        InformationPanelConfig source = new InformationPanelConfig();
        for (int index = 0; index < 24; index++) {
            source.mutableItems().add(InformationPanelConfig.Item.system(
                    "system.test." + index, "Статус " + index, "", "status"));
        }

        InformationPanelConfig vertical = DriverInformationTilePolicy.vertical(source);

        assertEquals(24, DriverInformationTilePolicy.enabledCount(vertical));
        assertEquals(24, vertical.rows);
        assertTrue(vertical.mutableItems().get(23).enabled);
    }
}
