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

    @Test public void extrapolatesActualSpeedAndStopsAtPauseAndTrackEnd() {
        assertEquals(4000L, MediaTimeline.position(1000, 2000, 1.5f, true, 10000));
        assertEquals(2000L, MediaTimeline.position(1000, 2000, .5f, true, 10000));
        assertEquals(1000L, MediaTimeline.position(1000, 2000, 2f, false, 10000));
        assertEquals(1000L, MediaTimeline.position(1000, -2000, 1f, true, 10000));
        assertEquals(10000L, MediaTimeline.position(9000, 2000, 1.5f, true, 10000));
        assertEquals(3000L, MediaTimeline.position(1000, 2000, Float.NaN, true, 10000));
        assertEquals(Long.MAX_VALUE, MediaTimeline.position(Long.MAX_VALUE - 10, Long.MAX_VALUE, 2f, true, 0));
    }
}
