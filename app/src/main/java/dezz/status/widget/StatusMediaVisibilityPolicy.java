/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

/** Pure visibility decision for the status-row media presentation. */
public final class StatusMediaVisibilityPolicy {
    private StatusMediaVisibilityPolicy() {}

    /**
     * Phone notifications temporarily reuse the media geometry and therefore remain visible.
     * Music can optionally require an exact MediaSession PLAYING state; a merely present paused,
     * buffering or connecting session is not enough in that mode.
     */
    public static boolean hasVisibleContent(boolean phoneNotificationActive,
                                            boolean mediaSessionPresent,
                                            boolean mediaSessionPlaying,
                                            boolean onlyWhilePlaying) {
        if (phoneNotificationActive) return true;
        if (!mediaSessionPresent) return false;
        return !onlyWhilePlaying || mediaSessionPlaying;
    }
}
