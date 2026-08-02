package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaGridLayoutMathTest {
    @Test public void spansFillExactAvailableWidthWithoutDrift() {
        int available = 1_207;
        int spacing = 7;
        int firstStart = MediaGridLayoutMath.startPx(available, spacing, 12, 0);
        int firstWidth = MediaGridLayoutMath.spanPx(available, spacing, 12, 0, 3);
        int nextStart = MediaGridLayoutMath.startPx(available, spacing, 12, 3);
        assertEquals(0, firstStart);
        assertEquals(nextStart - spacing, firstWidth);
        assertEquals(available - MediaGridLayoutMath.startPx(available, spacing, 12, 11),
                MediaGridLayoutMath.spanPx(available, spacing, 12, 11, 1));
    }

    @Test public void draggedLeadingEdgeSnapsAndStaysInsideGrid() {
        assertEquals(0, MediaGridLayoutMath.startForPx(-500, 1_200, 8, 12, 4));
        assertEquals(8, MediaGridLayoutMath.startForPx(5_000, 1_200, 8, 12, 4));
        int cellFive = MediaGridLayoutMath.startPx(1_200, 8, 12, 5);
        assertEquals(5, MediaGridLayoutMath.startForPx(cellFive + 2, 1_200, 8, 12, 2));
    }

    @Test public void invalidSpansAndStartsAreClamped() {
        assertEquals(1, MediaGridLayoutMath.clampSpan(0, 12));
        assertEquals(12, MediaGridLayoutMath.clampSpan(99, 12));
        assertEquals(7, MediaGridLayoutMath.clampStart(99, 5, 12));
    }

    @Test public void resizeHandleUsesTheSameRenderedGridEdges() {
        int width = 1_200;
        int spacing = 8;
        int expectedRight = MediaGridLayoutMath.startPx(width, spacing, 12, 9) - spacing;
        assertEquals(6, MediaGridLayoutMath.spanForEndPx(
                expectedRight, width, spacing, 12, 3));
        assertEquals(1, MediaGridLayoutMath.spanForEndPx(
                -500, width, spacing, 12, 3));
        assertEquals(9, MediaGridLayoutMath.spanForEndPx(
                5_000, width, spacing, 12, 3));
    }
}
