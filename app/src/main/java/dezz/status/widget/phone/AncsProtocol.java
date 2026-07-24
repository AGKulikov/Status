/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small, Android-independent codec for Apple Notification Center Service (ANCS).
 *
 * <p>ANCS data-source replies may be split at arbitrary BLE packet boundaries. The accumulator
 * therefore keeps one bounded response for the single in-flight control-point request and emits
 * a result only after every attribute requested by that command is complete.</p>
 */
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
    private static final int[] PRIVACY_NOTIFICATION_ATTRIBUTES = {
            ATTRIBUTE_APP_IDENTIFIER
    };
    private static final int[] FULL_NOTIFICATION_ATTRIBUTES = {
            ATTRIBUTE_APP_IDENTIFIER,
            ATTRIBUTE_TITLE,
            ATTRIBUTE_SUBTITLE,
            ATTRIBUTE_MESSAGE,
            ATTRIBUTE_DATE
    };
    private static final int MAX_NOTIFICATION_RESPONSE_BYTES = 16 * 1024;
    private static final int MAX_APP_RESPONSE_BYTES = 8 * 1024;
    private static final int MAX_APP_IDENTIFIER_BYTES = 1024;
    private static final int MAX_ATTRIBUTE_CHARS = 4096;

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

    public static final class Notification {
        public final long uid;
        @NonNull public final String appIdentifier;
        @NonNull public final String title;
        @NonNull public final String subtitle;
        @NonNull public final String message;
        @NonNull public final String date;

        Notification(long uid, @NonNull String appIdentifier, @NonNull String title,
                     @NonNull String subtitle, @NonNull String message,
                     @NonNull String date) {
            this.uid = uid;
            this.appIdentifier = appIdentifier;
            this.title = title;
            this.subtitle = subtitle;
            this.message = message;
            this.date = date;
        }
    }

    @Nullable
    public static Event parseEvent(@Nullable byte[] packet) {
        if (packet == null || packet.length < 8) return null;
        int eventId = unsigned(packet[0]);
        if (eventId > EVENT_REMOVED) return null;
        return new Event(eventId, unsigned(packet[1]), unsigned(packet[2]),
                unsigned(packet[3]), littleEndianUnsignedInt(packet, 4));
    }

    @NonNull
    public static byte[] notificationAttributeRequest(long uid, boolean includeText) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(COMMAND_GET_NOTIFICATION_ATTRIBUTES);
        writeLittleEndianInt(output, uid);
        output.write(ATTRIBUTE_APP_IDENTIFIER);
        if (!includeText) return output.toByteArray();
        output.write(ATTRIBUTE_TITLE);
        writeLittleEndianShort(output, 160);
        output.write(ATTRIBUTE_SUBTITLE);
        writeLittleEndianShort(output, 120);
        output.write(ATTRIBUTE_MESSAGE);
        writeLittleEndianShort(output, 512);
        output.write(ATTRIBUTE_DATE);
        return output.toByteArray();
    }

    /**
     * Builds Get App Attributes for the immutable human-readable DisplayName.
     *
     * <p>The ANCS wire format requires the UTF-8 AppIdentifier to be NUL-terminated. Empty,
     * embedded-NUL, malformed-Unicode and unreasonably large identifiers are rejected before a
     * control-point write can be attempted.</p>
     */
    @NonNull
    public static byte[] appAttributeRequest(@NonNull String appIdentifier) {
        String normalized = validateAppIdentifier(appIdentifier);
        byte[] encoded = normalized.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(encoded.length + 3);
        output.write(COMMAND_GET_APP_ATTRIBUTES);
        output.write(encoded, 0, encoded.length);
        output.write(0);
        output.write(APP_ATTRIBUTE_DISPLAY_NAME);
        return output.toByteArray();
    }

    /** Explicit alias documenting that DisplayName is the only app attribute requested. */
    @NonNull
    public static byte[] appDisplayNameRequest(@NonNull String appIdentifier) {
        return appAttributeRequest(appIdentifier);
    }

    public static final class AttributeAccumulator {
        private final long expectedUid;
        private final boolean includeText;
        private final int[] requiredAttributes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean invalid;

        /**
         * Compatibility constructor for callers that request the full notification payload.
         * Privacy-mode callers must use {@link #AttributeAccumulator(long, boolean)}.
         */
        public AttributeAccumulator(long expectedUid) {
            this(expectedUid, true);
        }

        public AttributeAccumulator(long expectedUid, boolean includeText) {
            this.expectedUid = expectedUid;
            this.includeText = includeText;
            this.requiredAttributes = includeText
                    ? FULL_NOTIFICATION_ATTRIBUTES : PRIVACY_NOTIFICATION_ATTRIBUTES;
        }

        /** Returns false when the response exceeded its strict bound and must be abandoned. */
        public boolean append(@Nullable byte[] fragment) {
            if (invalid || fragment == null || fragment.length == 0) return !invalid;
            if (bytes.size() + fragment.length > MAX_NOTIFICATION_RESPONSE_BYTES) {
                invalid = true;
                bytes.reset();
                return false;
            }
            bytes.write(fragment, 0, fragment.length);
            return true;
        }

        @Nullable
        public Notification complete() {
            if (invalid) return null;
            byte[] data = bytes.toByteArray();
            if (data.length < 5
                    || unsigned(data[0]) != COMMAND_GET_NOTIFICATION_ATTRIBUTES
                    || littleEndianUnsignedInt(data, 1) != expectedUid) {
                return null;
            }
            Map<Integer, String> attributes = parseAttributeTuples(data, 5);
            if (attributes == null) return null;
            for (int required : requiredAttributes) {
                if (!attributes.containsKey(required)) return null;
            }
            return new Notification(expectedUid,
                    value(attributes, ATTRIBUTE_APP_IDENTIFIER),
                    includeText ? value(attributes, ATTRIBUTE_TITLE) : "",
                    includeText ? value(attributes, ATTRIBUTE_SUBTITLE) : "",
                    includeText ? value(attributes, ATTRIBUTE_MESSAGE) : "",
                    includeText ? value(attributes, ATTRIBUTE_DATE) : "");
        }
    }

    /**
     * Bounded fragment accumulator for a single Get App Attributes(DisplayName) response.
     *
     * <p>One accumulator belongs to one control-point request. It verifies command ID, the
     * mandatory NUL terminator, exact AppIdentifier, strict UTF-8 and the complete DisplayName
     * tuple before returning a result.</p>
     */
    public static final class AppAttributeAccumulator {
        @NonNull private final String expectedAppIdentifier;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean invalid;

        public AppAttributeAccumulator(@NonNull String expectedAppIdentifier) {
            this.expectedAppIdentifier = validateAppIdentifier(expectedAppIdentifier);
        }

        /** Returns false when the response exceeded its strict bound and must be abandoned. */
        public boolean append(@Nullable byte[] fragment) {
            if (invalid || fragment == null || fragment.length == 0) return !invalid;
            if (bytes.size() + fragment.length > MAX_APP_RESPONSE_BYTES) {
                invalid = true;
                bytes.reset();
                return false;
            }
            bytes.write(fragment, 0, fragment.length);
            return true;
        }

        @Nullable
        public String complete() {
            if (invalid) return null;
            byte[] data = bytes.toByteArray();
            if (data.length < 2 || unsigned(data[0]) != COMMAND_GET_APP_ATTRIBUTES) {
                return null;
            }
            int terminator = indexOf(data, (byte) 0, 1);
            if (terminator < 0 || terminator - 1 > MAX_APP_IDENTIFIER_BYTES) return null;
            String appIdentifier = strictUtf8(data, 1, terminator - 1);
            if (appIdentifier == null
                    || !expectedAppIdentifier.equals(appIdentifier.trim())) {
                return null;
            }
            Map<Integer, String> attributes = parseAttributeTuples(data, terminator + 1);
            if (attributes == null
                    || !attributes.containsKey(APP_ATTRIBUTE_DISPLAY_NAME)) {
                return null;
            }
            return value(attributes, APP_ATTRIBUTE_DISPLAY_NAME);
        }
    }

    @NonNull
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

    private static long littleEndianUnsignedInt(@NonNull byte[] value, int offset) {
        if (offset < 0 || offset + 4 > value.length) return -1L;
        return (long) unsigned(value[offset])
                | (long) unsigned(value[offset + 1]) << 8
                | (long) unsigned(value[offset + 2]) << 16
                | (long) unsigned(value[offset + 3]) << 24;
    }

    private static void writeLittleEndianInt(@NonNull ByteArrayOutputStream output, long value) {
        output.write((int) (value & 0xff));
        output.write((int) (value >>> 8 & 0xff));
        output.write((int) (value >>> 16 & 0xff));
        output.write((int) (value >>> 24 & 0xff));
    }

    private static void writeLittleEndianShort(@NonNull ByteArrayOutputStream output, int value) {
        int bounded = Math.max(0, Math.min(0xffff, value));
        output.write(bounded & 0xff);
        output.write(bounded >>> 8 & 0xff);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    @Nullable
    private static Map<Integer, String> parseAttributeTuples(@NonNull byte[] data, int offset) {
        if (offset < 0 || offset > data.length) return null;
        Map<Integer, String> attributes = new LinkedHashMap<>();
        while (offset < data.length) {
            if (offset + 3 > data.length) return null;
            int id = unsigned(data[offset++]);
            int length = unsigned(data[offset]) | unsigned(data[offset + 1]) << 8;
            offset += 2;
            if (offset + length > data.length || attributes.containsKey(id)) return null;
            String decoded = strictUtf8(data, offset, length);
            if (decoded == null) return null;
            attributes.put(id, decoded.trim());
            offset += length;
        }
        return attributes;
    }

    @Nullable
    private static String strictUtf8(@NonNull byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) return null;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length))
                    .toString();
            if (decoded.indexOf('\u0000') >= 0 || decoded.length() > MAX_ATTRIBUTE_CHARS) {
                return null;
            }
            return decoded;
        } catch (CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    @NonNull
    private static String validateAppIdentifier(@Nullable String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isEmpty() || normalized.indexOf('\u0000') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(normalized)) {
            throw new IllegalArgumentException("Invalid ANCS AppIdentifier");
        }
        byte[] encoded = normalized.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_APP_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException("ANCS AppIdentifier is too large");
        }
        return normalized;
    }

    private static int indexOf(@NonNull byte[] value, byte needle, int start) {
        for (int index = Math.max(0, start); index < value.length; index++) {
            if (value[index] == needle) return index;
        }
        return -1;
    }

    @NonNull
    private static String value(@NonNull Map<Integer, String> values, int key) {
        String result = values.get(key);
        return result == null ? "" : result;
    }
}
