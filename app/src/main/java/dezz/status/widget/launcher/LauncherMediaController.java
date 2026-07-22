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
    private static final int MAX_ARTWORK_EDGE = 640;
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
    private boolean sessionsListenerRegistered;
    private boolean cacheReadInFlight;
    private boolean cacheReloadPending;
    private int cacheLoadGeneration;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!started) return;
            publish();
        }
    };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            if (started) publishSession();
        }
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            if (started) publishSession();
        }
        @Override public void onSessionDestroyed() {
            if (started) refresh();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> {
                if (!started) return;
                try {
                    select(controllers == null ? Collections.emptyList() : controllers);
                } catch (RuntimeException ignored) {
                    // Several ECARX builds expose a short-lived dead MediaSession binder while
                    // switching players. Keep the broadcast source alive instead of crashing HOME.
                    replace(null);
                    sessionState = null;
                    publish();
                }
            };

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            if (!started) return;
            String action = intent == null ? null : intent.getAction();
            if (MediaBroadcastRepository.ACTION_CACHE_UPDATED.equals(action)) {
                loadCachedBroadcast();
                return;
            }
            if (ACTION_MEDIA_CLEAR.equals(action) || ACTION_MEDIA_CLEAR_DEBUG.equals(action)) {
                MediaBroadcastRepository.processAsync(context, intent, null);
                invalidateCacheRead();
                replaceBroadcastState(null);
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
        if (manager != null && !sessionsListenerRegistered) {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(),
                        mainHandler);
                sessionsListenerRegistered = true;
            } catch (RuntimeException ignored) {
                // The compatible broadcast remains available without notification access.
                sessionsListenerRegistered = false;
            }
        }
        refresh();
    }

    public void stop() {
        if (!started) return;
        started = false;
        mainHandler.removeCallbacks(ticker);
        boolean removeSessionsListener = sessionsListenerRegistered;
        sessionsListenerRegistered = false;
        if (manager != null && removeSessionsListener) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) {}
        }
        unregisterBroadcastReceiver();
        replace(null);
        sessionState = null;
        // Keep the disk-backed state: the exported receiver may update it while HOME is closed.
        invalidateCacheRead();
        // Clear ImageView references before the owned broadcast bitmap is recycled on the next
        // main-loop turn. A stopped HOME must not pin or attempt to draw media artwork.
        listener.onMediaChanged(Snapshot.empty(volumePercent()));
        replaceBroadcastState(null);
    }

    public void refresh() {
        if (manager == null) {
            publish();
            return;
        }
        try {
            select(manager.getActiveSessions(listenerComponent()));
        } catch (RuntimeException ignored) {
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
        try {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
        } catch (RuntimeException ignored) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    public void previous() {
        MediaController controller = current;
        if (controller == null) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return;
        }
        try {
            controller.getTransportControls().skipToPrevious();
        } catch (RuntimeException ignored) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    public void next() {
        MediaController controller = current;
        if (controller == null) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            return;
        }
        try {
            controller.getTransportControls().skipToNext();
        } catch (RuntimeException ignored) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        }
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
        catch (RuntimeException ignored) {}
    }

    private void receiveBroadcast(@NonNull Intent intent) {
        // The manifest receiver handles this while HOME is closed. Persist here as well because
        // some Android Automotive builds deliver custom implicit broadcasts only to dynamically
        // registered receivers while the process is in the foreground.
        // Do not let an older disk read started during HOME initialization overwrite this newer
        // in-memory update before the cache writer sends ACTION_CACHE_UPDATED.
        invalidateCacheRead();
        MediaBroadcastRepository.processAsync(context, intent, null);
        boolean titlePresent = hasExtra(intent, "title");
        boolean artistPresent = hasExtra(intent, "artist");
        boolean albumPresent = hasExtra(intent, "album");
        boolean packagePresent = hasExtra(intent, "package_name");
        boolean durationPresent = hasExtra(intent, "duration_ms");
        boolean positionPresent = hasExtra(intent, "position_ms");
        boolean playingPresent = hasExtra(intent, "is_playing");
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
        // Position-only mHUD packets should not make the UI throw away text/artwork and decode
        // the same PNG again. Merge omitted scalar fields only for the same publisher/track; a
        // changed title deliberately drops the old cover until the cache writer publishes it.
        MediaState previous = broadcastState;
        boolean sameSource = MediaBroadcastMergePolicy.sameSource(previous != null,
                packagePresent, packageName, previous == null ? "" : previous.packageName);
        boolean sameTrack = MediaBroadcastMergePolicy.sameTrack(sameSource, titlePresent,
                title, previous == null ? "" : previous.title);
        Bitmap inheritedArtwork = null;
        if (sameSource) {
            title = MediaBroadcastMergePolicy.text(titlePresent, title, true, previous.title);
            artist = MediaBroadcastMergePolicy.text(artistPresent, artist, true, previous.artist);
            album = MediaBroadcastMergePolicy.text(albumPresent, album, true, previous.album);
            packageName = MediaBroadcastMergePolicy.text(packagePresent, packageName, true,
                    previous.packageName);
            duration = MediaBroadcastMergePolicy.number(durationPresent, duration, true,
                    previous.durationMs);
            if (!positionPresent) {
                // Freeze an omitted position at the previous timeline's current point. Reusing the
                // raw base would jump backwards when a playing packet only changes text/state.
                position = previous.currentPosition(receivedWall);
                timestamp = receivedWall;
            }
            playing = MediaBroadcastMergePolicy.flag(playingPresent, playing, true,
                    previous.playing);
            if (sameTrack) inheritedArtwork = previous.artwork;
        }
        MediaState state = new MediaState(title, artist, album, packageName,
                applicationLabel(packageName), inheritedArtwork, duration, position, playing,
                timestamp, SystemClock.elapsedRealtime());
        replaceBroadcastState(state);
        publish();
        // Artwork is decoded once by MediaBroadcastRepository. Its cache-updated broadcast then
        // reloads the complete state here; decoding it again in the controller doubled CPU and
        // peak bitmap memory whenever HOME was visible.
    }

    private void loadCachedBroadcast() {
        if (!started) return;
        int generation = ++cacheLoadGeneration;
        if (cacheReadInFlight) {
            // Cache-updated broadcasts may arrive in a burst when both manifest and dynamic
            // receivers observe one publisher update. Decode only the newest disk snapshot.
            cacheReloadPending = true;
            return;
        }
        beginCacheRead(generation);
    }

    private void beginCacheRead(int generation) {
        cacheReadInFlight = true;
        MediaBroadcastRepository.readAsync(context, state -> mainHandler.post(() ->
                completeCacheRead(generation, state)));
    }

    private void completeCacheRead(int generation, @Nullable MediaBroadcastRepository.State state) {
        cacheReadInFlight = false;
        if (!started || generation != cacheLoadGeneration) {
            recycleArtwork(state);
        } else {
            if (state == null) {
                replaceBroadcastState(null);
            } else {
                replaceBroadcastState(new MediaState(state.title, state.artist, state.album,
                        state.packageName, applicationLabel(state.packageName), state.artwork,
                        state.durationMs, state.positionMs, state.playing, state.timestampWallMs,
                        state.receivedElapsedMs));
            }
            publish();
        }
        if (started && cacheReloadPending) {
            cacheReloadPending = false;
            beginCacheRead(cacheLoadGeneration);
        }
    }

    private void invalidateCacheRead() {
        cacheLoadGeneration++;
        // A direct broadcast is newer than any pending disk snapshot. Wait for its writer's
        // ACTION_CACHE_UPDATED before requesting another decode.
        cacheReloadPending = false;
    }

    private static void recycleArtwork(@Nullable MediaBroadcastRepository.State state) {
        if (state != null && state.artwork != null && !state.artwork.isRecycled()) {
            state.artwork.recycle();
        }
    }

    private void replaceBroadcastState(@Nullable MediaState next) {
        MediaState previous = broadcastState;
        broadcastState = next;
        Bitmap obsolete = previous == null ? null : previous.artwork;
        if (obsolete == null || (next != null && obsolete == next.artwork)
                || obsolete.isRecycled()) return;
        // Publish replaces the ImageView bitmap in the same main-loop turn. Recycling one turn
        // later keeps the peak bounded without invalidating a frame currently being drawn.
        mainHandler.post(() -> {
            MediaState currentState = broadcastState;
            if ((currentState == null || currentState.artwork != obsolete)
                    && !obsolete.isRecycled()) obsolete.recycle();
        });
    }

    private void select(@NonNull List<MediaController> controllers) {
        MediaController selected = null;
        try {
            for (MediaController candidate : controllers) {
                if (candidate == null) continue;
                PlaybackState playback;
                try {
                    playback = candidate.getPlaybackState();
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING) {
                    selected = candidate;
                    break;
                }
                if (selected == null) selected = candidate;
            }
        } catch (RuntimeException ignored) {
            // Keep the best controller found before an OEM list/binder failed.
        }
        replace(selected);
        publishSession();
    }

    private void replace(@Nullable MediaController next) {
        MediaController previous = current;
        if (sameSession(previous, next)) {
            current = next;
            return;
        }
        // Publish callbacks can be dispatched synchronously by vendor implementations. Expose the
        // new controller before registering so such a callback never reads the old session.
        current = next;
        if (previous != null) {
            try { previous.unregisterCallback(mediaCallback); }
            catch (RuntimeException ignored) {}
        }
        if (next != null) {
            try { next.registerCallback(mediaCallback, mainHandler); }
            catch (RuntimeException ignored) {}
        }
    }

    private static boolean sameSession(@Nullable MediaController left,
                                       @Nullable MediaController right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        try {
            return left.getSessionToken().equals(right.getSessionToken());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void publishSession() {
        MediaController controller = current;
        if (controller == null) {
            sessionState = null;
            publish();
            return;
        }
        try {
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
            boolean playing = playback != null
                    && playback.getState() == PlaybackState.STATE_PLAYING;
            sessionState = new MediaState(title, artist, album, packageName,
                    applicationLabel(packageName), artwork, duration, position, playing,
                    updateWall, SystemClock.elapsedRealtime());
        } catch (RuntimeException ignored) {
            // A dead or malformed vendor session must not take the shared launcher/widget process
            // down. Drop only this source; the durable mHUD broadcast can still drive the panel.
            if (current == controller) sessionState = null;
        }
        publish();
    }

    private void publish() {
        int volume = volumePercent();
        MediaState session = sessionState;
        MediaState broadcast = broadcastState;
        if (session == null && broadcast == null) {
            listener.onMediaChanged(Snapshot.empty(volume));
            scheduleTicker(false);
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
            scheduleTicker(false);
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
        scheduleTicker(primary.playing);
    }

    private void scheduleTicker(boolean playing) {
        mainHandler.removeCallbacks(ticker);
        if (started && playing) mainHandler.postDelayed(ticker, UI_TICK_MS);
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

    private static boolean hasExtra(@NonNull Intent intent, @NonNull String key) {
        try {
            Bundle extras = intent.getExtras();
            return extras != null && extras.containsKey(key);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Nullable
    private static Object extrasValue(@NonNull Intent intent, @NonNull String key) {
        Bundle extras = intent.getExtras();
        return extras == null ? null : extras.get(key);
    }
}
