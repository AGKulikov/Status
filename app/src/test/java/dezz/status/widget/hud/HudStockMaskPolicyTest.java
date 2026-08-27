/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudStockMaskPolicyTest {
    @Test public void stockHudIsHiddenOnlyAfterUsefulCustomFrameIsConfirmed() {
        assertFalse(HudStockMaskPolicy.shouldHideStockCar(true, false, true));
        assertFalse(HudStockMaskPolicy.shouldHideStockCar(true, true, false));
        assertFalse(HudStockMaskPolicy.shouldHideStockCar(false, true, true));
        assertTrue(HudStockMaskPolicy.shouldHideStockCar(true, true, true));
    }
}
