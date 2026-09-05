/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.liveactivity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public final class LiveActivityPushProtocolV1Test {
    @Test public void att20FrameRoundTripsAndRejectsCorruption() {
        LiveActivityPushProtocolV1.Frame source = new LiveActivityPushProtocolV1.Frame(
                LiveActivityPushProtocolV1.TYPE_CONFIGURATION, 0x1263, 1, 2,
                new byte[]{9, 8, 7});
        byte[] raw = LiveActivityPushProtocolV1.encode(source);
        assertEquals(20, raw.length);
        LiveActivityPushProtocolV1.Frame decoded = LiveActivityPushProtocolV1.decode(raw);
        assertNotNull(decoded);
        assertEquals(source.type, decoded.type);
        assertEquals(source.messageId, decoded.messageId);
        assertArrayEquals(source.payload, decoded.payload);
        raw[10] ^= 1;
        assertNull(LiveActivityPushProtocolV1.decode(raw));
    }

    @Test public void outOfOrderChunksReassembleExactlyOnce() {
        byte[] payload = new byte[29];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) index;
        int count = (payload.length + 7) / 8;
        byte[][] frames = new byte[count][];
        for (int index = 0; index < count; index++) {
            int start = index * 8;
            frames[index] = LiveActivityPushProtocolV1.encode(
                    new LiveActivityPushProtocolV1.Frame(
                            LiveActivityPushProtocolV1.TYPE_ACTIVITY_PUSH_TOKEN,
                            63, index, count,
                            Arrays.copyOfRange(payload, start, Math.min(payload.length, start + 8))));
        }
        LiveActivityPushProtocolV1.Reassembler reassembler =
                new LiveActivityPushProtocolV1.Reassembler();
        assertNull(reassembler.accept(frames[2]));
        assertNull(reassembler.accept(frames[0]));
        assertNull(reassembler.accept(frames[1]));
        LiveActivityPushProtocolV1.Message message = reassembler.accept(frames[3]);
        assertNotNull(message);
        assertEquals(LiveActivityPushProtocolV1.TYPE_ACTIVITY_PUSH_TOKEN, message.type);
        assertArrayEquals(payload, message.payload);
        assertNull(reassembler.accept(frames[3]));
    }

    @Test public void apnsDerSignatureConvertsToFixedWidthJose() {
        byte[] jose = ApnsLiveActivityClient.derSignatureToJose(new byte[]{
                0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02
        });
        assertEquals(64, jose.length);
        assertEquals(1, jose[31]);
        assertEquals(2, jose[63]);
    }
}
