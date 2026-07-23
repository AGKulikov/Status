/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CarTelemetryDescriptorTest {
    @Test public void preservesConnectorNeutralCatalogFields() {
        CarTelemetryDescriptor value = new CarTelemetryDescriptor(
                "ISensor.gear", "Передача", "event", true, 0L);
        assertEquals("ISensor.gear", value.id);
        assertEquals("Передача", value.label);
        assertEquals("event", value.unit);
        assertTrue(value.streaming);
        assertEquals(0L, value.staleAfterMillis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeStaleTimeoutIsRejected() {
        new CarTelemetryDescriptor("metric", "Metric", "raw", false, -1L);
    }

    @Test public void staticMetricCanRemainAuthoritative() {
        CarTelemetryDescriptor value = new CarTelemetryDescriptor(
                "ICarInfo.fuel_capacity", "Бак", "raw", false, 0L);
        assertFalse(value.streaming);
    }
}
