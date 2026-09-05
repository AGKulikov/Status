/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IphoneTelemetryProtocolV2Test {
    @Test public void exactFrameRoundTripsAllFields() {
        byte[] frame = IphoneTelemetryProtocolV2.encode(80, true,
                IphoneTelemetryProtocolV2.ChargeState.CHARGING,
                IphoneTelemetryProtocolV2.Network.LTE, false, 0x1234);
        assertEquals(8, frame.length);
        assertArrayEquals(new byte[] {
                0x54, 0x02, 0x13, 0x50, 0x03, 0x34, 0x12, (byte) 0xb7
        }, frame);
        IphoneTelemetryV2 decoded = IphoneTelemetryProtocolV2.decode(frame);
        assertEquals(Integer.valueOf(80), decoded.batteryPercent);
        assertEquals(Boolean.TRUE, decoded.externalPower);
        assertEquals("charging", decoded.chargeState);
        assertEquals("lte", decoded.networkType);
        assertEquals(Boolean.FALSE, decoded.phoneLocked);
        assertEquals(0x1234, decoded.sequence);
    }

    @Test public void unknownBatteryUsesCanonicalFfEncoding() {
        byte[] frame = IphoneTelemetryProtocolV2.encode(null, false,
                IphoneTelemetryProtocolV2.ChargeState.UNKNOWN,
                IphoneTelemetryProtocolV2.Network.UNKNOWN, true, 7);
        assertEquals(0xff, frame[3] & 0xff);
        IphoneTelemetryV2 decoded = IphoneTelemetryProtocolV2.decode(frame);
        assertNull(decoded.batteryPercent);
        assertEquals(Boolean.TRUE, decoded.phoneLocked);

        frame[3] = 0;
        frame[7] = IphoneTelemetryProtocolV2.crc8(frame, 7);
        assertNull(IphoneTelemetryProtocolV2.decode(frame));
    }

    @Test public void malformedVersionNetworkFlagsAndCrcFailClosed() {
        byte[] valid = IphoneTelemetryProtocolV2.encode(1, false,
                IphoneTelemetryProtocolV2.ChargeState.DISCHARGING,
                IphoneTelemetryProtocolV2.Network.WIFI, false, 1);
        byte[] wrongVersion = valid.clone();
        wrongVersion[1] = 3;
        wrongVersion[7] = IphoneTelemetryProtocolV2.crc8(wrongVersion, 7);
        assertNull(IphoneTelemetryProtocolV2.decode(wrongVersion));
        byte[] wrongNetwork = valid.clone();
        wrongNetwork[4] = 99;
        wrongNetwork[7] = IphoneTelemetryProtocolV2.crc8(wrongNetwork, 7);
        assertNull(IphoneTelemetryProtocolV2.decode(wrongNetwork));
        byte[] wrongFlags = valid.clone();
        wrongFlags[2] |= (byte) 0x80;
        wrongFlags[7] = IphoneTelemetryProtocolV2.crc8(wrongFlags, 7);
        assertNull(IphoneTelemetryProtocolV2.decode(wrongFlags));
        byte[] wrongCrc = valid.clone();
        wrongCrc[7] ^= 1;
        assertNull(IphoneTelemetryProtocolV2.decode(wrongCrc));
        assertNull(IphoneTelemetryProtocolV2.decode(new byte[7]));
    }

    @Test public void encoderRejectsRanges() {
        assertThrows(IllegalArgumentException.class, () ->
                IphoneTelemetryProtocolV2.encode(101, false,
                        IphoneTelemetryProtocolV2.ChargeState.UNKNOWN,
                        IphoneTelemetryProtocolV2.Network.UNKNOWN, false, 0));
        assertThrows(IllegalArgumentException.class, () ->
                IphoneTelemetryProtocolV2.encode(1, false,
                        IphoneTelemetryProtocolV2.ChargeState.UNKNOWN,
                        IphoneTelemetryProtocolV2.Network.UNKNOWN, false, 0x1_0000));
    }

    @Test public void encodingIsDeterministic() {
        byte[] first = IphoneTelemetryProtocolV2.encode(42, false,
                IphoneTelemetryProtocolV2.ChargeState.FULL,
                IphoneTelemetryProtocolV2.Network.NR5G, true, 65535);
        byte[] second = IphoneTelemetryProtocolV2.encode(42, false,
                IphoneTelemetryProtocolV2.ChargeState.FULL,
                IphoneTelemetryProtocolV2.Network.NR5G, true, 65535);
        assertArrayEquals(first, second);
        assertFalse(first[7] == 0 && first[0] == 0);
        assertTrue(IphoneTelemetryProtocolV2.decode(first) != null);
    }
}
