/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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

    @Test public void privacyRequestContainsOnlyAppIdentifier() {
        byte[] privateRequest =
                AncsProtocol.notificationAttributeRequest(0x12345678L, false);
        assertArrayEquals(new byte[] {
                0, 0x78, 0x56, 0x34, 0x12, 0
        }, privateRequest);
    }

    @Test public void fullRequestContainsEveryConfiguredAttributeAndLength() {
        byte[] fullRequest = AncsProtocol.notificationAttributeRequest(1L, true);
        assertEquals(16, fullRequest.length);
        assertEquals(0, fullRequest[5]);
        assertEquals(1, fullRequest[6]);
        assertEquals(160, littleShort(fullRequest, 7));
        assertEquals(2, fullRequest[9]);
        assertEquals(120, littleShort(fullRequest, 10));
        assertEquals(3, fullRequest[12]);
        assertEquals(512, littleShort(fullRequest, 13));
        assertEquals(5, fullRequest[15]);
    }

    @Test public void fragmentedFullResponseCompletesOnlyAfterEveryAttribute() {
        long uid = 0x01020304L;
        byte[] response = response(uid,
                attribute(0, "com.apple.mobilemail"),
                attribute(1, "Тема"),
                attribute(2, "Подзаголовок"),
                attribute(3, "Сообщение"),
                attribute(5, "20260724T101500"));
        AncsProtocol.AttributeAccumulator accumulator =
                new AncsProtocol.AttributeAccumulator(uid, true);
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

    @Test public void privacyAccumulatorCompletesWithOnlyAppIdentifier() {
        long uid = 7L;
        AncsProtocol.AttributeAccumulator accumulator =
                new AncsProtocol.AttributeAccumulator(uid, false);
        assertTrue(accumulator.append(response(uid,
                attribute(0, "org.telegram.Telegram"))));

        AncsProtocol.Notification notification = accumulator.complete();
        assertNotNull(notification);
        assertEquals("org.telegram.Telegram", notification.appIdentifier);
        assertEquals("", notification.title);
        assertEquals("", notification.subtitle);
        assertEquals("", notification.message);
        assertEquals("", notification.date);
    }

    @Test public void fullAccumulatorDoesNotAcceptPrivacyResponse() {
        AncsProtocol.AttributeAccumulator accumulator =
                new AncsProtocol.AttributeAccumulator(1L, true);
        assertTrue(accumulator.append(response(1L, attribute(0, "app"))));
        assertNull(accumulator.complete());
    }

    @Test public void privacyAccumulatorNeverRetainsUnsolicitedTextAttributes() {
        AncsProtocol.AttributeAccumulator accumulator =
                new AncsProtocol.AttributeAccumulator(9L, false);
        assertTrue(accumulator.append(response(9L,
                attribute(0, "com.example.mail"),
                attribute(1, "Secret title"),
                attribute(2, "Secret subtitle"),
                attribute(3, "Secret message"),
                attribute(5, "20260724T101500"))));

        AncsProtocol.Notification notification = accumulator.complete();
        assertNotNull(notification);
        assertEquals("com.example.mail", notification.appIdentifier);
        assertEquals("", notification.title);
        assertEquals("", notification.subtitle);
        assertEquals("", notification.message);
        assertEquals("", notification.date);
    }

    @Test public void rejectsWrongUidMissingAttributesAndOversizedResponse() {
        AncsProtocol.AttributeAccumulator wrong =
                new AncsProtocol.AttributeAccumulator(2L, true);
        assertTrue(wrong.append(response(1L,
                attribute(0, "app"), attribute(1, ""), attribute(2, ""),
                attribute(3, ""), attribute(5, ""))));
        assertNull(wrong.complete());

        AncsProtocol.AttributeAccumulator missing =
                new AncsProtocol.AttributeAccumulator(1L, true);
        assertTrue(missing.append(response(1L, attribute(0, "app"))));
        assertNull(missing.complete());

        AncsProtocol.AttributeAccumulator bounded =
                new AncsProtocol.AttributeAccumulator(1L, false);
        assertFalse(bounded.append(new byte[16 * 1024 + 1]));
        assertFalse(bounded.append(new byte[] {1}));
        assertNull(bounded.complete());
    }

    @Test public void notificationAccumulatorRejectsMalformedUtf8AndDuplicateAttributes() {
        AncsProtocol.AttributeAccumulator malformed =
                new AncsProtocol.AttributeAccumulator(1L, false);
        assertTrue(malformed.append(response(1L,
                rawAttribute(0, new byte[] {(byte) 0xc3, 0x28}))));
        assertNull(malformed.complete());

        AncsProtocol.AttributeAccumulator duplicate =
                new AncsProtocol.AttributeAccumulator(1L, false);
        assertTrue(duplicate.append(response(1L,
                attribute(0, "one"), attribute(0, "two"))));
        assertNull(duplicate.complete());
    }

    @Test public void appDisplayNameRequestUsesUtf8NullTerminatorAndDisplayNameAttribute() {
        String appIdentifier = "com.apple.mobilemail";
        byte[] encoded = appIdentifier.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(1);
        expected.write(encoded, 0, encoded.length);
        expected.write(0);
        expected.write(0);

        assertArrayEquals(expected.toByteArray(),
                AncsProtocol.appAttributeRequest(appIdentifier));
        assertArrayEquals(expected.toByteArray(),
                AncsProtocol.appDisplayNameRequest(appIdentifier));
    }

    @Test public void fragmentedAppDisplayNameResponseCompletesAfterNullTerminatorAndTuple() {
        String appIdentifier = "com.apple.mobilemail";
        byte[] response = appResponse(appIdentifier, attribute(0, "Почта"));
        int split = 1 + appIdentifier.length();
        AncsProtocol.AppAttributeAccumulator accumulator =
                new AncsProtocol.AppAttributeAccumulator(appIdentifier);

        assertTrue(accumulator.append(Arrays.copyOfRange(response, 0, split)));
        assertNull(accumulator.complete());
        assertTrue(accumulator.append(Arrays.copyOfRange(response, split, response.length - 2)));
        assertNull(accumulator.complete());
        assertTrue(accumulator.append(Arrays.copyOfRange(
                response, response.length - 2, response.length)));

        assertEquals("Почта", accumulator.complete());
    }

    @Test public void appAccumulatorRejectsWrongAppMalformedUtf8AndOversizedResponse() {
        AncsProtocol.AppAttributeAccumulator wrong =
                new AncsProtocol.AppAttributeAccumulator("expected.app");
        assertTrue(wrong.append(appResponse("different.app", attribute(0, "Other"))));
        assertNull(wrong.complete());

        AncsProtocol.AppAttributeAccumulator malformed =
                new AncsProtocol.AppAttributeAccumulator("expected.app");
        assertTrue(malformed.append(appResponse("expected.app",
                rawAttribute(0, new byte[] {(byte) 0xe2, (byte) 0x82}))));
        assertNull(malformed.complete());

        AncsProtocol.AppAttributeAccumulator bounded =
                new AncsProtocol.AppAttributeAccumulator("expected.app");
        assertFalse(bounded.append(new byte[8 * 1024 + 1]));
        assertFalse(bounded.append(new byte[] {1}));
        assertNull(bounded.complete());
    }

    @Test public void appIdentifierValidationRejectsUnsafeWireValues() {
        assertThrows(IllegalArgumentException.class,
                () -> AncsProtocol.appDisplayNameRequest(""));
        assertThrows(IllegalArgumentException.class,
                () -> AncsProtocol.appDisplayNameRequest("app\u0000other"));
        assertThrows(IllegalArgumentException.class,
                () -> AncsProtocol.appDisplayNameRequest(repeat('a', 1025)));
        assertThrows(IllegalArgumentException.class,
                () -> new AncsProtocol.AppAttributeAccumulator("\ud800"));
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

    private static byte[] appResponse(String appIdentifier, byte[]... attributes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(1);
        byte[] identifier = appIdentifier.getBytes(StandardCharsets.UTF_8);
        output.write(identifier, 0, identifier.length);
        output.write(0);
        for (byte[] attribute : attributes) {
            output.write(attribute, 0, attribute.length);
        }
        return output.toByteArray();
    }

    private static byte[] attribute(int id, String value) {
        return rawAttribute(id, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] rawAttribute(int id, byte[] text) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(id);
        output.write(text.length & 0xff);
        output.write(text.length >>> 8 & 0xff);
        output.write(text, 0, text.length);
        return output.toByteArray();
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }
}
