/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MapEdgeFadePolicyTest {
    @Test public void offOrZeroConsumesNoMaskLayer() {
        assertFalse(MapEdgeFadePolicy.enabled(false, 728, 190, 24, 100));
        assertFalse(MapEdgeFadePolicy.enabled(true, 728, 190, 0, 100));
        assertFalse(MapEdgeFadePolicy.enabled(true, 728, 190, 24, 0));
        assertFalse(MapEdgeFadePolicy.enabled(true, 0, 190, 24, 100));
        assertTrue(MapEdgeFadePolicy.enabled(true, 728, 190, 24, 100));
    }

    @Test public void pixelBandIsIndependentOfMapSizeAndLeavesSharpCentre() {
        for (float size : new float[]{190, 728, 1200}) {
            float[] stops = MapEdgeFadePolicy.stops(size, 24);
            assertEquals(24f, stops[1] * size, .001f);
            assertEquals(size - 24f, stops[2] * size, .001f);
            assertTrue(stops[1] < stops[2]);
        }
    }

    @Test public void extremeBandOnSmallMapRemainsValid() {
        for (float width : new float[]{1, 2, 24, 190}) {
            float[] stops = MapEdgeFadePolicy.stops(width, 300);
            assertEquals(0f, stops[0], 0f);
            assertEquals(1f, stops[3], 0f);
            for (int i = 1; i < 4; i++) assertTrue(stops[i] > stops[i - 1]);
        }
    }

    @Test public void strengthControlsEdgeOpacityWithoutChangingBandSize() {
        assertEquals(255, MapEdgeFadePolicy.edgeAlpha(0));
        assertEquals(128, MapEdgeFadePolicy.edgeAlpha(50));
        assertEquals(0, MapEdgeFadePolicy.edgeAlpha(100));
        assertEquals(0, MapEdgeFadePolicy.edgeAlpha(200));
        assertEquals(255, MapEdgeFadePolicy.edgeAlpha(-1));
    }
}
