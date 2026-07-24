/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small, Android-independent codec for Apple Notification Center Service (ANCS).
 *
 * <p>ANCS data-source replies may be split at arbitrary BLE packet boundaries. The accumulator
 * therefore keeps one bounded response for the single in-flight control-point request and emits
 * a notification only after every requested attribute is complete.</p>
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
    private static final int ATTRIBUTE_APP_IDENTIFIER = 0;
    private static final int ATTRIBUTE_TITLE = 1;
    private static final int ATTRIBUTE_SUBTITLE = 2;
    private static final int ATTRIBUTE_MESSAGE = 3;
    private static final int ATTRIBUTE_DATE = 5;
    private static final int[] REQUESTED_ATTRIBUTES = {
            ATTRIBUTE_APP_IDENTIFIER,
            ATTRIBUTE_TITLE,
            ATTRIBUTE_SUBTITLE,
            ATTRIBUTE_MESSAGE,
            ATTRIBUTE_DATE
    };
    private static final int MAX_ACCUMULATED_BYTES = 16 * 1024;

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
        output.write(ATTRIBUTE_TITLE);
        writeLittleEndianShort(output, includeText ? 160 : 0);
        output.write(ATTRIBUTE_SUBTITLE);
        writeLittleEndianShort(output, includeText ? 120 : 0);
        output.write(ATTRIBUTE_MESSAGE);
        writeLittleEndianShort(output, includeText ? 512 : 0);
        output.write(ATTRIBUTE_DATE);
        return output.toByteArray();
    }

    public static final class AttributeAccumulator {
        private final long expectedUid;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean invalid;

        public AttributeAccumulator(long expectedUid) {
            this.expectedUid = expectedUid;
        }

        /** Returns false when the response exceeded its strict bound and must be abandoned. */
        public boolean append(@Nullable byte[] fragment) {
            if (invalid || fragment == null || fragment.length == 0) return !invalid;
            if (bytes.size() + fragment.length > MAX_ACCUMULATED_BYTES) {
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
            int offset = 5;
            Map<Integer, String> attributes = new LinkedHashMap<>();
            while (offset < data.length) {
                if (offset + 3 > data.length) return null;
                int id = unsigned(data[offset++]);
                int length = unsigned(data[offset]) | unsigned(data[offset + 1]) << 8;
                offset += 2;
                if (length < 0 || offset + length > data.length) return null;
                byte[] encoded = Arrays.copyOfRange(data, offset, offset + length);
                attributes.put(id, cleanUtf8(encoded));
                offset += length;
            }
            for (int required : REQUESTED_ATTRIBUTES) {
                if (!attributes.containsKey(required)) return null;
            }
            return new Notification(expectedUid,
                    value(attributes, ATTRIBUTE_APP_IDENTIFIER),
                    value(attributes, ATTRIBUTE_TITLE),
                    value(attributes, ATTRIBUTE_SUBTITLE),
                    value(attributes, ATTRIBUTE_MESSAGE),
                    value(attributes, ATTRIBUTE_DATE));
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

    @NonNull
    private static String cleanUtf8(@NonNull byte[] encoded) {
        String value = new String(encoded, StandardCharsets.UTF_8)
                .replace('\u0000', ' ').trim();
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }

    @NonNull
    private static String value(@NonNull Map<Integer, String> values, int key) {
        String result = values.get(key);
        return result == null ? "" : result;
    }
}
