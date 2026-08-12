/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

public final class IphoneBleControlProtocolV2Test {
    private static final UUID INSTALLATION =
            UUID.fromString("8f04fe8d-11c2-4b3a-9ab7-f4512aa2a21d");

    @Test public void peerProofRoundTripsInDefaultAttPayload() {
        byte[] encoded = IphoneBleControlProtocolV2.encodePeerProof(
                IphoneBleMode.ANDROID_CENTRAL, INSTALLATION, true, true);
        assertEquals(20, encoded.length);
        assertArrayEquals(hex("480201038f04fe8d11c24b3a9ab7f4512aa2a21d"), encoded);

        IphoneBleControlProtocolV2.Frame decoded =
                IphoneBleControlProtocolV2.decode(encoded);
        assertNotNull(decoded);
        assertEquals(IphoneBleControlProtocolV2.Type.PEER_PROOF, decoded.type);
        assertEquals(IphoneBleMode.ANDROID_CENTRAL, decoded.mode);
        assertTrue(decoded.telemetrySupported());
        assertTrue(decoded.ancsSupported());
        assertEquals(INSTALLATION,
                IphoneBleControlProtocolV2.installationUuid(decoded));
    }

    @Test public void closeAckMustEchoExactModeAndToken() {
        byte[] token = new byte[16];
        for (int index = 0; index < token.length; index++) token[index] = (byte) (index + 1);
        byte[] close = IphoneBleControlProtocolV2.encodeRoleClose(
                IphoneBleMode.ANDROID_PERIPHERAL, token);
        byte[] ack = IphoneBleControlProtocolV2.encodeRoleCloseAck(
                IphoneBleMode.ANDROID_PERIPHERAL, token);
        assertArrayEquals(hex("430202000102030405060708090a0b0c0d0e0f10"), close);
        assertArrayEquals(hex("410202000102030405060708090a0b0c0d0e0f10"), ack);

        IphoneBleControlProtocolV2.Frame closeFrame =
                IphoneBleControlProtocolV2.decode(close);
        IphoneBleControlProtocolV2.Frame ackFrame =
                IphoneBleControlProtocolV2.decode(ack);
        assertNotNull(closeFrame);
        assertNotNull(ackFrame);
        assertEquals(IphoneBleControlProtocolV2.Type.ROLE_CLOSE, closeFrame.type);
        assertEquals(IphoneBleControlProtocolV2.Type.ROLE_CLOSE_ACK, ackFrame.type);
        assertEquals(closeFrame.mode, ackFrame.mode);
        assertTrue(ackFrame.payloadEquals(closeFrame.payload()));

        byte[] changed = token.clone();
        changed[15] ^= 1;
        assertFalse(ackFrame.payloadEquals(changed));
    }

    @Test public void decoderRejectsVersionRoleFlagsLengthAndZeroPayload() {
        byte[] valid = IphoneBleControlProtocolV2.encodePeerProof(
                IphoneBleMode.ANDROID_PERIPHERAL, INSTALLATION, false, true);

        assertNull(IphoneBleControlProtocolV2.decode(Arrays.copyOf(valid, 19)));
        byte[] wrongVersion = valid.clone();
        wrongVersion[1] = 3;
        assertNull(IphoneBleControlProtocolV2.decode(wrongVersion));
        byte[] wrongRole = valid.clone();
        wrongRole[2] = 99;
        assertNull(IphoneBleControlProtocolV2.decode(wrongRole));
        byte[] wrongFlags = valid.clone();
        wrongFlags[3] = (byte) 0x80;
        assertNull(IphoneBleControlProtocolV2.decode(wrongFlags));
        byte[] noAncs = valid.clone();
        noAncs[3] = 0;
        assertNull(IphoneBleControlProtocolV2.decode(noAncs));
        byte[] zero = valid.clone();
        Arrays.fill(zero, 4, zero.length, (byte) 0);
        assertNull(IphoneBleControlProtocolV2.decode(zero));
    }

    @Test public void controlFramesRejectFlagsAndZeroOrWrongLengthTokens() {
        assertThrows(IllegalArgumentException.class, () ->
                IphoneBleControlProtocolV2.encodeRoleClose(
                        IphoneBleMode.ANDROID_CENTRAL, new byte[16]));
        assertThrows(IllegalArgumentException.class, () ->
                IphoneBleControlProtocolV2.encodeRoleCloseAck(
                        IphoneBleMode.ANDROID_CENTRAL, new byte[15]));

        byte[] token = new byte[16];
        token[0] = 1;
        byte[] close = IphoneBleControlProtocolV2.encodeRoleClose(
                IphoneBleMode.ANDROID_CENTRAL, token);
        close[3] = 1;
        assertNull(IphoneBleControlProtocolV2.decode(close));
    }

    @Test public void generatedSwitchTokenIsNonZeroAndDetached() {
        byte[] token = IphoneBleControlProtocolV2.newSwitchToken(new SecureRandom());
        assertEquals(16, token.length);
        byte[] frame = IphoneBleControlProtocolV2.encodeRoleClose(
                IphoneBleMode.ANDROID_CENTRAL, token);
        IphoneBleControlProtocolV2.Frame decoded =
                IphoneBleControlProtocolV2.decode(frame);
        assertNotNull(decoded);
        assertArrayEquals(token, decoded.payload());

        byte[] detached = decoded.payload();
        detached[0] ^= 1;
        assertFalse(Arrays.equals(detached, decoded.payload()));
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
