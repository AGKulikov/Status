package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DriverPanelLayoutPolicyTest {
    @Test
    public void oldPanelClimatePocketMatchesReferenceCoordinates() {
        DriverPanelLayoutPolicy.Layout value = DriverPanelLayoutPolicy.calculate(
                1080, 0, 0, 6, true);
        assertEquals(331, value.holeTop);
        assertEquals(480, value.holeBottom);
        assertTrue(value.hasHole());
        assertEquals(2, value.beforeCount);
        assertEquals(4, value.afterCount);
    }

    @Test
    public void pocketIsRealGapAndButtonsAreCappedAtTen() {
        DriverPanelLayoutPolicy.Layout value = DriverPanelLayoutPolicy.calculate(
                720, 8, 8, 99, true);
        assertEquals(221, value.holeTop);
        assertEquals(320, value.holeBottom);
        assertEquals(10, value.beforeCount + value.afterCount);
        assertTrue(value.beforeHeight() > 0);
        assertTrue(value.afterHeight() > 0);
    }

    @Test
    public void disablingStockClimateUsesWholeHeight() {
        DriverPanelLayoutPolicy.Layout value = DriverPanelLayoutPolicy.calculate(
                1080, 8, 12, 7, false);
        assertFalse(value.hasHole());
        assertEquals(7, value.beforeCount);
        assertEquals(0, value.afterCount);
        assertEquals(8, value.contentTop);
        assertEquals(1068, value.contentBottom);
    }

    @Test
    public void proxyTapUsesOldPanelClimateCentre() {
        DriverPanelLayoutPolicy.TapTarget left =
                DriverPanelLayoutPolicy.stockClimateTapTarget(1920, 1080, false);
        assertEquals(60, left.x);
        assertEquals(405, left.y);
        DriverPanelLayoutPolicy.TapTarget right =
                DriverPanelLayoutPolicy.stockClimateTapTarget(1920, 1080, true);
        assertEquals(1860, right.x);
        assertEquals(405, right.y);
    }
}
