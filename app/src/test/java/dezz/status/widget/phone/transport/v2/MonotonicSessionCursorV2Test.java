/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class MonotonicSessionCursorV2Test {
    @Test public void neverReusesOneAfterLongRollover() {
        MonotonicSessionCursorV2 cursor = new MonotonicSessionCursorV2(Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE - 1L, cursor.next());
        assertThrows(IllegalStateException.class, cursor::next);
        assertThrows(IllegalStateException.class, cursor::next);
    }
}
