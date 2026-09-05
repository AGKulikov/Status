/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HwgpsDrStatePolicyTest {
    @Test public void everyFindMeWidgetDrStateActivatesTheTrigger() {
        for (String state : new String[] {
                "fix_dr", "fix_sw_dr", "fix_sw_dr_mm", "fix_sw_yl_safe"}) {
            assertEquals(state, HwgpsDrStatePolicy.State.DR_ACTIVE,
                    HwgpsDrStatePolicy.classify(state));
            assertTrue(state, HwgpsDrStatePolicy.findMeAvailable(state));
        }
    }

    @Test public void ordinaryFixAndAdverseGnssStatesDoNotPretendDrIsActive() {
        for (String state : new String[] {"notFixed", "fix_ok", "filtered", "spoofing"}) {
            assertEquals(state, HwgpsDrStatePolicy.State.DR_INACTIVE,
                    HwgpsDrStatePolicy.classify(state));
            assertFalse(state, HwgpsDrStatePolicy.findMeAvailable(state));
        }
    }

    @Test public void emptyAndFutureStatesFailUnavailable() {
        assertEquals(HwgpsDrStatePolicy.State.UNAVAILABLE,
                HwgpsDrStatePolicy.classify(null));
        assertEquals(HwgpsDrStatePolicy.State.UNAVAILABLE,
                HwgpsDrStatePolicy.classify(""));
        assertEquals(HwgpsDrStatePolicy.State.UNAVAILABLE,
                HwgpsDrStatePolicy.classify("future_state"));
    }
}
