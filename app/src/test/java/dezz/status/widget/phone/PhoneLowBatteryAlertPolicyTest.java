/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneLowBatteryAlertPolicyTest {
    @Test public void coarseFallbackCannotTriggerOrRearmAnExactWarning() {
        for (String source : new String[]{null, "", "iphone_helper_coarse", "hfp_ecarx_coarse", "ancs"}) {
            Integer rejected = PhoneLowBatteryAlertPolicy.exactPercentage(20, source);
            assertNull(rejected);
            assertFalse(PhoneLowBatteryAlertPolicy.evaluate(true, 20, false, rejected).trigger);
            assertTrue(PhoneLowBatteryAlertPolicy.evaluate(true, 20, true,
                    PhoneLowBatteryAlertPolicy.exactPercentage(25, source)).latched);
        }
        for (String source : new String[]{"ble_bas", "android_broadcast", "hfp_ecarx_percent"}) {
            assertEquals(Integer.valueOf(19), PhoneLowBatteryAlertPolicy.exactPercentage(19, source));
            assertTrue(PhoneLowBatteryAlertPolicy.evaluate(true, 20, false,
                    PhoneLowBatteryAlertPolicy.exactPercentage(20, source)).trigger);
        }
    }

    @Test public void invalidOrFractionalSamplesAreNotRoundedIntoWarnings() {
        for (Object value : new Object[]{null, "20", -1, 101, 20.8, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertNull(PhoneLowBatteryAlertPolicy.exactPercentage(value, "ble_bas"));
        }
        assertEquals(Integer.valueOf(0), PhoneLowBatteryAlertPolicy.exactPercentage(0, "ble_bas"));
        assertEquals(Integer.valueOf(100), PhoneLowBatteryAlertPolicy.exactPercentage(100, "ble_bas"));
    }

    @Test public void sourceHandoffAtSamePercentageWarnsOnlyOnceThenRealRecoveryRearms() {
        boolean latched = false;
        PhoneLowBatteryAlertPolicy.Result coarse = PhoneLowBatteryAlertPolicy.evaluate(true, 20, latched,
                PhoneLowBatteryAlertPolicy.exactPercentage(20, "iphone_helper_coarse"));
        assertFalse(coarse.trigger);
        PhoneLowBatteryAlertPolicy.Result exact = PhoneLowBatteryAlertPolicy.evaluate(true, 20, coarse.latched,
                PhoneLowBatteryAlertPolicy.exactPercentage(20, "ble_bas"));
        assertTrue(exact.trigger);
        assertFalse(PhoneLowBatteryAlertPolicy.evaluate(true, 20, exact.latched,
                PhoneLowBatteryAlertPolicy.exactPercentage(19, "android_broadcast")).trigger);
        assertTrue(PhoneLowBatteryAlertPolicy.evaluate(true, 20, exact.latched,
                PhoneLowBatteryAlertPolicy.exactPercentage(25, "iphone_helper_coarse")).latched);
        PhoneLowBatteryAlertPolicy.Result recovered = PhoneLowBatteryAlertPolicy.evaluate(true, 20, exact.latched,
                PhoneLowBatteryAlertPolicy.exactPercentage(22, "ble_bas"));
        assertFalse(recovered.latched);
        assertTrue(PhoneLowBatteryAlertPolicy.evaluate(true, 20, recovered.latched,
                PhoneLowBatteryAlertPolicy.exactPercentage(19, "ble_bas")).trigger);
    }
    @Test
    public void alertFiresOnceAtExactThreshold() {
        PhoneLowBatteryAlertPolicy.Result first =
                PhoneLowBatteryAlertPolicy.evaluate(true, 20, false, 20);
        assertTrue(first.trigger);
        assertTrue(first.latched);

        PhoneLowBatteryAlertPolicy.Result repeated =
                PhoneLowBatteryAlertPolicy.evaluate(true, 20, first.latched, 18);
        assertFalse(repeated.trigger);
        assertTrue(repeated.latched);
    }

    @Test
    public void twoThresholdsMustBeStrictlyOrdered() {
        assertTrue(PhoneLowBatteryAlertPolicy.validOrderedThresholds(20, 10));
        assertFalse(PhoneLowBatteryAlertPolicy.validOrderedThresholds(20, 20));
        assertFalse(PhoneLowBatteryAlertPolicy.validOrderedThresholds(10, 20));
        assertFalse(PhoneLowBatteryAlertPolicy.validOrderedThresholds(101, 10));
        assertFalse(PhoneLowBatteryAlertPolicy.validOrderedThresholds(20, 0));
    }

    @Test
    public void hysteresisPreventsBoundarySpamAndRecoveryRearms() {
        PhoneLowBatteryAlertPolicy.Result boundary =
                PhoneLowBatteryAlertPolicy.evaluate(true, 20, true, 20);
        assertFalse(boundary.trigger);
        assertTrue(boundary.latched);

        PhoneLowBatteryAlertPolicy.Result recovered =
                PhoneLowBatteryAlertPolicy.evaluate(true, 20, true, 22);
        assertFalse(recovered.trigger);
        assertFalse(recovered.latched);

        PhoneLowBatteryAlertPolicy.Result nextDrop =
                PhoneLowBatteryAlertPolicy.evaluate(true, 20, recovered.latched, 19);
        assertTrue(nextDrop.trigger);
    }

    @Test
    public void disabledOrInvalidLevelsNeverTrigger() {
        assertFalse(PhoneLowBatteryAlertPolicy.evaluate(false, 20, true, 5).latched);
        assertFalse(PhoneLowBatteryAlertPolicy.evaluate(true, 20, false, null).trigger);
        assertFalse(PhoneLowBatteryAlertPolicy.evaluate(true, 20, false, 101).trigger);
    }
}
