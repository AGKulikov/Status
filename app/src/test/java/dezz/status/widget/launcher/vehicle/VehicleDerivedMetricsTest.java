/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleDerivedMetricsTest {
    @Test public void refillUsesMillilitreFuelAndEitherCapacityScale() {
        assertEquals(20.5d, VehicleDerivedMetrics.refillLitres(43_500d, 64d), 0.0001d);
        assertEquals(20.5d, VehicleDerivedMetrics.refillLitres(43_500d, 64_000d), 0.0001d);
        assertEquals(0d, VehicleDerivedMetrics.refillLitres(70_000d, 64d), 0.0001d);
    }

    @Test public void refillFallsBackToSixtyFourLitresWithoutCarInfoCapacity() {
        double capacity = VehicleDerivedMetrics.capacityLitresOrDefault(null,
                VehicleDerivedMetrics.DEFAULT_FUEL_CAPACITY_LITRES);
        assertEquals(64d, capacity, 0d);
        assertEquals(20.5d, VehicleDerivedMetrics.refillLitres(43_500d, capacity), 0.0001d);
        assertEquals(65d, VehicleDerivedMetrics.capacityLitresOrDefault(65_000d, 64d), 0d);
        assertEquals(64d, VehicleDerivedMetrics.capacityLitresOrDefault(Double.NaN, 64d), 0d);
    }

    @Test public void parkDetectionUsesExactEcarxGearEvent() {
        assertTrue(VehicleDerivedMetrics.isPark(2_097_712d));
        assertFalse(VehicleDerivedMetrics.isPark(2_097_696d));
        assertFalse(VehicleDerivedMetrics.isPark(2_097_728d));
    }

    @Test public void turnSignalsCollapseIntoOneStableState() {
        assertEquals(VehicleDerivedMetrics.TURN_OFF,
                VehicleDerivedMetrics.turnState(0d, 0d));
        assertEquals(VehicleDerivedMetrics.TURN_LEFT,
                VehicleDerivedMetrics.turnState(1d, 0d));
        assertEquals(VehicleDerivedMetrics.TURN_RIGHT,
                VehicleDerivedMetrics.turnState(0d, 1d));
        assertEquals(VehicleDerivedMetrics.TURN_HAZARD,
                VehicleDerivedMetrics.turnState(1d, 1d));
        assertEquals("↔", VehicleDerivedMetrics.turnText(VehicleDerivedMetrics.TURN_HAZARD));
    }

    @Test public void speedLimitAcceptsLocalizedTextAndAppliesConfiguredDelta() {
        assertEquals(60d, VehicleDerivedMetrics.parseSpeedLimit("60 км/ч"), 0d);
        assertEquals(90.5d, VehicleDerivedMetrics.parseSpeedLimit("90,5"), 0d);
        assertTrue(Double.isNaN(VehicleDerivedMetrics.parseSpeedLimit("нет лимита")));
        // Raw ECARX 19.0 * 3.72 = 70.68; limit 60 + tolerance 10 => 0.68 excess.
        assertEquals(.68d, VehicleDerivedMetrics.speedExcess(19d, 60d, 10), .0001d);
        assertEquals(0d, VehicleDerivedMetrics.speedExcess(18d, 60d, 10), .0001d);
    }

    @Test public void autoHoldContractAcceptsBooleanNumberAndCanonicalStrings() {
        assertEquals(Boolean.TRUE, VehicleDerivedMetrics.booleanState(true));
        assertEquals(Boolean.FALSE, VehicleDerivedMetrics.booleanState(false));
        assertEquals(Boolean.TRUE, VehicleDerivedMetrics.booleanState(1));
        assertEquals(Boolean.FALSE, VehicleDerivedMetrics.booleanState(0L));
        assertEquals(Boolean.TRUE, VehicleDerivedMetrics.booleanState("TRUE"));
        assertEquals(Boolean.FALSE, VehicleDerivedMetrics.booleanState("0"));
        assertNull(VehicleDerivedMetrics.booleanState("on"));
        assertNull(VehicleDerivedMetrics.booleanState(null));
    }
}
