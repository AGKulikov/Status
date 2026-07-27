/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Patches {@code PA_PSET_ProfileCloudData.vfhudbyte0} without reserialising the vendor message.
 *
 * <p>The public ECARX user-profile API exposes only a subset of the 150 fields present in the
 * underlying protobuf. Rebuilding that protobuf from {@code IProfile} JSON therefore clears
 * fields which the public API cannot see. This wire-level patch preserves every original byte,
 * including unknown future fields, and replaces only protobuf field 111.</p>
 */
public final class HudProfileWirePatcher {
    /** {@code Profileclouddata.vfhudbyte0}. */
    static final int HUD_AR_FIELD_NUMBER = 111;
    static final int LAST_KNOWN_FIELD_NUMBER = 150;
    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_START_GROUP = 3;
    private static final int WIRE_END_GROUP = 4;
    private static final int WIRE_FIXED32 = 5;

    private HudProfileWirePatcher() {
    }

    /** Read the HUD AR value after validating all 150 fields emitted by this vendor schema. */
    public static int readHudAr(@NonNull byte[] profileBytes) {
        ParseResult result = scan(profileBytes);
        requireCompleteProfile(result);
        return result.hudArValue;
    }

    /**
     * Return a byte-for-byte copy with only field 111 changed to {@code 0} or {@code 1}.
     *
     * <p>The inspected vendor writer always emits fields 1..150, including zeroes. Missing,
     * duplicate or non-varint known fields and all-zero pre-PA placeholders are rejected. Unknown
     * future fields above 150 are preserved byte-for-byte.</p>
     */
    @NonNull
    public static byte[] patchHudAr(@NonNull byte[] profileBytes, boolean enabled) {
        ParseResult result = scan(profileBytes);
        requireCompleteProfile(result);

        byte[] replacement = encodeVarint(enabled ? 1L : 0L);
        byte[] patched = new byte[
                profileBytes.length - (result.hudArValueEnd - result.hudArValueStart)
                        + replacement.length];
        System.arraycopy(profileBytes, 0, patched, 0, result.hudArValueStart);
        System.arraycopy(replacement, 0, patched, result.hudArValueStart, replacement.length);
        System.arraycopy(profileBytes, result.hudArValueEnd, patched,
                result.hudArValueStart + replacement.length,
                profileBytes.length - result.hudArValueEnd);
        return patched;
    }

    /** Verify that applying the patch did not alter any byte outside field 111. */
    public static boolean isExactPatch(@NonNull byte[] before, @NonNull byte[] after,
                                       boolean enabled) {
        try {
            return Arrays.equals(after, patchHudAr(before, enabled))
                    && readHudAr(after) == (enabled ? 1 : 0);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    @NonNull
    private static ParseResult scan(@NonNull byte[] bytes) {
        ParseResult result = new ParseResult();
        boolean[] knownFields = new boolean[LAST_KNOWN_FIELD_NUMBER + 1];
        int offset = 0;
        while (offset < bytes.length) {
            Varint tag = readVarint(bytes, offset);
            if (tag.value == 0L || tag.value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid protobuf tag at offset " + offset);
            }
            offset = tag.end;
            int fieldNumber = (int) (tag.value >>> 3);
            int wireType = (int) (tag.value & 7L);
            if (fieldNumber <= 0) {
                throw new IllegalArgumentException(
                        "Invalid protobuf field number at offset " + offset);
            }
            result.fieldOccurrences++;
            if (fieldNumber <= LAST_KNOWN_FIELD_NUMBER) {
                if (wireType != WIRE_VARINT) {
                    throw new IllegalArgumentException(
                            "Known ProfileCloudData field " + fieldNumber
                                    + " has unexpected wire type " + wireType);
                }
                if (knownFields[fieldNumber]) {
                    throw new IllegalArgumentException(
                            "Duplicate ProfileCloudData field " + fieldNumber);
                }
                knownFields[fieldNumber] = true;
                result.knownFieldCount++;
                Varint value = readVarint(bytes, offset);
                if (fieldNumber == HUD_AR_FIELD_NUMBER) {
                    if (value.value != 0L && value.value != 1L) {
                        throw new IllegalArgumentException(
                                "HUD AR field has unsupported value " + value.value);
                    }
                    result.hudArOccurrences++;
                    result.hudArValue = (int) value.value;
                    result.hudArValueStart = offset;
                    result.hudArValueEnd = value.end;
                } else if (value.value != 0L) {
                    result.hasNonZeroValueOutsideHud = true;
                }
                offset = value.end;
            } else {
                offset = skipValue(bytes, offset, wireType, fieldNumber);
            }
        }
        return result;
    }

    private static void requireCompleteProfile(@NonNull ParseResult result) {
        if (result.knownFieldCount != LAST_KNOWN_FIELD_NUMBER) {
            throw new IllegalArgumentException(
                    "ECARX ProfileCloudData is incomplete: "
                            + result.knownFieldCount + "/" + LAST_KNOWN_FIELD_NUMBER
                            + " known fields");
        }
        if (result.hudArOccurrences != 1) {
            throw new IllegalArgumentException(
                    "ECARX ProfileCloudData does not contain exactly one HUD AR field");
        }
        if (!result.hasNonZeroValueOutsideHud) {
            throw new IllegalArgumentException(
                    "ECARX ProfileCloudData is an all-zero placeholder; PA data is not ready");
        }
    }

    private static int skipValue(byte[] bytes, int offset, int wireType, int fieldNumber) {
        switch (wireType) {
            case WIRE_VARINT:
                return readVarint(bytes, offset).end;
            case WIRE_FIXED64:
                return checkedAdvance(bytes, offset, 8);
            case WIRE_LENGTH_DELIMITED: {
                Varint length = readVarint(bytes, offset);
                if (length.value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Protobuf field is too large");
                }
                return checkedAdvance(bytes, length.end, (int) length.value);
            }
            case WIRE_START_GROUP:
                return skipGroup(bytes, offset, fieldNumber);
            case WIRE_END_GROUP:
                throw new IllegalArgumentException("Unexpected protobuf end-group");
            case WIRE_FIXED32:
                return checkedAdvance(bytes, offset, 4);
            default:
                throw new IllegalArgumentException(
                        "Unsupported protobuf wire type " + wireType);
        }
    }

    private static int skipGroup(byte[] bytes, int offset, int groupFieldNumber) {
        int cursor = offset;
        while (cursor < bytes.length) {
            Varint tag = readVarint(bytes, cursor);
            cursor = tag.end;
            int fieldNumber = (int) (tag.value >>> 3);
            int wireType = (int) (tag.value & 7L);
            if (wireType == WIRE_END_GROUP) {
                if (fieldNumber != groupFieldNumber) {
                    throw new IllegalArgumentException("Mismatched protobuf end-group");
                }
                return cursor;
            }
            cursor = skipValue(bytes, cursor, wireType, fieldNumber);
        }
        throw new IllegalArgumentException("Unterminated protobuf group");
    }

    private static int checkedAdvance(byte[] bytes, int offset, int length) {
        if (length < 0 || offset < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("Truncated protobuf field");
        }
        return offset + length;
    }

    @NonNull
    private static Varint readVarint(byte[] bytes, int offset) {
        long value = 0L;
        for (int shift = 0; shift < 64 && offset < bytes.length; shift += 7, offset++) {
            int current = bytes[offset] & 0xff;
            if (shift == 63 && (current & 0xfe) != 0) {
                throw new IllegalArgumentException("Oversized protobuf varint");
            }
            value |= ((long) (current & 0x7f)) << shift;
            if ((current & 0x80) == 0) {
                return new Varint(value, offset + 1);
            }
        }
        throw new IllegalArgumentException("Truncated or oversized protobuf varint");
    }

    @NonNull
    private static byte[] encodeVarint(long value) {
        byte[] encoded = new byte[10];
        int count = 0;
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            encoded[count++] = (byte) (value == 0 ? next : next | 0x80);
        } while (value != 0);
        return Arrays.copyOf(encoded, count);
    }

    private static final class Varint {
        final long value;
        final int end;

        Varint(long value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static final class ParseResult {
        int fieldOccurrences;
        int knownFieldCount;
        int hudArOccurrences;
        int hudArValue;
        int hudArValueStart;
        int hudArValueEnd;
        boolean hasNonZeroValueOutsideHud;
    }
}
