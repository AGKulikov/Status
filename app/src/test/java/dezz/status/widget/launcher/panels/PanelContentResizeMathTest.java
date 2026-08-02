/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PanelContentResizeMathTest {
    @Test public void hitTestingRecognizesAllFourCornersAndKeepsCenterForDrag() {
        assertEquals(PanelContentResizeMath.Corner.TOP_LEFT,
                hit(4f, 4f, 120f, 90f));
        assertEquals(PanelContentResizeMath.Corner.TOP_RIGHT,
                hit(116f, 4f, 120f, 90f));
        assertEquals(PanelContentResizeMath.Corner.BOTTOM_LEFT,
                hit(4f, 86f, 120f, 90f));
        assertEquals(PanelContentResizeMath.Corner.BOTTOM_RIGHT,
                hit(116f, 86f, 120f, 90f));
        assertEquals(PanelContentResizeMath.Corner.NONE,
                hit(60f, 45f, 120f, 90f));
        assertEquals(PanelContentResizeMath.Corner.NONE,
                hit(4f, 45f, 120f, 90f));
    }

    @Test public void smallTilesStillHaveAnUnambiguousCentralDragArea() {
        assertEquals(15f, PanelContentResizeMath.handleExtent(45f, 30f), 0f);
        assertEquals(PanelContentResizeMath.Corner.TOP_LEFT,
                hit(8f, 8f, 45f, 45f));
        assertEquals(PanelContentResizeMath.Corner.BOTTOM_RIGHT,
                hit(37f, 37f, 45f, 45f));
        assertEquals(PanelContentResizeMath.Corner.NONE,
                hit(22.5f, 22.5f, 45f, 45f));
    }

    @Test public void narrowTilesKeepUsefulIndependentHorizontalAndVerticalHandles() {
        assertEquals(15f, PanelContentResizeMath.handleExtent(45f, 30f), 0f);
        assertEquals(30f, PanelContentResizeMath.handleExtent(180f, 30f), 0f);
        assertEquals(PanelContentResizeMath.Corner.TOP_LEFT,
                hit(8f, 25f, 45f, 180f));
        assertEquals(PanelContentResizeMath.Corner.NONE,
                hit(22.5f, 90f, 45f, 180f));
    }

    @Test public void everyCornerKeepsItsDiagonalAnchorFixed() {
        assertPlacement(resize(PanelContentResizeMath.Corner.TOP_LEFT, -1, -2),
                1, 0, 4, 4);
        assertPlacement(resize(PanelContentResizeMath.Corner.TOP_RIGHT, 2, -1),
                2, 1, 5, 3);
        assertPlacement(resize(PanelContentResizeMath.Corner.BOTTOM_LEFT, -2, 2),
                0, 2, 5, 4);
        assertPlacement(resize(PanelContentResizeMath.Corner.BOTTOM_RIGHT, 2, 2),
                2, 2, 5, 4);
    }

    @Test public void resizeClampsAtGridEdgesAndCannotCrossTheFixedAnchor() {
        assertPlacement(resize(PanelContentResizeMath.Corner.TOP_LEFT, -99, -99),
                0, 0, 5, 4);
        assertPlacement(resize(PanelContentResizeMath.Corner.TOP_LEFT, 99, 99),
                4, 3, 1, 1);
        assertPlacement(resize(PanelContentResizeMath.Corner.TOP_RIGHT, -99, 99),
                2, 3, 1, 1);
        assertPlacement(resize(PanelContentResizeMath.Corner.TOP_RIGHT, 99, -99),
                2, 0, 6, 4);
        assertPlacement(resize(PanelContentResizeMath.Corner.BOTTOM_LEFT, -99, -99),
                0, 2, 5, 1);
        assertPlacement(resize(PanelContentResizeMath.Corner.BOTTOM_LEFT, 99, 99),
                4, 2, 1, 5);
        assertPlacement(resize(PanelContentResizeMath.Corner.BOTTOM_RIGHT, -99, -99),
                2, 2, 1, 1);
        assertPlacement(resize(PanelContentResizeMath.Corner.BOTTOM_RIGHT, 99, 99),
                2, 2, 6, 5);
    }

    private static PanelContentResizeMath.Corner hit(
            float x, float y, float width, float height) {
        return PanelContentResizeMath.hitCorner(
                x, y, 0f, 0f, width, height, 30f);
    }

    private static PanelContentResizeMath.Result resize(
            PanelContentResizeMath.Corner corner, int deltaColumn, int deltaRow) {
        return PanelContentResizeMath.resize(
                corner, 2, 2, 3, 2, deltaColumn, deltaRow, 8, 7);
    }

    private static void assertPlacement(PanelContentResizeMath.Result actual,
                                        int column, int row, int columnSpan, int rowSpan) {
        assertEquals("column", column, actual.column);
        assertEquals("row", row, actual.row);
        assertEquals("columnSpan", columnSpan, actual.columnSpan);
        assertEquals("rowSpan", rowSpan, actual.rowSpan);
    }
}
