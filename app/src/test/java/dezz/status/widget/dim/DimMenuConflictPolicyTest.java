/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DimMenuConflictPolicyTest {
    private final DimMenuPanelConfig config = new DimMenuPanelConfig();

    @Test public void stockSafetySurfacesAlwaysWin() {
        assertEquals(DimMenuConflictPolicy.Reason.CONTROL_CENTER,
                reason(true, true, false, false, 2, 1));
        assertEquals(DimMenuConflictPolicy.Reason.INSTRUMENT_PANEL,
                reason(true, true, false, true, 2, 0));
        assertEquals(DimMenuConflictPolicy.Reason.MNAVI,
                reason(true, true, true, false, 2, 0));
    }

    @Test public void menuAppearsOnlyOnNavigationTabByDefault() {
        assertEquals(DimMenuConflictPolicy.Reason.OTHER_DIM_TAB,
                reason(true, true, false, false, 3, 0));
        assertEquals(DimMenuConflictPolicy.Reason.NONE,
                reason(true, true, false, false, 2, 0));
        assertEquals(DimMenuConflictPolicy.Reason.NONE,
                reason(true, true, false, false, -1, 0));
    }

    @Test public void engineAndDisplayStateHideWithoutStoppingTheService() {
        assertEquals(DimMenuConflictPolicy.Reason.ENGINE_OFF,
                DimMenuConflictPolicy.reason(true, true, false,
                        false, false, 2, 0, config));
        assertEquals(DimMenuConflictPolicy.Reason.DISPLAY_OFF,
                DimMenuConflictPolicy.reason(true, false, true,
                        false, false, 2, 0, config));
    }

    private DimMenuConflictPolicy.Reason reason(boolean enabled, boolean display,
                                                boolean mnav, boolean instrument,
                                                int tab, int center) {
        return DimMenuConflictPolicy.reason(enabled, display, true, mnav,
                instrument, tab, center, config);
    }
}
