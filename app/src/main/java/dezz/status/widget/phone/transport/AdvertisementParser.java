/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Parser for BLE advertising structures missing from the Android 9 public API. */
public final class AdvertisementParser {
    public static final int TYPE_COMPLETE_LOCAL_NAME = 0x09;
    public static final int TYPE_SHORT_LOCAL_NAME = 0x08;
    public static final int TYPE_SERVICE_SOLICITATION_128 = 0x15;

    private AdvertisementParser() {
    }

    public static final class Parsed {
        public final List<UUID> solicitationUuids;
        public final String localName;
        public final String hex;

        Parsed(List<UUID> solicitationUuids, String localName, String hex) {
            this.solicitationUuids = Collections.unmodifiableList(solicitationUuids);
            this.localName = localName;
            this.hex = hex;
        }

        public boolean solicits(UUID uuid) {
            return solicitationUuids.contains(uuid);
        }
    }

    public static Parsed parse(byte[] bytes) {
        if (bytes == null) return new Parsed(new ArrayList<>(), "", "");
        List<UUID> solicitations = new ArrayList<>();
        String name = "";
        int offset = 0;
        while (offset < bytes.length) {
            int length = unsigned(bytes[offset]);
            if (length == 0) break;
            int next = offset + length + 1;
            if (length < 1 || next > bytes.length) break;
            int type = unsigned(bytes[offset + 1]);
            int valueOffset = offset + 2;
            int valueLength = length - 1;
            if (type == TYPE_SERVICE_SOLICITATION_128) {
                for (int cursor = valueOffset;
                     cursor + 16 <= valueOffset + valueLength;
                     cursor += 16) {
                    solicitations.add(uuidFromBluetoothLittleEndian(bytes, cursor));
                }
            } else if ((type == TYPE_COMPLETE_LOCAL_NAME || type == TYPE_SHORT_LOCAL_NAME)
                    && valueLength > 0) {
                name = new String(bytes, valueOffset, valueLength, StandardCharsets.UTF_8).trim();
            }
            offset = next;
        }
        return new Parsed(solicitations, name, hex(bytes, 96));
    }

    static UUID uuidFromBluetoothLittleEndian(byte[] value, int offset) {
        if (value == null || offset < 0 || offset + 16 > value.length) {
            throw new IllegalArgumentException("A 128-bit UUID needs 16 bytes");
        }
        byte[] canonical = new byte[16];
        for (int index = 0; index < 16; index++) {
            canonical[index] = value[offset + 15 - index];
        }
        long most = 0L;
        long least = 0L;
        for (int index = 0; index < 8; index++) {
            most = most << 8 | unsigned(canonical[index]);
            least = least << 8 | unsigned(canonical[index + 8]);
        }
        return new UUID(most, least);
    }

    static byte[] uuidToBluetoothLittleEndian(UUID uuid) {
        byte[] canonical = new byte[16];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int index = 7; index >= 0; index--) {
            canonical[index] = (byte) most;
            most >>>= 8;
            canonical[index + 8] = (byte) least;
            least >>>= 8;
        }
        byte[] little = new byte[16];
        for (int index = 0; index < 16; index++) {
            little[index] = canonical[15 - index];
        }
        return little;
    }

    public static String hex(byte[] value, int maxBytes) {
        if (value == null || value.length == 0) return "";
        int length = Math.min(value.length, Math.max(0, maxBytes));
        StringBuilder result = new StringBuilder(length * 3);
        for (int index = 0; index < length; index++) {
            if (index > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X", unsigned(value[index])));
        }
        if (length < value.length) result.append(" …");
        return result.toString();
    }

    static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part == null ? 0 : part.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            if (part == null) continue;
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    static byte[] copyOfRange(byte[] value, int from, int to) {
        return Arrays.copyOfRange(value, from, to);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
