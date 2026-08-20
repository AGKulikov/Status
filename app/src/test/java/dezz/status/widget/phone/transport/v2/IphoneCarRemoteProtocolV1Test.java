/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class IphoneCarRemoteProtocolV1Test {
    @Test public void exactInt32VectorsMatchHelper53() {
        assertVector(new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.HELLO, 0, 0, 0,
                        0, 0x10203040L, 0, 0),
                "4e0101000000000040302010000000000000723d");
        assertVector(new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.COMMAND, 31, 1, 0,
                        0x1234, 0x10203043L, 0x22010103, 30),
                "4e01041f0100341243302010030101221e0007b5");
        assertVector(new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.COMMAND, 11, 1, 0,
                        0x1236, 0x10203047L, 2250, 30),
                "4e01040b0100361247302010ca0800001e005c0b");
    }

    @Test public void decodePreservesLowEcarxBitsAndRejectsTornFrames() {
        byte[] exact = hex("4e010308000700004230201002010310000072b7");
        IphoneCarRemoteProtocolV1.Frame frame = IphoneCarRemoteProtocolV1.decode(exact);
        assertNotNull(frame);
        assertEquals(0x10030102, frame.value);
        assertEquals(0x10203042L, frame.sequence);

        exact[12] ^= 1;
        assertNull(IphoneCarRemoteProtocolV1.decode(exact));
        assertNull(IphoneCarRemoteProtocolV1.decode(new byte[19]));
    }

    @Test public void strictTypedFieldsRejectPrivilegeAndReplayAmbiguity() {
        byte[] noConfirmation = IphoneCarRemoteProtocolV1.encode(
                new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.COMMAND, 30, 1, 0,
                        1, 1, 1, 30));
        assertNotNull(IphoneCarRemoteProtocolV1.decode(noConfirmation));

        noConfirmation[5] = (byte) IphoneCarRemoteProtocolV1.FLAG_MECHANICAL;
        rewriteCrc(noConfirmation);
        assertNull(IphoneCarRemoteProtocolV1.decode(noConfirmation));

        byte[] zeroSequence = IphoneCarRemoteProtocolV1.encode(
                new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.COMMAND, 1, 1, 0,
                        1, 0, 1, 30));
        assertNull(IphoneCarRemoteProtocolV1.decode(zeroSequence));

        byte[] zeroTransaction = IphoneCarRemoteProtocolV1.encode(
                new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.COMMAND, 1, 1, 0,
                        0, 1, 1, 30));
        assertNull(IphoneCarRemoteProtocolV1.decode(zeroTransaction));

        byte[] contradictoryResult = IphoneCarRemoteProtocolV1.encode(
                new IphoneCarRemoteProtocolV1.Frame(
                        IphoneCarRemoteProtocolV1.Type.RESULT, 1,
                        IphoneCarRemoteProtocolV1.Result.REJECTED.wire, 0,
                        1, 1, 1, 0));
        assertNull(IphoneCarRemoteProtocolV1.decode(contradictoryResult));
    }

    @Test public void registryIsFiniteUniqueAndMechanicalEntriesStayConfirmed() {
        assertEquals(39, CarRemoteControlRegistryV1.all().size());
        Set<Integer> wire = new HashSet<>();
        Set<String> controls = new HashSet<>();
        for (CarRemoteControlRegistryV1.Entry entry : CarRemoteControlRegistryV1.all()) {
            assertTrue(wire.add(entry.wireId));
            assertTrue(controls.add(entry.controlId));
            assertTrue(entry.wireId > 0 && entry.wireId <= 255);
            assertTrue(entry.scale == 1 || entry.scale == 100);
        }
        assertNull(CarRemoteControlRegistryV1.forWireId(0));
        assertNull(CarRemoteControlRegistryV1.forWireId(255));
        CarRemoteControlRegistryV1.Entry trunk =
                CarRemoteControlRegistryV1.forWireId(30);
        assertNotNull(trunk);
        assertTrue(trunk.mechanical);
        assertTrue(trunk.requiresConfirmation);
        assertFalse(trunk.media);
        assertEquals(100, CarRemoteControlRegistryV1.forWireId(11).scale);
        assertEquals(100, CarRemoteControlRegistryV1.forWireId(54).scale);
    }

    private static void assertVector(IphoneCarRemoteProtocolV1.Frame source, String expectedHex) {
        byte[] encoded = IphoneCarRemoteProtocolV1.encode(source);
        assertArrayEquals(hex(expectedHex), encoded);
        IphoneCarRemoteProtocolV1.Frame decoded = IphoneCarRemoteProtocolV1.decode(encoded);
        assertNotNull(decoded);
        assertEquals(source.type, decoded.type);
        assertEquals(source.controlId, decoded.controlId);
        assertEquals(source.code, decoded.code);
        assertEquals(source.flags, decoded.flags);
        assertEquals(source.transactionId, decoded.transactionId);
        assertEquals(source.sequence, decoded.sequence);
        assertEquals(source.value, decoded.value);
        assertEquals(source.maxAgeDeciseconds, decoded.maxAgeDeciseconds);
    }

    private static void rewriteCrc(byte[] frame) {
        int crc = IphoneCarRemoteProtocolV1.crc16(frame, 0, 18);
        frame[18] = (byte) crc;
        frame[19] = (byte) (crc >>> 8);
    }

    private static byte[] hex(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }
}
