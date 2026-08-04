/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneBleRoleTest {
    @Test public void unknownValuesFallBackToExistingPeripheralRoute() {
        assertEquals(PhoneBleRole.IPHONE_PERIPHERAL, PhoneBleRole.normalize(-1));
        assertEquals(PhoneBleRole.IPHONE_PERIPHERAL, PhoneBleRole.normalize(0));
        assertEquals(PhoneBleRole.IPHONE_PERIPHERAL, PhoneBleRole.normalize(42));
        assertFalse(PhoneBleRole.isIphoneCentral(42));
    }

    @Test public void centralIsTheOnlyOptInAlternateRoute() {
        assertEquals(PhoneBleRole.IPHONE_CENTRAL,
                PhoneBleRole.normalize(PhoneBleRole.IPHONE_CENTRAL));
        assertTrue(PhoneBleRole.isIphoneCentral(PhoneBleRole.IPHONE_CENTRAL));
        assertEquals("iphone_peripheral",
                PhoneBleRole.diagnosticName(PhoneBleRole.IPHONE_PERIPHERAL));
        assertEquals("iphone_central",
                PhoneBleRole.diagnosticName(PhoneBleRole.IPHONE_CENTRAL));
    }
}
