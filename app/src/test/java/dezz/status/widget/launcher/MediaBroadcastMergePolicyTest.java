/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaBroadcastMergePolicyTest {
    @Test
    public void omittedFieldsInheritSameSourceState() {
        assertTrue(MediaBroadcastMergePolicy.sameSource(true, false, "", "player"));
        assertTrue(MediaBroadcastMergePolicy.sameSource(true, true, "player", ""));
        assertTrue(MediaBroadcastMergePolicy.sameTrack(true, false, "", "Song"));
        assertEquals("Song", MediaBroadcastMergePolicy.text(
                false, "", true, "Song"));
        assertEquals(42_000L, MediaBroadcastMergePolicy.number(
                false, 0L, true, 42_000L));
        assertTrue(MediaBroadcastMergePolicy.flag(false, false, true, true));
    }

    @Test
    public void explicitEmptyZeroAndFalseAreNotTreatedAsOmissions() {
        assertEquals("", MediaBroadcastMergePolicy.text(true, "", true, "Song"));
        assertFalse(MediaBroadcastMergePolicy.sameTrack(true, true, "", "Song"));
        assertEquals(0L, MediaBroadcastMergePolicy.number(true, 0L, true, 42_000L));
        assertFalse(MediaBroadcastMergePolicy.flag(true, false, true, true));
    }

    @Test
    public void explicitPublisherOrTrackChangeDoesNotInheritIdentity() {
        assertFalse(MediaBroadcastMergePolicy.sameSource(true, true,
                "other.player", "player"));
        assertFalse(MediaBroadcastMergePolicy.sameTrack(true, true,
                "Second", "First"));
        assertEquals("", MediaBroadcastMergePolicy.text(
                false, "", false, "Old title"));
    }
}
