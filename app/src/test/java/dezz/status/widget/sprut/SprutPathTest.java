/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SprutPathTest {
    @Test public void parsesCanonicalAndLegacyForms() {
        SprutPath expected = new SprutPath(101, 201, 301);
        assertEquals(expected, SprutPath.parse("101/201/301"));
        assertEquals(expected, SprutPath.parse("101_201_301"));
        assertEquals(expected, SprutPath.parse("101:201:301"));
        assertEquals("101/201/301", expected.stableId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompletePath() {
        SprutPath.parse("101/201");
    }
}
