/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.hudlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public final class HudProfileWirePatcherTest {
    @Test public void changesOnlyField111() {
        byte[] before = completeProfile(0, 1);
        byte[] after = HudProfileWirePatcher.patchHudAr(before, true);
        assertArrayEquals(completeProfile(1, 1), after);
        assertTrue(HudProfileWirePatcher.isExactPatch(before, after, true));
        assertEquals(1, HudProfileWirePatcher.readHudAr(after));
        assertEquals(1, HudProfileWirePatcher.readHudMode(after));
    }

    @Test public void changesOnlyField124() {
        byte[] before = completeProfile(1, 1);
        byte[] after = HudProfileWirePatcher.patchHudMode(before, 2);
        assertArrayEquals(completeProfile(1, 2), after);
        assertTrue(HudProfileWirePatcher.isExactHudModePatch(before, after, 2));
        assertEquals(2, HudProfileWirePatcher.readHudMode(after));
        assertEquals(1, HudProfileWirePatcher.readHudAr(after));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompleteProfile() {
        HudProfileWirePatcher.patchHudAr(bytes(0x08, 0x01, 0xf8, 0x06, 0x00), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAllZeroPlaceholder() {
        HudProfileWirePatcher.patchHudAr(
                completeProfileWithFirstValue(0, 0, 0), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedHudValueVarint() {
        HudProfileWirePatcher.patchHudAr(completeProfileWithOversizedHudValue(), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidHudMode() {
        HudProfileWirePatcher.patchHudMode(completeProfile(0, 1), 4);
    }

    private static byte[] completeProfile(int hudArValue, int hudModeValue) {
        return completeProfileWithFirstValue(42, hudArValue, hudModeValue);
    }

    private static byte[] completeProfileWithFirstValue(int firstValue, int hudValue) {
        return completeProfileWithFirstValue(firstValue, hudValue, 1);
    }

    private static byte[] completeProfileWithFirstValue(
            int firstValue, int hudArValue, int hudModeValue) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int field = 1; field <= HudProfileWirePatcher.LAST_KNOWN_FIELD; field++) {
            byte[] tag = varint(((long) field) << 3);
            byte[] value = varint(field == 1 ? firstValue
                    : field == 111 ? hudArValue
                    : field == 124 ? hudModeValue : 0);
            out.write(tag, 0, tag.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    private static byte[] completeProfileWithOversizedHudValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int field = 1; field <= HudProfileWirePatcher.LAST_KNOWN_FIELD; field++) {
            byte[] tag = varint(((long) field) << 3);
            out.write(tag, 0, tag.length);
            if (field == 111) {
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

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            out[index] = (byte) values[index];
        }
        return out;
    }
}
