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

    @Test public void decodesBas11BatteryLevelStatusAndOptionalLevel() {
        // Flags: battery level present. Power: present, wired source, charging, good.
        int power = 1 | 1 << 1 | 1 << 5 | 1 << 7;
        PhoneConnectorPolicy.BatteryLevelStatus charging =
                PhoneConnectorPolicy.decodeBatteryLevelStatus(new byte[] {
                        0x02, (byte) power, (byte) (power >>> 8), 73
                });
        assertEquals(Integer.valueOf(73), charging.level);
        assertEquals(Boolean.TRUE, charging.charging);
        assertEquals(Boolean.TRUE, charging.externalPower);
        assertEquals("charging", charging.chargeState);
        assertEquals("good", charging.chargeLevel);

        // Active discharge and no external source.
        power = 1 | 2 << 5 | 2 << 7;
        PhoneConnectorPolicy.BatteryLevelStatus discharging =
                PhoneConnectorPolicy.decodeBatteryLevelStatus(new byte[] {
                        0x00, (byte) power, (byte) (power >>> 8)
                });
        assertNull(discharging.level);
        assertEquals(Boolean.FALSE, discharging.charging);
        assertEquals(Boolean.FALSE, discharging.externalPower);
        assertEquals("discharging", discharging.chargeState);
        assertEquals("low", discharging.chargeLevel);

        assertNull(PhoneConnectorPolicy.decodeBatteryLevelStatus(new byte[] {0x02, 0, 0}));
        assertNull(PhoneConnectorPolicy.decodeBatteryLevelStatus(new byte[] {0x01, 0, 0, 0}));
    }

    @Test public void basPowerStateKeepsExternalPowerSeparateFromActiveCharging() {
        // Wired power remains connected while a full battery is discharging only by leakage.
        int pluggedIdle = 1 | 1 << 1 | 3 << 5 | 1 << 7;
        PhoneConnectorPolicy.BatteryLevelStatus idle =
                PhoneConnectorPolicy.decodeBatteryLevelStatus(new byte[] {
                        0x00, (byte) pluggedIdle, (byte) (pluggedIdle >>> 8)
                });
        assertEquals(Boolean.FALSE, idle.charging);
        assertEquals(Boolean.TRUE, idle.externalPower);
        assertEquals("idle", idle.chargeState);

        // A charger may be known while the battery charge state itself is still unknown.
        int pluggedUnknown = 1 | 1 << 1;
        PhoneConnectorPolicy.BatteryLevelStatus unknown =
                PhoneConnectorPolicy.decodeBatteryLevelStatus(new byte[] {
                        0x00, (byte) pluggedUnknown, (byte) (pluggedUnknown >>> 8)
                });
        assertNull(unknown.charging);
        assertEquals(Boolean.TRUE, unknown.externalPower);
    }

    @Test public void decodesOnlyExplicitAndroidBluetoothChargingMetadata() {
        assertEquals(Boolean.TRUE, PhoneConnectorPolicy.decodeBluetoothChargingMetadata(
                "true".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(Boolean.TRUE, PhoneConnectorPolicy.decodeBluetoothChargingMetadata(
                "charging".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(Boolean.FALSE, PhoneConnectorPolicy.decodeBluetoothChargingMetadata(
                "0".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(Boolean.FALSE, PhoneConnectorPolicy.decodeBluetoothChargingMetadata(
                "not charging".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(PhoneConnectorPolicy.decodeBluetoothChargingMetadata(
                "unknown".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(PhoneConnectorPolicy.decodeBluetoothChargingMetadata(null));
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
