/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.hudlab;

import java.util.Arrays;

/**
 * Byte-preserving protobuf patches for the two HUD fields in Profileclouddata.
 *
 * <p>ECARX maps vfhudbyte0 (field 111) to HUD_AR_ENGINE and profiletransferbyte3
 * (field 124) to Profile.CAR_FUNC_HUD_MODE. The complete 150-field stream is
 * validated before either value is changed.</p>
 */
final class HudProfileWirePatcher {
    private static final int HUD_AR_FIELD = 111;
    private static final int HUD_MODE_FIELD = 124;
    static final int LAST_KNOWN_FIELD = 150;

    private HudProfileWirePatcher() {
    }

    static int readHudAr(byte[] bytes) {
        Scan scan = scan(bytes, HUD_AR_FIELD);
        requireComplete(scan);
        if (scan.value != 0 && scan.value != 1) {
            throw new IllegalArgumentException("Invalid HUD AR value");
        }
        return scan.value;
    }

    static byte[] patchHudAr(byte[] bytes, boolean enabled) {
        return patchField(bytes, HUD_AR_FIELD, enabled ? 1 : 0);
    }

    static int readHudMode(byte[] bytes) {
        Scan scan = scan(bytes, HUD_MODE_FIELD);
        requireComplete(scan);
        return scan.value;
    }

    static byte[] patchHudMode(byte[] bytes, int mode) {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("HUD mode must be 0…3");
        }
        return patchField(bytes, HUD_MODE_FIELD, mode);
    }

    private static byte[] patchField(byte[] bytes, int field, int value) {
        Scan scan = scan(bytes, field);
        requireComplete(scan);
        byte[] replacement = encode(value);
        byte[] result = new byte[
                bytes.length - (scan.valueEnd - scan.valueStart) + replacement.length];
        System.arraycopy(bytes, 0, result, 0, scan.valueStart);
        System.arraycopy(replacement, 0, result, scan.valueStart, replacement.length);
        System.arraycopy(bytes, scan.valueEnd, result,
                scan.valueStart + replacement.length, bytes.length - scan.valueEnd);
        return result;
    }

    static boolean isExactPatch(byte[] before, byte[] after, boolean enabled) {
        try {
            return Arrays.equals(after, patchHudAr(before, enabled))
                    && readHudAr(after) == (enabled ? 1 : 0);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static boolean isExactHudModePatch(byte[] before, byte[] after, int mode) {
        try {
            return Arrays.equals(after, patchHudMode(before, mode))
                    && readHudMode(after) == mode;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static Scan scan(byte[] bytes, int targetField) {
        if (bytes == null) throw new IllegalArgumentException("ProfileCloudData is null");
        Scan scan = new Scan();
        boolean[] seen = new boolean[LAST_KNOWN_FIELD + 1];
        int offset = 0;
        while (offset < bytes.length) {
            Varint tag = varint(bytes, offset);
            if (tag.value == 0 || tag.value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid protobuf tag");
            }
            offset = tag.end;
            int field = (int) (tag.value >>> 3);
            int wire = (int) (tag.value & 7);
            if (field <= 0) throw new IllegalArgumentException("Invalid protobuf field");
            scan.fields++;
            if (field <= LAST_KNOWN_FIELD) {
                if (wire != 0) {
                    throw new IllegalArgumentException(
                            "Invalid wire type for known field " + field);
                }
                if (seen[field]) {
                    throw new IllegalArgumentException("Duplicate known field " + field);
                }
                seen[field] = true;
                scan.knownFields++;
                Varint value = varint(bytes, offset);
                if (field == targetField) {
                    scan.count++;
                    scan.value = (int) value.value;
                    scan.valueStart = offset;
                    scan.valueEnd = value.end;
                } else if (value.value != 0) {
                    scan.hasNonZeroOutsideHud = true;
                }
                offset = value.end;
            } else {
                offset = skip(bytes, offset, wire, field);
            }
        }
        return scan;
    }

    private static void requireComplete(Scan scan) {
        if (scan.knownFields != LAST_KNOWN_FIELD) {
            throw new IllegalArgumentException(
                    "Incomplete ProfileCloudData: " + scan.knownFields
                            + "/" + LAST_KNOWN_FIELD + " known fields");
        }
        if (scan.count != 1) {
            throw new IllegalArgumentException("Expected exactly one target HUD field");
        }
        if (!scan.hasNonZeroOutsideHud) {
            throw new IllegalArgumentException("All-zero ProfileCloudData placeholder");
        }
    }

    private static int skip(byte[] bytes, int offset, int wire, int field) {
        switch (wire) {
            case 0:
                return varint(bytes, offset).end;
            case 1:
                return advance(bytes, offset, 8);
            case 2: {
                Varint length = varint(bytes, offset);
                if (length.value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Oversized protobuf field");
                }
                return advance(bytes, length.end, (int) length.value);
            }
            case 3:
                return skipGroup(bytes, offset, field);
            case 4:
                throw new IllegalArgumentException("Unexpected end-group");
            case 5:
                return advance(bytes, offset, 4);
            default:
                throw new IllegalArgumentException("Unsupported protobuf wire type");
        }
    }

    private static int skipGroup(byte[] bytes, int offset, int groupField) {
        int cursor = offset;
        while (cursor < bytes.length) {
            Varint tag = varint(bytes, cursor);
            cursor = tag.end;
            int field = (int) (tag.value >>> 3);
            int wire = (int) (tag.value & 7);
            if (wire == 4) {
                if (field != groupField) {
                    throw new IllegalArgumentException("Mismatched end-group");
                }
                return cursor;
            }
            cursor = skip(bytes, cursor, wire, field);
        }
        throw new IllegalArgumentException("Unterminated protobuf group");
    }

    private static int advance(byte[] bytes, int offset, int length) {
        if (length < 0 || offset < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("Truncated protobuf field");
        }
        return offset + length;
    }

    private static Varint varint(byte[] bytes, int offset) {
        long value = 0;
        for (int shift = 0; shift < 64 && offset < bytes.length; shift += 7, offset++) {
            int current = bytes[offset] & 0xff;
            if (shift == 63 && (current & 0xfe) != 0) {
                throw new IllegalArgumentException("Oversized protobuf varint");
            }
            value |= ((long) (current & 0x7f)) << shift;
            if ((current & 0x80) == 0) return new Varint(value, offset + 1);
        }
        throw new IllegalArgumentException("Truncated/oversized protobuf varint");
    }

    private static byte[] encode(long value) {
        byte[] result = new byte[10];
        int count = 0;
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            result[count++] = (byte) (value == 0 ? next : next | 0x80);
        } while (value != 0);
        return Arrays.copyOf(result, count);
    }

    private static final class Varint {
        final long value;
        final int end;
        Varint(long value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static final class Scan {
        int fields;
        int knownFields;
        int count;
        int value;
        int valueStart;
        int valueEnd;
        boolean hasNonZeroOutsideHud;
    }
}
