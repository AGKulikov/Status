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
    private MediaStateFreshness() {}

    static long changedAt(boolean changed, long observedElapsedMs, long previousChangedElapsedMs) {
        if (!changed && previousChangedElapsedMs > 0L) return previousChangedElapsedMs;
        return Math.max(1L, observedElapsedMs);
    }

    static boolean incomingWins(long incomingChangedElapsedMs, long currentChangedElapsedMs) {
        return incomingChangedElapsedMs >= currentChangedElapsedMs;
    }

    static boolean artworkChanged(boolean contentChanged, long previousArtworkIdentity,
                                  long incomingArtworkIdentity) {
        return contentChanged || previousArtworkIdentity != incomingArtworkIdentity;
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
        if (!leftTitle.isEmpty() && !rightTitle.isEmpty()
                && !leftTitle.equals(rightTitle)) return false;
        if (!leftArtist.isEmpty() && !rightArtist.isEmpty()
                && !leftArtist.equals(rightArtist)) return false;
        if (!leftAlbum.isEmpty() && !rightAlbum.isEmpty()
                && !leftAlbum.equals(rightAlbum)) return false;
        return true;
    }
}
