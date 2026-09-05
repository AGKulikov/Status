/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaStateFreshnessTest {
    @Test public void positionOnlyUpdatePreservesIndependentFieldClocks() {
        assertTrue(MediaStateFreshness.changedAt(false, 900L, 120L) == 120L);
        assertTrue(MediaStateFreshness.changedAt(false, 900L, 220L) == 220L);
        assertTrue(MediaStateFreshness.changedAt(false, 900L, 320L) == 320L);
    }

    @Test public void trackAndPlaybackChangesAdvanceOnlyTheirOwnClocks() {
        assertTrue(MediaStateFreshness.changedAt(true, 900L, 120L) == 900L);
        assertTrue(MediaStateFreshness.changedAt(false, 900L, 220L) == 220L);
        assertTrue(MediaStateFreshness.incomingWins(900L, 899L));
        assertFalse(MediaStateFreshness.incomingWins(899L, 900L));
    }

    @Test public void correlatedBroadcastTrackCannotBeRolledBackByLaterStaleSessionPoll() {
        // Broadcast has advanced A -> B at t=200. The OEM MediaSession briefly reported B and
        // then regressed to A at t=300. Its later observation time must not resurrect A.
        assertTrue(MediaStateFreshness.broadcastContentWins(
                false, true, 10_000L, 2_000L, 200L, 300L));
        // A disk snapshot seen before the sources ever agreed is not granted sticky authority.
        assertFalse(MediaStateFreshness.broadcastContentWins(
                false, false, 10_000L, 2_000L, 200L, 300L));
    }

    @Test public void staleCorrelatedBroadcastReleasesNewerSessionTrackAfterGrace() {
        assertFalse(MediaStateFreshness.broadcastContentWins(
                false, true, 10_001L, 2_000L, 200L, 300L));
        // A genuinely newer broadcast content event still wins by its normal change clock.
        assertTrue(MediaStateFreshness.broadcastContentWins(
                false, true, 20_000L, 2_000L, 400L, 300L));
    }

    @Test public void matchingSourcesStillUseTheirRealContentChangeClocks() {
        assertTrue(MediaStateFreshness.broadcastContentWins(
                true, true, 10_000L, 10_000L, 300L, 200L));
        assertFalse(MediaStateFreshness.broadcastContentWins(
                true, true, 10_000L, 10_000L, 200L, 300L));
        assertFalse(MediaStateFreshness.broadcastContentWins(
                true, true, 10_000L, 10_000L, 300L, 300L));
    }

    @Test public void packageMismatchCanCorrelateFromStrongMatchingMetadata() {
        assertTrue(MediaStateFreshness.sameTrackMetadata(
                "Track", "Artist", "Album", "Track", "Artist", "Album"));
        assertTrue(MediaStateFreshness.hasSharedTrackEvidence(
                "Track", "", "", "Track", "Artist", "Album"));
        assertTrue(MediaStateFreshness.hasSharedTrackEvidence(
                "", "Artist", "Album", "", "Artist", "Album"));
    }

    @Test public void emptyOrWeakMetadataCannotCorrelateUnrelatedSessions() {
        assertFalse(MediaStateFreshness.hasSharedTrackEvidence(
                "", "", "", "", "", ""));
        assertFalse(MediaStateFreshness.hasSharedTrackEvidence(
                "", "Artist", "", "", "Artist", ""));
        assertFalse(MediaStateFreshness.sameTrackMetadata(
                "Track A", "Artist", "Album", "Track B", "Artist", "Album"));
    }

    @Test public void broadcastCorrelationEndsOnClearExpiryOrPublisherChange() {
        assertTrue(MediaStateFreshness.shouldResetBroadcastCorrelation(
                true, "publisher.a", false, ""));
        assertTrue(MediaStateFreshness.shouldResetBroadcastCorrelation(
                true, "publisher.a", true, "publisher.b"));
        assertTrue(MediaStateFreshness.shouldResetBroadcastCorrelation(
                false, "", true, "publisher.a"));
        assertFalse(MediaStateFreshness.shouldResetBroadcastCorrelation(
                true, "publisher.a", true, "publisher.a"));
    }

    @Test public void equivalentSameTrackArtworkKeepsPublishedWrapper() {
        assertTrue(MediaStateFreshness.shouldReuseBroadcastArtwork(
                true, true, 101L, true, 101L));
        assertFalse(MediaStateFreshness.shouldReuseBroadcastArtwork(
                false, true, 101L, true, 101L));
        assertFalse(MediaStateFreshness.shouldReuseBroadcastArtwork(
                true, true, 101L, true, 102L));
        assertFalse(MediaStateFreshness.shouldReuseBroadcastArtwork(
                true, true, 0L, true, 0L));
        assertFalse(MediaStateFreshness.shouldReuseBroadcastArtwork(
                true, false, 101L, true, 101L));
    }

    @Test public void activeCurrentMediaSessionSurvivesVendorListReordering() {
        assertTrue(MediaStateFreshness.shouldKeepCurrentSession(true, true, true));
        assertTrue(MediaStateFreshness.shouldKeepCurrentSession(true, false, false));
        assertFalse(MediaStateFreshness.shouldKeepCurrentSession(true, false, true));
        assertFalse(MediaStateFreshness.shouldKeepCurrentSession(false, false, true));
    }

    @Test public void missingOptionalMetadataCanSupplementOnlyTheSameTitle() {
        assertTrue(MediaStateFreshness.sameTrack(
                "ru.yandex.music", "Track", "", "",
                "ru.yandex.music", "Track", "Artist", "Album"));
        assertFalse(MediaStateFreshness.sameTrack(
                "ru.yandex.music", "Track A", "", "",
                "ru.yandex.music", "Track B", "Artist", "Album"));
    }

    @Test public void sameTitleDoesNotHideArtistOrAlbumConflict() {
        assertFalse(MediaStateFreshness.sameTrack(
                "ru.yandex.music", "Intro", "Artist A", "Album",
                "ru.yandex.music", "Intro", "Artist B", "Album"));
        assertFalse(MediaStateFreshness.sameTrack(
                "ru.yandex.music", "Intro", "Artist", "Album A",
                "ru.yandex.music", "Intro", "Artist", "Album B"));
        assertTrue(MediaStateFreshness.sameTrack(
                "ru.yandex.music", "Intro", "Artist", "Album",
                "ru.yandex.music", "Intro", "Artist", "Album"));
    }

    @Test public void artworkVersionChangesIndependentlyFromMetadata() {
        assertTrue(MediaStateFreshness.artworkChanged(false, 101L, 102L));
        assertFalse(MediaStateFreshness.artworkChanged(false, 101L, 101L));
        assertTrue(MediaStateFreshness.artworkChanged(true, 101L, 101L));
    }

    @Test public void unchangedSessionArtworkIsHiddenAcrossTrackBoundary() {
        assertFalse(MediaStateFreshness.shouldDisplaySessionArtwork(
                true, true, 101L, true, 101L));
        // Repeated session polling must not resurrect the rejected previous-track bitmap.
        assertFalse(MediaStateFreshness.shouldDisplaySessionArtwork(
                true, false, 101L, false, 101L));
        assertTrue(MediaStateFreshness.shouldDisplaySessionArtwork(
                true, false, 101L, false, 102L));
    }

    @Test public void missingArtworkAlwaysClearsAndFirstRealArtworkIsVisible() {
        assertFalse(MediaStateFreshness.shouldDisplaySessionArtwork(
                true, true, 101L, true, 0L));
        assertTrue(MediaStateFreshness.shouldDisplaySessionArtwork(
                false, true, 0L, false, 101L));
    }

    @Test public void sameTrackScalarRefreshKeepsItsArtworkVisible() {
        assertTrue(MediaStateFreshness.shouldDisplaySessionArtwork(
                true, false, 101L, true, 101L));
    }

    @Test public void matchingTrackPrefersDecodedArtworkOverNewerTemporaryEmptyState() {
        assertTrue(MediaStateFreshness.incomingArtworkWins(
                true, 100L, false, 200L));
        assertFalse(MediaStateFreshness.incomingArtworkWins(
                false, 300L, true, 100L));
        assertTrue(MediaStateFreshness.incomingArtworkWins(
                true, 300L, true, 200L));
        assertFalse(MediaStateFreshness.incomingArtworkWins(
                true, 100L, true, 200L));
    }

    @Test public void sessionRefreshIsBoundedToConfiguredInterval() {
        assertFalse(MediaStateFreshness.shouldRefreshSession(
                false, 10_000L, 1_000L, 2_500L));
        assertFalse(MediaStateFreshness.shouldRefreshSession(
                true, 3_499L, 1_000L, 2_500L));
        assertTrue(MediaStateFreshness.shouldRefreshSession(
                true, 3_500L, 1_000L, 2_500L));
        assertTrue(MediaStateFreshness.shouldRefreshSession(
                true, 21_000L, 1_000L, 20_000L));
    }

    @Test public void lateArtworkCannotRollBackNewerTrackOrPlayback() {
        // A track-A decode may finish after track B has already advanced content and playback.
        assertFalse(MediaStateFreshness.incomingWins(100L, 200L));
        assertFalse(MediaStateFreshness.incomingWins(110L, 210L));
        // Even if the asynchronous file write has a later artwork clock, the track gate rejects it.
        assertTrue(MediaStateFreshness.incomingWins(300L, 220L));
        assertFalse(MediaStateFreshness.sameTrack(
                "vendor.yandex.music", "Track B", "Artist B", "Album B",
                "vendor.yandex.music", "Track A", "Artist A", "Album A"));
    }

    @Test public void contentIdentityIgnoresPositionButIncludesDuration() {
        assertTrue(MediaStateFreshness.sameContent(
                "pkg", "Track", "Artist", "Album", 180_000L,
                "pkg", "Track", "Artist", "Album", 180_000L));
        assertFalse(MediaStateFreshness.sameContent(
                "pkg", "Track", "Artist", "Album", 180_000L,
                "pkg", "Track", "Artist", "Album", 181_000L));
    }
}
