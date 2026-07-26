package ru.natro.ancstest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class AncsProtocolTest {
    @Test
    public void parsesNotificationSourceEvent() {
        AncsProtocol.Event event = AncsProtocol.parseEvent(new byte[] {
                0x00, 0x12, 0x06, 0x01, 0x78, 0x56, 0x34, 0x12
        }, 0);

        assertNotNull(event);
        assertEquals(AncsProtocol.EVENT_ADDED, event.eventId);
        assertEquals(6, event.categoryId);
        assertEquals(0x12345678L, event.uid);
    }

    @Test
    public void notificationRequestUsesLittleEndianUidAndFullTextAttributes() {
        byte[] request = AncsProtocol.notificationAttributeRequest(0x12345678L);

        assertArrayEquals(new byte[] {
                0x00, 0x78, 0x56, 0x34, 0x12,
                0x00,
                0x01, (byte) 0xA0, 0x00,
                0x02, 0x78, 0x00,
                0x03, 0x00, 0x02,
                0x05
        }, request);
    }

    @Test
    public void accumulatesFragmentedNotificationAttributes() {
        long uid = 0x01020304L;
        byte[] response = notificationResponse(uid,
                "com.apple.MobileSMS", "Алексей", "", "Тест", "20260726T120000");
        AncsProtocol.NotificationAccumulator accumulator =
                new AncsProtocol.NotificationAccumulator(uid);

        accumulator.append(slice(response, 0, 9));
        assertNull(accumulator.complete());
        accumulator.append(slice(response, 9, response.length));

        AncsProtocol.NotificationData value = accumulator.complete();
        assertNotNull(value);
        assertEquals("com.apple.MobileSMS", value.appIdentifier);
        assertEquals("Алексей", value.title);
        assertEquals("Тест", value.message);
    }

    @Test
    public void parsesAppDisplayName() {
        String identifier = "com.apple.MobileSMS";
        byte[] id = identifier.getBytes(StandardCharsets.UTF_8);
        byte[] display = "Сообщения".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(1);
        response.write(id, 0, id.length);
        response.write(0);
        tuple(response, 0, display);

        AncsProtocol.AppNameAccumulator accumulator =
                new AncsProtocol.AppNameAccumulator(identifier);
        accumulator.append(response.toByteArray());

        assertEquals("Сообщения", accumulator.complete());
    }

    @Test
    public void appRequestMatchesWireFormat() {
        assertArrayEquals(new byte[] {
                0x01, 'c', 'o', 'm', '.', 't', 'e', 's', 't', 0x00, 0x00
        }, AncsProtocol.appDisplayNameRequest("com.test"));
    }

    @Test
    public void notificationResponseWorksAtEveryFragmentBoundary() {
        long uid = 0xf1020304L;
        byte[] response = notificationResponse(uid,
                "com.apple.MobileSMS", "Алексей", "", "Тест", "20260726T120000");

        for (int boundary = 1; boundary < response.length; boundary++) {
            AncsProtocol.NotificationAccumulator accumulator =
                    new AncsProtocol.NotificationAccumulator(uid);
            accumulator.append(slice(response, 0, boundary));
            accumulator.append(slice(response, boundary, response.length));
            AncsProtocol.NotificationData value = accumulator.complete();
            assertNotNull("boundary=" + boundary, value);
            assertEquals("Алексей", value.title);
            assertEquals("Тест", value.message);
        }
    }

    @Test
    public void rejectsWrongUidWithoutWaitingForTimeout() {
        byte[] response = notificationResponse(5L,
                "com.test", "", "", "", "");
        AncsProtocol.NotificationAccumulator accumulator =
                new AncsProtocol.NotificationAccumulator(6L);

        assertEquals(false, accumulator.append(response));
        assertEquals(true, accumulator.isMalformed());
    }

    private static byte[] notificationResponse(long uid, String app, String title,
                                               String subtitle, String message, String date) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        output.write((int) (uid & 0xff));
        output.write((int) (uid >>> 8 & 0xff));
        output.write((int) (uid >>> 16 & 0xff));
        output.write((int) (uid >>> 24 & 0xff));
        tuple(output, 0, app.getBytes(StandardCharsets.UTF_8));
        tuple(output, 1, title.getBytes(StandardCharsets.UTF_8));
        tuple(output, 2, subtitle.getBytes(StandardCharsets.UTF_8));
        tuple(output, 3, message.getBytes(StandardCharsets.UTF_8));
        tuple(output, 5, date.getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static void tuple(ByteArrayOutputStream output, int id, byte[] value) {
        output.write(id);
        output.write(value.length & 0xff);
        output.write(value.length >>> 8 & 0xff);
        output.write(value, 0, value.length);
    }

    private static byte[] slice(byte[] value, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(value, from, result, 0, result.length);
        return result;
    }
}
