/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

public final class LauncherActionsGridConfigStoreTest {
    @Test public void staticEncodeDecodeRoundTripPreservesGridAndStableCells()
            throws JSONException {
        LauncherActionsGridConfig source = new LauncherActionsGridConfig();
        source.columns = 4;
        source.rows = 3;
        source.gapPx = 17;
        source.put(new LauncherActionsGridConfig.Placement("visible", 0, 0, 2, 1));
        source.put(new LauncherActionsGridConfig.Placement(
                LauncherActionsGridConfig.ADD_TILE_ID, 2, 0, 1, 1));
        source.put(new LauncherActionsGridConfig.Placement("hidden", 0, 1, 3, 2));

        LauncherActionsGridConfigStore.DecodeResult decoded =
                LauncherActionsGridConfigStore.decode(
                        LauncherActionsGridConfigStore.encode(source).toString());

        assertTrue(decoded.valid);
        assertFalse(decoded.missing);
        assertNotNull(decoded.value);
        assertEquals(4, decoded.value.columns);
        assertEquals(3, decoded.value.rows);
        assertEquals(17, decoded.value.gapPx);
        assertEquals(3, decoded.value.placements().size());
        assertPlacement(decoded.value.placements().get(0), "visible", 0, 0, 2, 1);
        assertPlacement(decoded.value.placements().get(1),
                LauncherActionsGridConfig.ADD_TILE_ID, 2, 0, 1, 1);
        assertPlacement(decoded.value.placements().get(2), "hidden", 0, 1, 3, 2);
    }

    @Test public void corruptAndFutureDocumentsFailClosedWithoutDefaults() {
        assertInvalid(LauncherActionsGridConfigStore.decode("{oops"));
        assertInvalid(LauncherActionsGridConfigStore.decode(
                "{\"version\":2,\"columns\":3,\"rows\":1,\"gapPx\":8,"
                        + "\"placements\":[]}"));
        assertInvalid(LauncherActionsGridConfigStore.decode(
                "{\"version\":1,\"columns\":3,\"rows\":1,\"gapPx\":8}"));
        assertInvalid(LauncherActionsGridConfigStore.decode(
                "{\"version\":1,\"columns\":3,\"rows\":1,\"gapPx\":8,"
                        + "\"placements\":[null]}"));
        assertInvalid(LauncherActionsGridConfigStore.decode(
                "{\"version\":1,\"columns\":3,\"rows\":1,\"gapPx\":8,"
                        + "\"placements\":[{\"id\":\"duplicate\",\"column\":0,\"row\":0,"
                        + "\"columnSpan\":1,\"rowSpan\":1},{\"id\":\"duplicate\","
                        + "\"column\":1,\"row\":0,\"columnSpan\":1,\"rowSpan\":1}]}"));
    }

    @Test public void emptyDocumentIsMissingRatherThanValidOrCorrupt() {
        LauncherActionsGridConfigStore.DecodeResult decoded =
                LauncherActionsGridConfigStore.decode("  ");

        assertTrue(decoded.missing);
        assertFalse(decoded.valid);
        assertNull(decoded.value);
    }

    private static void assertInvalid(LauncherActionsGridConfigStore.DecodeResult decoded) {
        assertFalse(decoded.missing);
        assertFalse(decoded.valid);
        assertNull(decoded.value);
    }

    private static void assertPlacement(LauncherActionsGridConfig.Placement placement,
                                        String id, int column, int row,
                                        int columnSpan, int rowSpan) {
        assertEquals(id, placement.id);
        assertEquals(column, placement.column);
        assertEquals(row, placement.row);
        assertEquals(columnSpan, placement.columnSpan);
        assertEquals(rowSpan, placement.rowSpan);
    }
}
