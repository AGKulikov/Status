/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HudTurnSignalsTest {
    @Test public void inactiveSideStaysHiddenThroughWholeCycle() {
        for (long t = 0; t < 2000; t += 100) {
            assertFalse(HudTurnSignals.visible(false, false, true, t, 500));
        }
    }
    @Test public void hazardSharesExactPhaseBoundary() {
        assertFalse(HudTurnSignals.visible(true, false, true, 499, 500));
        assertTrue(HudTurnSignals.visible(true, false, true, 500, 500));
        assertFalse(HudTurnSignals.visible(true, false, true, 1000, 500));
    }
    @Test public void editorShowsBothEvenWithoutTelemetry() {
        assertTrue(HudTurnSignals.visible(false, true, true, 0, 500));
    }
    @Test public void disabledAnimationStillRequiresActiveSignal() {
        assertTrue(HudTurnSignals.visible(true, false, false, 0, 500));
        assertFalse(HudTurnSignals.visible(false, false, false, 0, 500));
    }
}
