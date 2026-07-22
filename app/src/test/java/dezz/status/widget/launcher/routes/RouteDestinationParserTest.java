package dezz.status.widget.launcher.routes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RouteDestinationParserTest {
    @Test public void buildsSinglePoint() {
        assertEquals("~55.7558,37.6176",
                RouteDestinationParser.coordinateRouteText("55.7558, 37.6176"));
    }

    @Test public void buildsMultipleWaypoints() {
        assertEquals("~55.75,37.61~55.71,37.55",
                RouteDestinationParser.coordinateRouteText(
                        "55.7500,37.6100  55.71,37.55"));
    }

    @Test public void addressIsUsedWhenCoordinatesAreEmpty() {
        assertEquals("Москва, Тверская 1",
                RouteDestinationParser.routeText("  Москва, Тверская 1  ", ""));
    }

    @Test public void validatesRangesAndGarbage() {
        assertFalse(RouteDestinationParser.hasDestination("", "91,37"));
        assertFalse(RouteDestinationParser.hasDestination("", "55,37 garbage"));
        assertTrue(RouteDestinationParser.hasDestination("", "-90,-180; 90,180"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHalfCoordinate() {
        RouteDestinationParser.coordinateRouteText("55.75");
    }
}
