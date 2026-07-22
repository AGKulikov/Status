/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;

/**
 * Chooses the active Android media session and augments it with the media broadcast used by
 * mHUD/mSaver-compatible publishers. The broadcast path is especially useful on head units where
 * notification-listener access is unavailable or the player does not expose a MediaSession.
 */
public final class LauncherMediaController {
    public static final String ACTION_MEDIA_UPDATE = "plus.monjaro.MEDIA_INFO_UPDATE";
    public static final String ACTION_MEDIA_CLEAR = "plus.monjaro.MEDIA_INFO_CLEAR";
    public static final String ACTION_MEDIA_UPDATE_DEBUG = "debug.monjaro.MEDIA_INFO_UPDATE";
    public static final String ACTION_MEDIA_CLEAR_DEBUG = "debug.monjaro.MEDIA_INFO_CLEAR";

    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_ARTWORK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ARTWORK_EDGE = 1024;
    private static final long UI_TICK_MS = 1_000L;
    /** Small tolerance for publishers whose wall-clock timestamps are not emitted atomically. */
    private static final long DIFFERENT_SOURCE_RECENCY_SLOP_MS = 10_000L;

    public interface Listener { void onMediaChanged(@NonNull Snapshot state); }

    public static final class Snapshot {
        @NonNull public final String title;
        @NonNull public final String artist;
        @NonNull public final String album;
        @NonNull public final String application;
        @Nullable public final Bitmap artwork;
        public final long durationMs;
        public final long positionMs;
        public final boolean playing;
        public final boolean available;
        /** Current STREAM_MUSIC volume, from 0 to 100. */
        public final int volumePercent;

        Snapshot(@NonNull String title, @NonNull String artist, @NonNull String album,
                 @NonNull String application, @Nullable Bitmap artwork, long durationMs,
                 long positionMs, boolean playing, boolean available, int volumePercent) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.application = application;
            this.artwork = artwork;
            this.durationMs = Math.max(0L, durationMs);
            this.positionMs = MediaTimeline.clampPosition(positionMs, this.durationMs);
            this.playing = playing;
            this.available = available;
            this.volumePercent = Math.max(0, Math.min(100, volumePercent));
        }

        static Snapshot empty(int volumePercent) {
            return new Snapshot("Музыка не воспроизводится", "", "", "", null,
                    0L, 0L, false, false, volumePercent);
        }
    }

    /** Raw state retained separately so fields can be merged only for the same player. */
    private static final class MediaState {
        @NonNull final String title;
        @NonNull final String artist;
        @NonNull final String album;
        @NonNull final String packageName;
        @NonNull final String application;
        @Nullable final Bitmap artwork;
        final long durationMs;
        final long positionMs;
        final boolean playing;
        final long positionTimestampWallMs;
        final long receivedElapsedMs;

        MediaState(@NonNull String title, @NonNull String artist, @NonNull String album,
                   @NonNull String packageName, @NonNull String application,
                   @Nullable Bitmap artwork, long durationMs, long positionMs, boolean playing,
                   long positionTimestampWallMs, long receivedElapsedMs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.packageName = packageName;
            this.application = application;
            this.artwork = artwork;
            this.durationMs = Math.max(0L, durationMs);
            this.positionMs = Math.max(0L, positionMs);
            this.playing = playing;
            this.positionTimestampWallMs = positionTimestampWallMs;
            this.receivedElapsedMs = receivedElapsedMs;
        }

        long currentPosition(long nowWallMs) {
            long value = positionMs;
            if (playing && positionTimestampWallMs > 0L) {
                value += Math.max(0L, nowWallMs - positionTimestampWallMs);
            }
            return MediaTimeline.clampPosition(value, durationMs);
        }
    }

    private final Context context;
    private final MediaSessionManager manager;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    @Nullable private MediaController current;
    @Nullable private MediaState sessionState;
    @Nullable private MediaState broadcastState;
    private boolean started;
    private boolean receiverRegistered;
    private int cacheLoadGeneration;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!started) return;
            publish();
            mainHandler.postDelayed(this, UI_TICK_MS);
        }
    };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publishSession(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { publishSession(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> select(controllers == null ? Collections.emptyList() : controllers);

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (MediaBroadcastRepository.ACTION_CACHE_UPDATED.equals(action)) {
                loadCachedBroadcast();
                return;
            }
            if (ACTION_MEDIA_CLEAR.equals(action) || ACTION_MEDIA_CLEAR_DEBUG.equals(action)) {
                MediaBroadcastRepository.processAsync(context, intent, null);
                cacheLoadGeneration++;
                broadcastState = null;
                publish();
                return;
            }
            if (!ACTION_MEDIA_UPDATE.equals(action) && !ACTION_MEDIA_UPDATE_DEBUG.equals(action)) {
                return;
            }
            receiveBroadcast(intent);
        }
    };

    public LauncherMediaController(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start() {
        if (started) return;
        started = true;
        registerBroadcastReceiver();
        loadCachedBroadcast();
        if (manager != null) {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(),
                        mainHandler);
            } catch (SecurityException ignored) {
                // The compatible broadcast remains available without notification access.
            }
        }
        refresh();
        mainHandler.removeCallbacks(ticker);
        mainHandler.postDelayed(ticker, UI_TICK_MS);
    }

    public void stop() {
        if (!started) return;
        started = false;
        mainHandler.removeCallbacks(ticker);
        if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsListener);
        unregisterBroadcastReceiver();
        replace(null);
        sessionState = null;
        // Keep the disk-backed state: the exported receiver may update it while HOME is closed.
        cacheLoadGeneration++;
        broadcastState = null;
    }

    public void refresh() {
        if (manager == null) {
            publish();
            return;
        }
        try {
            select(manager.getActiveSessions(listenerComponent()));
        } catch (SecurityException ignored) {
            replace(null);
            sessionState = null;
            publish();
        }
    }

    public void playPause() {
        MediaController controller = current;
        if (controller == null) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return;
        }
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    public void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
        else dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public void next() {
        if (current != null) current.getTransportControls().skipToNext();
        else dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    private void dispatchMediaKey(int keyCode) {
        if (audioManager == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                    keyCode, 0));
        } catch (RuntimeException ignored) {}
    }

    private void registerBroadcastReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MEDIA_UPDATE);
        filter.addAction(ACTION_MEDIA_CLEAR);
        filter.addAction(ACTION_MEDIA_UPDATE_DEBUG);
        filter.addAction(ACTION_MEDIA_CLEAR_DEBUG);
        filter.addAction(MediaBroadcastRepository.ACTION_CACHE_UPDATED);
        try {
            ContextCompat.registerReceiver(context, broadcastReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ignored) {
            receiverRegistered = false;
        }
    }

    private void unregisterBroadcastReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try { context.unregisterReceiver(broadcastReceiver); }
        catch (IllegalArgumentException ignored) {}
    }

    private void receiveBroadcast(@NonNull Intent intent) {
        // The manifest receiver handles this while HOME is closed. Persist here as well because
        // some Android Automotive builds deliver custom implicit broadcasts only to dynamically
        // registered receivers while the process is in the foreground.
        MediaBroadcastRepository.processAsync(context, intent, null);
        // Do not let an older disk read started during HOME initialization overwrite this newer
        // in-memory update before the cache writer sends ACTION_CACHE_UPDATED.
        cacheLoadGeneration++;
        String title = cleanText(stringExtra(intent, "title"));
        String artist = cleanText(stringExtra(intent, "artist"));
        String album = cleanText(stringExtra(intent, "album"));
        String packageName = cleanText(stringExtra(intent, "package_name"));
        long duration = Math.max(0L, longExtra(intent, "duration_ms", 0L));
        long position = Math.max(0L, longExtra(intent, "position_ms", 0L));
        boolean playing = booleanExtra(intent, "is_playing", false);
        long receivedWall = System.currentTimeMillis();
        long timestamp = longExtra(intent, "timestamp", receivedWall);
        // A malformed/future or monotonic-clock timestamp must not make progress jump forever.
        if (timestamp <= 0L || timestamp > receivedWall + 60_000L
                || timestamp < receivedWall - 24L * 60L * 60L * 1_000L) {
            timestamp = receivedWall;
        }
        MediaState state = new MediaState(title, artist, album, packageName,
                applicationLabel(packageName), null, duration, position, playing, timestamp,
                SystemClock.elapsedRealtime());
        broadcastState = state;
        publish();
        // Artwork is decoded once by MediaBroadcastRepository. Its cache-updated broadcast then
        // reloads the complete state here; decoding it again in the controller doubled CPU and
        // peak bitmap memory whenever HOME was visible.
    }

    private void loadCachedBroadcast() {
        final int generation = ++cacheLoadGeneration;
        MediaBroadcastRepository.readAsync(context, state -> mainHandler.post(() -> {
            if (!started || generation != cacheLoadGeneration) {
                if (state != null && state.artwork != null && !state.artwork.isRecycled()) {
                    state.artwork.recycle();
                }
                return;
            }
            if (state == null) {
                broadcastState = null;
            } else {
                broadcastState = new MediaState(state.title, state.artist, state.album,
                        state.packageName, applicationLabel(state.packageName), state.artwork,
                        state.durationMs, state.positionMs, state.playing, state.timestampWallMs,
                        state.receivedElapsedMs);
            }
            publish();
        }));
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
        publishSession();
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

    private void publishSession() {
        MediaController controller = current;
        if (controller == null) {
            sessionState = null;
            publish();
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playback = controller.getPlaybackState();
        String title = metadata == null ? "" : first(metadata,
                MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = metadata == null ? "" : first(metadata,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String album = metadata == null ? "" : cleanText(
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        Bitmap artwork = metadata == null ? null
                : metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (artwork == null && metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        String packageName = cleanText(controller.getPackageName());
        long duration = metadata == null ? 0L
                : Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        long position = playback == null ? 0L : Math.max(0L, playback.getPosition());
        long updateElapsed = playback == null ? 0L : playback.getLastPositionUpdateTime();
        long updateWall = System.currentTimeMillis();
        if (updateElapsed > 0L) {
            updateWall -= Math.max(0L, SystemClock.elapsedRealtime() - updateElapsed);
        }
        boolean playing = playback != null && playback.getState() == PlaybackState.STATE_PLAYING;
        sessionState = new MediaState(title, artist, album, packageName,
                applicationLabel(packageName), artwork, duration, position, playing, updateWall,
                SystemClock.elapsedRealtime());
        publish();
    }

    private void publish() {
        int volume = volumePercent();
        MediaState session = sessionState;
        MediaState broadcast = broadcastState;
        if (session == null && broadcast == null) {
            listener.onMediaChanged(Snapshot.empty(volume));
            return;
        }
        MediaState primary;
        MediaState supplement = null;
        if (session == null) {
            primary = broadcast;
        } else if (broadcast == null) {
            primary = session;
        } else if (samePackage(session.packageName, broadcast.packageName)) {
            primary = session;
            supplement = broadcast;
        } else if (session.playing) {
            primary = session;
        } else if (broadcast.playing) {
            primary = broadcast;
        } else if (broadcast.positionTimestampWallMs + DIFFERENT_SOURCE_RECENCY_SLOP_MS
                >= session.positionTimestampWallMs) {
            // A paused rich-broadcast player may intentionally expose no MediaSession. Do not
            // replace it after an arbitrary grace period with an unrelated, older paused session.
            primary = broadcast;
        } else {
            primary = session;
        }
        if (primary == null) {
            listener.onMediaChanged(Snapshot.empty(volume));
            return;
        }
        String title = preferred(primary.title, supplement == null ? "" : supplement.title);
        String artist = preferred(primary.artist, supplement == null ? "" : supplement.artist);
        String album = preferred(primary.album, supplement == null ? "" : supplement.album);
        String application = preferred(primary.application,
                supplement == null ? "" : supplement.application);
        Bitmap artwork = primary.artwork != null ? primary.artwork
                : supplement == null ? null : supplement.artwork;
        long duration = primary.durationMs > 0L ? primary.durationMs
                : supplement == null ? 0L : supplement.durationMs;
        long position = primary.currentPosition(System.currentTimeMillis());
        if (position <= 0L && supplement != null) {
            position = supplement.currentPosition(System.currentTimeMillis());
        }
        listener.onMediaChanged(new Snapshot(title.isEmpty() ? "Неизвестный трек" : title,
                artist, album, application, artwork, duration, position, primary.playing, true,
                volume));
    }

    private int volumePercent() {
        if (audioManager == null) return 0;
        try {
            int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maximum <= 0) return 0;
            return Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    * 100f / maximum);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @NonNull
    private String applicationLabel(@NonNull String packageName) {
        if (packageName.isEmpty()) return "";
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    @Nullable
    static Bitmap decodeArtwork(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ARTWORK_BYTES) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > MAX_ARTWORK_EDGE
                    || bounds.outHeight / sample > MAX_ARTWORK_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    @NonNull
    private ComponentName listenerComponent() {
        return new ComponentName(context, MediaNotificationListener.class);
    }

    @NonNull
    private static String first(@NonNull MediaMetadata metadata, @NonNull String... keys) {
        for (String key : keys) {
            String value = cleanText(metadata.getString(key));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    @NonNull
    private static String cleanText(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.equalsIgnoreCase("Unknown")) return "";
        if (clean.length() > MAX_TEXT_LENGTH) clean = clean.substring(0, MAX_TEXT_LENGTH);
        return clean;
    }

    @NonNull
    private static String preferred(@NonNull String primary, @NonNull String fallback) {
        return primary.isEmpty() ? fallback : primary;
    }

    private static boolean samePackage(@NonNull String left, @NonNull String right) {
        return !left.isEmpty() && left.equals(right);
    }

    @Nullable
    private static String stringExtra(@NonNull Intent intent, @NonNull String key) {
        try {
            Object value = extrasValue(intent, key);
            return value instanceof CharSequence ? value.toString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long longExtra(@NonNull Intent intent, @NonNull String key, long fallback) {
        try {
            Object value = extrasValue(intent, key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof CharSequence) return Long.parseLong(value.toString().trim());
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    private static boolean booleanExtra(@NonNull Intent intent, @NonNull String key,
                                        boolean fallback) {
        try {
            Object value = extrasValue(intent, key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).longValue() != 0L;
            if (value instanceof CharSequence) {
                String text = value.toString().trim();
                if ("1".equals(text) || "true".equalsIgnoreCase(text)) return true;
                if ("0".equals(text) || "false".equalsIgnoreCase(text)) return false;
            }
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    @Nullable
    private static Object extrasValue(@NonNull Intent intent, @NonNull String key) {
        Bundle extras = intent.getExtras();
        return extras == null ? null : extras.get(key);
    }
}
