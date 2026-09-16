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
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private static final long MEDIA_NOTIFICATION_REFRESH_MS = 2_000L;
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
    /**
     * MediaSessionManager is an OEM Binder service and may block while the head unit is under
     * GPU pressure. One shared worker keeps that call off every HOME/HUD main Looper.
     */
    private static final ThreadPoolExecutor SESSION_QUERY_LANE = createSessionQueryLane("media-session-list");
    private static final ThreadPoolExecutor SESSION_READ_LANE = createSessionQueryLane("media-session-read");
    private static final ThreadPoolExecutor SESSION_CALLBACK_LANE = createSessionQueryLane("media-session-callbacks");
    private static final ThreadPoolExecutor VOLUME_QUERY_LANE = createSessionQueryLane("media-volume-read");
    private static final ThreadPoolExecutor ARTWORK_QUERY_LANE = createSessionQueryLane("media-artwork");

    /** One runtime, one notification/session selection and one ticker for every display. */
    private static SharedPlayback sharedPlayback;
    private static synchronized SharedPlayback shared(Context context, Preferences preferences) {
        if (sharedPlayback == null) sharedPlayback = new SharedPlayback(context, preferences);
        return sharedPlayback;
    }

    private static final class SharedPlayback {
        final ArrayList<LauncherMediaController> clients = new ArrayList<>();
        final LauncherMediaController engine;
        Snapshot latest;
        SharedPlayback(Context context, Preferences preferences) {
            engine = new LauncherMediaController(context, preferences, this::publish, true);
        }
        void add(LauncherMediaController client) {
            if (clients.contains(client)) return;
            clients.add(client);
            if (latest != null) client.listener.onMediaChanged(latest);
            if (clients.size() == 1) engine.start();
        }
        void remove(LauncherMediaController client) {
            clients.remove(client);
            client.listener.onMediaChanged(Snapshot.empty(engine.cachedVolume));
            if (clients.isEmpty()) { engine.stop(); latest = null; }
        }
        void publish(Snapshot state) {
            latest = state;
            for (LauncherMediaController client : new ArrayList<>(clients)) {
                if (!client.clientActive) continue;
                try { client.listener.onMediaChanged(state); }
                catch (RuntimeException failure) { Log.w(TAG, "Media display callback failed", failure); }
            }
        }
    }

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
        /** True when the represented player exposes MediaSession rating or a notification action. */
        public final boolean likeAvailable;
        /** Nullable because many notification actions expose no selected-state signal. */
        @Nullable public final Boolean liked;
        /** Current STREAM_MUSIC volume, from 0 to 100. */
        public final int volumePercent;
        /** Exact AudioManager steps; -1 means unavailable, never inferred from a percentage. */
        public final int volumeSteps;
        public final int volumeMaximum;

        Snapshot(@NonNull String title, @NonNull String artist, @NonNull String album,
                 @NonNull String application, @Nullable Bitmap artwork, long durationMs,
                 long positionMs, boolean playing, boolean available, boolean likeAvailable,
                 @Nullable Boolean liked, int volumePercent) {
            this(title, artist, album, application, artwork, durationMs, positionMs, playing,
                    available, likeAvailable, liked, volumePercent, -1, -1);
        }

        Snapshot(@NonNull String title, @NonNull String artist, @NonNull String album,
                 @NonNull String application, @Nullable Bitmap artwork, long durationMs,
                 long positionMs, boolean playing, boolean available, boolean likeAvailable,
                 @Nullable Boolean liked, int volumePercent, int volumeSteps, int volumeMaximum) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.application = application;
            this.artwork = artwork;
            this.durationMs = Math.max(0L, durationMs);
            this.positionMs = MediaTimeline.clampPosition(positionMs, this.durationMs);
            this.playing = playing;
            this.available = available;
            this.likeAvailable = likeAvailable;
            this.liked = liked;
            this.volumePercent = Math.max(0, Math.min(100, volumePercent));
            this.volumeMaximum = volumeMaximum > 0 ? volumeMaximum : -1;
            this.volumeSteps = volumeSteps >= 0 && this.volumeMaximum > 0
                    ? Math.min(volumeSteps, this.volumeMaximum) : -1;
        }

        static Snapshot empty(int volumePercent) {
            return new Snapshot("Музыка не воспроизводится", "", "", "", null,
                    0L, 0L, false, false, false, null, volumePercent);
        }

        static Snapshot empty(VolumeState volume) {
            return new Snapshot("Музыка не воспроизводится", "", "", "", null,
                    0L, 0L, false, false, false, null,
                    volume.percent(), volume.steps, volume.maximum);
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
        final boolean likeAvailable;
        @Nullable final Boolean liked;
        final long positionTimestampWallMs;
        final long receivedElapsedMs;
        final long contentChangedElapsedMs;
        final long playbackChangedElapsedMs;
        final long artworkChangedElapsedMs;
        final float playbackSpeed;

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
            this(title, artist, album, packageName, application, artwork,
                    observedArtworkIdentity, durationMs, positionMs, playing, false, null,
                    positionTimestampWallMs, receivedElapsedMs, contentChangedElapsedMs,
                    playbackChangedElapsedMs, artworkChangedElapsedMs);
        }

        MediaState(@NonNull String title, @NonNull String artist, @NonNull String album,
                   @NonNull String packageName, @NonNull String application,
                   @Nullable Bitmap artwork, long observedArtworkIdentity,
                   long durationMs, long positionMs, boolean playing, boolean likeAvailable,
                   @Nullable Boolean liked, long positionTimestampWallMs, long receivedElapsedMs,
                   long contentChangedElapsedMs, long playbackChangedElapsedMs,
                   long artworkChangedElapsedMs) {
            this(title, artist, album, packageName, application, artwork, observedArtworkIdentity,
                    durationMs, positionMs, playing, likeAvailable, liked, positionTimestampWallMs,
                    receivedElapsedMs, contentChangedElapsedMs, playbackChangedElapsedMs,
                    artworkChangedElapsedMs, 1f);
        }

        MediaState(@NonNull String title, @NonNull String artist, @NonNull String album,
                   @NonNull String packageName, @NonNull String application,
                   @Nullable Bitmap artwork, long observedArtworkIdentity,
                   long durationMs, long positionMs, boolean playing, boolean likeAvailable,
                   @Nullable Boolean liked, long positionTimestampWallMs, long receivedElapsedMs,
                   long contentChangedElapsedMs, long playbackChangedElapsedMs,
                   long artworkChangedElapsedMs, float playbackSpeed) {
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
            this.likeAvailable = likeAvailable;
            this.liked = liked;
            this.positionTimestampWallMs = positionTimestampWallMs;
            this.receivedElapsedMs = receivedElapsedMs;
            this.contentChangedElapsedMs = contentChangedElapsedMs;
            this.playbackChangedElapsedMs = playbackChangedElapsedMs;
            this.artworkChangedElapsedMs = artworkChangedElapsedMs;
            this.playbackSpeed = playbackSpeed;
        }

        long currentPosition(long nowWallMs) {
            return MediaTimeline.position(positionMs,
                    positionTimestampWallMs > 0L ? Math.max(0L, nowWallMs - positionTimestampWallMs) : 0L,
                    playbackSpeed, playing, durationMs);
        }
    }

    private final Context context;
    private final Preferences preferences;
    private final MediaSessionManager manager;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    @Nullable private final SharedPlayback shared;
    private boolean clientActive;
    private VolumeState cachedVolume = new VolumeState(-1, -1);
    private boolean volumeReadInFlight;
    private boolean volumeWriteInFlight;
    private int pendingVolumePercent = -1;
    private final Runnable volumeVerify = () -> requestVolume(true);
    private long volumeReadAt;
    private int volumeGeneration;
    @Nullable private MediaDisplayNotification displayNotification;
    @Nullable private Bitmap notificationArtwork;
    private long artworkNotificationGeneration;
    private boolean artworkReadInFlight;
    private long notificationProbeGeneration;
    private int sessionRevision;
    @Nullable private MediaMetadata cachedMetadata;
    @Nullable private PlaybackState cachedPlayback;
    private final java.util.Map<String, String> applicationLabels = new java.util.HashMap<>();
    private boolean sessionReadInFlight;
    private boolean sessionReadPending;
    private int sessionReadGeneration;
    private final java.util.Map<android.media.session.MediaSession.Token, PlaybackState>
            queriedPlayback = new java.util.HashMap<>();
    @Nullable private MediaController current;
    private final java.util.LinkedHashSet<android.media.session.MediaSession.Token> deadTokens = new java.util.LinkedHashSet<>();
    @Nullable private MediaState sessionState;
    @Nullable private MediaState broadcastState;
    /**
     * True after the current MediaSession and mHUD stream have described the same player.
     * Kept across track transitions so a lagging OEM session cannot pull the panel backwards.
     */
    private boolean sessionBroadcastCorrelated;
    private volatile boolean started;
    private boolean receiverRegistered;
    private volatile boolean sessionsListenerRegistered;
    private final java.util.concurrent.atomic.AtomicBoolean sessionListenerUpdateQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean callbackUpdateQueued = new java.util.concurrent.atomic.AtomicBoolean();
    @Nullable private volatile SessionCallbackBinding desiredCallbackBinding, registeredCallbackBinding;
    private static final class SessionCallbackBinding {
        final MediaController controller;
        final MediaController.Callback callback;
        SessionCallbackBinding(MediaController controller, MediaController.Callback callback) {
            this.controller = controller; this.callback = callback;
        }
    }
    private boolean cacheReadInFlight;
    private boolean cacheReloadPending;
    private int cacheLoadGeneration;
    private long lastSessionRefreshElapsedMs;
    private long lastSeekDispatchElapsedMs;
    private long pendingSeekPositionMs = -1L;
    private boolean seekDispatchScheduled;
    private boolean seekFailureToastShown;
    private boolean sessionAccessGrantAttempted;
    private boolean sessionQueryInFlight;
    private boolean sessionQueryPending;
    private int sessionQueryGeneration;
    @NonNull private List<MediaController> seekGestureControllers = Collections.emptyList();
    @NonNull private String seekGesturePackage = "";
    @NonNull private String visiblePackage = "";
    @NonNull private String visibleTitle = "";
    @NonNull private String visibleArtist = "";
    @NonNull private String pendingLikePackage = "";
    @Nullable private Boolean pendingLikeTarget;
    private long pendingLikeStartedElapsed;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!started) return;
            MediaNotificationListener.reconcileMediaNotifications();
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

    private final MediaNotificationListener.MediaLikeObserver mediaLikeObserver = () ->
            mainHandler.post(() -> {
                if (!started) return;
                if (current != null) publishSession();
                else publish();
            });

    private final MediaNotificationListener.MediaDisplayObserver mediaDisplayObserver = () -> {
        if (Looper.myLooper() == Looper.getMainLooper()) receiveMediaNotification();
        else mainHandler.post(this::receiveMediaNotification);
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

    @Nullable private MediaController.Callback mediaCallback;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = controllers -> {
        if (started) refresh();
    };

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            if (!started) return;
            String action = intent == null ? null : intent.getAction();
            if ("android.media.VOLUME_CHANGED_ACTION".equals(action)) {
                // Re-read on the dedicated worker; this wakeup never blocks a track update.
                requestVolume(true);
                publish();
                return;
            }
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
        this(context, preferences, listener, false);
    }

    private LauncherMediaController(@NonNull Context context, @NonNull Preferences preferences,
                                    @NonNull Listener listener, boolean engine) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.preferences = preferences;
        this.listener = listener;
        shared = engine ? null : shared(this.context, preferences);
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post(this::start); return; }
        if (shared != null) {
            if (!clientActive) { clientActive = true; shared.add(this); }
            return;
        }
        if (started) return;
        started = true;
        MediaNotificationListener.addMediaLikeObserver(mediaLikeObserver);
        MediaNotificationListener.addMediaDisplayObserver(mediaDisplayObserver);
        receiveMediaNotification();
        requestVolume(true);
        registerBroadcastReceiver();
        loadCachedBroadcast();
        registerSessionsListener();
        ensureSessionAccessForTransportControls();
        refresh();
    }

    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post(this::stop); return; }
        if (shared != null) {
            if (clientActive) { clientActive = false; shared.remove(this); }
            return;
        }
        if (!started) return;
        started = false;
        MediaNotificationListener.removeMediaLikeObserver(mediaLikeObserver);
        MediaNotificationListener.removeMediaDisplayObserver(mediaDisplayObserver);
        displayNotification = null;
        notificationArtwork = null;
        artworkNotificationGeneration = 0L;
        volumeGeneration++;
        notificationProbeGeneration++;
        sessionReadGeneration++;
        sessionReadInFlight = false;
        sessionReadPending = false;
        mainHandler.removeCallbacks(ticker);
        mainHandler.removeCallbacks(volumeVerify);
        pendingVolumePercent = -1;
        mainHandler.removeCallbacks(commandReconcile);
        mainHandler.removeCallbacks(broadcastExpiry);
        mainHandler.removeCallbacks(pendingSeekDispatch);
        pendingSeekPositionMs = -1L;
        seekDispatchScheduled = false;
        sessionQueryPending = false;
        sessionQueryInFlight = false;
        sessionQueryGeneration++;
        clearSeekGesture();
        lastSessionRefreshElapsedMs = 0L;
        registerSessionsListener();
        unregisterBroadcastReceiver();
        replace(null);
        sessionState = null;
        clearVisibleMedia();
        // Keep the disk-backed state: the exported receiver may update it while HOME is closed.
        invalidateCacheRead();
        // Clear ImageView references before the owned broadcast bitmap is recycled on the next
        // main-loop turn. A stopped HOME must not pin or attempt to draw media artwork.
        listener.onMediaChanged(Snapshot.empty(cachedVolume));
        replaceBroadcastState(null);
    }

    public void refresh() {
        if (shared != null) { shared.engine.refresh(); return; }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::refresh);
            return;
        }
        if (!started) return;
        if (displayNotification != null && preferences.launcherMediaFixedPlayerEnabled.get()
                && !displayNotification.packageName.equals(preferences.launcherMediaFixedPlayerPackage.get()))
            receiveMediaNotification();
        if (manager == null) {
            publish();
            return;
        }
        if (sessionQueryInFlight) {
            sessionQueryPending = true;
            return;
        }
        sessionQueryInFlight = true;
        final int generation = ++sessionQueryGeneration;
        try {
            SESSION_QUERY_LANE.execute(() -> {
                List<MediaController> controllers = null;
                RuntimeException failure = null;
                try {
                    // Notification tokens precede the OEM list, which can retain old tokens.
                    controllers = new ArrayList<>(MediaNotificationListener.activeMediaNotificationControllers(context));
                    for (MediaController candidate : manager.getActiveSessions(listenerComponent())) {
                        boolean duplicate = false;
                        for (MediaController existing : controllers) {
                            if (sameSession(existing, candidate)) { duplicate = true; break; }
                        }
                        if (!duplicate) controllers.add(candidate);
                    }
                } catch (RuntimeException error) {
                    failure = error;
                }
                List<MediaController> result = controllers;
                RuntimeException queryFailure = failure;
                java.util.Map<android.media.session.MediaSession.Token, PlaybackState> playback = new java.util.HashMap<>();
                if (result != null) for (MediaController candidate : result) {
                    try { playback.put(candidate.getSessionToken(), candidate.getPlaybackState()); }
                    catch (RuntimeException ignored) { }
                }
                mainHandler.post(() -> {
                    if (generation != sessionQueryGeneration) return;
                    queriedPlayback.clear(); queriedPlayback.putAll(playback);
                    completeSessionQuery(generation, result,
                            result != null && !result.isEmpty() ? null : queryFailure);
                });
            });
        } catch (RejectedExecutionException saturated) {
            sessionQueryInFlight = false;
            sessionQueryPending = false;
            // Keep the callback-tracked controller that is already driving the visible media
            // card. Queue pressure is temporary; dropping this known-good session would force
            // the next steering command through the expensive global fallback precisely while
            // the system is busy.
            publish();
        }
    }

    private void completeSessionQuery(int generation,
                                      @Nullable List<MediaController> controllers,
                                      @Nullable RuntimeException failure) {
        if (generation != sessionQueryGeneration) return;
        sessionQueryInFlight = false;
        if (!started) return;
        if (failure == null) {
            try {
                select(controllers == null ? Collections.emptyList() : controllers);
            } catch (RuntimeException staleSession) {
                // A malformed entry in the fresh list must not evict the already selected player.
                publish();
            }
        } else {
            // An OEM Binder timeout/failure is transient. The existing controller is still the
            // lowest-latency command route and its callbacks will invalidate it if truly dead.
            publish();
        }
        if (sessionQueryPending) {
            sessionQueryPending = false;
            refresh();
        }
    }

    public void playPause() {
        if (shared != null) { shared.engine.playPause(); return; }
        MediaAutoResumeController.onManualTransportControl(context);
        String target = commandTargetPackage();
        if (target.isEmpty()) return;
        if (!dispatchCurrentPlayPause(target)) {
            MediaResumeCommand.playPause(context, target);
        }
        scheduleCommandReconcile();
    }

    /** All media sliders share one latest-value write and one off-MAIN stream read. */
    public void setVolumePercent(int percent) {
        if (shared != null) { shared.engine.setVolumePercent(percent); return; }
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post(() -> setVolumePercent(percent)); return; }
        pendingVolumePercent = Math.max(0, Math.min(100, percent));
        drainVolumeWrite();
    }

    private void drainVolumeWrite() {
        if (volumeWriteInFlight || pendingVolumePercent < 0 || audioManager == null) return;
        int percent = pendingVolumePercent; pendingVolumePercent = -1;
        volumeWriteInFlight = true; ++volumeGeneration;
        try { VOLUME_QUERY_LANE.execute(() -> {
            try {
                int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (maximum > 0) {
                    int step = dezz.status.widget.launcher.media.MediaVolumeMath.stepForPercent(percent, maximum);
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
                }
            } catch (RuntimeException unavailable) { }
            mainHandler.post(() -> {
                volumeWriteInFlight = false;
                if (pendingVolumePercent >= 0) { drainVolumeWrite(); return; }
                requestVolume(true);
                mainHandler.removeCallbacks(volumeVerify);
                if (started) {
                    mainHandler.postDelayed(volumeVerify, 180L);
                    mainHandler.postDelayed(volumeVerify, 650L);
                }
            });
        }); } catch (RejectedExecutionException busy) { volumeWriteInFlight = false; }
    }

    public void previous() {
        if (shared != null) { shared.engine.previous(); return; }
        MediaAutoResumeController.onManualTransportControl(context);
        String target = commandTargetPackage();
        if (target.isEmpty()) return;
        if (!dispatchCurrentSkip(target, false)) {
            MediaResumeCommand.previous(context, target);
        }
        scheduleCommandReconcile();
    }

    public void next() {
        if (shared != null) { shared.engine.next(); return; }
        MediaAutoResumeController.onManualTransportControl(context);
        String target = commandTargetPackage();
        if (target.isEmpty()) return;
        if (!dispatchCurrentSkip(target, true)) {
            MediaResumeCommand.next(context, target);
        }
        scheduleCommandReconcile();
    }

    /**
     * The visible session is already selected and callback-tracked. Use it before the diagnostic
     * fallback scans every process and MediaSession; this keeps steering/HOME commands responsive
     * when the renderer is busy and also removes avoidable Binder traffic from the common path.
     */
    private boolean dispatchCurrentPlayPause(@NonNull String targetPackage) {
        MediaController controller = current;
        if (controller == null || !samePackage(targetPackage, controllerPackage(controller))) {
            return false;
        }
        try {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
            return true;
        } catch (RuntimeException staleSession) {
            return false;
        }
    }

    private boolean dispatchCurrentSkip(@NonNull String targetPackage, boolean nextTrack) {
        MediaController controller = current;
        if (controller == null || !samePackage(targetPackage, controllerPackage(controller))) {
            return false;
        }
        try {
            if (nextTrack) controller.getTransportControls().skipToNext();
            else controller.getTransportControls().skipToPrevious();
            return true;
        } catch (RuntimeException staleSession) {
            return false;
        }
    }

    /**
     * mSaver-compatible Like: heart rating on the represented MediaSession first, then the
     * captured Like action from that player's live media notification. No mSaver-private
     * broadcast is emitted.
     */
    public boolean like() {
        if (shared != null) return shared.engine.like();
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
                String commandPackage = controllerPackage(controller);
                if (commandPackage.isEmpty()) commandPackage = targetPackage;
                Boolean pendingTarget = pendingLikeFor(commandPackage);
                MediaMetadata metadata = controller.getMetadata();
                Rating rating = metadata == null ? null
                        : metadata.getRating(MediaMetadata.METADATA_KEY_RATING);
                Rating userRating = metadata == null ? null
                        : metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
                // Toggle the same authoritative state that HOME renders. In particular, when a
                // player publishes contradictory RATING and USER_RATING values, USER_RATING wins
                // for both the filled heart and the next command, so a visible Like always turns
                // into an Unlike on the next tap.
                Boolean displayedHeart = MediaLikeActionPolicy.displayHeart(
                        heartValue(rating), heartValue(userRating));
                if (displayedHeart == null) {
                    MediaNotificationListener.MediaNotificationLikeSnapshot notificationState =
                            MediaNotificationListener.latestMediaNotificationLike(commandPackage);
                    if (notificationState != null) displayedHeart = notificationState.active;
                }
                boolean target = MediaLikeActionPolicy.nextHeartTarget(
                        displayedHeart, pendingTarget);
                controller.getTransportControls().setRating(Rating.newHeartRating(target));
                setPendingLike(commandPackage, target);
                publish();
                recordLike("media_session", commandPackage);
                scheduleCommandReconcile();
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        MediaNotificationListener.MediaNotificationLikeSnapshot notificationLike =
                MediaNotificationListener.latestMediaNotificationLike(targetPackage);
        if (notificationLike != null) {
            String commandPackage = notificationLike.packageName.isEmpty()
                    ? targetPackage : notificationLike.packageName;
            Boolean authoritative = notificationLike.active;
            boolean target = MediaLikeActionPolicy.nextHeartTarget(
                    authoritative, pendingLikeFor(commandPackage));
            if (!MediaNotificationListener.sendMediaNotificationLike(commandPackage)) {
                recordLike("unavailable", commandPackage);
                return false;
            }
            setPendingLike(commandPackage, target);
            publish();
            recordLike("notification_action", commandPackage);
            scheduleCommandReconcile();
            return true;
        }
        recordLike("unavailable", targetPackage);
        return false;
    }

    @Nullable
    private Boolean pendingLikeFor(@NonNull String packageName) {
        if (pendingLikeTarget == null || !samePackage(packageName, pendingLikePackage)) return null;
        long elapsed = SystemClock.elapsedRealtime() - pendingLikeStartedElapsed;
        if (elapsed >= COMMAND_RECONCILE_FINAL_MS) {
            clearPendingLike();
            return null;
        }
        return pendingLikeTarget;
    }

    private void setPendingLike(@NonNull String packageName, boolean target) {
        pendingLikePackage = packageName;
        pendingLikeTarget = target;
        pendingLikeStartedElapsed = SystemClock.elapsedRealtime();
    }

    private void clearPendingLike() {
        pendingLikePackage = "";
        pendingLikeTarget = null;
        pendingLikeStartedElapsed = 0L;
    }

    private static void recordLike(@NonNull String route, @NonNull String packageName) {
        ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "MEDIA_LIKE",
                ActionRecorder.object("route", route, "package", packageName));
    }

    /** Coalesces live scrubbing while preserving the exact player represented on HOME. */
    public void seekTo(long positionMs) {
        if (shared != null) { shared.engine.seekTo(positionMs); return; }
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
        if (shared != null) { shared.engine.finishSeek(positionMs); return; }
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
        if (shared != null) return shared.engine.openTargetPlayer();
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
        if (manager == null || !sessionListenerUpdateQueued.compareAndSet(false, true)) return;
        try { SESSION_CALLBACK_LANE.execute(() -> {
            boolean requested = started;
            try {
                if (requested && !sessionsListenerRegistered) {
                    manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), mainHandler);
                    sessionsListenerRegistered = true;
                } else if (!requested && sessionsListenerRegistered) {
                    manager.removeOnActiveSessionsChangedListener(sessionsListener);
                    sessionsListenerRegistered = false;
                }
            } catch (RuntimeException unavailable) { }
            finally {
                sessionListenerUpdateQueued.set(false);
                if (requested != started) registerSessionsListener();
            }
        }); } catch (RejectedExecutionException busy) { sessionListenerUpdateQueued.set(false); }
    }

    private void registerBroadcastReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MEDIA_UPDATE);
        filter.addAction(ACTION_MEDIA_CLEAR);
        filter.addAction(ACTION_MEDIA_UPDATE_DEBUG);
        filter.addAction(ACTION_MEDIA_CLEAR_DEBUG);
        filter.addAction(MediaBroadcastRepository.ACTION_CACHE_UPDATED);
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
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
                if (deadTokens.contains(candidate.getSessionToken())) continue;
                PlaybackState playback;
                try {
                    playback = queriedPlayback.get(candidate.getSessionToken());
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
        MediaDisplayNotification live = displayNotification;
        MediaController notificationController = null;
        if (live != null && live.token != null && !deadTokens.contains(live.token)) {
            for (MediaController candidate : controllers) {
                if (live.token.equals(candidate.getSessionToken())) { notificationController = candidate; break; }
            }
        }
        if (notificationController != null) {
            selected = notificationController;
        } else if (targetPlaying != null || target != null) {
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
        if (!preferences.launcherMediaFixedPlayerEnabled.get() && displayNotification != null
                && !displayNotification.packageName.isEmpty()) return displayNotification.packageName;
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
        if (sameSession(previous, next)) return;
        current = next; sessionRevision++;
        cachedMetadata = null; cachedPlayback = null;
        sessionState = null;
        if (!sameControllerPackage(previous, next)) sessionBroadcastCorrelated = false;
        MediaController.Callback callback = next == null ? null : new MediaController.Callback() {
            private boolean active() { return started && current == next && mediaCallback == this; }
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                if (!active()) return;
                ++sessionRevision; cachedPlayback = state;
                queriedPlayback.put(next.getSessionToken(), state);
                acceptSession(next, cachedMetadata, cachedPlayback);
                if (cachedMetadata == null) publishSession();
            }
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                if (!active()) return;
                ++sessionRevision; cachedMetadata = metadata;
                acceptSession(next, cachedMetadata, cachedPlayback);
                if (cachedPlayback == null) publishSession();
            }
            @Override public void onSessionDestroyed() {
                if (!active()) return;
                deadTokens.add(next.getSessionToken());
                while (deadTokens.size() > 8) deadTokens.remove(deadTokens.iterator().next());
                ++sessionRevision; replace(null); sessionState = null;
                receiveMediaNotification(); refresh();
            }
        };
        mediaCallback = callback;
        desiredCallbackBinding = next == null ? null : new SessionCallbackBinding(next, callback);
        updateSessionCallback();
    }

    /** Coalesces token churn: at most one registered callback and one pending replacement. */
    private void updateSessionCallback() {
        if (!callbackUpdateQueued.compareAndSet(false, true)) return;
        try { SESSION_CALLBACK_LANE.execute(() -> {
            SessionCallbackBinding desired = desiredCallbackBinding;
            SessionCallbackBinding old = registeredCallbackBinding;
            try {
                if (old != desired) {
                    if (old != null) old.controller.unregisterCallback(old.callback);
                    registeredCallbackBinding = null;
                    if (desired != null) {
                        desired.controller.registerCallback(desired.callback, mainHandler);
                        registeredCallbackBinding = desired;
                    }
                }
            } catch (RuntimeException unavailable) { }
            finally {
                callbackUpdateQueued.set(false);
                if (desiredCallbackBinding != desired) updateSessionCallback();
            }
        }); } catch (RejectedExecutionException busy) { callbackUpdateQueued.set(false); }
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
        if (!started) return;
        updateSessionCallback();
        registerSessionsListener();
        if (sessionReadInFlight) { sessionReadPending = true; return; }
        MediaController selected = current;
        if (selected == null) { sessionState = null; publish(); return; }
        sessionReadInFlight = true;
        int generation = sessionReadGeneration;
        int revision = sessionRevision;
        try {
            SESSION_READ_LANE.execute(() -> {
                MediaMetadata metadata = null;
                PlaybackState playback = null;
                try { metadata = selected.getMetadata(); playback = selected.getPlaybackState(); }
                catch (RuntimeException ignored) { }
                MediaMetadata resultMetadata = metadata;
                PlaybackState resultPlayback = playback;
                mainHandler.post(() -> {
                    if (generation != sessionReadGeneration) return;
                    sessionReadInFlight = false;
                    if (started && sameSession(current, selected) && revision == sessionRevision) {
                        cachedMetadata = resultMetadata; cachedPlayback = resultPlayback;
                        acceptSession(selected, resultMetadata, resultPlayback);
                    }
                    if (started && sessionReadPending) { sessionReadPending = false; publishSession(); }
                });
            });
        } catch (RejectedExecutionException busy) { sessionReadInFlight = false; publish(); }
    }

    private android.media.session.MediaSession.Token timelineOwner;
    private String timelineMediaId = "";
    private long rawSessionDuration, rawSessionPosition, timelineRevision;
    private String lastTimelineTrace = "";
    private long lastTimelineTraceElapsed;

    private void acceptSession(MediaController controller, MediaMetadata metadata, PlaybackState playback) {
        long observedElapsed = SystemClock.elapsedRealtime();
        lastSessionRefreshElapsedMs = observedElapsed;
        if (controller == null) {
            sessionState = null;
            timelineOwner = null;
            timelineMediaId = "";
            rawSessionDuration = rawSessionPosition = 0L;
            timelineRevision++;
            publish();
            return;
        }
        try {
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
            rawSessionDuration = duration;
            rawSessionPosition = playback == null ? -1L : playback.getPosition();
            long updateElapsed = playback == null ? 0L : playback.getLastPositionUpdateTime();
            long updateWall = System.currentTimeMillis();
            if (updateElapsed > 0L) {
                updateWall -= Math.max(0L, SystemClock.elapsedRealtime() - updateElapsed);
            }
            boolean playing = playback != null
                    && playback.getState() == PlaybackState.STATE_PLAYING;
            boolean likeAvailable = playback != null
                    && (playback.getActions() & PlaybackState.ACTION_SET_RATING) != 0L;
            Boolean liked = metadata == null ? null : MediaLikeActionPolicy.displayHeart(
                    heartValue(metadata.getRating(MediaMetadata.METADATA_KEY_RATING)),
                    heartValue(metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING)));
            long receivedElapsed = observedElapsed;
            MediaState previous = sessionState;
            String mediaId = metadata == null ? "" : cleanText(
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            boolean sameTimeline = previous != null && MediaTimelineIdentity.mayRetainDuration(
                    controller.getSessionToken().equals(timelineOwner), timelineMediaId, mediaId,
                    previous.title, previous.artist, title, artist);
            if (duration <= 0L && sameTimeline) duration = previous.durationMs;
            if (sameTimeline) {
                if (title.isEmpty()) title = previous.title;
                if (artist.isEmpty()) artist = previous.artist;
            } else timelineRevision++;
            timelineOwner = controller.getSessionToken();
            timelineMediaId = sameTimeline && mediaId.isEmpty() ? timelineMediaId : mediaId;
            boolean contentChanged = previous == null || !MediaStateFreshness.sameContent(
                    previous.packageName, previous.title, previous.artist, previous.album,
                    previous.durationMs, packageName, title, artist, album, duration);
            boolean trackChanged = previous == null || !MediaStateFreshness.sameTrack(
                    previous.packageName, previous.title, previous.artist, previous.album,
                    packageName, title, artist, album);
            boolean playbackChanged = previous == null || previous.playing != playing;
            long incomingArtworkIdentity = artworkIdentity(metadataArtwork);
            // Equal album artwork is valid on adjacent songs; notification ownership guards
            // stale covers instead of rejecting an image just because its pixels stayed equal.
            boolean displayArtwork = metadataArtwork != null && !metadataArtwork.isRecycled();
            Bitmap artwork = displayArtwork ? metadataArtwork : null;
            boolean artworkChanged = previous == null || MediaStateFreshness.artworkChanged(
                    contentChanged, previous.artworkIdentity, incomingArtworkIdentity)
                    || (previous.artwork != null) != displayArtwork;
            sessionState = new MediaState(title, artist, album, packageName,
                    applicationLabel(packageName), artwork, incomingArtworkIdentity,
                    duration, position, playing, likeAvailable, liked,
                    updateWall, receivedElapsed,
                    MediaStateFreshness.changedAt(contentChanged, receivedElapsed,
                            previous == null ? 0L : previous.contentChangedElapsedMs),
                    MediaStateFreshness.changedAt(playbackChanged, receivedElapsed,
                            previous == null ? 0L : previous.playbackChangedElapsedMs),
                    MediaStateFreshness.changedAt(artworkChanged, receivedElapsed,
                            previous == null ? 0L : previous.artworkChangedElapsedMs),
                    playback == null ? 1f : playback.getPlaybackSpeed());
        } catch (RuntimeException ignored) {
            // A dead or malformed vendor session must not take the shared launcher/widget process
            // down. Drop only this source; the durable mHUD broadcast can still drive the panel.
            if (current == controller) sessionState = null;
        }
        publish();
    }

    private void publish() {
        expireBroadcastIfNeeded();
        requestVolume(false);
        VolumeState volume = cachedVolume;
        MediaState session = sessionState;
        MediaState broadcast = broadcastState;
        if (publishLiveNotification(session, volume)) return;
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
                         @NonNull MediaState timeline, VolumeState volume) {
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
        LikeUiState likeState = resolveLikeUiState(packageName);
        MediaPlaybackHistoryStore.record(context, packageName, playback.playing);
        listener.onMediaChanged(new Snapshot(title.isEmpty() ? "Неизвестный трек" : title,
                artist, album, application, artworkBitmap, duration, position, playback.playing, true,
                likeState.available, likeState.active,
                volume.percent(), volume.steps, volume.maximum));
        scheduleTicker(playback.playing);
    }

    private void clearVisibleMedia() {
        visiblePackage = "";
        visibleTitle = "";
        visibleArtist = "";
        clearPendingLike();
    }

    @Nullable
    private static Boolean heartValue(@Nullable Rating rating) {
        if (rating == null || rating.getRatingStyle() != Rating.RATING_HEART) return null;
        return rating.hasHeart();
    }

    @NonNull
    private LikeUiState resolveLikeUiState(@NonNull String packageName) {
        boolean available = false;
        Boolean authoritative = null;
        MediaState session = sessionState;
        if (session != null && samePackage(packageName, session.packageName)) {
            available = session.likeAvailable;
            authoritative = session.liked;
        }
        MediaNotificationListener.MediaNotificationLikeSnapshot notification = packageName.isEmpty()
                ? null : MediaNotificationListener.latestMediaNotificationLike(packageName);
        if (notification != null) {
            available = true;
            if (authoritative == null) authoritative = notification.active;
        }
        if (pendingLikeTarget != null && samePackage(packageName, pendingLikePackage)) {
            long elapsed = SystemClock.elapsedRealtime() - pendingLikeStartedElapsed;
            if (MediaLikeActionPolicy.keepPending(pendingLikeTarget, authoritative, elapsed,
                    COMMAND_RECONCILE_SETTLED_MS, COMMAND_RECONCILE_FINAL_MS)) {
                return new LikeUiState(true, pendingLikeTarget);
            }
            clearPendingLike();
        }
        return new LikeUiState(available, authoritative);
    }

    private static final class LikeUiState {
        final boolean available;
        @Nullable final Boolean active;

        LikeUiState(boolean available, @Nullable Boolean active) {
            this.available = available;
            this.active = active;
        }
    }

    private void scheduleTicker(boolean playing) {
        mainHandler.removeCallbacks(ticker);
        if (started) {
            mainHandler.postDelayed(ticker, playing ? UI_TICK_MS : MEDIA_NOTIFICATION_REFRESH_MS);
        }
    }

    private static final class VolumeState {
        final int steps, maximum;
        VolumeState(int steps, int maximum) { this.steps = steps; this.maximum = maximum; }
        int percent() { return steps < 0 || maximum <= 0 ? 0 : Math.round(steps * 100f / maximum); }
    }

    private VolumeState readVolume() {
        if (audioManager == null) return new VolumeState(-1, -1);
        try {
            int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int steps = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            return new VolumeState(steps, maximum);
        } catch (RuntimeException ignored) {
            return new VolumeState(-1, -1);
        }
    }

    private void requestVolume(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!started || volumeReadInFlight || volumeWriteInFlight || (!force && now - volumeReadAt < UI_TICK_MS)) return;
        volumeReadInFlight = true;
        volumeReadAt = now;
        int generation = volumeGeneration;
        try {
            VOLUME_QUERY_LANE.execute(() -> {
                VolumeState next = readVolume();
                mainHandler.post(() -> {
                    volumeReadInFlight = false;
                    if (!started || generation != volumeGeneration) return;
                    if (next.steps == cachedVolume.steps && next.maximum == cachedVolume.maximum) return;
                    cachedVolume = next;
                    publish();
                });
            });
        } catch (RejectedExecutionException busy) { volumeReadInFlight = false; }
    }

    private void receiveMediaNotification() {
        if (!started) return;
        boolean fixed = preferences.launcherMediaFixedPlayerEnabled.get();
        String target = fixed ? preferences.launcherMediaFixedPlayerPackage.get() : null;
        MediaDisplayNotification next = MediaNotificationListener.latestMediaDisplay(target, deadTokens);
        long probe = ++notificationProbeGeneration;
        if (next == null) { adoptNotification(null); return; }
        if (next == displayNotification) return;
        boolean sameToken = displayNotification != null && next.token != null
                && next.token.equals(displayNotification.token);
        if (fixed || displayNotification == null || sameToken
                || "ru.yandex.music".equals(next.packageName)
                || sessionState == null || !sessionState.playing) {
            adoptNotification(next); return;
        }
        // A paused player's notification must not steal the display from the active player.
        // Probe the candidate's exact token off MAIN, without waiting for the OEM session list.
        MediaDisplayNotification request = next;
        try { SESSION_READ_LANE.execute(() -> {
            PlaybackState state = null;
            try { if (request.token != null) state = new MediaController(context, request.token).getPlaybackState(); }
            catch (RuntimeException ignored) {}
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            mainHandler.post(() -> {
                if (!started || probe != notificationProbeGeneration) return;
                if (playing) adoptNotification(request);
                else if (displayNotification != null) {
                    MediaDisplayNotification retained = MediaNotificationListener.latestMediaDisplay(displayNotification.packageName, deadTokens);
                    adoptNotification(retained != null ? retained : request);
                }
            });
        }); } catch (RejectedExecutionException busy) { }
    }

    private void adoptNotification(@Nullable MediaDisplayNotification next) {
        if (next == displayNotification) return;
        MediaDisplayNotification previous = displayNotification;
        displayNotification = next;
        boolean sameTrack = next != null && previous != null
                && MediaStateFreshness.sameContent(previous.packageName, previous.title,
                previous.artist, previous.album, 0L, next.packageName, next.title, next.artist, next.album, 0L);
        if (!sameTrack) notificationArtwork = null;
        if (next != null && next.artwork != null && !next.artwork.isRecycled()) notificationArtwork = next.artwork;
        if (next != null && next.token != null && !deadTokens.contains(next.token)
                && (current == null || !next.token.equals(current.getSessionToken()))) {
            try { replace(new MediaController(context, next.token)); }
            catch (RuntimeException stale) {}
        }
        publish();
        publishSession();
        requestNotificationArtwork();
        if (next == null) refresh();
    }

    private void requestNotificationArtwork() {
        MediaDisplayNotification request = displayNotification;
        if (!started || artworkReadInFlight || request == null || request.artwork != null
                || request.artworkIcon == null || request.generation == artworkNotificationGeneration) return;
        artworkReadInFlight = true;
        artworkNotificationGeneration = request.generation;
        try {
            ARTWORK_QUERY_LANE.execute(() -> {
                Bitmap decoded = null;
                try {
                    Drawable drawable = request.artworkIcon.loadDrawable(context);
                    if (drawable != null) {
                        int width = Math.max(1, drawable.getIntrinsicWidth());
                        int height = Math.max(1, drawable.getIntrinsicHeight());
                        float scale = Math.min(1f, MAX_ARTWORK_EDGE / (float) Math.max(width, height));
                        decoded = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
                        drawable.setBounds(0, 0, decoded.getWidth(), decoded.getHeight());
                        drawable.draw(new Canvas(decoded));
                    }
                } catch (RuntimeException | OutOfMemoryError ignored) {}
                Bitmap result = decoded;
                mainHandler.post(() -> {
                    artworkReadInFlight = false;
                    if (started && displayNotification == request) {
                        if (result != null) { notificationArtwork = result; publish(); }
                    } else if (result != null) result.recycle();
                    requestNotificationArtwork();
                });
            });
        } catch (RejectedExecutionException busy) {
            artworkReadInFlight = false; artworkNotificationGeneration = 0L;
        }
    }

    /** The live Android notification owns text/cover as in mSaver; MConfig is fallback only. */
    private boolean publishLiveNotification(MediaState session, VolumeState volume) {
        MediaDisplayNotification live = displayNotification;
        if (live == null || "com.android.bluetooth".equals(live.packageName)) return false;
        boolean samePlayer = session != null && samePackage(live.packageName, session.packageName)
                && (live.token == null || (current != null && live.token.equals(current.getSessionToken())));
        String title = live.title.isEmpty() && samePlayer ? session.title : live.title;
        if (title.isEmpty()) return false;
        String artist = live.artist.isEmpty() && samePlayer ? session.artist : live.artist;
        String evidence = MediaTimelineIdentity.notificationEvidence(samePlayer,
                live.mediaId, timelineMediaId, live.trackTitle, live.trackArtist,
                session == null ? "" : session.title, session == null ? "" : session.artist,
                live.sessionOwnsText);
        boolean sameTrack = MediaTimelineIdentity.accepts(evidence);
        long duration = sameTrack ? session.durationMs : 0L;
        long position = sameTrack ? session.currentPosition(System.currentTimeMillis()) : 0L;
        traceTimeline(evidence, duration, position, samePlayer);
        boolean playing = samePlayer && session.playing;
        Bitmap artwork = notificationArtwork;
        if (artwork == null && sameTrack) artwork = session.artwork;
        visiblePackage = live.packageName; visibleTitle = title; visibleArtist = artist;
        LikeUiState like = resolveLikeUiState(live.packageName);
        listener.onMediaChanged(new Snapshot(title, artist, live.album,
                applicationLabel(live.packageName), artwork, duration, position, playing, true,
                like.available, like.active, volume.percent(), volume.steps, volume.maximum));
        MediaPlaybackHistoryStore.record(context, live.packageName, playing);
        scheduleTicker(playing);
        return true;
    }

    /** No song names, media IDs, session tokens or notification text enter this journal. */
    private void traceTimeline(String evidence, long duration, long position, boolean sameOwner) {
        if (!dezz.status.widget.diagnostics.DiagnosticJournal.isEnabled()) return;
        long now = SystemClock.elapsedRealtime();
        String key = evidence + ":" + timelineRevision + ":" + rawSessionDuration + ":" + duration;
        if (key.equals(lastTimelineTrace) && now - lastTimelineTraceElapsed < 5_000L) return;
        lastTimelineTrace = key;
        lastTimelineTraceElapsed = now;
        dezz.status.widget.diagnostics.DiagnosticJournal.infoAsync("media-timeline",
                "source=notification+session, owner_match=" + sameOwner + ", evidence=" + evidence
                        + ", track_revision=" + timelineRevision + ", raw_duration_ms=" + rawSessionDuration
                        + ", raw_position_ms=" + rawSessionPosition + ", duration_ms=" + duration
                        + ", position_ms=" + position + ", duration_retained="
                        + (rawSessionDuration == 0L && duration > 0L)
                        + ", timeline_hidden=" + (duration == 0L && position == 0L));
    }

    @NonNull
    private static ThreadPoolExecutor createSessionQueryLane(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, 1, 10L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread worker = new Thread(task, name);
            worker.setDaemon(true);
            return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @NonNull
    private String applicationLabel(@NonNull String packageName) {
        if (packageName.isEmpty()) return "";
        String known = applicationLabels.get(packageName);
        if (known != null) return known;
        applicationLabels.put(packageName, packageName);
        try { ARTWORK_QUERY_LANE.execute(() -> {
            String label = packageName;
            try {
                label = context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
            } catch (Exception ignored) {}
            String value = label;
            mainHandler.post(() -> { applicationLabels.put(packageName, value); if (started) publish(); });
        }); } catch (RejectedExecutionException busy) {}
        return packageName;
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
