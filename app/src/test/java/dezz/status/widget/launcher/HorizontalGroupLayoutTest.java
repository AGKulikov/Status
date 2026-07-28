/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class HorizontalGroupLayoutTest {
    @Test public void compactRowAcceptsTrulyZeroGapAndPadding() {
        List<HorizontalGroupLayout.Rect> result = HorizontalGroupLayout.layout(
                10, 20, 300, 100,
                0, 0, 0, 0, 0,
                0, 1, HorizontalGroupLayout.DISTRIBUTION_COMPACT,
                Arrays.asList(new HorizontalGroupLayout.Size(80, 40),
                        new HorizontalGroupLayout.Size(120, 60)));

        assertEquals(2, result.size());
        assertEquals(10, result.get(0).x);
        assertEquals(80, result.get(0).width);
        assertEquals(50, result.get(0).y);
        assertEquals(90, result.get(1).x);
        assertEquals(120, result.get(1).width);
        assertEquals(40, result.get(1).y);
    }

    @Test public void equalDistributionUsesCompleteInnerWidthWithoutTextScaling() {
        List<HorizontalGroupLayout.Rect> result = HorizontalGroupLayout.layout(
                0, 0, 305, 80,
                5, 4, 5, 4, 5,
                0, 0, HorizontalGroupLayout.DISTRIBUTION_EQUAL,
                Arrays.asList(new HorizontalGroupLayout.Size(20, 24),
                        new HorizontalGroupLayout.Size(90, 24),
                        new HorizontalGroupLayout.Size(40, 24)));

        assertEquals(3, result.size());
        assertEquals(5, result.get(0).x);
        assertEquals(result.get(0).width + 5, result.get(1).x);
        assertEquals(result.get(1).x + result.get(1).width + 5, result.get(2).x);
        assertEquals(300, result.get(2).x + result.get(2).width);
        assertEquals(4, result.get(0).y);
        assertEquals(24, result.get(0).height);
    }
}
