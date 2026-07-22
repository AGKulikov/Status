package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaBroadcastFreshnessTest {
    @Test public void unknownStateExpiresQuickly() {
        assertEquals(5L * 60L * 1_000L,
                MediaBroadcastFreshness.ttl(false, true, 0L, 0L));
    }

    @Test public void knownStateSurvivesTrackChangeOnlyPublishers() {
        assertEquals(2L * 60L * 60L * 1_000L,
                MediaBroadcastFreshness.ttl(true, true, 4L * 60L * 1_000L, 0L));
        assertEquals(4L * 60L * 60L * 1_000L + 15L * 60L * 1_000L,
                MediaBroadcastFreshness.ttl(true, true, 5L * 60L * 60L * 1_000L,
                        60L * 60L * 1_000L));
    }

    @Test public void malformedHugeDurationIsCapped() {
        assertEquals(24L * 60L * 60L * 1_000L,
                MediaBroadcastFreshness.ttl(true, true, Long.MAX_VALUE, 0L));
    }
}
