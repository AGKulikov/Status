/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test public void bas11FallbackExtractsOnlyOptionalPercentage() {
        assertEquals(Integer.valueOf(73),
                PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(new byte[] {
                        0x02, 0x00, 0x00, 73
                }));
        assertEquals(Integer.valueOf(88),
                PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(new byte[] {
                        0x03, 0x00, 0x00, 0x34, 0x12, 88
                }));
        assertNull(PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(
                new byte[] {0x00, 0x7f, 0x7f}));
        assertNull(PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(
                new byte[] {0x02, 0x00, 0x00}));
        assertNull(PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(
                new byte[] {0x02, 0x00, 0x00, (byte) 101}));
    }

    @Test public void reconnectBackoffIsBoundedButNeverStops() {
        assertEquals(2_000L, PhoneConnectorPolicy.reconnectDelayMillis(0));
        assertEquals(5_000L, PhoneConnectorPolicy.reconnectDelayMillis(1));
        assertEquals(60_000L, PhoneConnectorPolicy.reconnectDelayMillis(5));
        assertEquals(60_000L, PhoneConnectorPolicy.reconnectDelayMillis(1_000));
    }

    @Test public void stockOwnerGetsBoundedRetriesAndASettleWindowBeforeGatt() {
        assertEquals(3, PhoneConnectorPolicy.stockConnectionMaxAttempts());
        assertEquals(1_000L, PhoneConnectorPolicy.stockConnectionRetryDelayMillis(0));
        assertEquals(2_500L, PhoneConnectorPolicy.stockConnectionRetryDelayMillis(1));
        assertEquals(2_500L, PhoneConnectorPolicy.stockConnectionRetryDelayMillis(100));
        assertEquals(2_500L, PhoneConnectorPolicy.stockConnectionSettleMillis());
    }
}
