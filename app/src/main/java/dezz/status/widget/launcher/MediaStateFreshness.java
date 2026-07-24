/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/**
 * Pure ordering and identity rules for combining MediaSession and rich-broadcast updates.
 *
 * <p>Track metadata/artwork and playback state intentionally have separate clocks. A player may
 * republish an unchanged, stale MediaSession every second while a richer broadcast has already
 * announced the next track, or it may send position-only packets after an artwork decode started.
 * One global "last packet wins" clock would make either case regress the visible panel.</p>
 */
final class MediaStateFreshness {
    /** Covers OEM callback/reconcile bursts without pinning a dead broadcaster for its full TTL. */
    static final long CORRELATED_CONFLICT_GRACE_MS = 8_000L;

    private MediaStateFreshness() {}

    static long changedAt(boolean changed, long observedElapsedMs, long previousChangedElapsedMs) {
        if (!changed && previousChangedElapsedMs > 0L) return previousChangedElapsedMs;
        return Math.max(1L, observedElapsedMs);
    }

    static boolean incomingWins(long incomingChangedElapsedMs, long currentChangedElapsedMs) {
        return incomingChangedElapsedMs >= currentChangedElapsedMs;
    }

    /**
     * Chooses metadata inside one already-correlated player.
     *
     * <p>On the ECARX Yandex build a MediaSession can briefly expose the next track and then
     * regress to the previous metadata while the mHUD broadcast already carries the real track.
     * The regressed session is observed later and therefore looks "newer" to a timestamp-only
     * merge. A conflicting rich broadcast is temporarily authoritative while its packets remain
     * fresh; ordinary clock ordering resumes once both sources agree or the broadcaster stops.
     * This avoids both rapid A→B→A oscillation and pinning an abandoned broadcast for its much
     * longer disk-cache TTL.</p>
     */
    static boolean broadcastContentWins(boolean sameTrack, boolean previouslyCorrelated,
                                        long nowElapsedMs, long broadcastReceivedElapsedMs,
                                        long broadcastContentChangedElapsedMs,
                                        long sessionContentChangedElapsedMs) {
        boolean broadcastFresh = broadcastReceivedElapsedMs > 0L
                && nowElapsedMs >= broadcastReceivedElapsedMs
                && nowElapsedMs - broadcastReceivedElapsedMs
                <= CORRELATED_CONFLICT_GRACE_MS;
        if (!sameTrack && previouslyCorrelated && broadcastFresh) return true;
        return broadcastContentChangedElapsedMs > sessionContentChangedElapsedMs;
    }

    /**
     * Compares track metadata without package names.
     *
     * <p>Some mHUD publishers report the host/navigation package while MediaSession reports the
     * actual player package. Once the two sources have been correlated, their package mismatch
     * must not prevent coherent same-track merging.</p>
     */
    static boolean sameTrackMetadata(String leftTitle, String leftArtist, String leftAlbum,
                                     String rightTitle, String rightArtist, String rightAlbum) {
        if (!leftTitle.isEmpty() && !rightTitle.isEmpty()
                && !leftTitle.equals(rightTitle)) return false;
        if (!leftArtist.isEmpty() && !rightArtist.isEmpty()
                && !leftArtist.equals(rightArtist)) return false;
        if (!leftAlbum.isEmpty() && !rightAlbum.isEmpty()
                && !leftAlbum.equals(rightAlbum)) return false;
        return true;
    }

    /**
     * Requires positive metadata evidence before correlating sources whose packages differ.
     * Empty wildcard fields are useful when merging, but are not evidence that two players are
     * the same. An exact title is strong enough; otherwise require both artist and album.
     */
    static boolean hasSharedTrackEvidence(String leftTitle, String leftArtist, String leftAlbum,
                                          String rightTitle, String rightArtist,
                                          String rightAlbum) {
        if (!leftTitle.isEmpty() && leftTitle.equals(rightTitle)) return true;
        return !leftArtist.isEmpty() && leftArtist.equals(rightArtist)
                && !leftAlbum.isEmpty() && leftAlbum.equals(rightAlbum);
    }

    /**
     * Correlation belongs to one concrete broadcast publisher lifetime. CLEAR/expiry and a
     * package handoff must revoke it before a future unrelated stream can enter correlated merge.
     */
    static boolean shouldResetBroadcastCorrelation(boolean hasPrevious,
                                                   String previousPackage,
                                                   boolean hasNext,
                                                   String nextPackage) {
        if (!hasPrevious || !hasNext) return true;
        return !previousPackage.equals(nextPackage);
    }

    /**
     * Reuses the already-published bitmap wrapper when a cache decode produced identical pixels
     * for the same track. The controller may then recycle the unpublished duplicate safely.
     */
    static boolean shouldReuseBroadcastArtwork(boolean sameTrack,
                                               boolean previousArtworkUsable,
                                               long previousArtworkIdentity,
                                               boolean incomingArtworkUsable,
                                               long incomingArtworkIdentity) {
        return sameTrack && previousArtworkUsable && incomingArtworkUsable
                && previousArtworkIdentity != 0L
                && previousArtworkIdentity == incomingArtworkIdentity;
    }

    static boolean artworkChanged(boolean contentChanged, long previousArtworkIdentity,
                                  long incomingArtworkIdentity) {
        return contentChanged || previousArtworkIdentity != incomingArtworkIdentity;
    }

    /**
     * MediaSession publishers sometimes update the title before replacing their artwork bitmap.
     * Treating that still-identical bitmap as the new track's cover makes the old cover stick
     * indefinitely on players that keep returning it from {@code getMetadata()}.
     *
     * <p>Once an unchanged bitmap has been hidden for a new track, keep it hidden until the
     * publisher exposes genuinely different pixels. A missing bitmap is always an empty artwork
     * cell, never permission to retain the previous track's image.</p>
     */
    static boolean shouldDisplaySessionArtwork(boolean hasPrevious, boolean trackChanged,
                                               long previousObservedArtworkIdentity,
                                               boolean previousArtworkDisplayed,
                                               long incomingArtworkIdentity) {
        if (incomingArtworkIdentity == 0L) return false;
        if (!hasPrevious) return true;
        if (incomingArtworkIdentity != previousObservedArtworkIdentity) return true;
        if (trackChanged) return false;
        return previousArtworkDisplayed;
    }

    /**
     * A decoded cover that is known to belong to the selected track is more useful than a newer
     * source which is temporarily empty while its asynchronous artwork update is pending.
     */
    static boolean incomingArtworkWins(boolean incomingHasArtwork,
                                       long incomingChangedElapsedMs,
                                       boolean currentHasArtwork,
                                       long currentChangedElapsedMs) {
        if (incomingHasArtwork != currentHasArtwork) return incomingHasArtwork;
        return incomingWins(incomingChangedElapsedMs, currentChangedElapsedMs);
    }

    /**
     * Keeps a stable MediaSession token while it is still the playing controller. Vendor session
     * lists can reorder several stale "playing" Yandex controllers on every poll; selecting the
     * first entry would alternate complete track snapshots several times per second.
     */
    static boolean shouldKeepCurrentSession(boolean currentPresent, boolean currentPlaying,
                                            boolean anyPlayingSession) {
        return currentPresent && (currentPlaying || !anyPlayingSession);
    }

    static boolean shouldRefreshSession(boolean hasController,
                                        long nowElapsedMs, long lastRefreshElapsedMs,
                                        long intervalMs) {
        if (!hasController) return false;
        if (lastRefreshElapsedMs <= 0L) return true;
        return Math.max(0L, nowElapsedMs - lastRefreshElapsedMs)
                >= Math.max(1L, intervalMs);
    }

    static boolean sameContent(String leftPackage, String leftTitle, String leftArtist,
                               String leftAlbum, long leftDurationMs,
                               String rightPackage, String rightTitle, String rightArtist,
                               String rightAlbum, long rightDurationMs) {
        return leftPackage.equals(rightPackage)
                && leftTitle.equals(rightTitle)
                && leftArtist.equals(rightArtist)
                && leftAlbum.equals(rightAlbum)
                && leftDurationMs == rightDurationMs;
    }

    static boolean sameTrack(String leftPackage, String leftTitle, String leftArtist,
                             String leftAlbum, String rightPackage, String rightTitle,
                             String rightArtist, String rightAlbum) {
        if (!leftPackage.isEmpty() && !rightPackage.isEmpty()
                && !leftPackage.equals(rightPackage)) return false;
        return sameTrackMetadata(leftTitle, leftArtist, leftAlbum,
                rightTitle, rightArtist, rightAlbum);
    }
}
