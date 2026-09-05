/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneLowBatteryAlertPolicyTest {
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
