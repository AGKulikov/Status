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

    @Test public void previousPixelsRemainBlockedAfterTheNewCoverWasAlreadyShown() {
        long rejected = MediaArtworkBindingPolicy.previousTrackFingerprintToReject(
                true, "First album", "Second album", 42L);
        assertTrue(MediaArtworkBindingPolicy.isRejectedForCurrentTrack(rejected, 42L));
        assertFalse(MediaArtworkBindingPolicy.isRejectedForCurrentTrack(rejected, 43L));
        // Showing fingerprint 43 must not erase the remembered fingerprint 42. A late 42 is
        // still the old track and remains rejected until the next track boundary.
        assertTrue(MediaArtworkBindingPolicy.isRejectedForCurrentTrack(rejected, 42L));
    }

    @Test public void explicitSharedAlbumArtworkIsNotRejected() {
        long rejected = MediaArtworkBindingPolicy.previousTrackFingerprintToReject(
                true, "Shared album", "Shared album", 42L);
        assertFalse(MediaArtworkBindingPolicy.isRejectedForCurrentTrack(rejected, 42L));
    }
}
