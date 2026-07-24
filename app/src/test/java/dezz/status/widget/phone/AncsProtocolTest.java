/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class AncsProtocolTest {
    @Test public void parsesExactEightByteNotificationSourceEvent() {
        AncsProtocol.Event event = AncsProtocol.parseEvent(new byte[] {
                0, 0x12, 6, 3, 0x78, 0x56, 0x34, 0x12
        });
        assertNotNull(event);
        assertEquals(AncsProtocol.EVENT_ADDED, event.eventId);
        assertEquals(0x12, event.flags);
        assertEquals(6, event.categoryId);
        assertEquals(3, event.categoryCount);
        assertEquals(0x12345678L, event.uid);

        assertNull(AncsProtocol.parseEvent(new byte[7]));
        assertNull(AncsProtocol.parseEvent(new byte[] {9, 0, 0, 0, 0, 0, 0, 0}));
    }

    @Test public void attributeRequestUsesLittleEndianUidAndPrivacyBounds() {
        byte[] privateRequest =
                AncsProtocol.notificationAttributeRequest(0x12345678L, false);
        assertArrayEquals(new byte[] {
                0, 0x78, 0x56, 0x34, 0x12,
                0,
                1, 0, 0,
                2, 0, 0,
                3, 0, 0,
                5
        }, privateRequest);

        byte[] fullRequest = AncsProtocol.notificationAttributeRequest(1L, true);
        assertEquals(160, littleShort(fullRequest, 7));
        assertEquals(120, littleShort(fullRequest, 10));
        assertEquals(512, littleShort(fullRequest, 13));
    }

    @Test public void fragmentedDataSourceResponseCompletesOnlyAfterEveryAttribute() {
        long uid = 0x01020304L;
        byte[] response = response(uid,
                attribute(0, "com.apple.mobilemail"),
                attribute(1, "Тема"),
                attribute(2, "Подзаголовок"),
                attribute(3, "Сообщение"),
                attribute(5, "20260724T101500"));
        AncsProtocol.AttributeAccumulator accumulator =
                new AncsProtocol.AttributeAccumulator(uid);
        assertTrue(accumulator.append(Arrays.copyOfRange(response, 0, 11)));
        assertNull(accumulator.complete());
        assertTrue(accumulator.append(Arrays.copyOfRange(response, 11, response.length)));

        AncsProtocol.Notification notification = accumulator.complete();
        assertNotNull(notification);
        assertEquals(uid, notification.uid);
        assertEquals("com.apple.mobilemail", notification.appIdentifier);
        assertEquals("Тема", notification.title);
        assertEquals("Подзаголовок", notification.subtitle);
        assertEquals("Сообщение", notification.message);
        assertEquals("20260724T101500", notification.date);
    }

    @Test public void rejectsWrongUidMissingAttributesAndOversizedResponse() {
        AncsProtocol.AttributeAccumulator wrong =
                new AncsProtocol.AttributeAccumulator(2L);
        assertTrue(wrong.append(response(1L,
                attribute(0, "app"), attribute(1, ""), attribute(2, ""),
                attribute(3, ""), attribute(5, ""))));
        assertNull(wrong.complete());

        AncsProtocol.AttributeAccumulator missing =
                new AncsProtocol.AttributeAccumulator(1L);
        assertTrue(missing.append(response(1L, attribute(0, "app"))));
        assertNull(missing.complete());

        AncsProtocol.AttributeAccumulator bounded =
                new AncsProtocol.AttributeAccumulator(1L);
        assertFalse(bounded.append(new byte[16 * 1024 + 1]));
        assertFalse(bounded.append(new byte[] {1}));
        assertNull(bounded.complete());
    }

    private static int littleShort(byte[] bytes, int offset) {
        return bytes[offset] & 0xff | (bytes[offset + 1] & 0xff) << 8;
    }

    private static byte[] response(long uid, byte[]... attributes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        output.write((int) uid & 0xff);
        output.write((int) (uid >>> 8) & 0xff);
        output.write((int) (uid >>> 16) & 0xff);
        output.write((int) (uid >>> 24) & 0xff);
        for (byte[] attribute : attributes) {
            output.write(attribute, 0, attribute.length);
        }
        return output.toByteArray();
    }

    private static byte[] attribute(int id, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(id);
        output.write(text.length & 0xff);
        output.write(text.length >>> 8 & 0xff);
        output.write(text, 0, text.length);
        return output.toByteArray();
    }
}
