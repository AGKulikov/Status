package ru.natro.ancstest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Android-independent codec for Apple Notification Center Service. */
public final class AncsProtocol {
    public static final UUID SERVICE =
            UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0");
    public static final UUID NOTIFICATION_SOURCE =
            UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd");
    public static final UUID CONTROL_POINT =
            UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9");
    public static final UUID DATA_SOURCE =
            UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb");
    public static final UUID CLIENT_CONFIGURATION =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int EVENT_ADDED = 0;
    public static final int EVENT_MODIFIED = 1;
    public static final int EVENT_REMOVED = 2;

    private static final int COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0;
    private static final int COMMAND_GET_APP_ATTRIBUTES = 1;
    private static final int ATTRIBUTE_APP_IDENTIFIER = 0;
    private static final int ATTRIBUTE_TITLE = 1;
    private static final int ATTRIBUTE_SUBTITLE = 2;
    private static final int ATTRIBUTE_MESSAGE = 3;
    private static final int ATTRIBUTE_DATE = 5;
    private static final int APP_ATTRIBUTE_DISPLAY_NAME = 0;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private AncsProtocol() {
    }

    public static final class Event {
        public final int eventId;
        public final int flags;
        public final int categoryId;
        public final int categoryCount;
        public final long uid;

        Event(int eventId, int flags, int categoryId, int categoryCount, long uid) {
            this.eventId = eventId;
            this.flags = flags;
            this.categoryId = categoryId;
            this.categoryCount = categoryCount;
            this.uid = uid;
        }
    }

    public static final class NotificationData {
        public final long uid;
        public final String appIdentifier;
        public final String title;
        public final String subtitle;
        public final String message;
        public final String date;

        NotificationData(long uid, String appIdentifier, String title,
                         String subtitle, String message, String date) {
            this.uid = uid;
            this.appIdentifier = appIdentifier;
            this.title = title;
            this.subtitle = subtitle;
            this.message = message;
            this.date = date;
        }
    }

    public static Event parseEvent(byte[] packet, int offset) {
        if (packet == null || offset < 0 || offset + 8 > packet.length) return null;
        int eventId = unsigned(packet[offset]);
        if (eventId > EVENT_REMOVED) return null;
        return new Event(eventId, unsigned(packet[offset + 1]),
                unsigned(packet[offset + 2]), unsigned(packet[offset + 3]),
                littleEndianUnsignedInt(packet, offset + 4));
    }

    public static byte[] notificationAttributeRequest(long uid) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(COMMAND_GET_NOTIFICATION_ATTRIBUTES);
        writeLittleEndianInt(output, uid);
        output.write(ATTRIBUTE_APP_IDENTIFIER);
        output.write(ATTRIBUTE_TITLE);
        writeLittleEndianShort(output, 160);
        output.write(ATTRIBUTE_SUBTITLE);
        writeLittleEndianShort(output, 120);
        output.write(ATTRIBUTE_MESSAGE);
        writeLittleEndianShort(output, 512);
        output.write(ATTRIBUTE_DATE);
        return output.toByteArray();
    }

    public static byte[] appDisplayNameRequest(String appIdentifier) {
        String normalized = appIdentifier == null ? "" : appIdentifier.trim();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid AppIdentifier");
        }
        byte[] encoded = normalized.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(encoded.length + 3);
        output.write(COMMAND_GET_APP_ATTRIBUTES);
        output.write(encoded, 0, encoded.length);
        output.write(0);
        output.write(APP_ATTRIBUTE_DISPLAY_NAME);
        return output.toByteArray();
    }

    public static final class NotificationAccumulator {
        private final long expectedUid;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean invalid;
        private String error = "";

        public NotificationAccumulator(long expectedUid) {
            this.expectedUid = expectedUid;
        }

        public boolean append(byte[] fragment) {
            if (invalid || fragment == null || fragment.length == 0) return !invalid;
            if (bytes.size() + fragment.length > MAX_RESPONSE_BYTES) {
                invalidate("response exceeds " + MAX_RESPONSE_BYTES + " bytes");
                bytes.reset();
                return false;
            }
            bytes.write(fragment, 0, fragment.length);
            validatePrefix();
            return !invalid;
        }

        public boolean isMalformed() {
            return invalid;
        }

        public String error() {
            return error;
        }

        public int size() {
            return bytes.size();
        }

        public NotificationData complete() {
            if (invalid) return null;
            byte[] data = bytes.toByteArray();
            if (data.length < 5
                    || unsigned(data[0]) != COMMAND_GET_NOTIFICATION_ATTRIBUTES
                    || littleEndianUnsignedInt(data, 1) != expectedUid) {
                return null;
            }
            Map<Integer, String> attributes = parseAttributeTuples(data, 5);
            if (attributes == null
                    || !attributes.containsKey(ATTRIBUTE_APP_IDENTIFIER)
                    || !attributes.containsKey(ATTRIBUTE_TITLE)
                    || !attributes.containsKey(ATTRIBUTE_SUBTITLE)
                    || !attributes.containsKey(ATTRIBUTE_MESSAGE)
                    || !attributes.containsKey(ATTRIBUTE_DATE)) {
                return null;
            }
            return new NotificationData(expectedUid,
                    value(attributes, ATTRIBUTE_APP_IDENTIFIER),
                    value(attributes, ATTRIBUTE_TITLE),
                    value(attributes, ATTRIBUTE_SUBTITLE),
                    value(attributes, ATTRIBUTE_MESSAGE),
                    value(attributes, ATTRIBUTE_DATE));
        }

        private void validatePrefix() {
            byte[] data = bytes.toByteArray();
            if (data.length >= 1
                    && unsigned(data[0]) != COMMAND_GET_NOTIFICATION_ATTRIBUTES) {
                invalidate("unexpected notification CommandID " + unsigned(data[0]));
                return;
            }
            if (data.length >= 5 && littleEndianUnsignedInt(data, 1) != expectedUid) {
                invalidate("unexpected NotificationUID");
                return;
            }
            if (data.length >= 5) {
                String tupleError = partialTupleError(data, 5);
                if (tupleError != null) invalidate(tupleError);
            }
        }

        private void invalidate(String reason) {
            invalid = true;
            error = reason;
        }
    }

    public static final class AppNameAccumulator {
        private final String expectedAppIdentifier;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean invalid;
        private String error = "";

        public AppNameAccumulator(String expectedAppIdentifier) {
            this.expectedAppIdentifier = expectedAppIdentifier.trim();
        }

        public boolean append(byte[] fragment) {
            if (invalid || fragment == null || fragment.length == 0) return !invalid;
            if (bytes.size() + fragment.length > MAX_RESPONSE_BYTES) {
                invalidate("response exceeds " + MAX_RESPONSE_BYTES + " bytes");
                bytes.reset();
                return false;
            }
            bytes.write(fragment, 0, fragment.length);
            validatePrefix();
            return !invalid;
        }

        public boolean isMalformed() {
            return invalid;
        }

        public String error() {
            return error;
        }

        public int size() {
            return bytes.size();
        }

        public String complete() {
            if (invalid) return null;
            byte[] data = bytes.toByteArray();
            if (data.length < 2 || unsigned(data[0]) != COMMAND_GET_APP_ATTRIBUTES) {
                return null;
            }
            int terminator = indexOf(data, (byte) 0, 1);
            if (terminator < 0) return null;
            String identifier = strictUtf8(data, 1, terminator - 1);
            if (identifier == null || !expectedAppIdentifier.equals(identifier.trim())) {
                return null;
            }
            Map<Integer, String> attributes = parseAttributeTuples(data, terminator + 1);
            return attributes != null && attributes.containsKey(APP_ATTRIBUTE_DISPLAY_NAME)
                    ? value(attributes, APP_ATTRIBUTE_DISPLAY_NAME) : null;
        }

        private void validatePrefix() {
            byte[] data = bytes.toByteArray();
            if (data.length >= 1 && unsigned(data[0]) != COMMAND_GET_APP_ATTRIBUTES) {
                invalidate("unexpected app CommandID " + unsigned(data[0]));
                return;
            }
            int terminator = indexOf(data, (byte) 0, 1);
            if (terminator < 0) return;
            String identifier = strictUtf8(data, 1, terminator - 1);
            if (identifier == null
                    || !expectedAppIdentifier.equals(identifier.trim())) {
                invalidate("unexpected AppIdentifier");
                return;
            }
            String tupleError = partialTupleError(data, terminator + 1);
            if (tupleError != null) invalidate(tupleError);
        }

        private void invalidate(String reason) {
            invalid = true;
            error = reason;
        }
    }

    public static String categoryLabel(int categoryId) {
        switch (categoryId) {
            case 1: return "Входящий звонок";
            case 2: return "Пропущенный звонок";
            case 3: return "Голосовая почта";
            case 4: return "Социальные сети";
            case 5: return "Календарь";
            case 6: return "Почта";
            case 7: return "Новости";
            case 8: return "Здоровье и спорт";
            case 9: return "Финансы";
            case 10: return "Местоположение";
            case 11: return "Развлечения";
            default: return "Уведомление";
        }
    }

    private static Map<Integer, String> parseAttributeTuples(byte[] data, int offset) {
        if (offset < 0 || offset > data.length) return null;
        Map<Integer, String> result = new LinkedHashMap<>();
        while (offset < data.length) {
            if (offset + 3 > data.length) return null;
            int id = unsigned(data[offset++]);
            int length = unsigned(data[offset]) | unsigned(data[offset + 1]) << 8;
            offset += 2;
            if (offset + length > data.length || result.containsKey(id)) return null;
            String decoded = strictUtf8(data, offset, length);
            if (decoded == null) return null;
            result.put(id, decoded.trim());
            offset += length;
        }
        return result;
    }

    /**
     * Returns null for both valid-complete and valid-incomplete tuple streams.
     * A non-null value means the bytes already received cannot become valid.
     */
    private static String partialTupleError(byte[] data, int offset) {
        Set<Integer> seen = new HashSet<>();
        while (offset < data.length) {
            if (offset + 3 > data.length) return null;
            int id = unsigned(data[offset++]);
            int length = unsigned(data[offset]) | unsigned(data[offset + 1]) << 8;
            offset += 2;
            if (!seen.add(id)) return "duplicate attribute " + id;
            if (offset + length > data.length) return null;
            if (strictUtf8(data, offset, length) == null) {
                return "invalid UTF-8 in attribute " + id;
            }
            offset += length;
        }
        return null;
    }

    private static String strictUtf8(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) return null;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length)).toString();
            return decoded.indexOf('\0') >= 0 ? null : decoded;
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    private static long littleEndianUnsignedInt(byte[] value, int offset) {
        if (offset < 0 || offset + 4 > value.length) return -1L;
        return (long) unsigned(value[offset])
                | (long) unsigned(value[offset + 1]) << 8
                | (long) unsigned(value[offset + 2]) << 16
                | (long) unsigned(value[offset + 3]) << 24;
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, long value) {
        output.write((int) (value & 0xff));
        output.write((int) (value >>> 8 & 0xff));
        output.write((int) (value >>> 16 & 0xff));
        output.write((int) (value >>> 24 & 0xff));
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write(value >>> 8 & 0xff);
    }

    private static int indexOf(byte[] value, byte needle, int start) {
        for (int index = Math.max(0, start); index < value.length; index++) {
            if (value[index] == needle) return index;
        }
        return -1;
    }

    private static String value(Map<Integer, String> values, int key) {
        String result = values.get(key);
        return result == null ? "" : result;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
