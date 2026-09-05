/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** HUD presentation follows playback; it never pauses, resumes or destroys the player. */
public final class HudMediaVisibility {
    private HudMediaVisibility() { }

    public static boolean visible(HudElementType type, boolean editor,
                                  boolean available, boolean playing,
                                  boolean hasArtwork, long durationMs,
                                  boolean volumeTransientVisible) {
        if (editor) return true;
        switch (type) {
            case MEDIA_VOLUME:
                return volumeTransientVisible;
            case MEDIA_ARTWORK:
                return available && playing && hasArtwork;
            case MEDIA_TIMER:
                return available && playing && durationMs > 0;
            case MEDIA_COMBINED:
            case MEDIA_TITLE:
            case MEDIA_ARTIST:
            case MEDIA_ALBUM:
            case MEDIA_APPLICATION:
                return available && playing;
            default:
                return true;
        }
    }
}
