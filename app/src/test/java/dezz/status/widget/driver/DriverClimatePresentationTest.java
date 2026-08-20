package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DriverClimatePresentationTest {
    @Test
    public void temperatureAlwaysHasOneDecimal() {
        assertEquals("22.0", DriverClimatePresentation.temperature(22d, true));
        assertEquals("21.5", DriverClimatePresentation.temperature(21.5d, true));
        assertEquals("—", DriverClimatePresentation.temperature(Double.NaN, true));
        assertEquals("—", DriverClimatePresentation.temperature(22d, false));
    }

    @Test
    public void allSevenEcarxAirflowCombinationsBecomeIconTargets() {
        int face = DriverClimatePresentation.AIRFLOW_FACE;
        int legs = DriverClimatePresentation.AIRFLOW_LEGS;
        int windshield = DriverClimatePresentation.AIRFLOW_WINDSHIELD;

        assertEquals(face, DriverClimatePresentation.airflowTargets("Лицо"));
        assertEquals(legs, DriverClimatePresentation.airflowTargets("Ноги"));
        assertEquals(face | legs,
                DriverClimatePresentation.airflowTargets("Лицо + ноги"));
        assertEquals(windshield, DriverClimatePresentation.airflowTargets("Стекло"));
        assertEquals(face | windshield,
                DriverClimatePresentation.airflowTargets("Лицо + стекло"));
        assertEquals(legs | windshield,
                DriverClimatePresentation.airflowTargets("Ноги + стекло"));
        assertEquals(face | legs | windshield,
                DriverClimatePresentation.airflowTargets("Лицо + ноги + стекло"));
        assertEquals(0, DriverClimatePresentation.airflowTargets("AUTO"));
        assertEquals(0, DriverClimatePresentation.airflowTargets("Выкл"));
    }

    @Test
    public void confirmedAutoWinsAndFanLabelBridgesOnlyUnknownAutoState() {
        assertTrue(DriverClimatePresentation.automatic(true, true, "5"));
        assertFalse(DriverClimatePresentation.automatic(true, false, "AUTO · обычно"));
        assertTrue(DriverClimatePresentation.automatic(false, false, "AUTO · тихо"));
        assertFalse(DriverClimatePresentation.automatic(false, false, "3"));
    }

}
