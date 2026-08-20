/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneIndicatorVisualPolicyTest {
    @Test public void carPlayCellularGapNeverCollapsesAtSmallSizes() {
        assertEquals(6, PhoneIndicatorVisualPolicy.cellularIconTextGapPx(1));
        assertEquals(3, PhoneIndicatorVisualPolicy.cellularTextGapPx(1));
    }

    @Test public void cellularGapScalesWithTheConfiguredPhysicalIcon() {
        int small = PhoneIndicatorVisualPolicy.cellularIconTextGapPx(40);
        int large = PhoneIndicatorVisualPolicy.cellularIconTextGapPx(100);
        assertEquals(6, small);
        assertEquals(16, large);
        assertTrue(PhoneIndicatorVisualPolicy.cellularTextGapPx(100) < large);
    }

    @Test public void outlinedNetworkTypeAlwaysGetsAnEdgeReserve() {
        assertEquals(2, PhoneIndicatorVisualPolicy.cellularTextEdgeReservePx(1, 0f));
        assertTrue(PhoneIndicatorVisualPolicy.cellularTextEdgeReservePx(100, 4f)
                > PhoneIndicatorVisualPolicy.cellularTextEdgeReservePx(40, 1f));
    }
}
