/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public final class HudProfileWirePatcherTest {
    @Test public void replacesOnlyExistingHudArValue() {
        byte[] source = completeProfile(0);

        byte[] patched = HudProfileWirePatcher.patchHudAr(source, true);

        byte[] expected = completeProfile(1);
        assertArrayEquals(expected, patched);
        assertTrue(HudProfileWirePatcher.isExactPatch(source, patched, true));
        assertEquals(1, HudProfileWirePatcher.readHudAr(patched));
    }

    @Test public void preservesUnknownFutureFieldWithoutReencoding() {
        byte[] base = completeProfile(0);
        byte[] unknown = concat(varint(201L << 3), varint(7));
        byte[] source = concat(base, unknown);

        byte[] patched = HudProfileWirePatcher.patchHudAr(source, true);

        assertArrayEquals(concat(completeProfile(1), unknown), patched);
        assertEquals(1, HudProfileWirePatcher.readHudAr(patched));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesIncompleteProfile() {
        HudProfileWirePatcher.patchHudAr(bytes(0x08, 0x01, 0xf8, 0x06, 0x00), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAllZeroNotReadyProfile() {
        HudProfileWirePatcher.patchHudAr(completeProfileWithFirstValue(0, 0), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesTruncatedWireData() {
        HudProfileWirePatcher.patchHudAr(bytes(0x0a, 0x05, 0x01), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesOversizedHudValueVarint() {
        HudProfileWirePatcher.patchHudAr(
                completeProfileWithOversizedHudValue(), true);
    }

    private static byte[] completeProfile(int hudValue) {
        return completeProfileWithFirstValue(42, hudValue);
    }

    private static byte[] completeProfileWithFirstValue(int firstValue, int hudValue) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int field = 1; field <= HudProfileWirePatcher.LAST_KNOWN_FIELD_NUMBER; field++) {
            byte[] tag = varint(((long) field) << 3);
            byte[] value = varint(field == 1 ? firstValue
                    : field == HudProfileWirePatcher.HUD_AR_FIELD_NUMBER ? hudValue : 0);
            out.write(tag, 0, tag.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    private static byte[] completeProfileWithOversizedHudValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int field = 1; field <= HudProfileWirePatcher.LAST_KNOWN_FIELD_NUMBER; field++) {
            byte[] tag = varint(((long) field) << 3);
            out.write(tag, 0, tag.length);
            if (field == HudProfileWirePatcher.HUD_AR_FIELD_NUMBER) {
                for (int index = 0; index < 9; index++) out.write(0x80);
                out.write(0x02);
            } else {
                byte[] value = varint(field == 1 ? 42 : 0);
                out.write(value, 0, value.length);
            }
        }
        return out.toByteArray();
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.write(array, 0, array.length);
        return out.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }
}
