package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaTimelineTest {
    @Test public void formatsShortAndLongDurations() {
        assertEquals("0:00", MediaTimeline.format(-1L));
        assertEquals("1:05", MediaTimeline.format(65_900L));
        assertEquals("1:01:01", MediaTimeline.format(3_661_000L));
    }

    @Test public void clampsAndCalculatesProgress() {
        assertEquals(0L, MediaTimeline.clampPosition(-5L, 1_000L));
        assertEquals(1_000L, MediaTimeline.clampPosition(2_000L, 1_000L));
        assertEquals(250, MediaTimeline.progress(1_000L, 4_000L, 1_000));
        assertEquals(1_000, MediaTimeline.progress(5_000L, 4_000L, 1_000));
        assertEquals(0, MediaTimeline.progress(1_000L, 0L, 1_000));
    }
}
