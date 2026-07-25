/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InformationPanelFreePlacementTest {
    @Test public void editorMovesAndResizesATileAtomically() {
        InformationPanelConfig config = new InformationPanelConfig();
        config.columns = 4;
        config.rows = 4;
        InformationPanelConfig.Item first =
                InformationPanelConfig.Item.system("clock", "Часы", "", "time");
        config.add(first);
        String id = config.items().get(0).id;

        assertTrue(config.setPlacement(id, 2, 1, 2, 2));
        InformationPanelConfig.Item moved = config.find(id);
        assertTrue(moved != null && moved.column == 2 && moved.row == 1);
        assertTrue(moved != null && moved.columnSpan == 2 && moved.rowSpan == 2);
    }

    @Test public void editorRejectsOverlappingInformationTiles() {
        InformationPanelConfig config = new InformationPanelConfig();
        config.columns = 3;
        config.rows = 3;
        config.add(InformationPanelConfig.Item.system("clock", "Часы", "", "time"));
        config.add(InformationPanelConfig.Item.system("wifi", "Wi-Fi", "", "wifi"));
        InformationPanelConfig.Item first = config.items().get(0);
        InformationPanelConfig.Item second = config.items().get(1);

        assertFalse(config.setPlacement(second.id, first.column, first.row,
                first.columnSpan, first.rowSpan));
    }
}
