/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;

/** Chooses the active Android media session and exposes it to the HOME media panel. */
public final class LauncherMediaController {
    public interface Listener { void onMediaChanged(@NonNull Snapshot state); }

    public static final class Snapshot {
        @NonNull public final String title;
        @NonNull public final String artist;
        @NonNull public final String application;
        @Nullable public final Bitmap artwork;
        public final boolean playing;
        public final boolean available;

        Snapshot(String title, String artist, String application, @Nullable Bitmap artwork,
                 boolean playing, boolean available) {
            this.title = title;
            this.artist = artist;
            this.application = application;
            this.artwork = artwork;
            this.playing = playing;
            this.available = available;
        }

        static Snapshot empty() {
            return new Snapshot("Музыка не воспроизводится", "", "", null, false, false);
        }
    }

    private final Context context;
    private final MediaSessionManager manager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    @Nullable private MediaController current;
    private boolean started;

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> select(controllers == null ? Collections.emptyList() : controllers);

    public LauncherMediaController(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public void start() {
        if (started || manager == null) return;
        started = true;
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(),
                    mainHandler);
        } catch (SecurityException ignored) {
        }
        refresh();
    }

    public void stop() {
        if (!started) return;
        started = false;
        if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsListener);
        replace(null);
    }

    public void refresh() {
        if (manager == null) return;
        try {
            select(manager.getActiveSessions(listenerComponent()));
        } catch (SecurityException ignored) {
            replace(null);
            listener.onMediaChanged(Snapshot.empty());
        }
    }

    public void playPause() {
        MediaController controller = current;
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    public void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
    }

    public void next() {
        if (current != null) current.getTransportControls().skipToNext();
    }

    private void select(@NonNull List<MediaController> controllers) {
        MediaController selected = null;
        for (MediaController candidate : controllers) {
            PlaybackState playback = candidate.getPlaybackState();
            if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING) {
                selected = candidate;
                break;
            }
            if (selected == null) selected = candidate;
        }
        replace(selected);
        publish();
    }

    private void replace(@Nullable MediaController next) {
        if (current != null && (next == null
                || !current.getSessionToken().equals(next.getSessionToken()))) {
            current.unregisterCallback(mediaCallback);
        }
        if (next != null && (current == null
                || !next.getSessionToken().equals(current.getSessionToken()))) {
            next.registerCallback(mediaCallback, mainHandler);
        }
        current = next;
    }

    private void publish() {
        MediaController controller = current;
        if (controller == null) {
            listener.onMediaChanged(Snapshot.empty());
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playback = controller.getPlaybackState();
        String title = metadata == null ? "" : first(metadata,
                MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = metadata == null ? "" : first(metadata,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        Bitmap artwork = metadata == null ? null
                : metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (artwork == null && metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        String app = controller.getPackageName();
        try {
            app = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(app, 0)).toString();
        } catch (Exception ignored) {
        }
        boolean playing = playback != null && playback.getState() == PlaybackState.STATE_PLAYING;
        listener.onMediaChanged(new Snapshot(title.isEmpty() ? "Неизвестный трек" : title,
                artist, app, artwork, playing, true));
    }

    @NonNull
    private ComponentName listenerComponent() {
        return new ComponentName(context, MediaNotificationListener.class);
    }

    @NonNull
    private static String first(@NonNull MediaMetadata metadata, @NonNull String... keys) {
        for (String key : keys) {
            String value = metadata.getString(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
