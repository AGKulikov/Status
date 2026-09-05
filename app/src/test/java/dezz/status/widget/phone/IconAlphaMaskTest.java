/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class IconAlphaMaskTest {
    @Test public void outsideCornerBecomesARealTransparentPixel() {
        int[] source = {0xFF34C759, 0x8034C759, 0xFF34C759};
        int[] mask = {0x00000000, 0x80000000, 0xFFFFFFFF};

        IconAlphaMask.apply(source, mask);

        assertEquals(0x0034C759, source[0]);
        assertEquals(0x4034C759, source[1]);
        assertEquals(0xFF34C759, source[2]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedMaskCannotSilentlyLeaveSquarePixels() {
        IconAlphaMask.apply(new int[2], new int[1]);
    }
}
