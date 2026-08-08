/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EcarxAdasSignalCatalogTest {
    @Test public void dumpConfirmedKx11PropertySetStaysExactAndReadOnly() {
        assertArrayEquals(new int[] {
                28916, 28917, 28965, 29024, 29044, 30469,
                30860, 30861, 30891, 31354, 31363
        }, EcarxAdasSignalCatalog.propertyIds());
        assertTrue(EcarxAdasSignalCatalog.contains(30469));
        assertEquals("SteerWhlBtnPsd", EcarxAdasSignalCatalog.signalName(30469));
        assertEquals("getCrsCtrlrSts", EcarxAdasSignalCatalog.getterName(31363));
        assertEquals("steering_input", EcarxAdasSignalCatalog.signalKind(30861));
        assertEquals("adas_state", EcarxAdasSignalCatalog.signalKind(31354));
        assertFalse(EcarxAdasSignalCatalog.contains(1));
    }

    @Test public void knownCruiseLimiterAndGapValuesAreHumanReadable() {
        assertEquals("TimeGap_3", EcarxAdasSignalCatalog.decode(29024, 3));
        assertEquals("Active", EcarxAdasSignalCatalog.decode(31354, 3));
        assertEquals("Standby", EcarxAdasSignalCatalog.decode(31363, 2));
        assertEquals("TemporaryFailure", EcarxAdasSignalCatalog.decode(29044, 7));
        assertEquals("raw=42", EcarxAdasSignalCatalog.decode(30469, 42));
    }
}
