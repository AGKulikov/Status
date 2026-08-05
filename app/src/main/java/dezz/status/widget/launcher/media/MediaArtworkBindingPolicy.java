/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import androidx.annotation.NonNull;

/** Pure UI policy which prevents a publisher's previous-track bitmap from becoming sticky. */
final class MediaArtworkBindingPolicy {
    private MediaArtworkBindingPolicy() {}

    /**
     * Empty metadata is treated as a wildcard because many OEM sessions publish title first and
     * artist/album on the following callback. A conflicting non-empty value is a real track
     * boundary. Application is included so two paused players cannot share one cover by accident.
     */
    static boolean sameTrack(@NonNull String oldApplication, @NonNull String oldTitle,
                             @NonNull String oldArtist, @NonNull String oldAlbum,
                             @NonNull String newApplication, @NonNull String newTitle,
                             @NonNull String newArtist, @NonNull String newAlbum) {
        return compatible(oldApplication, newApplication)
                && compatible(oldTitle, newTitle)
                && compatible(oldArtist, newArtist)
                && compatible(oldAlbum, newAlbum);
    }

    /**
     * The same pixels are valid across two songs only when both explicitly name the same album.
     * Otherwise they are the most common MediaSession failure mode: new metadata paired with the
     * old track's bitmap. Once rejected, the caller keeps that fingerprint hidden for this track.
     */
    static boolean staleAcrossTrackBoundary(boolean trackChanged,
                                            @NonNull String previousAlbum,
                                            @NonNull String nextAlbum,
                                            long renderedFingerprint,
                                            long incomingFingerprint) {
        if (!trackChanged || renderedFingerprint == 0L || incomingFingerprint == 0L
                || renderedFingerprint != incomingFingerprint) return false;
        return previousAlbum.isEmpty() || nextAlbum.isEmpty()
                || !previousAlbum.equals(nextAlbum);
    }

    /**
     * Remembers the previous track's pixels for the whole lifetime of the next track.
     *
     * <p>Some players publish A(old) -> B(new) -> A(old) -> B(new) during one hand-off.  Merely
     * rejecting A until B first appears is not enough: the second A produces the visible flash
     * reported on the head unit.  Keep A blocked until another real track boundary.  Explicitly
     * shared album artwork remains valid for adjacent songs from the same album.</p>
     */
    static long previousTrackFingerprintToReject(boolean trackChanged,
                                                 @NonNull String previousAlbum,
                                                 @NonNull String nextAlbum,
                                                 long renderedFingerprint) {
        if (!trackChanged || renderedFingerprint == 0L) return 0L;
        if (!previousAlbum.isEmpty() && previousAlbum.equals(nextAlbum)) return 0L;
        return renderedFingerprint;
    }

    static boolean isRejectedForCurrentTrack(long rejectedFingerprint,
                                             long incomingFingerprint) {
        return rejectedFingerprint != 0L && rejectedFingerprint == incomingFingerprint;
    }

    @NonNull
    static String rejectionKey(@NonNull String application, @NonNull String title,
                               @NonNull String artist, @NonNull String album) {
        return frame(application) + frame(title) + frame(artist) + frame(album);
    }

    private static boolean compatible(@NonNull String left, @NonNull String right) {
        return left.isEmpty() || right.isEmpty() || left.equals(right);
    }

    @NonNull
    private static String frame(@NonNull String value) {
        return value.length() + ":" + value + ";";
    }
}
