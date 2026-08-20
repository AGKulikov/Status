package dezz.status.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaProgressWidthPolicyTest {
    @Test public void shortTitleUsesOnlyMeasuredTitleAndPadding() {
        assertEquals(127, MediaProgressWidthPolicy.width(120.2f, 3, 3, 600));
    }

    @Test public void longTitleIsCappedByItsVisibleViewport() {
        assertEquals(420, MediaProgressWidthPolicy.width(900f, 4, 4, 420));
    }

    @Test public void durationAndParentRowNeverEnterTheCalculation() {
        assertEquals(100, MediaProgressWidthPolicy.width(100f, 0, 0, 800));
    }

    @Test public void invalidMeasurementsFailToOneVisiblePixel() {
        assertEquals(1, MediaProgressWidthPolicy.width(Float.NaN, -4, -9, 0));
    }

    @Test public void timelineStartsAfterAnInlinePlaybackIcon() {
        assertEquals(34, MediaProgressWidthPolicy.leadingMargin(38, 4));
        assertEquals(0, MediaProgressWidthPolicy.leadingMargin(0, 8));
    }
}
