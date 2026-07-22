/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

public final class GeelyCarTelemetryValidationTest {
    @Test public void recoveryPollUsesFastBootWindowThenContinuesSlowly() {
        assertEquals(2_000L, GeelyCarIntegration.availabilityPollDelayMillis(0));
        assertEquals(2_000L, GeelyCarIntegration.availabilityPollDelayMillis(29));
        assertEquals(30_000L, GeelyCarIntegration.availabilityPollDelayMillis(30));
        assertEquals(30_000L, GeelyCarIntegration.availabilityPollDelayMillis(Integer.MAX_VALUE));
    }

    @Test public void genericTelemetryAcceptsOnlyFiniteNonSentinelValues() {
        assertTrue(GeelyCarIntegration.isValidTelemetryValue(0f, false));
        assertTrue(GeelyCarIntegration.isValidTelemetryValue(-123.5f, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(Float.NaN, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(Float.POSITIVE_INFINITY, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(Float.NEGATIVE_INFINITY, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(Float.MIN_VALUE, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(-Float.MIN_VALUE, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(Float.MAX_VALUE, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(-Float.MAX_VALUE, false));
    }

    @Test public void knownCelsiusSensorsUseInclusivePlausibilityBounds() {
        assertTrue(GeelyCarIntegration.isValidTelemetryValue(-40f, true));
        assertTrue(GeelyCarIntegration.isValidTelemetryValue(85f, true));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(-40.01f, true));
        assertFalse(GeelyCarIntegration.isValidTelemetryValue(85.01f, true));
    }

    @Test public void integerEventSentinelsAreRejectedBeforeFloatConversion() {
        assertTrue(GeelyCarIntegration.isValidTelemetryEventValue(0, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryEventValue(Integer.MIN_VALUE, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryEventValue(Integer.MAX_VALUE, false));
    }

    @Test public void requestedMetricsAreFilteredWithoutImplicitSubscriptions() {
        Set<String> requested = new LinkedHashSet<>(Arrays.asList(
                "unknown.metric",
                "ICarInfo.fuel_capacity",
                "ISensor.fuel_level"));

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                        "ISensor.fuel_level", "ICarInfo.fuel_capacity")),
                GeelyCarIntegration.selectKnownTelemetryMetricIds(requested));
        assertTrue(GeelyCarIntegration.selectKnownTelemetryMetricIds(
                new LinkedHashSet<>()).isEmpty());
    }
}
