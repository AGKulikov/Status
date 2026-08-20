/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

final class MediaCommandTransitionGuard {
    static final long WINDOW_MS = 2400;
    private boolean active;
    private boolean advanced;
    private long startedElapsedMs;
    private java.lang.String packageName = "";
    private java.lang.String title = "";
    private java.lang.String artist = "";
    private java.lang.String album = "";

    MediaCommandTransitionGuard() {
    }

    void begin(long nowElapsedMs, java.lang.String packageName, java.lang.String title, java.lang.String artist, java.lang.String album) {
        clear();
        if (hasStrongIdentity(title, artist, album)) {
            this.active = true;
            this.startedElapsedMs = java.lang.Math.max(0L, nowElapsedMs);
            this.packageName = value(packageName);
            this.title = value(title);
            this.artist = value(artist);
            this.album = value(album);
        }
    }

    void clear() {
        this.active = false;
        this.advanced = false;
        this.startedElapsedMs = 0L;
        this.packageName = "";
        this.title = "";
        this.artist = "";
        this.album = "";
    }

    boolean shouldSuppress(long nowElapsedMs, java.lang.String candidatePackage, java.lang.String candidateTitle, java.lang.String candidateArtist, java.lang.String candidateAlbum) {
        if (!this.active) {
            return false;
        }
        long now = java.lang.Math.max(0L, nowElapsedMs);
        if (now < this.startedElapsedMs || now - this.startedElapsedMs >= WINDOW_MS) {
            clear();
            return false;
        }
        java.lang.String nextPackage = value(candidatePackage);
        java.lang.String nextTitle = value(candidateTitle);
        java.lang.String nextArtist = value(candidateArtist);
        java.lang.String nextAlbum = value(candidateAlbum);
        if (!this.packageName.isEmpty() && !nextPackage.isEmpty() && !this.packageName.equals(nextPackage)) {
            clear();
            return false;
        }
        if (this.packageName.isEmpty() && !nextPackage.isEmpty()) {
            this.packageName = nextPackage;
        }
        boolean exactPrevious = samePreviousTrack(nextTitle, nextArtist, nextAlbum);
        if (exactPrevious) {
            return this.advanced;
        }
        if (conflicts(this.title, nextTitle) || conflicts(this.artist, nextArtist) || conflicts(this.album, nextAlbum)) {
            this.advanced = true;
        }
        return false;
    }

    private boolean samePreviousTrack(java.lang.String candidateTitle, java.lang.String candidateArtist, java.lang.String candidateAlbum) {
        if (this.title.isEmpty() || !this.title.equals(candidateTitle)) {
            return this.title.isEmpty() && !this.artist.isEmpty() && this.artist.equals(candidateArtist) && !this.album.isEmpty() && this.album.equals(candidateAlbum);
        }
        return (conflicts(this.artist, candidateArtist) || conflicts(this.album, candidateAlbum)) ? false : true;
    }

    private static boolean hasStrongIdentity(java.lang.String title, java.lang.String artist, java.lang.String album) {
        java.lang.String normalizedTitle = value(title);
        return (normalizedTitle.isEmpty() && (value(artist).isEmpty() || value(album).isEmpty())) ? false : true;
    }

    private static boolean conflicts(java.lang.String previous, java.lang.String candidate) {
        return (previous.isEmpty() || candidate.isEmpty() || previous.equals(candidate)) ? false : true;
    }

    private static java.lang.String value(java.lang.String value) {
        return value == null ? "" : value;
    }
}
