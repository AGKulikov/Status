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
        assertFalse(GeelyCarIntegration.isValidTelemetryEventValue(-1, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryEventValue(Integer.MIN_VALUE, false));
        assertFalse(GeelyCarIntegration.isValidTelemetryEventValue(Integer.MAX_VALUE, false));
    }

    @Test public void tirePressureAcceptsBarAndHundredthsOfBar() {
        assertEquals(2.4f, GeelyCarIntegration.normalizeTirePressureBar(2.4f), 0.0001f);
        assertEquals(2.4f, GeelyCarIntegration.normalizeTirePressureBar(240f), 0.0001f);
        assertTrue(Float.isNaN(GeelyCarIntegration.normalizeTirePressureBar(0f)));
        assertTrue(Float.isNaN(GeelyCarIntegration.normalizeTirePressureBar(Float.MAX_VALUE)));
    }

    @Test public void tireTemperatureUsesASeparateHigherSafetyBound() {
        assertTrue(GeelyCarIntegration.isValidTireTemperature(-40f));
        assertTrue(GeelyCarIntegration.isValidTireTemperature(150f));
        assertFalse(GeelyCarIntegration.isValidTireTemperature(150.1f));
        assertFalse(GeelyCarIntegration.isValidTireTemperature(Float.NaN));
    }

    @Test public void bcmStateExposesOnlyBinaryOffAndOn() {
        assertEquals(0, GeelyCarIntegration.normalizeBcmBinaryValue(0));
        assertEquals(1, GeelyCarIntegration.normalizeBcmBinaryValue(1));
        assertEquals(-1, GeelyCarIntegration.normalizeBcmBinaryValue(2));
        assertEquals(-1, GeelyCarIntegration.normalizeBcmBinaryValue(253));
        assertEquals(-1, GeelyCarIntegration.normalizeBcmBinaryValue(255));
    }

    @Test public void turnSignalDarkPhaseIsHeldButRealOffEventuallyWins() {
        assertEquals(1, GeelyCarIntegration.stabilizeTurnSignalValue(1, -1L, 5_000L));
        assertEquals(1, GeelyCarIntegration.stabilizeTurnSignalValue(0, 5_000L, 5_900L));
        assertEquals(0, GeelyCarIntegration.stabilizeTurnSignalValue(0, 5_000L, 6_001L));
        assertEquals(0, GeelyCarIntegration.stabilizeTurnSignalValue(0, -1L, 5_000L));
        assertEquals(-1, GeelyCarIntegration.stabilizeTurnSignalValue(2, 5_000L, 5_100L));
    }

    @Test public void lowLevelSignalPriorityExpiresAfterCallbackSilence() {
        assertTrue(GeelyCarIntegration.isFreshLowLevelSample(10_000L, 10_000L));
        assertTrue(GeelyCarIntegration.isFreshLowLevelSample(10_000L, 25_000L));
        assertFalse(GeelyCarIntegration.isFreshLowLevelSample(10_000L, 25_001L));
        assertFalse(GeelyCarIntegration.isFreshLowLevelSample(0L, 10_000L));
        assertFalse(GeelyCarIntegration.isFreshLowLevelSample(10_001L, 10_000L));
    }

    @Test public void requestedMetricsAreFilteredWithoutImplicitSubscriptions() {
        Set<String> requested = new LinkedHashSet<>(Arrays.asList(
                "unknown.metric",
                "ICarInfo.fuel_capacity",
                "ISensor.fuel_level",
                "ISensor.avg_fuel_consumption",
                "ISensor.gear",
                "TPMS.pressure.front_left",
                "IBcm.high_beam",
                "IBcm.turn_signal_right",
                "External.auto_hold"));

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                        "ISensor.fuel_level", "ISensor.avg_fuel_consumption",
                        "ISensor.gear", "ICarInfo.fuel_capacity",
                        "TPMS.pressure.front_left", "IBcm.high_beam",
                        "IBcm.turn_signal_right", "External.auto_hold")),
                GeelyCarIntegration.selectKnownTelemetryMetricIds(requested));
        assertTrue(GeelyCarIntegration.selectKnownTelemetryMetricIds(
                new LinkedHashSet<>()).isEmpty());
    }
}
