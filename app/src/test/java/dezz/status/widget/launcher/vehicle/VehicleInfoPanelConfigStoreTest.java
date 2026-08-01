/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VehicleInfoPanelConfigStoreTest {
    @Test public void derivedMetricOptionsRoundTripInVersionTwoSchema() throws Exception {
        VehicleInfoPanelConfig source = new VehicleInfoPanelConfig();
        VehicleInfoPanelConfig.Metric refill = source.metric(VehicleDerivedMetrics.REFILL_FUEL_ID);
        refill.enabled = true;
        refill.refillOnlyInPark = true;
        refill.refillAutomaticCapacity = false;
        refill.refillManualCapacityLitres = 71.5d;
        VehicleInfoPanelConfig.Metric warning =
                source.metric(VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID);
        warning.enabled = true;
        warning.speedLimitThresholdKmh = 13;
        warning.speedLimitBlink = false;
        warning.speedLimitWhiteBackground = true;
        warning.speedLimitOnlyActiveRoute = false;
        warning.warningColor = "#AB1234";

        VehicleInfoPanelConfig decoded = VehicleInfoPanelConfigStore.decode(
                VehicleInfoPanelConfigStore.encode(source).toString());
        VehicleInfoPanelConfig.Metric decodedRefill =
                decoded.metric(VehicleDerivedMetrics.REFILL_FUEL_ID);
        assertTrue(decodedRefill.enabled);
        assertTrue(decodedRefill.refillOnlyInPark);
        assertFalse(decodedRefill.refillAutomaticCapacity);
        assertEquals(71.5d, decodedRefill.refillManualCapacityLitres, 0d);
        VehicleInfoPanelConfig.Metric decodedWarning =
                decoded.metric(VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID);
        assertEquals(13, decodedWarning.speedLimitThresholdKmh);
        assertFalse(decodedWarning.speedLimitBlink);
        assertTrue(decodedWarning.speedLimitWhiteBackground);
        assertFalse(decodedWarning.speedLimitOnlyActiveRoute);
        assertEquals("#AB1234", decodedWarning.warningColor);
    }

    @Test public void oldSchemaGetsSafeHumanDefaults() {
        VehicleInfoPanelConfig decoded = VehicleInfoPanelConfigStore.decode(
                "{\"version\":1,\"metrics\":[{\"id\":\"Derived.refill_fuel\","
                        + "\"enabled\":true}]}" );
        VehicleInfoPanelConfig.Metric refill =
                decoded.metric(VehicleDerivedMetrics.REFILL_FUEL_ID);
        assertTrue(refill.refillAutomaticCapacity);
        assertEquals(64d, refill.refillManualCapacityLitres, 0d);
    }
}
