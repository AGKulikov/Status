/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InstrumentValuePolicyTest {
    @Test public void normalizesKnownKx11RawValuesExactlyOnce() {
        assertEquals(37.2f, InstrumentValuePolicy.normalize("ISensor.speed", 10f), .0001f);
        assertEquals(31.5f,
                InstrumentValuePolicy.normalize("ISensor.fuel_level", 31_500f), .0001f);
        assertEquals(2_750f,
                InstrumentValuePolicy.normalize("ISensor.rpm", 2_750f), .0001f);
        assertTrue(Float.isNaN(
                InstrumentValuePolicy.normalize("ISensor.speed", Float.NaN)));
    }

    @Test public void telemetryDemandTracksOnlyVisibleElements() {
        InstrumentPanelConfig config = InstrumentPanelConfig.defaults();
        assertEquals(3, config.telemetryMetricIds().size());
        assertTrue(config.telemetryMetricIds().contains("ISensor.speed"));
        assertTrue(config.telemetryMetricIds().contains("ISensor.rpm"));
        assertTrue(config.telemetryMetricIds().contains("ISensor.gear"));
        assertFalse(config.telemetryMetricIds().contains("ISensor.fuel_level"));

        InstrumentElementConfig range = new InstrumentElementConfig("range",
                InstrumentElementType.RANGE, InstrumentStyleFamily.MINIMAL_PANORAMA);
        config.elements.add(range);
        assertTrue(config.telemetryMetricIds().contains("ISensor.range_total"));
        assertTrue(config.telemetryMetricIds().contains("ISensor.range_fuel"));
        range.enabled = false;
        assertFalse(config.telemetryMetricIds().contains("ISensor.range_total"));
    }
}
