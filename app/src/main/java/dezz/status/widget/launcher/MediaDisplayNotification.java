/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;

/** Small immutable live-media input, not a retained Notification/RemoteViews or a disk cache. */
public final class MediaDisplayNotification {
    public final String key, packageName, title, artist, album;
    public final String trackTitle, trackArtist;
    @Nullable public final MediaSession.Token token;
    @Nullable public final Icon artworkIcon;
    @Nullable public final Bitmap artwork;
    public final long generation, receivedElapsedMs, postTime;

    private MediaDisplayNotification(StatusBarNotification source, long generation) {
        Notification notification = source.getNotification();
        Bundle extras = notification.extras == null ? Bundle.EMPTY : notification.extras;
        Object rawToken = extras.get(Notification.EXTRA_MEDIA_SESSION);
        token = rawToken instanceof MediaSession.Token ? (MediaSession.Token) rawToken : null;
        key = source.getKey();
        packageName = source.getPackageName();
        this.generation = generation;
        receivedElapsedMs = SystemClock.elapsedRealtime();
        postTime = source.getPostTime();
        Bundle metadata = extras.getBundle("android.mediaMetadata");
        // Navigation notification text is guidance, not a song. Its token's metadata is used
        // by the shared controller instead (the same distinction as the reference player).
        boolean navigation = "ru.yandex.yandexnavi".equals(packageName)
                || "ru.yandex.yandexmaps".equals(packageName)
                || "com.yandex.yango".equals(packageName);
        String parsedTitle = first(text(metadata, MediaMetadata.METADATA_KEY_TITLE), text(extras, Notification.EXTRA_TITLE));
        String parsedArtist = first(text(metadata, MediaMetadata.METADATA_KEY_ARTIST),
                first(text(metadata, MediaMetadata.METADATA_KEY_AUTHOR),
                first(text(metadata, MediaMetadata.METADATA_KEY_WRITER),
                first(text(metadata, MediaMetadata.METADATA_KEY_COMPOSER),
                first(text(extras, Notification.EXTRA_TEXT), text(extras, Notification.EXTRA_SUB_TEXT))))));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length >= 2) {
            if (lines[0] != null) parsedTitle = lines[0].toString().trim();
            if (lines[1] != null) parsedArtist = lines[1].toString().trim();
        } else if (lines != null && lines.length == 1 && lines[0] != null) {
            String line = lines[0].toString();
            int dash = line.indexOf(" - "), dot = line.indexOf(" • ");
            if (dash >= 0) { parsedArtist = line.substring(0, dash).trim(); parsedTitle = line.substring(dash + 3).trim(); }
            else if (dot >= 0) { parsedTitle = line.substring(0, dot).trim(); parsedArtist = line.substring(dot + 3).trim(); }
        }
        String identityTitle = parsedTitle, identityArtist = parsedArtist;
        if ("com.spotify.music".equals(packageName)) {
            parsedTitle = first(text(extras, Notification.EXTRA_TITLE), text(extras, Notification.EXTRA_TEXT));
            String artist = text(extras, Notification.EXTRA_TEXT), sub = text(extras, Notification.EXTRA_SUB_TEXT);
            identityTitle = parsedTitle; identityArtist = artist;
            parsedArtist = artist.isEmpty() ? sub : sub.isEmpty() ? artist : artist + " • " + sub;
        } else if ("ru.fmplay".equals(packageName) && !text(extras, Notification.EXTRA_SUB_TEXT).isEmpty()) {
            parsedTitle += " | " + text(extras, Notification.EXTRA_SUB_TEXT);
        }
        boolean sessionText = navigation || "com.audiobookshelf.app".equals(packageName);
        title = sessionText ? "" : bounded(parsedTitle);
        artist = sessionText ? "" : bounded(parsedArtist);
        trackTitle = sessionText ? "" : bounded(identityTitle);
        trackArtist = sessionText ? "" : bounded(identityArtist);
        album = text(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        artworkIcon = notification.getLargeIcon();
        Object large = extras.get(Notification.EXTRA_LARGE_ICON);
        artwork = large instanceof Bitmap ? (Bitmap) large : null;
    }

    @Nullable public static MediaDisplayNotification read(StatusBarNotification source,
                                                         long generation) {
        if (source == null || source.getNotification() == null || source.getKey() == null) return null;
        try {
            Notification n = source.getNotification();
            Object token = n.extras == null ? null : n.extras.get(Notification.EXTRA_MEDIA_SESSION);
            if (!(token instanceof MediaSession.Token)
                    && !Notification.CATEGORY_TRANSPORT.equals(n.category)) return null;
            return new MediaDisplayNotification(source, generation);
        } catch (RuntimeException | LinkageError malformed) {
            return null;
        }
    }

    private static String text(Bundle source, String key) {
        if (source == null) return "";
        Object value = source.get(key);
        if (!(value instanceof CharSequence)) return "";
        String text = value.toString().trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
    /** Recovery scans reuse the displayed cover when Android reports the very same publication. */
    public boolean samePublication(@Nullable MediaDisplayNotification other) {
        return other != null && key.equals(other.key) && packageName.equals(other.packageName)
                && postTime == other.postTime && title.equals(other.title) && artist.equals(other.artist)
                && album.equals(other.album) && trackTitle.equals(other.trackTitle) && trackArtist.equals(other.trackArtist)
                && java.util.Objects.equals(token, other.token);
    }
    private static String first(String primary, String fallback) {
        return primary.isEmpty() ? fallback : primary;
    }
    private static String bounded(String value) { return value.length() > 500 ? value.substring(0, 500) : value; }
}
