/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.AppProcessPolicy;
import dezz.status.widget.MediaNotificationListener;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Low-latency exact-session route for the physical steering-wheel media keys.
 *
 * <p>The accessibility callback is the earliest application-visible KX11 key boundary. Session
 * discovery happens ahead of time on a dedicated background looper; a key press therefore performs
 * only one direct {@link MediaController.TransportControls} Binder call. It never waits for HOME,
 * HUD, the instrument map or a synchronous active-session scan, and it never emits a global media
 * key which Android could route to the paired phone or another player.</p>
 */
public final class SteeringMediaKeyRouter {
    private static final String PREF_FIXED_ENABLED = "launcherMediaFixedPlayerEnabled";
    private static final String PREF_FIXED_PACKAGE = "launcherMediaFixedPlayerPackage";
    private static final String PREFS_SUFFIX = "_preferences";

    @NonNull private final Context context;
    @Nullable private final MediaSessionManager manager;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final HandlerThread resolverThread;
    @NonNull private final Handler resolver;
    @NonNull private final ComponentName listenerComponent;
    @Nullable private volatile Route route;
    private boolean started;
    private boolean listenerRegistered;
    private boolean refreshInFlight;
    private boolean refreshPending;
    private int refreshGeneration;

    @NonNull private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> scheduleSelection(controllers == null
                    ? Collections.emptyList() : controllers);

    public SteeringMediaKeyRouter(@NonNull Context source) {
        Context app = source.getApplicationContext();
        context = app == null ? source : app;
        manager = context.getSystemService(MediaSessionManager.class);
        listenerComponent = new ComponentName(context, MediaNotificationListener.class);
        resolverThread = new HandlerThread(
                "steering-media-route", Process.THREAD_PRIORITY_DEFAULT);
        resolverThread.start();
        resolver = new Handler(resolverThread.getLooper());
    }

    /** Starts one cached exact-session subscription; safe to call after every service reconnect. */
    public void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::start);
            return;
        }
        if (started) return;
        started = true;
        if (manager != null) {
            try {
                manager.addOnActiveSessionsChangedListener(
                        sessionsListener, listenerComponent, main);
                listenerRegistered = true;
            } catch (RuntimeException unavailable) {
                listenerRegistered = false;
            }
        }
        requestRefresh();
    }

    /** Releases the accessibility-owned subscription and its small resolver looper. */
    public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::close);
            return;
        }
        started = false;
        refreshGeneration++;
        refreshInFlight = false;
        refreshPending = false;
        if (manager != null && listenerRegistered) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) {}
        }
        listenerRegistered = false;
        replaceRoute(null, null);
        resolverThread.quitSafely();
    }

    /**
     * Dispatches the key synchronously to the already selected controller.
     *
     * @return {@code true} only after an exact MediaSession accepted this command.
     */
    public boolean dispatch(int keyCode, long inputUptimeMs) {
        Route current = route;
        if (!isSupportedKey(keyCode) || current == null) return false;
        long dispatchStarted = SystemClock.uptimeMillis();
        String outcome = "accepted";
        try {
            MediaController.TransportControls controls = current.controller.getTransportControls();
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    controls.skipToNext();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    controls.skipToPrevious();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    controls.play();
                    current.playbackState = PlaybackState.STATE_PLAYING;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    controls.pause();
                    current.playbackState = PlaybackState.STATE_PAUSED;
                    break;
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (current.playbackState == PlaybackState.STATE_PLAYING) {
                        controls.pause();
                        current.playbackState = PlaybackState.STATE_PAUSED;
                    } else {
                        controls.play();
                        current.playbackState = PlaybackState.STATE_PLAYING;
                    }
                    break;
                default:
                    return false;
            }
            trace("input=" + inputUptimeMs + ", dispatch=" + dispatchStarted
                    + ", completed=" + SystemClock.uptimeMillis()
                    + ", key=" + keyCode + ", package=" + current.packageName
                    + ", result=" + outcome);
            return true;
        } catch (RuntimeException staleSession) {
            outcome = staleSession.getClass().getSimpleName();
            trace("input=" + inputUptimeMs + ", dispatch=" + dispatchStarted
                    + ", completed=" + SystemClock.uptimeMillis()
                    + ", key=" + keyCode + ", package=" + current.packageName
                    + ", result=" + outcome);
            main.post(this::requestRefresh);
            return false;
        }
    }

    public static boolean isSupportedKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    private void requestRefresh() {
        if (!started || manager == null) return;
        if (refreshInFlight) {
            refreshPending = true;
            return;
        }
        refreshInFlight = true;
        final int generation = ++refreshGeneration;
        resolver.post(() -> {
            List<MediaController> controllers;
            try {
                controllers = manager.getActiveSessions(listenerComponent);
                if (controllers == null) controllers = Collections.emptyList();
            } catch (RuntimeException unavailable) {
                controllers = Collections.emptyList();
            }
            List<MediaController> result = controllers;
            main.post(() -> completeRefresh(generation, result));
        });
    }

    private void completeRefresh(int generation, @NonNull List<MediaController> controllers) {
        if (generation != refreshGeneration) return;
        refreshInFlight = false;
        if (!started) return;
        scheduleSelection(controllers);
        if (refreshPending) {
            refreshPending = false;
            requestRefresh();
        }
    }

    private void scheduleSelection(@NonNull List<MediaController> controllers) {
        if (!started) return;
        final int generation = ++refreshGeneration;
        refreshInFlight = true;
        resolver.post(() -> {
            Selection selection = select(controllers);
            main.post(() -> {
                if (generation != refreshGeneration) return;
                refreshInFlight = false;
                if (!started) return;
                replaceRoute(selection.controller, selection.playbackState);
                if (refreshPending) {
                    refreshPending = false;
                    requestRefresh();
                }
            });
        });
    }

    @NonNull
    private Selection select(@NonNull List<MediaController> controllers) {
        String preferred = preferredPackage();
        boolean fixed = fixedPlayerEnabled();
        MediaController preferredAny = null;
        PlaybackState preferredAnyState = null;
        MediaController preferredPlaying = null;
        PlaybackState preferredPlayingState = null;
        MediaController first = null;
        PlaybackState firstState = null;
        MediaController firstPlaying = null;
        PlaybackState firstPlayingState = null;
        for (MediaController controller : controllers) {
            if (controller == null) continue;
            String packageName = packageName(controller);
            if (packageName.isEmpty() || context.getPackageName().equals(packageName)) continue;
            PlaybackState state;
            try { state = controller.getPlaybackState(); }
            catch (RuntimeException stale) { continue; }
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            if (first == null) {
                first = controller;
                firstState = state;
            }
            if (playing && firstPlaying == null) {
                firstPlaying = controller;
                firstPlayingState = state;
            }
            if (!preferred.isEmpty() && preferred.equals(packageName)) {
                if (preferredAny == null) {
                    preferredAny = controller;
                    preferredAnyState = state;
                }
                if (playing && preferredPlaying == null) {
                    preferredPlaying = controller;
                    preferredPlayingState = state;
                }
            }
        }
        if (preferredPlaying != null) return new Selection(preferredPlaying, preferredPlayingState);
        if (preferredAny != null) return new Selection(preferredAny, preferredAnyState);
        // A fixed package is an explicit safety boundary. If its session is absent, allow Android's
        // normal handling instead of sending the press to a different controller ourselves.
        if (fixed && !preferred.isEmpty()) return new Selection(null, null);
        if (firstPlaying != null) return new Selection(firstPlaying, firstPlayingState);
        return new Selection(first, firstState);
    }

    private void replaceRoute(@Nullable MediaController controller,
                              @Nullable PlaybackState state) {
        Route previous = route;
        if (previous != null && sameSession(previous.controller, controller)) {
            previous.playbackState = state == null
                    ? PlaybackState.STATE_NONE : state.getState();
            return;
        }
        route = null;
        if (previous != null) {
            try { previous.controller.unregisterCallback(previous.callback); }
            catch (RuntimeException ignored) {}
        }
        if (controller == null) {
            trace("session=none, selected=" + SystemClock.uptimeMillis());
            return;
        }
        Route next = new Route(controller, packageName(controller), state);
        try {
            controller.registerCallback(next.callback, main);
            route = next;
            trace("session=" + next.packageName + ", selected=" + SystemClock.uptimeMillis()
                    + ", playbackState=" + next.playbackState);
        } catch (RuntimeException stale) {
            trace("session=" + next.packageName + ", selection_failed="
                    + stale.getClass().getSimpleName());
        }
    }

    private boolean fixedPlayerEnabled() {
        return preferences().getBoolean(PREF_FIXED_ENABLED, false);
    }

    @NonNull
    private String preferredPackage() {
        SharedPreferences preferences = preferences();
        if (preferences.getBoolean(PREF_FIXED_ENABLED, false)) {
            String fixed = preferences.getString(PREF_FIXED_PACKAGE, "");
            if (fixed != null && !fixed.trim().isEmpty()) return fixed.trim();
        }
        return MediaPlaybackHistoryStore.read(context).packageName;
    }

    @NonNull
    private SharedPreferences preferences() {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(
                context.getPackageName() + PREFS_SUFFIX, AppProcessPolicy.preferenceMode());
    }

    @NonNull
    private static String packageName(@NonNull MediaController controller) {
        try {
            String value = controller.getPackageName();
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean sameSession(@NonNull MediaController left,
                                       @Nullable MediaController right) {
        if (right == null) return false;
        try { return left.getSessionToken().equals(right.getSessionToken()); }
        catch (RuntimeException ignored) { return false; }
    }

    /** File I/O is deliberately kept behind the already-sent transport command. */
    private void trace(@NonNull String message) {
        if (!resolver.post(() -> DiagnosticJournal.info("steering-media", message))) {
            android.util.Log.i("SteeringMedia", message);
        }
    }

    private final class Route {
        @NonNull final MediaController controller;
        @NonNull final String packageName;
        volatile int playbackState;
        @NonNull final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(@Nullable PlaybackState state) {
                if (route != Route.this) return;
                playbackState = state == null ? PlaybackState.STATE_NONE : state.getState();
                trace("session=" + packageName + ", playback_callback="
                        + SystemClock.uptimeMillis() + ", state=" + playbackState);
            }

            @Override public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                if (route != Route.this) return;
                trace("session=" + packageName + ", metadata_callback="
                        + SystemClock.uptimeMillis());
            }

            @Override public void onSessionDestroyed() {
                if (route != Route.this) return;
                trace("session=" + packageName + ", destroyed=" + SystemClock.uptimeMillis());
                route = null;
                requestRefresh();
            }
        };

        Route(@NonNull MediaController controller, @NonNull String packageName,
              @Nullable PlaybackState state) {
            this.controller = controller;
            this.packageName = packageName;
            playbackState = state == null ? PlaybackState.STATE_NONE : state.getState();
        }
    }

    private static final class Selection {
        @Nullable final MediaController controller;
        @Nullable final PlaybackState playbackState;

        Selection(@Nullable MediaController controller, @Nullable PlaybackState playbackState) {
            this.controller = controller;
            this.playbackState = playbackState;
        }
    }
}
