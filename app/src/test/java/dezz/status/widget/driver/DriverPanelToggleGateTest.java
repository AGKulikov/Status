/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DriverPanelToggleGateTest {
    @Test public void duplicateCallbackFromOnePhysicalPressIsIgnored() {
        DriverPanelToggleGate gate = new DriverPanelToggleGate();
        assertTrue(gate.accept("favorites:default", 99L, 1_000L));
        assertFalse(gate.accept("favorites:default", 99L, 1_050L));
    }

    @Test public void realSecondPressAndDifferentTargetRemainAvailable() {
        DriverPanelToggleGate repeated = new DriverPanelToggleGate();
        assertTrue(repeated.accept("all_apps", 101L, 1_000L));
        assertTrue(repeated.accept("all_apps", 102L, 1_010L));
        DriverPanelToggleGate changed = new DriverPanelToggleGate();
        assertTrue(changed.accept("all_apps", 0L, 1_000L));
        assertTrue(changed.accept("favorites:default", 0L, 1_001L));
    }
}
