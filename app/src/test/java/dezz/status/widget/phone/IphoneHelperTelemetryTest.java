/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class IphoneHelperTelemetryTest {
    @Test public void parsesExactPowerStateWithoutInference() {
        IphoneHelperTelemetry value = parse("TEL2;P;60;1;C;42");
        assertEquals(IphoneHelperTelemetry.Kind.POWER, value.kind);
        assertEquals(Integer.valueOf(60), value.batteryLevel);
        assertEquals(Boolean.TRUE, value.externalPower);
        assertEquals("charging", value.chargeState);
        assertEquals(42, value.sequence);

        IphoneHelperTelemetry unplugged = parse("TEL2;P;-;0;U;43");
        assertNull(unplugged.batteryLevel);
        assertFalse(unplugged.externalPower);
        assertEquals("unplugged", unplugged.chargeState);
    }

    @Test public void parsesCanonicalAppleNetworkLabels() {
        assertEquals("LTE", parse("TEL2;N;LTE;1").networkType);
        assertEquals("5G_UW", parse("TEL2;N;5G_UW;2").networkType);
        assertEquals("", parse("TEL2;N;-;3").networkType);
    }

    @Test public void parsesAtomicTel3SnapshotWithinLegacyAttPayload() {
        String frame = "TEL3;60;1;C;6;44";
        assertTrue(frame.getBytes(StandardCharsets.UTF_8).length <= 20);
        IphoneHelperTelemetry value = parse(frame);
        assertEquals(IphoneHelperTelemetry.Kind.SNAPSHOT, value.kind);
        assertEquals(Integer.valueOf(60), value.batteryLevel);
        assertEquals(Boolean.TRUE, value.externalPower);
        assertEquals("charging", value.chargeState);
        assertEquals("LTE", value.networkType);
        assertEquals(44, value.sequence);

        IphoneHelperTelemetry unknown = parse("TEL3;-;-;X;-;45");
        assertNull(unknown.batteryLevel);
        assertNull(unknown.externalPower);
        assertEquals("unknown", unknown.chargeState);
        assertEquals("", unknown.networkType);
    }

    @Test public void parsesFixedBinarySnapshotAndRejectsCorruption() {
        byte[] frame = binary(60, 0x0F, 2, 0x1234);
        IphoneHelperTelemetry value = IphoneHelperTelemetry.parse(frame);
        assertTrue(value != null);
        assertEquals(IphoneHelperTelemetry.Kind.SNAPSHOT, value.kind);
        assertEquals(Integer.valueOf(60), value.batteryLevel);
        assertEquals(Boolean.TRUE, value.externalPower);
        assertEquals("charging", value.chargeState);
        assertEquals("LTE", value.networkType);
        assertEquals(0x1234, value.sequence);

        frame[2] = 61;
        assertNull(IphoneHelperTelemetry.parse(frame));

        IphoneHelperTelemetry unplugged = IphoneHelperTelemetry.parse(
                binary(9, 0x05, 4, 7));
        assertTrue(unplugged != null);
        assertEquals(Boolean.FALSE, unplugged.externalPower);
        assertEquals("unplugged", unplugged.chargeState);
        assertEquals("3G", unplugged.networkType);
    }

    @Test public void rejectsMalformedOrUntrustedVocabulary() {
        assertNull(IphoneHelperTelemetry.parse(null));
        assertNull(raw("TEL1;N;LTE;1"));
        assertNull(raw("TEL2;P;101;1;C;1"));
        assertNull(raw("TEL2;P;50;maybe;C;1"));
        assertNull(raw("TEL2;N;WIFI;1"));
        assertNull(raw("TEL2;N;LTE;10000"));
        assertNull(raw("TEL3;60;1;C;Z;1"));
        assertNull(raw("TEL3;60;1;C;6;10000"));
    }

    private static IphoneHelperTelemetry parse(String value) {
        IphoneHelperTelemetry parsed = raw(value);
        assertTrue(parsed != null);
        return parsed;
    }

    private static IphoneHelperTelemetry raw(String value) {
        return IphoneHelperTelemetry.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] binary(int level, int flags, int network, int sequence) {
        byte[] value = new byte[] {
                (byte) 0xA5, 1, (byte) level, (byte) flags, (byte) network,
                (byte) sequence, (byte) (sequence >>> 8), 0
        };
        int crc = 0;
        for (int index = 0; index < value.length - 1; index++) {
            crc ^= value[index] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF
                        : (crc << 1) & 0xFF;
            }
        }
        value[value.length - 1] = (byte) crc;
        return value;
    }
}
