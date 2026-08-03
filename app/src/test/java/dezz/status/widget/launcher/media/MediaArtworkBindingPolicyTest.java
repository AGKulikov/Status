/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaArtworkBindingPolicyTest {
    @Test public void conflictingTitleCreatesATrackBoundary() {
        assertFalse(MediaArtworkBindingPolicy.sameTrack(
                "player", "First", "Artist", "Album",
                "player", "Second", "Artist", "Album"));
    }

    @Test public void incrementalEmptyMetadataDoesNotInventABoundary() {
        assertTrue(MediaArtworkBindingPolicy.sameTrack(
                "player", "Song", "", "",
                "player", "Song", "Artist", "Album"));
    }

    @Test public void previousPixelsAreHiddenUntilTheNewCoverArrives() {
        assertTrue(MediaArtworkBindingPolicy.staleAcrossTrackBoundary(
                true, "First album", "Second album", 42L, 42L));
        assertFalse(MediaArtworkBindingPolicy.staleAcrossTrackBoundary(
                true, "Shared album", "Shared album", 42L, 42L));
        assertFalse(MediaArtworkBindingPolicy.staleAcrossTrackBoundary(
                true, "First album", "Second album", 42L, 43L));
        assertNotEquals(
                MediaArtworkBindingPolicy.rejectionKey("p", "First", "a", "x"),
                MediaArtworkBindingPolicy.rejectionKey("p", "Second", "a", "x"));
    }
}
