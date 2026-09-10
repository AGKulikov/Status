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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dezz.status.widget.AppProcessPolicy;
import dezz.status.widget.MediaNotificationListener;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Low-latency exact-session route for the physical steering-wheel media keys.
 *
 * <p>The accessibility callback is the earliest application-visible KX11 key boundary. Session
 * discovery happens ahead of time on a dedicated route looper; the callback immediately puts a
 * press into a separate bounded command channel which performs exactly one
 * {@link MediaController.TransportControls} Binder call. It never waits for HOME, HUD, the
 * instrument map, a synchronous active-session scan or diagnostic file I/O, and it never emits a
 * global media key which Android could route to the paired phone or another player.</p>
 */
public final class SteeringMediaKeyRouter {
    private static final String PREF_FIXED_ENABLED = "launcherMediaFixedPlayerEnabled";
    private static final String PREF_FIXED_PACKAGE = "launcherMediaFixedPlayerPackage";
    private static final String PREFS_SUFFIX = "_preferences";
    /** Never replay a command which waited behind a slow/dead player Binder. */
    private static final long MAX_COMMAND_QUEUE_AGE_MS = 750L;
    /** Physical presses are valuable, but an unbounded late burst is worse than stock fallback. */
    private static final int MAX_PENDING_COMMANDS = 4;
    private static final int MAX_PENDING_TRACES = 64;

    @NonNull private final Context context;
    @Nullable private final MediaSessionManager manager;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final HandlerThread resolverThread;
    @NonNull private final Handler resolver;
    @NonNull private final HandlerThread commandThread;
    @NonNull private final Handler commandHandler;
    @NonNull private final HandlerThread journalThread;
    @NonNull private final Handler journalHandler;
    @NonNull private final ComponentName listenerComponent;
    @Nullable private volatile Route route;
    private volatile boolean started;
    private boolean listenerRegistered;
    /* Resolver-owned; volatile only for the main-thread close barrier. */
    private volatile boolean refreshInFlight;
    private volatile boolean refreshPending;
    private volatile int refreshGeneration;
    private volatile int routeGeneration;
    @NonNull private final AtomicLong commandSequence = new AtomicLong();
    @NonNull private final Object commandLock = new Object();
    @NonNull private final ArrayDeque<Command> pendingCommands = new ArrayDeque<>();
    private boolean commandDrainPosted;
    @NonNull private final Object traceLock = new Object();
    @NonNull private final ArrayDeque<String> pendingTraces = new ArrayDeque<>();
    private boolean traceDrainPosted;
    private int droppedTraceCount;

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
        commandThread = new HandlerThread(
                "steering-media-command", Process.THREAD_PRIORITY_DISPLAY);
        commandThread.start();
        commandHandler = new Handler(commandThread.getLooper());
        journalThread = new HandlerThread(
                "steering-media-journal", Process.THREAD_PRIORITY_BACKGROUND);
        journalThread.start();
        journalHandler = new Handler(journalThread.getLooper());
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
                        sessionsListener, listenerComponent, resolver);
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
        routeGeneration++;
        refreshInFlight = false;
        refreshPending = false;
        if (manager != null && listenerRegistered) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (RuntimeException ignored) {}
        }
        listenerRegistered = false;
        clearPendingCommands("close");
        replaceRoute(null, null);
        resolverThread.quitSafely();
        commandThread.quitSafely();
        journalThread.quitSafely();
    }

    /**
     * Accepts the key into the dedicated bounded command channel for the selected controller.
     *
     * <p>No active-session scan, diagnostic write or unrelated application task shares this
     * channel. A blocked player can hold at most one in-flight Binder call and four fresh queued
     * presses; queued commands expire rather than playing back as a late burst.</p>
     *
     * @return {@code true} only when an exact cached MediaSession exists and this press was queued.
     */
    public boolean dispatch(int keyCode, long eventTimeMs, long downTimeMs,
                            long callbackEntryUptimeMs) {
        Route current = route;
        if (!started || !isSupportedKey(keyCode) || current == null) return false;
        Command command = new Command(commandSequence.incrementAndGet(), keyCode,
                eventTimeMs, downTimeMs, callbackEntryUptimeMs,
                SystemClock.uptimeMillis(), routeGeneration, current);
        if (!enqueueCommand(command)) {
            trace(command.describe("queue_rejected", 0L, SystemClock.uptimeMillis()));
            return false;
        }
        trace(command.describe("queued", 0L, SystemClock.uptimeMillis()));
        return true;
    }

    private boolean enqueueCommand(@NonNull Command queued) {
        boolean postDrain = false;
        synchronized (commandLock) {
            long now = SystemClock.uptimeMillis();
            while (!pendingCommands.isEmpty()
                    && now - pendingCommands.peekFirst().enqueuedAtMs
                    > MAX_COMMAND_QUEUE_AGE_MS) {
                Command expired = pendingCommands.removeFirst();
                trace(expired.describe("expired_before_enqueue", 0L, now));
            }
            if (pendingCommands.size() >= MAX_PENDING_COMMANDS) return false;
            pendingCommands.addLast(queued);
            if (!commandDrainPosted) {
                commandDrainPosted = true;
                postDrain = true;
            }
        }
        if (!postDrain || commandHandler.post(commandDrain)) return true;
        synchronized (commandLock) {
            pendingCommands.remove(queued);
            commandDrainPosted = !pendingCommands.isEmpty();
        }
        return false;
    }

    @NonNull private final Runnable commandDrain = new Runnable() {
        @Override public void run() {
            while (true) {
                Command next;
                synchronized (commandLock) {
                    next = pendingCommands.pollFirst();
                    if (next == null) {
                        commandDrainPosted = false;
                        return;
                    }
                }
                dispatchQueued(next);
            }
        }
    };

    private void dispatchQueued(@NonNull Command queued) {
        long dispatchStarted = SystemClock.uptimeMillis();
        if (!started || queued.routeGeneration != routeGeneration || route != queued.target) {
            trace(queued.describe("route_changed", dispatchStarted,
                    SystemClock.uptimeMillis()));
            return;
        }
        if (dispatchStarted - queued.enqueuedAtMs > MAX_COMMAND_QUEUE_AGE_MS) {
            trace(queued.describe("expired", dispatchStarted, SystemClock.uptimeMillis()));
            return;
        }
        String outcome = "accepted";
        try {
            MediaController.TransportControls controls =
                    queued.target.controller.getTransportControls();
            switch (queued.keyCode) {
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    controls.skipToNext();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    controls.skipToPrevious();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    controls.play();
                    queued.target.playbackState = PlaybackState.STATE_PLAYING;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    controls.pause();
                    queued.target.playbackState = PlaybackState.STATE_PAUSED;
                    break;
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (queued.target.playbackState == PlaybackState.STATE_PLAYING) {
                        controls.pause();
                        queued.target.playbackState = PlaybackState.STATE_PAUSED;
                    } else {
                        controls.play();
                        queued.target.playbackState = PlaybackState.STATE_PLAYING;
                    }
                    break;
                default:
                    outcome = "unsupported";
                    break;
            }
            queued.target.lastCommandSequence = queued.sequence;
            queued.target.lastCommandKey = queued.keyCode;
            trace(queued.describe(outcome, dispatchStarted, SystemClock.uptimeMillis()));
        } catch (RuntimeException staleSession) {
            outcome = staleSession.getClass().getSimpleName();
            trace(queued.describe(outcome, dispatchStarted, SystemClock.uptimeMillis()));
            resolver.post(() -> {
                if (route != queued.target) return;
                replaceRoute(null, null);
                requestRefresh();
            });
        }
    }

    private void clearPendingCommands(@NonNull String reason) {
        int cleared;
        synchronized (commandLock) {
            cleared = pendingCommands.size();
            pendingCommands.clear();
        }
        if (cleared > 0) trace("queue_cleared=" + cleared + ", reason=" + reason
                + ", at=" + SystemClock.uptimeMillis());
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
        if (Looper.myLooper() != resolver.getLooper()) {
            resolver.post(this::requestRefresh);
            return;
        }
        if (!started || manager == null) return;
        if (refreshInFlight) {
            refreshPending = true;
            return;
        }
        refreshInFlight = true;
        final int generation = ++refreshGeneration;
        List<MediaController> controllers;
        try {
            controllers = manager.getActiveSessions(listenerComponent);
            if (controllers == null) controllers = Collections.emptyList();
        } catch (RuntimeException unavailable) {
            controllers = Collections.emptyList();
        }
        completeRefresh(generation, controllers);
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
        if (Looper.myLooper() != resolver.getLooper()) {
            resolver.post(() -> scheduleSelection(controllers));
            return;
        }
        if (!started) return;
        final int generation = ++refreshGeneration;
        refreshInFlight = true;
        Selection selection = select(controllers);
        if (generation != refreshGeneration) return;
        refreshInFlight = false;
        if (!started) return;
        replaceRoute(selection.controller, selection.playbackState);
        if (refreshPending) {
            refreshPending = false;
            requestRefresh();
        }
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
        routeGeneration++;
        route = null;
        clearPendingCommands("session_generation");
        if (previous != null) {
            resolver.post(() -> {
                try { previous.controller.unregisterCallback(previous.callback); }
                catch (RuntimeException ignored) {}
            });
        }
        if (controller == null) {
            trace("session=none, selected=" + SystemClock.uptimeMillis());
            return;
        }
        final int generation = routeGeneration;
        Route next = new Route(controller, packageName(controller), state, generation);
        route = next;
        resolver.post(() -> registerRouteCallback(next));
    }

    private void registerRouteCallback(@NonNull Route next) {
        if (!started || route != next || routeGeneration != next.generation) return;
        try {
            next.controller.registerCallback(next.callback, resolver);
            trace("session=" + next.packageName + ", token=" + next.sessionId
                    + ", generation=" + next.generation
                    + ", selected=" + SystemClock.uptimeMillis()
                    + ", playbackState=" + next.playbackState);
        } catch (RuntimeException stale) {
            trace("session=" + next.packageName + ", token=" + next.sessionId
                    + ", selection_failed=" + stale.getClass().getSimpleName());
            resolver.post(() -> {
                if (route != next) return;
                replaceRoute(null, null);
                requestRefresh();
            });
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

    /** File I/O has its own bounded queue and can block neither route selection nor commands. */
    private void trace(@NonNull String message) {
        boolean postDrain = false;
        synchronized (traceLock) {
            if (pendingTraces.size() == MAX_PENDING_TRACES) {
                pendingTraces.removeFirst();
                droppedTraceCount++;
            }
            pendingTraces.addLast(message);
            if (!traceDrainPosted) {
                traceDrainPosted = true;
                postDrain = true;
            }
        }
        if (postDrain && !journalHandler.post(traceDrain)) {
            synchronized (traceLock) {
                pendingTraces.clear();
                traceDrainPosted = false;
            }
            android.util.Log.i("SteeringMedia", message);
        }
    }

    @NonNull private final Runnable traceDrain = new Runnable() {
        @Override public void run() {
            while (true) {
                String message;
                int dropped;
                synchronized (traceLock) {
                    message = pendingTraces.pollFirst();
                    if (message == null) {
                        traceDrainPosted = false;
                        return;
                    }
                    dropped = droppedTraceCount;
                    droppedTraceCount = 0;
                }
                if (dropped > 0) {
                    DiagnosticJournal.info("steering-media",
                            "diagnostic_queue_dropped=" + dropped);
                }
                DiagnosticJournal.info("steering-media", message);
            }
        }
    };

    private final class Route {
        @NonNull final MediaController controller;
        @NonNull final String packageName;
        @NonNull final String sessionId;
        final int generation;
        volatile int playbackState;
        volatile long lastCommandSequence;
        volatile int lastCommandKey;
        @NonNull final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(@Nullable PlaybackState state) {
                if (route != Route.this) return;
                playbackState = state == null ? PlaybackState.STATE_NONE : state.getState();
                trace("session=" + packageName + ", playback_callback="
                        + SystemClock.uptimeMillis() + ", state=" + playbackState
                        + ", after_sequence=" + lastCommandSequence
                        + ", after_key=" + lastCommandKey);
            }

            @Override public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                if (route != Route.this) return;
                trace("session=" + packageName + ", metadata_callback="
                        + SystemClock.uptimeMillis() + ", after_sequence="
                        + lastCommandSequence + ", after_key=" + lastCommandKey);
            }

            @Override public void onSessionDestroyed() {
                if (route != Route.this) return;
                trace("session=" + packageName + ", destroyed=" + SystemClock.uptimeMillis());
                replaceRoute(null, null);
                requestRefresh();
            }
        };

        Route(@NonNull MediaController controller, @NonNull String packageName,
              @Nullable PlaybackState state, int generation) {
            this.controller = controller;
            this.packageName = packageName;
            this.generation = generation;
            String token;
            try { token = Integer.toHexString(controller.getSessionToken().hashCode()); }
            catch (RuntimeException unavailable) { token = "unknown"; }
            sessionId = token;
            playbackState = state == null ? PlaybackState.STATE_NONE : state.getState();
        }
    }

    private static final class Command {
        final long sequence;
        final int keyCode;
        final long eventTimeMs;
        final long downTimeMs;
        final long callbackEntryUptimeMs;
        final long enqueuedAtMs;
        final int routeGeneration;
        @NonNull final Route target;

        Command(long sequence, int keyCode, long eventTimeMs, long downTimeMs,
                long callbackEntryUptimeMs, long enqueuedAtMs, int routeGeneration,
                @NonNull Route target) {
            this.sequence = sequence;
            this.keyCode = keyCode;
            this.eventTimeMs = eventTimeMs;
            this.downTimeMs = downTimeMs;
            this.callbackEntryUptimeMs = callbackEntryUptimeMs;
            this.enqueuedAtMs = enqueuedAtMs;
            this.routeGeneration = routeGeneration;
            this.target = target;
        }

        @NonNull String describe(@NonNull String result, long dispatchStartedMs,
                                 long completedMs) {
            return "sequence=" + sequence + ", event=" + eventTimeMs
                    + ", down=" + downTimeMs + ", callback=" + callbackEntryUptimeMs
                    + ", queued=" + enqueuedAtMs + ", dispatch=" + dispatchStartedMs
                    + ", completed=" + completedMs + ", key=" + keyCode
                    + ", package=" + target.packageName + ", token=" + target.sessionId
                    + ", generation=" + routeGeneration + ", result=" + result;
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
