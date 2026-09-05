/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SystemShadeConfigTest {
    @Test public void layoutRoundTripsWithoutSharingLauncherGeometry() {
        SystemShadeConfig source = new SystemShadeConfig();
        source.panelHeightPx = 590;
        source.element(SystemShadeConfig.Kind.MEDIA).x = 777;
        source.element(SystemShadeConfig.Kind.ACTIONS).columns = 5;

        SystemShadeConfig restored = SystemShadeConfig.fromJson(source.toJson());

        assertEquals(590, restored.panelHeightPx);
        assertEquals(777, restored.element(SystemShadeConfig.Kind.MEDIA).x);
        assertEquals(5, restored.element(SystemShadeConfig.Kind.ACTIONS).columns);
        assertEquals(SystemShadeConfig.Kind.values().length, restored.elements.size());
    }

    @Test public void invalidGeometryIsClampedToShadeBounds() {
        SystemShadeConfig value = new SystemShadeConfig();
        value.panelHeightPx = 400;
        SystemShadeConfig.Element clock = value.element(SystemShadeConfig.Kind.CLOCK);
        clock.x = 4_000;
        clock.y = 4_000;
        clock.width = 4_000;
        clock.height = 4_000;
        value.normalize();

        assertEquals(SystemShadeConfig.LOGICAL_WIDTH, clock.width);
        assertEquals(400, clock.height);
        assertEquals(0, clock.x);
        assertEquals(0, clock.y);
    }

    @Test public void gestureRequiresMeaningfulTravelAndHonoursSafetyGate() {
        assertTrue(SystemShadeGesturePolicy.settleOpen(false, 80f, 0f, 72, 72));
        assertFalse(SystemShadeGesturePolicy.settleOpen(false, 40f, 0f, 72, 72));
        assertFalse(SystemShadeGesturePolicy.settleOpen(true, -90f, 0f, 72, 72));
        assertTrue(SystemShadeGesturePolicy.canOpen(true, true, false));
        assertFalse(SystemShadeGesturePolicy.canOpen(true, true, true));
    }

    @Test public void closedWindowExpandsOnlyAfterGestureSettlesOpen() {
        assertFalse(SystemShadeGesturePolicy.expandWindowBeforeSettle(false, false));
        assertTrue(SystemShadeGesturePolicy.expandWindowBeforeSettle(false, true));
        assertFalse(SystemShadeGesturePolicy.expandWindowBeforeSettle(true, false));
        assertFalse(SystemShadeGesturePolicy.expandWindowBeforeSettle(true, true));
    }
}
