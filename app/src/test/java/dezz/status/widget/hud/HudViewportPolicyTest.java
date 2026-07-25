/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudViewportPolicyTest {
    @Test public void verifiedMhudPlaneHasExactHardwareCoordinates() {
        assertEquals(728, HudViewportPolicy.SAFE_WIDTH);
        assertEquals(190, HudViewportPolicy.SAFE_HEIGHT);
        assertEquals(0, HudViewportPolicy.SAFE_LEFT);
        assertEquals(720, HudViewportPolicy.SAFE_TOP);
        assertEquals(728, HudViewportPolicy.SAFE_RIGHT);
        assertEquals(910, HudViewportPolicy.SAFE_BOTTOM);
    }

    @Test public void completePlaneRequiresAtLeast728By910Surface() {
        assertTrue(HudViewportPolicy.containsCompleteHudPlane(728, 910));
        assertTrue(HudViewportPolicy.containsCompleteHudPlane(1760, 1440));
        assertFalse(HudViewportPolicy.containsCompleteHudPlane(727, 910));
        assertFalse(HudViewportPolicy.containsCompleteHudPlane(728, 909));
    }

    @Test public void clippingNeverEscapesActualSurfaceOrHudPlane() {
        HudViewportPolicy.Bounds full = HudViewportPolicy.clipToSurface(1920, 1080);
        assertEquals(0, full.left);
        assertEquals(720, full.top);
        assertEquals(728, full.right);
        assertEquals(910, full.bottom);

        HudViewportPolicy.Bounds shortSurface =
                HudViewportPolicy.clipToSurface(500, 800);
        assertEquals(0, shortSurface.left);
        assertEquals(720, shortSurface.top);
        assertEquals(500, shortSurface.right);
        assertEquals(800, shortSurface.bottom);

        HudViewportPolicy.Bounds noHudPlane =
                HudViewportPolicy.clipToSurface(1920, 700);
        assertTrue(noHudPlane.isEmpty());
    }
}
