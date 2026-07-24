package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DriverClimatePresentationTest {
    @Test
    public void temperatureAlwaysHasOneDecimal() {
        assertEquals("22.0", DriverClimatePresentation.temperature(22d, true));
        assertEquals("21.5", DriverClimatePresentation.temperature(21.5d, true));
        assertEquals("—", DriverClimatePresentation.temperature(Double.NaN, true));
        assertEquals("—", DriverClimatePresentation.temperature(22d, false));
    }

}
