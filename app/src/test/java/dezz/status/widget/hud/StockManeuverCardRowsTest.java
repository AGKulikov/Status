/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.*;
import org.junit.Test;

public final class StockManeuverCardRowsTest {
    private static StockManeuverCardRows card(String road, boolean showRoad,
            boolean signs, boolean auxiliary) {
        return new StockManeuverCardRows(10, 8, 290, 152, 56, 2,
                road, showRoad, signs, auxiliary);
    }

    @Test public void erinskoeRoadUsesFullWidthBelowArrowAndDistance() {
        StockManeuverCardRows rows = card("Еринское ш.", true, false, false);
        assertEquals("Еринское ш.", rows.roadText);
        assertEquals(10f, rows.nextRoad.left, .001f);
        assertEquals(290f, rows.nextRoad.right, .001f);
        assertEquals(rows.main.bottom + 2f, rows.nextRoad.top, .001f);
        assertEquals(152f, rows.nextRoad.bottom, .001f);
        assertNull(rows.signs);
        assertNull(rows.auxiliary);
    }

    @Test public void noStockRoadGivesWholeCardToArrowAndDistance() {
        for (String road : new String[]{null, "", "  "}) {
            StockManeuverCardRows rows = card(road, true, false, false);
            assertEquals("", rows.roadText);
            assertEquals(8f, rows.main.top, .001f);
            assertEquals(152f, rows.main.bottom, .001f);
            assertNull(rows.nextRoad);
        }
    }

    @Test public void userCanHideRoadWithoutLeavingAnEmptyRow() {
        StockManeuverCardRows rows = card("Еринское ш.", false, false, false);
        assertEquals("", rows.roadText);
        assertNull(rows.nextRoad);
        assertEquals(152f, rows.main.bottom, .001f);
    }

    @Test public void plainRoadReferenceDoesNotInventColoredSign() {
        StockManeuverCardRows rows = card("М2", true, false, false);
        assertEquals("М2", rows.roadText);
        assertNotNull(rows.nextRoad);
        assertNull(rows.signs);
    }

    @Test public void everyCombinationCollapsesAbsentRowsAndStaysOrdered() {
        for (int fields = 0; fields < 8; fields++) {
            boolean road = (fields & 1) != 0, signs = (fields & 2) != 0,
                    auxiliary = (fields & 4) != 0;
            StockManeuverCardRows rows = card(road ? "Еринское ш." : "", true,
                    signs, auxiliary);
            assertEquals(road, rows.nextRoad != null);
            assertEquals(signs, rows.signs != null);
            assertEquals(auxiliary, rows.auxiliary != null);
            checkBounds(rows, 10, 8, 290, 152, 2);
        }
    }

    @Test public void customFractionsAndSmallCardsKeepAllRowsInsideCard() {
        for (int percent : new int[]{-1, 20, 38, 56, 80, 200}) {
            for (float height : new float[]{0, 1, 24, 200}) {
                StockManeuverCardRows rows = new StockManeuverCardRows(
                        15, 25, 100, 25 + height, percent, 20, "Еринское ш.", true, true, true);
                checkBounds(rows, 15, 25, 100, 25 + height, Math.min(20, height / 6));
            }
        }
    }

    @Test public void distanceFractionStillControlsUserMainRow() {
        StockManeuverCardRows small = new StockManeuverCardRows(0, 0, 300, 200,
                30, 0, "Еринское ш.", true, false, false);
        StockManeuverCardRows large = new StockManeuverCardRows(0, 0, 300, 200,
                70, 0, "Еринское ш.", true, false, false);
        assertEquals(60f, small.main.bottom, .001f);
        assertEquals(140f, large.main.bottom, .001f);
        assertEquals(200f, small.nextRoad.bottom, .001f);
        assertEquals(200f, large.nextRoad.bottom, .001f);
    }

    private static void checkBounds(StockManeuverCardRows rows,
            float left, float top, float right, float bottom, float gap) {
        float previous = top;
        boolean first = true;
        for (StockManeuverCardRows.Row row : new StockManeuverCardRows.Row[]{
                rows.main, rows.nextRoad, rows.signs, rows.auxiliary}) {
            if (row == null) continue;
            assertEquals(left, row.left, .001f);
            assertEquals(right, row.right, .001f);
            assertEquals(previous + (first ? 0 : gap), row.top, .001f);
            assertTrue(row.bottom + .001f >= row.top);
            assertTrue(row.bottom <= bottom + .001f);
            previous = row.bottom;
            first = false;
        }
        assertEquals(bottom, previous, .001f);
    }
}
