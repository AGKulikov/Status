/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HwgpsRouteStatePolicyTest {
    @Test public void exactNoFixStateIsLost() {
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                HwgpsRouteStatePolicy.classify("notFixed"));
    }

    @Test public void everyEvidencedFixWidgetStateClearsLost() {
        for (String state : new String[] {"fix_ok", "fix_dr", "fix_sw_dr",
                "fix_sw_dr_mm", "fix_sw_yl_safe", "filtered", "spoofing"}) {
            assertEquals(state, HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                    HwgpsRouteStatePolicy.classify(state));
        }
    }

    @Test public void emptyAndFutureStatesFailUnavailable() {
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE,
                HwgpsRouteStatePolicy.classify(null));
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE,
                HwgpsRouteStatePolicy.classify(""));
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE,
                HwgpsRouteStatePolicy.classify("future_state"));
    }
}
