/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneConnectorPolicyTest {
    @Test public void normalizesHfpSixStepBatteryAndSignalToPercent() {
        assertEquals(Integer.valueOf(0), PhoneConnectorPolicy.normalizeHfpBattery(0));
        assertEquals(Integer.valueOf(60), PhoneConnectorPolicy.normalizeHfpBattery(3));
        assertEquals(Integer.valueOf(100), PhoneConnectorPolicy.normalizeHfpBattery(5));
        assertEquals(Integer.valueOf(73), PhoneConnectorPolicy.normalizeHfpBattery(73));
        assertNull(PhoneConnectorPolicy.normalizeHfpBattery(-1));
        assertNull(PhoneConnectorPolicy.normalizeHfpBattery(101));

        assertEquals(Integer.valueOf(40), PhoneConnectorPolicy.normalizeHfpSignal(2));
        assertEquals(Integer.valueOf(88), PhoneConnectorPolicy.normalizeHfpSignal(88));
        assertNull(PhoneConnectorPolicy.normalizeHfpSignal(255));
    }

    @Test public void decodesGattBatteryPowerStateChargingBits() {
        assertFalse(PhoneConnectorPolicy.decodeBatteryPowerState(2 << 4));
        assertTrue(PhoneConnectorPolicy.decodeBatteryPowerState(3 << 4));
        assertNull(PhoneConnectorPolicy.decodeBatteryPowerState(0));
        assertNull(PhoneConnectorPolicy.decodeBatteryPowerState(1 << 4));
    }

    @Test public void reconnectBackoffIsBoundedButNeverStops() {
        assertEquals(2_000L, PhoneConnectorPolicy.reconnectDelayMillis(0));
        assertEquals(5_000L, PhoneConnectorPolicy.reconnectDelayMillis(1));
        assertEquals(60_000L, PhoneConnectorPolicy.reconnectDelayMillis(5));
        assertEquals(60_000L, PhoneConnectorPolicy.reconnectDelayMillis(1_000));
    }
}
