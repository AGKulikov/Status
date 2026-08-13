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
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;
import dezz.status.widget.Permissions;
import dezz.status.widget.Preferences;
import dezz.status.widget.diagnostics.ActionRecorder;
import dezz.status.widget.launcher.media.MediaAppLauncher;
import dezz.status.widget.shell.PrivilegedShell;

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
    private static final long SESSION_REFRESH_PLAYING_MS = 2_500L;
    /** Some ECARX players miss callbacks while paused; keep stale metadata bounded. */
    private static final long SESSION_REFRESH_PAUSED_MS = 20_000L;
    private static final long COMMAND_RECONCILE_FAST_MS = 140L;
    private static final long COMMAND_RECONCILE_SETTLED_MS = 720L;
    private static final long COMMAND_RECONCILE_FINAL_MS = 2_400L;
    /** Small tolerance for publishers whose wall-clock timestamps are not emitted atomically. */
    private static final long DIFFERENT_SOURCE_RECENCY_SLOP_MS = 10_000L;
    private static final long SEEK_COMMAND_INTERVAL_MS = 90L;
    private static final String TAG = "LauncherMedia";

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
        final long artworkIdentity;
        final long durationMs;
        final long positionMs;
        final boolean playing;
        final long positionTimestampWallMs;
        final long receivedElapsedMs;
        final long contentChangedElapsedMs;
        final long playbackChangedElapsedMs;
        final long artworkChangedElapsedMs;

        MediaState(@NonNull String title, @NonNull String artist, @NonNull String album,
                   @NonNull String packageName, @NonNull String application,
                   @Nullable Bitmap artwork, long durationMs, long positionMs, boolean playing,
                   long positionTimestampWallMs, long receivedElapsedMs,
                   long contentChangedElapsedMs, long playbackChangedElapsedMs,
                   long artworkChangedElapsedMs) {
            this(title, artist, album, packageName, application, artwork,
                    artworkIdentity(artwork), durationMs, positionMs, playing,
                    positionTimestampWallMs, receivedElapsedMs, contentChangedElapsedMs,
                    playbackChangedElapsedMs, artworkChangedElapsedMs);
        }

        MediaState(@NonNull String title, @NonNull String artist, @NonNull String album,
                   @NonNull String packageName, @NonNull String application,
                   @Nullable Bitmap artwork, long observedArtworkIdentity,
                   long durationMs, long positionMs, boolean playing,
                   long positionTimestampWallMs, long receivedElapsedMs,
                   long contentChangedElapsedMs, long playbackChangedElapsedMs,
                   long artworkChangedElapsedMs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.packageName = packageName;
            this.application = application;
            this.artwork = artwork;
            this.artworkIdentity = observedArtworkIdentity;
            this.durationMs = Math.max(0L, durationMs);
            this.positionMs = Math.max(0L, positionMs);
            this.playing = playing;
            this.positionTimestampWallMs = positionTimestampWallMs;
            this.receivedElapsedMs = receivedElapsedMs;
            this.contentChangedElapsedMs = contentChangedElapsedMs;
            this.playbackChangedElapsedMs = playbackChangedElapsedMs;
            this.artworkChangedElapsedMs = artworkChangedElapsedMs;
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
    private final Preferences preferences;
    private final MediaSessionManager manager;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    @Nullable private MediaController current;
    @Nullable private MediaState sessionState;
    @Nullable private MediaState broadcastState;
    /**
     * True after the current MediaSession and mHUD stream have described the same player.
     * Kept across track transitions so a lagging OEM session cannot pull the panel backwards.
     */
    private boolean sessionBroadcastCorrelated;
    private boolean started;
    private boolean receiverRegistered;
    private boolean sessionsListenerRegistered;
    private boolean cacheReadInFlight;
    private boolean cacheReloadPending;
    private int cacheLoadGeneration;
    private long lastSessionRefreshElapsedMs;
    private long lastSeekDispatchElapsedMs;
    private long pendingSeekPositionMs = -1L;
    private boolean seekDispatchScheduled;
    private boolean seekFailureToastShown;
    private boolean sessionAccessGrantAttempted;
    @NonNull private List<MediaController> seekGestureControllers = Collections.emptyList();
    @NonNull private String seekGesturePackage = "";
    @NonNull private String visiblePackage = "";
    @NonNull private String visibleTitle = "";
    @NonNull private String visibleArtist = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!started) return;
            long nowElapsed = SystemClock.elapsedRealtime();
            boolean playing = (sessionState != null && sessionState.playing)
                    || (broadcastState != null && broadcastState.playing);
            long refreshInterval = playing
                    ? SESSION_REFRESH_PLAYING_MS : SESSION_REFRESH_PAUSED_MS;
            if (MediaStateFreshness.shouldRefreshSession(current != null,
                    nowElapsed, lastSessionRefreshElapsedMs, refreshInterval)) {
                // Mark before the Binder call so an OEM exception cannot create a tight retry loop.
                lastSessionRefreshElapsedMs = nowElapsed;
                refresh();
            } else {
                publish();
            }
        }
    };

    /**
     * Some ECARX MediaSession implementations accept a transport command without dispatching the
     * corresponding callback. Three bounded refreshes keep the button state responsive without
     * turning the panel into a polling loop.
     */
    private final Runnable commandReconcile = new Runnable() {
        @Override public void run() {
            if (!started) return;
            refresh();
        }
    };

    private final Runnable pendingSeekDispatch = () -> {
        seekDispatchScheduled = false;
        dispatchPendingSeek(false);
    };

    private final Runnable broadcastExpiry = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (expireBroadcastIfNeeded()) publish();
            else scheduleBroadcastExpiry();
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
        this(context, new Preferences(context), listener);
    }

    /** Reuses HOME's already-migrated graph instead of repeating migration on the UI thread. */
    public LauncherMediaController(@NonNull Context context,
                                   @NonNull Preferences preferences,
                                   @NonNull Listener listener) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.preferences = preferences;
        this.listener = listener;
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start() {
        if (started) return;
        started = true;
        registerBroadcastReceiver();
        loadCachedBroadcast();
        registerSessionsListener();
        ensureSessionAccessForTransportControls();
        refresh();
    }

    public void stop() {
        if (!started) return;
        started = false;
        mainHandler.removeCallbacks(ticker);
        mainHandler.removeCallbacks(commandReconcile);
        mainHandler.removeCallbacks(broadcastExpiry);
        mainHandler.removeCallbacks(pendingSeekDispatch);
        pendingSeekPositionMs = -1L;
        seekDispatchScheduled = false;
        clearSeekGesture();
        lastSessionRefreshElapsedMs = 0L;
        boolean removeSessionsListener = sessionsListenerRegistered;
        sessionsListenerRegistered = false;
        if (manager != null && removeSessionsListener) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) {}
        }
        unregisterBroadcastReceiver();
        replace(null);
        sessionState = null;
        clearVisibleMedia();
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
        String target = commandTargetPackage();
        if (target.isEmpty()) return;
        MediaResumeCommand.playPause(context, target);
        scheduleCommandReconcile();
    }

    public void previous() {
        String target = commandTargetPackage();
        if (target.isEmpty()) return;
        MediaResumeCommand.previous(context, target);
        scheduleCommandReconcile();
    }

    public void next() {
        String target = commandTargetPackage();
        if (target.isEmpty()) return;
        MediaResumeCommand.next(context, target);
        scheduleCommandReconcile();
    }

    /**
     * mSaver-compatible Like: heart rating on the represented MediaSession first, then the
     * captured Like action from that player's live media notification. No mSaver-private
     * broadcast is emitted.
     */
    public boolean like() {
        String targetPackage = seekTargetPackage();
        for (MediaController controller : resolveSeekControllers(true)) {
            boolean matches = !targetPackage.isEmpty()
                    ? samePackage(targetPackage, controllerPackage(controller))
                    : controllerMatchesVisibleTrack(controller) || sameSession(current, controller);
            if (!matches) continue;
            try {
                PlaybackState playback = controller.getPlaybackState();
                if (playback == null
                        || (playback.getActions() & PlaybackState.ACTION_SET_RATING) == 0L) {
                    continue;
                }
                MediaMetadata metadata = controller.getMetadata();
                Rating rating = metadata == null ? null
                        : metadata.getRating(MediaMetadata.METADATA_KEY_RATING);
                if (rating == null && metadata != null) {
                    rating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
                }
                boolean heartRating = rating != null
                        && rating.getRatingStyle() == Rating.RATING_HEART;
                boolean hasHeart = heartRating && rating.hasHeart();
                controller.getTransportControls().setRating(Rating.newHeartRating(
                        MediaLikeActionPolicy.nextHeart(heartRating, hasHeart)));
                recordLike("media_session", targetPackage);
                scheduleCommandReconcile();
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        if (MediaNotificationListener.sendMediaNotificationLike(targetPackage)) {
            recordLike("notification_action", targetPackage);
            scheduleCommandReconcile();
            return true;
        }
        recordLike("unavailable", targetPackage);
        return false;
    }

    private static void recordLike(@NonNull String route, @NonNull String packageName) {
        ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "MEDIA_LIKE",
                ActionRecorder.object("route", route, "package", packageName));
    }

    /** Coalesces live scrubbing while preserving the exact player represented on HOME. */
    public void seekTo(long positionMs) {
        pendingSeekPositionMs = Math.max(0L, positionMs);
        long elapsed = SystemClock.elapsedRealtime() - lastSeekDispatchElapsedMs;
        if (!seekDispatchScheduled && elapsed >= SEEK_COMMAND_INTERVAL_MS) {
            dispatchPendingSeek(false);
            return;
        }
        if (seekDispatchScheduled) return;
        seekDispatchScheduled = true;
        mainHandler.postDelayed(pendingSeekDispatch,
                Math.max(1L, SEEK_COMMAND_INTERVAL_MS - Math.max(0L, elapsed)));
    }

    /** Commits ACTION_UP immediately, including players visible only through notifications. */
    public void finishSeek(long positionMs) {
        pendingSeekPositionMs = Math.max(0L, positionMs);
        mainHandler.removeCallbacks(pendingSeekDispatch);
        seekDispatchScheduled = false;
        dispatchPendingSeek(true);
        clearSeekGesture();
        lastSeekDispatchElapsedMs = 0L;
    }

    private void dispatchPendingSeek(boolean finish) {
        long positionMs = pendingSeekPositionMs;
        if (positionMs < 0L) return;
        pendingSeekPositionMs = -1L;
        lastSeekDispatchElapsedMs = SystemClock.elapsedRealtime();
        String targetPackage = seekTargetPackage();
        if (!targetPackage.equals(seekGesturePackage)
                || seekGestureControllers.isEmpty() || finish) {
            seekGesturePackage = targetPackage;
            seekGestureControllers = resolveSeekControllers(finish);
        }
        int routes = sendSeekToMatchingControllers(
                seekGestureControllers, targetPackage, positionMs);
        if (routes == 0 && !finish) {
            seekGestureControllers = resolveSeekControllers(true);
            routes = sendSeekToMatchingControllers(
                    seekGestureControllers, targetPackage, positionMs);
        }
        if (routes > 0) {
            seekFailureToastShown = false;
            scheduleCommandReconcile();
        } else if (finish) {
            onSeekRouteMissing(targetPackage, positionMs);
        }
        if (finish) {
            Log.i(TAG, "seek finish package=" + targetPackage
                    + " positionMs=" + positionMs + " routes=" + routes
                    + " notificationAccess="
                    + Permissions.isNotificationAccessGranted(context));
            ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "MEDIA_SEEK",
                    ActionRecorder.object(
                            "package", targetPackage,
                            "position_ms", positionMs,
                            "routes", routes,
                            "notification_access",
                            Permissions.isNotificationAccessGranted(context)));
        }
    }

    @NonNull
    private List<MediaController> resolveSeekControllers(boolean includeManagerLookup) {
        List<MediaController> result = new ArrayList<>();
        for (MediaController controller
                : MediaNotificationListener.activeMediaNotificationControllers(context)) {
            addSeekController(result, controller);
        }
        addSeekController(result, current);
        if (includeManagerLookup && manager != null) {
            try {
                List<MediaController> active = manager.getActiveSessions(listenerComponent());
                if (active != null) {
                    for (MediaController controller : active) addSeekController(result, controller);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return result;
    }

    private static void addSeekController(@NonNull List<MediaController> result,
                                          @Nullable MediaController candidate) {
        if (candidate == null) return;
        for (MediaController existing : result) {
            if (sameSession(existing, candidate)) return;
        }
        result.add(candidate);
    }

    private int sendSeekToMatchingControllers(@NonNull List<MediaController> controllers,
                                              @NonNull String targetPackage,
                                              long positionMs) {
        int sent = 0;
        for (MediaController controller : controllers) {
            boolean matches = !targetPackage.isEmpty()
                    ? samePackage(targetPackage, controllerPackage(controller))
                    : controllerMatchesVisibleTrack(controller)
                    || sameSession(current, controller);
            if (!matches) continue;
            try {
                controller.getTransportControls().seekTo(positionMs);
                sent++;
            } catch (RuntimeException ignored) {
            }
        }
        return sent;
    }

    private boolean controllerMatchesVisibleTrack(@NonNull MediaController controller) {
        if (visibleTitle.isEmpty() && visibleArtist.isEmpty()) return false;
        MediaMetadata metadata;
        try {
            metadata = controller.getMetadata();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (metadata == null) return false;
        String title = first(metadata, MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = first(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        int evidence = 0;
        if (!visibleTitle.isEmpty() && !title.isEmpty()) {
            if (!visibleTitle.equalsIgnoreCase(title)) return false;
            evidence++;
        }
        if (!visibleArtist.isEmpty() && !artist.isEmpty()) {
            if (!visibleArtist.equalsIgnoreCase(artist)) return false;
            evidence++;
        }
        return evidence > 0;
    }

    @NonNull
    private String seekTargetPackage() {
        if (preferences.launcherMediaFixedPlayerEnabled.get()) {
            String fixed = cleanText(preferences.launcherMediaFixedPlayerPackage.get());
            if (!fixed.isEmpty()) return fixed;
        }
        if (!visiblePackage.isEmpty()) return visiblePackage;
        MediaState broadcast = broadcastState;
        if (broadcast != null && !broadcast.packageName.isEmpty()) return broadcast.packageName;
        MediaState session = sessionState;
        return session != null && !session.packageName.isEmpty()
                ? session.packageName : commandTargetPackage();
    }

    private void onSeekRouteMissing(@NonNull String targetPackage, long positionMs) {
        boolean notificationAccess = Permissions.isNotificationAccessGranted(context);
        Log.w(TAG, "seek route missing package=" + targetPackage
                + " positionMs=" + positionMs
                + " notificationAccess=" + notificationAccess);
        try {
            NotificationListenerService.requestRebind(listenerComponent());
        } catch (RuntimeException ignored) {
        }
        ensureSessionAccessForTransportControls();
        if (seekFailureToastShown) return;
        seekFailureToastShown = true;
        Toast.makeText(context,
                notificationAccess
                        ? "Плеер не передал медиасессию для перемотки"
                        : "Для перемотки нужен доступ к уведомлениям",
                Toast.LENGTH_LONG).show();
    }

    private void clearSeekGesture() {
        seekGestureControllers = Collections.emptyList();
        seekGesturePackage = "";
    }

    /** Opens the same player that owns HOME transport commands. */
    public boolean openTargetPlayer() {
        String target = commandTargetPackage();
        return target.isEmpty()
                ? MediaAppLauncher.launchYandexMusic(context)
                : MediaAppLauncher.launchPackage(context, target);
    }

    private void scheduleCommandReconcile() {
        mainHandler.removeCallbacks(commandReconcile);
        if (!started) return;
        mainHandler.postDelayed(commandReconcile, COMMAND_RECONCILE_FAST_MS);
        mainHandler.postDelayed(commandReconcile, COMMAND_RECONCILE_SETTLED_MS);
        mainHandler.postDelayed(commandReconcile, COMMAND_RECONCILE_FINAL_MS);
    }

    private void ensureSessionAccessForTransportControls() {
        if (sessionAccessGrantAttempted) return;
        sessionAccessGrantAttempted = true;
        if (Permissions.isNotificationAccessGranted(context)) {
            try {
                NotificationListenerService.requestRebind(listenerComponent());
            } catch (RuntimeException ignored) {
            }
            registerSessionsListener();
            return;
        }
        PrivilegedShell.get(context).ensurePrivileges(
                PrivilegedShell.Request.forPackage(context.getPackageName())
                        .withNotificationListener(PrivilegedShell.notificationListenerComponent(
                                context.getPackageName(), MediaNotificationListener.class))
                        .build(), result -> {
                    if (!Permissions.isNotificationAccessGranted(context)) return;
                    try {
                        NotificationListenerService.requestRebind(listenerComponent());
                    } catch (RuntimeException ignored) {
                    }
                    if (started) {
                        registerSessionsListener();
                        refresh();
                    }
                });
    }

    private void registerSessionsListener() {
        if (manager == null || sessionsListenerRegistered) return;
        try {
            manager.addOnActiveSessionsChangedListener(
                    sessionsListener, listenerComponent(), mainHandler);
            sessionsListenerRegistered = true;
        } catch (RuntimeException ignored) {
            // The durable broadcast source remains usable without MediaSession authorization.
            sessionsListenerRegistered = false;
        }
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
        // Do not let an older disk read started during HOME initialization overwrite this newer
        // in-memory update before the cache writer sends ACTION_CACHE_UPDATED.
        invalidateCacheRead();
        boolean titlePresent = hasExtra(intent, "title");
        boolean artistPresent = hasExtra(intent, "artist");
        boolean albumPresent = hasExtra(intent, "album");
        boolean packagePresent = hasExtra(intent, "package_name");
        boolean durationPresent = hasExtra(intent, "duration_ms");
        boolean positionPresent = hasExtra(intent, "position_ms");
        boolean playingPresent = hasExtra(intent, "is_playing");
        boolean artworkDirectivePresent = hasExtra(intent, "artwork_uri")
                || hasExtra(intent, "artwork_bytes") || hasExtra(intent, "has_artwork");
        String title = cleanText(stringExtra(intent, "title"));
        String artist = cleanText(stringExtra(intent, "artist"));
        String album = cleanText(stringExtra(intent, "album"));
        String packageName = cleanText(stringExtra(intent, "package_name"));
        String artworkUri = cleanText(stringExtra(intent, "artwork_uri"));
        boolean artworkSourcePresent = !artworkUri.isEmpty()
                || hasNonEmptyByteArrayExtra(intent, "artwork_bytes");
        boolean artworkExpected = artworkSourcePresent
                || booleanExtra(intent, "has_artwork", false);
        boolean explicitArtworkClear = artworkDirectivePresent && !artworkExpected;
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
        // Position-only mHUD packets should not make the UI throw away metadata or the current
        // cover. Omitted fields are inherited only from the same publisher.
        MediaState previous = broadcastState;
        boolean sameSource = MediaBroadcastMergePolicy.sameSource(previous != null,
                packagePresent, packageName, previous == null ? "" : previous.packageName);
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
        }
        // An explicitly changed title is authoritative even if a publisher accidentally repeats
        // stale artist/album values. With no explicit title, use the remaining identity fields.
        boolean sameTrack = previous != null && sameSource
                && (!titlePresent || title.equals(previous.title))
                && MediaStateFreshness.sameTrack(previous.packageName, previous.title,
                previous.artist, previous.album, packageName, title, artist, album);
        Bitmap inheritedArtwork = sameTrack && !explicitArtworkClear ? previous.artwork : null;
        long receivedElapsed = SystemClock.elapsedRealtime();
        boolean contentChanged = previous == null || !MediaStateFreshness.sameContent(
                previous.packageName, previous.title, previous.artist, previous.album,
                previous.durationMs, packageName, title, artist, album, duration);
        boolean playbackChanged = previous == null || previous.playing != playing;
        MediaState state = new MediaState(title, artist, album, packageName,
                applicationLabel(packageName), inheritedArtwork, duration, position, playing,
                timestamp, receivedElapsed,
                MediaStateFreshness.changedAt(contentChanged, receivedElapsed,
                        previous == null ? 0L : previous.contentChangedElapsedMs),
                MediaStateFreshness.changedAt(playbackChanged, receivedElapsed,
                        previous == null ? 0L : previous.playbackChangedElapsedMs),
                MediaStateFreshness.changedAt(previous == null || !sameTrack
                                || artworkDirectivePresent,
                        receivedElapsed,
                        previous == null ? 0L : previous.artworkChangedElapsedMs));
        replaceBroadcastState(state);
        publish();
        // Persist only after the direct state has received its monotonic markers. An older artwork
        // decode that finishes late can then be merged field-by-field instead of rolling back the
        // newer track or play/pause state.
        MediaBroadcastRepository.processAsync(context, intent, null);
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
                mergeCachedBroadcastState(new MediaState(state.title, state.artist, state.album,
                        state.packageName, applicationLabel(state.packageName), state.artwork,
                        state.durationMs, state.positionMs, state.playing, state.timestampWallMs,
                        state.receivedElapsedMs, state.contentChangedElapsedMs,
                        state.playbackChangedElapsedMs, state.artworkChangedElapsedMs));
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

    private boolean expireBroadcastIfNeeded() {
        MediaState state = broadcastState;
        if (state == null) return false;
        long ttl = broadcastTtl(state);
        if (!MediaBroadcastFreshness.expired(SystemClock.elapsedRealtime(),
                state.receivedElapsedMs, ttl)) return false;
        replaceBroadcastState(null);
        return true;
    }

    private void scheduleBroadcastExpiry() {
        mainHandler.removeCallbacks(broadcastExpiry);
        MediaState state = broadcastState;
        if (!started || state == null) return;
        long delay = MediaBroadcastFreshness.remaining(SystemClock.elapsedRealtime(),
                state.receivedElapsedMs, broadcastTtl(state));
        mainHandler.postDelayed(broadcastExpiry, Math.max(1L, delay));
    }

    private static long broadcastTtl(@NonNull MediaState state) {
        boolean known = !state.title.isEmpty() || !state.artist.isEmpty()
                || !state.packageName.isEmpty();
        return MediaBroadcastFreshness.ttl(
                known, state.playing, state.durationMs, state.positionMs);
    }

    /**
     * A cache read may have started before a newer foreground packet arrived. Select each field by
     * its own clock, then reject any playback/timeline/artwork belonging to another track.
     */
    private void mergeCachedBroadcastState(@NonNull MediaState incoming) {
        MediaState currentState = broadcastState;
        if (currentState == null) {
            replaceBroadcastState(incoming);
            return;
        }
        MediaState content = MediaStateFreshness.incomingWins(
                incoming.contentChangedElapsedMs, currentState.contentChangedElapsedMs)
                ? incoming : currentState;
        MediaState playback = MediaStateFreshness.incomingWins(
                incoming.playbackChangedElapsedMs, currentState.playbackChangedElapsedMs)
                ? incoming : currentState;
        MediaState artwork = MediaStateFreshness.incomingWins(
                incoming.artworkChangedElapsedMs, currentState.artworkChangedElapsedMs)
                ? incoming : currentState;
        MediaState timeline = incoming.receivedElapsedMs >= currentState.receivedElapsedMs
                ? incoming : currentState;
        if (!sameTrack(content, playback)) playback = content;
        if (!sameTrack(content, artwork)) artwork = content;
        if (!sameTrack(content, timeline)) timeline = content;

        MediaState merged = new MediaState(content.title, content.artist, content.album,
                content.packageName, content.application, artwork.artwork, content.durationMs,
                timeline.positionMs, playback.playing, timeline.positionTimestampWallMs,
                Math.max(incoming.receivedElapsedMs, currentState.receivedElapsedMs),
                content.contentChangedElapsedMs, playback.playbackChangedElapsedMs,
                artwork.artworkChangedElapsedMs);
        replaceBroadcastState(merged);

        // replaceBroadcastState owns currentState's bitmap. Recycle only an incoming bitmap that
        // lost every field-selection race and is therefore not referenced by the merged state.
        Bitmap discarded = incoming.artwork;
        if (discarded != null && discarded != merged.artwork
                && discarded != currentState.artwork && !discarded.isRecycled()) {
            discarded.recycle();
        }
    }

    private void replaceBroadcastState(@Nullable MediaState next) {
        MediaState previous = broadcastState;
        boolean resetCorrelation = MediaStateFreshness.shouldResetBroadcastCorrelation(
                previous != null, previous == null ? "" : previous.packageName,
                next != null, next == null ? "" : next.packageName);
        if (resetCorrelation) sessionBroadcastCorrelated = false;

        Bitmap previousArtwork = previous == null ? null : previous.artwork;
        Bitmap incomingArtwork = next == null ? null : next.artwork;
        Bitmap duplicateArtwork = null;
        if (!resetCorrelation && previous != null && next != null
                && incomingArtwork != previousArtwork
                && MediaStateFreshness.shouldReuseBroadcastArtwork(
                        sameTrack(previous, next),
                        previousArtwork != null && !previousArtwork.isRecycled(),
                        previous.artworkIdentity,
                        incomingArtwork != null && !incomingArtwork.isRecycled(),
                        next.artworkIdentity)) {
            duplicateArtwork = incomingArtwork;
            // Keep the wrapper already owned by the visible snapshot. A one-second cache decode
            // can produce a different Bitmap object with identical pixels; swapping wrappers and
            // recycling the old one would invalidate a UI that correctly skipped the no-op bind.
            next = new MediaState(next.title, next.artist, next.album,
                    next.packageName, next.application, previousArtwork,
                    previous.artworkIdentity, next.durationMs, next.positionMs, next.playing,
                    next.positionTimestampWallMs, next.receivedElapsedMs,
                    next.contentChangedElapsedMs, next.playbackChangedElapsedMs,
                    next.artworkChangedElapsedMs);
        }

        broadcastState = next;
        scheduleBroadcastExpiry();
        if (duplicateArtwork != null && !duplicateArtwork.isRecycled()) {
            duplicateArtwork.recycle();
        }
        Bitmap obsolete = previousArtwork;
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
        MediaController first = null;
        MediaController firstPlaying = null;
        MediaController target = null;
        MediaController targetPlaying = null;
        MediaController retained = null;
        boolean retainedPlaying = false;
        String targetPackage = commandTargetPackage();
        try {
            for (MediaController candidate : controllers) {
                if (candidate == null) continue;
                PlaybackState playback;
                try {
                    playback = candidate.getPlaybackState();
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (first == null) first = candidate;
                boolean playing = playback != null
                        && playback.getState() == PlaybackState.STATE_PLAYING;
                if (playing && firstPlaying == null) firstPlaying = candidate;
                if (!targetPackage.isEmpty()
                        && targetPackage.equals(controllerPackage(candidate))) {
                    if (target == null) target = candidate;
                    if (playing && targetPlaying == null) targetPlaying = candidate;
                }
                if (sameSession(current, candidate)) {
                    retained = candidate;
                    retainedPlaying = playing;
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the best controller found before an OEM list/binder failed.
        }
        boolean keepCurrent = MediaStateFreshness.shouldKeepCurrentSession(
                retained != null, retainedPlaying, firstPlaying != null);
        MediaController selected;
        if (targetPlaying != null || target != null) {
            selected = targetPlaying != null ? targetPlaying : target;
        } else if (preferences.launcherMediaFixedPlayerEnabled.get()
                && !targetPackage.isEmpty()) {
            // Do not let an unrelated Bluetooth/radio session replace the fixed Android player
            // merely because the selected app has not restored its MediaSession yet.
            selected = null;
        } else {
            selected = keepCurrent ? retained : firstPlaying != null ? firstPlaying : first;
        }
        replace(selected);
        publishSession();
    }

    @NonNull
    private String commandTargetPackage() {
        MediaPlaybackHistoryStore.Snapshot history = MediaPlaybackHistoryStore.read(context);
        String target = MediaPlaybackTargetPolicy.resolve(
                preferences.launcherMediaFixedPlayerEnabled.get(),
                preferences.launcherMediaFixedPlayerPackage.get(),
                history.packageName);
        if (!target.isEmpty()) return target;
        return controllerPackage(current);
    }

    @NonNull
    private static String controllerPackage(@Nullable MediaController controller) {
        if (controller == null) return "";
        try {
            String packageName = controller.getPackageName();
            return packageName == null ? "" : packageName.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
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
        // Yandex may rotate between several tokens owned by the same package during a track
        // handoff. Preserve the proven broadcast relationship across that rotation, but never
        // leak it to a genuinely different player.
        if (!sameControllerPackage(previous, next)) sessionBroadcastCorrelated = false;
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

    private static boolean sameControllerPackage(@Nullable MediaController left,
                                                 @Nullable MediaController right) {
        if (left == null || right == null) return false;
        try {
            return samePackage(cleanText(left.getPackageName()),
                    cleanText(right.getPackageName()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void publishSession() {
        long observedElapsed = SystemClock.elapsedRealtime();
        lastSessionRefreshElapsedMs = observedElapsed;
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
            Bitmap metadataArtwork = metadata == null ? null
                    : metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (metadataArtwork == null && metadata != null) {
                metadataArtwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
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
            long receivedElapsed = observedElapsed;
            MediaState previous = sessionState;
            boolean contentChanged = previous == null || !MediaStateFreshness.sameContent(
                    previous.packageName, previous.title, previous.artist, previous.album,
                    previous.durationMs, packageName, title, artist, album, duration);
            boolean trackChanged = previous == null || !MediaStateFreshness.sameTrack(
                    previous.packageName, previous.title, previous.artist, previous.album,
                    packageName, title, artist, album);
            boolean playbackChanged = previous == null || previous.playing != playing;
            long incomingArtworkIdentity = artworkIdentity(metadataArtwork);
            boolean displayArtwork = MediaStateFreshness.shouldDisplaySessionArtwork(
                    previous != null, trackChanged,
                    previous == null ? 0L : previous.artworkIdentity,
                    previous != null && previous.artwork != null, incomingArtworkIdentity);
            Bitmap artwork = displayArtwork ? metadataArtwork : null;
            boolean artworkChanged = previous == null || MediaStateFreshness.artworkChanged(
                    contentChanged, previous.artworkIdentity, incomingArtworkIdentity)
                    || (previous.artwork != null) != displayArtwork;
            sessionState = new MediaState(title, artist, album, packageName,
                    applicationLabel(packageName), artwork, incomingArtworkIdentity,
                    duration, position, playing,
                    updateWall, receivedElapsed,
                    MediaStateFreshness.changedAt(contentChanged, receivedElapsed,
                            previous == null ? 0L : previous.contentChangedElapsedMs),
                    MediaStateFreshness.changedAt(playbackChanged, receivedElapsed,
                            previous == null ? 0L : previous.playbackChangedElapsedMs),
                    MediaStateFreshness.changedAt(artworkChanged, receivedElapsed,
                            previous == null ? 0L : previous.artworkChangedElapsedMs));
        } catch (RuntimeException ignored) {
            // A dead or malformed vendor session must not take the shared launcher/widget process
            // down. Drop only this source; the durable mHUD broadcast can still drive the panel.
            if (current == controller) sessionState = null;
        }
        publish();
    }

    private void publish() {
        expireBroadcastIfNeeded();
        int volume = volumePercent();
        MediaState session = sessionState;
        MediaState broadcast = broadcastState;
        if (session == null && broadcast == null) {
            clearVisibleMedia();
            listener.onMediaChanged(Snapshot.empty(volume));
            scheduleTicker(false);
            return;
        }
        if (session == null) {
            publish(broadcast, null, broadcast, broadcast, broadcast, volume);
            return;
        } else if (broadcast == null) {
            publish(session, null, session, session, session, volume);
            return;
        }

        boolean packageMatches = samePackage(session.packageName, broadcast.packageName);
        boolean metadataMatches = MediaStateFreshness.sameTrackMetadata(
                session.title, session.artist, session.album,
                broadcast.title, broadcast.artist, broadcast.album);
        boolean sharedTrackEvidence = MediaStateFreshness.hasSharedTrackEvidence(
                session.title, session.artist, session.album,
                broadcast.title, broadcast.artist, broadcast.album);
        if (metadataMatches && sharedTrackEvidence) {
            sessionBroadcastCorrelated = true;
        }
        if (packageMatches || sessionBroadcastCorrelated) {
            boolean tracksMatch = sameCorrelatedTrack(session, broadcast);
            MediaState content = MediaStateFreshness.broadcastContentWins(
                    tracksMatch, sessionBroadcastCorrelated,
                    SystemClock.elapsedRealtime(), broadcast.receivedElapsedMs,
                    broadcast.contentChangedElapsedMs,
                    session.contentChangedElapsedMs) ? broadcast : session;
            MediaState supplement = content == broadcast ? session : broadcast;
            MediaState playback = broadcast.playbackChangedElapsedMs
                    > session.playbackChangedElapsedMs ? broadcast : session;
            MediaState sessionArtwork = sameCorrelatedTrack(content, session)
                    ? session : content;
            MediaState broadcastArtwork = sameCorrelatedTrack(content, broadcast)
                    ? broadcast : content;
            MediaState artwork = MediaStateFreshness.incomingArtworkWins(
                    broadcastArtwork.artwork != null, broadcastArtwork.artworkChangedElapsedMs,
                    sessionArtwork.artwork != null, sessionArtwork.artworkChangedElapsedMs)
                    ? broadcastArtwork : sessionArtwork;
            MediaState timeline = broadcast.receivedElapsedMs
                    > session.receivedElapsedMs ? broadcast : session;
            if (!sameCorrelatedTrack(content, playback)) playback = content;
            if (!sameCorrelatedTrack(content, timeline)) timeline = content;
            if (!sameCorrelatedTrack(content, supplement)) supplement = null;
            publish(content, supplement, playback, artwork, timeline, volume);
            return;
        }

        MediaState content;
        if (session.playing) {
            content = session;
        } else if (broadcast.playing) {
            content = broadcast;
        } else if (broadcast.positionTimestampWallMs + DIFFERENT_SOURCE_RECENCY_SLOP_MS
                >= session.positionTimestampWallMs) {
            // A paused rich-broadcast player may intentionally expose no MediaSession. Do not
            // replace it after an arbitrary grace period with an unrelated, older paused session.
            content = broadcast;
        } else {
            content = session;
        }
        publish(content, null, content, content, content, volume);
    }

    private void publish(@NonNull MediaState content, @Nullable MediaState supplement,
                         @NonNull MediaState playback, @NonNull MediaState artwork,
                         @NonNull MediaState timeline, int volume) {
        String title = preferred(content.title, supplement == null ? "" : supplement.title);
        String artist = preferred(content.artist, supplement == null ? "" : supplement.artist);
        String album = preferred(content.album, supplement == null ? "" : supplement.album);
        String application = preferred(content.application,
                supplement == null ? "" : supplement.application);
        String packageName = preferred(content.packageName,
                supplement == null ? "" : supplement.packageName);
        Bitmap artworkBitmap = artwork.artwork;
        long duration = content.durationMs > 0L ? content.durationMs
                : supplement == null ? 0L : supplement.durationMs;
        long position = timeline.currentPosition(System.currentTimeMillis());
        if (position <= 0L && supplement != null) {
            position = supplement.currentPosition(System.currentTimeMillis());
        }
        visiblePackage = packageName;
        visibleTitle = title;
        visibleArtist = artist;
        MediaPlaybackHistoryStore.record(context, packageName, playback.playing);
        listener.onMediaChanged(new Snapshot(title.isEmpty() ? "Неизвестный трек" : title,
                artist, album, application, artworkBitmap, duration, position, playback.playing, true,
                volume));
        scheduleTicker(playback.playing);
    }

    private void clearVisibleMedia() {
        visiblePackage = "";
        visibleTitle = "";
        visibleArtist = "";
    }

    private void scheduleTicker(boolean playing) {
        mainHandler.removeCallbacks(ticker);
        if (started) {
            mainHandler.postDelayed(ticker, playing ? UI_TICK_MS : SESSION_REFRESH_PAUSED_MS);
        }
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

    private static long artworkIdentity(@Nullable Bitmap artwork) {
        if (artwork == null) return 0L;
        try {
            int width = artwork.getWidth();
            int height = artwork.getHeight();
            if (width <= 0 || height <= 0 || artwork.isRecycled()) return 0L;
            // MediaMetadata may unparcel a fresh Bitmap object on every Binder read. Object
            // identity and generationId therefore make unchanged pixels look "new" every 2.5 s
            // and let a stale MediaSession cover permanently outrank the rich-media broadcast.
            // A bounded pixel fingerprint stays stable across those equivalent Bitmap instances.
            long value = 0xcbf29ce484222325L;
            value = mixArtworkFingerprint(value, width);
            value = mixArtworkFingerprint(value, height);
            int columns = Math.min(9, width);
            int rows = Math.min(9, height);
            for (int row = 0; row < rows; row++) {
                int y = rows == 1 ? 0
                        : (int) ((long) row * (height - 1) / (rows - 1));
                for (int column = 0; column < columns; column++) {
                    int x = columns == 1 ? 0
                            : (int) ((long) column * (width - 1) / (columns - 1));
                    value = mixArtworkFingerprint(value, artwork.getPixel(x, y));
                }
            }
            return value == 0L ? 1L : value;
        } catch (RuntimeException ignored) {
            // Some hardware-backed vendor bitmaps forbid getPixel(). generationId is less stable
            // than the sampled fingerprint but still avoids depending on Java wrapper identity.
            try {
                long value = ((long) artwork.getGenerationId()) & 0xffff_ffffL;
                value = value * 31L + artwork.getWidth();
                value = value * 31L + artwork.getHeight();
                return value == 0L ? 1L : value;
            } catch (RuntimeException invalid) {
                return 0L;
            }
        }
    }

    private static long mixArtworkFingerprint(long value, int sample) {
        return (value ^ (((long) sample) & 0xffff_ffffL)) * 0x100000001b3L;
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

    private static boolean sameTrack(@NonNull MediaState left, @NonNull MediaState right) {
        return MediaStateFreshness.sameTrack(left.packageName, left.title, left.artist, left.album,
                right.packageName, right.title, right.artist, right.album);
    }

    private boolean sameCorrelatedTrack(@NonNull MediaState left, @NonNull MediaState right) {
        if (samePackage(left.packageName, right.packageName)) return sameTrack(left, right);
        return sessionBroadcastCorrelated && MediaStateFreshness.sameTrackMetadata(
                left.title, left.artist, left.album, right.title, right.artist, right.album);
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

    private static boolean hasNonEmptyByteArrayExtra(@NonNull Intent intent,
                                                     @NonNull String key) {
        try {
            Object value = extrasValue(intent, key);
            return value instanceof byte[] && ((byte[]) value).length > 0;
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
