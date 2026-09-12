/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MapFirstFrameDetectorTest {
    @Test public void rejectsTheUniformOpaqueWhiteKx11BootstrapBuffer() {
        int[] pixels = new int[MapFirstFrameDetector.SAMPLE_WIDTH
                * MapFirstFrameDetector.SAMPLE_HEIGHT];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);

        assertFalse(MapFirstFrameDetector.hasRenderableContent(pixels, pixels.length));
    }

    @Test public void rejectsMinorNoiseInsideAnOtherwiseWhiteBootstrapBuffer() {
        int[] pixels = new int[500];
        java.util.Arrays.fill(pixels, 0xFFFEFDFC);
        for (int index = 0; index < 9; index++) pixels[index] = 0xFFF9FAFB;

        assertFalse(MapFirstFrameDetector.hasRenderableContent(pixels, pixels.length));
    }

    @Test public void acceptsContentButNeverAnEmptyTransparentBuffer() {
        int[] map = new int[500];
        java.util.Arrays.fill(map, 0xFFFFFFFF);
        for (int index = 0; index < 500; index += 13) map[index] = 0xFF162033;
        int[] transparent = new int[500];

        assertTrue(MapFirstFrameDetector.hasRenderableContent(map, map.length));
        assertFalse(MapFirstFrameDetector.hasRenderableContent(transparent, transparent.length));
        for (int index = 0; index < 500; index += 13) transparent[index] = 0xFF40D020;
        assertTrue(MapFirstFrameDetector.hasRenderableContent(transparent, transparent.length));
    }

    @Test public void readyAcknowledgementAndConsecutiveContentAreBothRequired() {
        MapFirstFrameDetector.Gate gate = new MapFirstFrameDetector.Gate();
        for (int index = 0; index < 20; index++) assertFalse(gate.acceptSample(false, true));
        assertFalse(gate.acceptSample(true, true));
        assertFalse(gate.acceptSample(true, false));
        assertFalse(gate.acceptSample(true, true));
        assertFalse(gate.acceptSample(true, true));
        assertTrue(gate.acceptSample(true, true));
        gate.reset();
        assertFalse(gate.acceptSample(true, true));
    }
}
