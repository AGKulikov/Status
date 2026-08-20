/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneBatteryLevelPolicyTest {
    @Test public void androidOnePercentValueWinsOverSteppedHelper() {
        PhoneBatteryLevelPolicy.Reading reading = PhoneBatteryLevelPolicy.resolve(
                false, null, 0L,
                true, 96, 200L,
                95,
                true, 100, false);

        assertNotNull(reading);
        assertEquals(96, reading.level);
        assertEquals("android_broadcast", reading.source);
        assertTrue(reading.direct);
    }

    @Test public void latestDirectBasValueWinsAndCannotBeRolledBackByHelper() {
        PhoneBatteryLevelPolicy.Reading reading = PhoneBatteryLevelPolicy.resolve(
                true, 94, 300L,
                true, 95, 200L,
                90,
                true, 100, false);

        assertNotNull(reading);
        assertEquals(94, reading.level);
        assertEquals("ble_bas", reading.source);
        assertTrue(reading.direct);
    }

    @Test public void helperIsExplicitlyCoarseFallbackOnly() {
        PhoneBatteryLevelPolicy.Reading reading = PhoneBatteryLevelPolicy.resolve(
                false, null, 0L,
                false, null, 0L,
                95,
                true, 100, false);

        assertNotNull(reading);
        assertEquals(95, reading.level);
        assertEquals("iphone_helper_coarse", reading.source);
        assertFalse(reading.direct);
    }

    @Test public void fullRangeOemValueBeatsCoarseSources() {
        PhoneBatteryLevelPolicy.Reading reading = PhoneBatteryLevelPolicy.resolve(
                false, null, 0L,
                false, null, 0L,
                95,
                true, 96, true);

        assertNotNull(reading);
        assertEquals(96, reading.level);
        assertEquals("hfp_ecarx_percent", reading.source);
        assertTrue(reading.direct);
    }

    @Test public void invalidValuesAreNeverPublished() {
        assertNull(PhoneBatteryLevelPolicy.resolve(
                true, 101, 5L,
                true, -1, 6L,
                null,
                false, null, false));
    }
}
