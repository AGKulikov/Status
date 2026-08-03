/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class LauncherInformationGeometryTest {
    @Test public void informationHasIndependentDefaultRectangle() {
        LauncherLayoutStore.Geometry geometry =
                LauncherLayoutStore.defaults(1920, 720).get(LauncherLayoutStore.INFORMATION);
        assertNotNull(geometry);
        assertEquals(620, geometry.width);
        assertEquals(240, geometry.height);
    }

    @Test public void geometryClampKeepsInformationFrameInsideUsableHome() {
        LauncherLayoutStore.Geometry clamped = LauncherLayoutStore.clamp(
                new LauncherLayoutStore.Geometry(-80, 900, 9000, 2), 1280, 600);
        assertEquals(0, clamped.x);
        assertEquals(504, clamped.y);
        assertEquals(1280, clamped.width);
        assertEquals(96, clamped.height);
    }
}
