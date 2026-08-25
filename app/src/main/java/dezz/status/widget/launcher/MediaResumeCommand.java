/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;
import dezz.status.widget.phone.PhoneConnectionJournal;

/**
 * Exact-player media command. It never sends a global key through {@code AudioManager}, because
 * that path may be owned by the paired phone instead of the Android player shown on HOME.
 */
final class MediaResumeCommand {
    private static final String YANDEX_MUSIC_PACKAGE = "ru.yandex.music";
    /** The Yandex receiver used by mSaver requires a real press rather than two adjacent frames. */
    private static final long YANDEX_PLAY_KEY_UP_DELAY_MS = 100L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    enum Result {
        ALREADY_PLAYING,
        SESSION_COMMAND,
        RECEIVER_COMMAND,
        RECEIVER_AND_BROWSER_BOOTSTRAP,
        BROWSER_BOOTSTRAP,
        WAITING_FOR_SESSION,
        DISPATCH_FAILED,
        NO_TARGET
    }

    /** Diagnostic result used by boot auto-resume without changing normal media controls. */
    static final class DispatchTrace {
        @NonNull final Result result;
        @NonNull final String detail;

        private DispatchTrace(@NonNull Result result, @NonNull String detail) {
            this.result = result;
            this.detail = detail;
        }
    }

    private MediaResumeCommand() {}

    @NonNull
    static Result play(@NonNull Context context, @NonNull String targetPackage) {
        return playWithTrace(context, targetPackage).result;
    }

    @NonNull
    static DispatchTrace playWithTrace(@NonNull Context context,
                                       @NonNull String targetPackage) {
        return playWithTrace(context, targetPackage, false);
    }

    /**
     * A boot retry may escalate beyond the exported receiver after the controller has allowed a
     * verification grace period. Normal HOME controls never use this cold-start route.
     */
    @NonNull
    static DispatchTrace playWithTrace(@NonNull Context context,
                                       @NonNull String targetPackage,
                                       boolean coldStartEscalation) {
        return playWithTrace(context, targetPackage, coldStartEscalation,
                false, false, false);
    }

    /**
     * Durable Yandex boot-recovery state is owned by {@link MediaAutoResumeController}. Passing it
     * here keeps every address exact. The first cold dispatch deliberately races the receiver and
     * MediaBrowser like mSaver; later dispatches use only the package session/browser. No path
     * opens an Activity or emits a global media key.
     */
    @NonNull
    static DispatchTrace playWithTrace(@NonNull Context context,
                                       @NonNull String targetPackage,
                                       boolean coldStartEscalation,
                                       boolean requestYandexBrowserBootstrap,
                                       boolean yandexBrowserBootstrapRequested,
                                       boolean yandexSessionPlayAttempted) {
        return sendWithTrace(context, targetPackage, Command.PLAY, coldStartEscalation,
                requestYandexBrowserBootstrap, yandexBrowserBootstrapRequested,
                yandexSessionPlayAttempted);
    }

    @NonNull
    static Result playPause(@NonNull Context context, @NonNull String targetPackage) {
        return sendWithTrace(context, targetPackage, Command.PLAY_PAUSE, false,
                false, false, false).result;
    }

    @NonNull
    static Result previous(@NonNull Context context, @NonNull String targetPackage) {
        return sendWithTrace(context, targetPackage, Command.PREVIOUS, false,
                false, false, false).result;
    }

    @NonNull
    static Result next(@NonNull Context context, @NonNull String targetPackage) {
        return sendWithTrace(context, targetPackage, Command.NEXT, false,
                false, false, false).result;
    }

    /** A timeline position has no safe media-button fallback, so require the exact session. */
    @NonNull
    static Result seekTo(@NonNull Context context, @NonNull String targetPackage,
                         long positionMs) {
        String target = targetPackage.trim();
        if (target.isEmpty()) return Result.NO_TARGET;
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions == null) return Result.NO_TARGET;
        try {
            ComponentName listener = new ComponentName(context,
                    MediaNotificationListener.class);
            List<MediaController> controllers = sessions.getActiveSessions(listener);
            if (controllers == null) controllers = Collections.emptyList();
            for (MediaController controller : controllers) {
                if (!target.equals(controller.getPackageName())) continue;
                controller.getTransportControls().seekTo(Math.max(0L, positionMs));
                return Result.SESSION_COMMAND;
            }
        } catch (RuntimeException ignored) {
            // Seeking a different/global session would be worse than leaving the position alone.
        }
        return Result.NO_TARGET;
    }

    /**
     * mSaver re-addresses the exact package session from both MediaBrowser success and failure
     * callbacks. This helper deliberately has no receiver/browser fallback, so the callback can
     * never recurse into another bootstrap.
     */
    @NonNull
    static DispatchTrace playExactSessionOnly(@NonNull Context context,
                                              @NonNull String targetPackage) {
        String target = targetPackage.trim();
        if (target.isEmpty()) return trace(Result.NO_TARGET, "target=empty");
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions == null) {
            return trace(Result.NO_TARGET, "route=exact_session_only, manager=unavailable");
        }
        try {
            ComponentName listener = new ComponentName(context,
                    MediaNotificationListener.class);
            List<MediaController> controllers = sessions.getActiveSessions(listener);
            if (controllers == null) controllers = Collections.emptyList();
            for (MediaController controller : controllers) {
                if (!target.equals(controller.getPackageName())) continue;
                PlaybackState state = controller.getPlaybackState();
                int playbackState = state == null ? -1 : state.getState();
                long actions = state == null ? 0L : state.getActions();
                if (playbackState == PlaybackState.STATE_PLAYING) {
                    return trace(Result.ALREADY_PLAYING,
                            "route=exact_session_only, playbackState=" + playbackState
                                    + ", actions=" + actions);
                }
                controller.getTransportControls().play();
                return trace(Result.SESSION_COMMAND,
                        "route=exact_session_only, playbackState=" + playbackState
                                + ", actions=" + actions);
            }
            return trace(Result.NO_TARGET,
                    "route=exact_session_only, activeSessions=" + controllers.size());
        } catch (RuntimeException failure) {
            return trace(Result.DISPATCH_FAILED,
                    "route=exact_session_only, error="
                            + failure.getClass().getSimpleName());
        }
    }

    private enum Command {
        PLAY,
        PLAY_PAUSE,
        PREVIOUS,
        NEXT
    }

    @NonNull
    private static DispatchTrace sendWithTrace(@NonNull Context context,
                                               @NonNull String targetPackage,
                                               @NonNull Command command,
                                               boolean coldStartEscalation,
                                               boolean requestYandexBrowserBootstrap,
                                               boolean yandexBrowserBootstrapRequested,
                                               boolean yandexSessionPlayAttempted) {
        String target = targetPackage.trim();
        if (target.isEmpty()) return trace(Result.NO_TARGET, "target=empty");
        String targetProcessState = processState(context, target);
        String sessionError = "none";
        String sessionInventory = "[]";
        int activeSessionCount = -1;
        int ignoredYandexSessionState = Integer.MIN_VALUE;
        long ignoredYandexSessionActions = 0L;
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions != null) {
            try {
                ComponentName listener = new ComponentName(context,
                        MediaNotificationListener.class);
                List<MediaController> controllers = sessions.getActiveSessions(listener);
                if (controllers == null) controllers = Collections.emptyList();
                activeSessionCount = controllers.size();
                StringBuilder inventory = new StringBuilder("[");
                int inventoryCount = 0;
                for (MediaController controller : controllers) {
                    if (inventoryCount >= 6) break;
                    PlaybackState itemState = controller.getPlaybackState();
                    if (inventoryCount++ > 0) inventory.append(';');
                    inventory.append(controller.getPackageName())
                            .append(':')
                            .append(itemState == null ? -1 : itemState.getState());
                }
                if (controllers.size() > inventoryCount) inventory.append(";...");
                sessionInventory = inventory.append(']').toString();
                for (MediaController controller : controllers) {
                    if (!target.equals(controller.getPackageName())) continue;
                    PlaybackState state = controller.getPlaybackState();
                    boolean playing = state != null
                            && state.getState() == PlaybackState.STATE_PLAYING;
                    int playbackState = state == null ? -1 : state.getState();
                    long actions = state == null ? 0L : state.getActions();
                    if (coldStartEscalation && YANDEX_MUSIC_PACKAGE.equals(target)
                            && command == Command.PLAY && !isUsablePlaySession(state)) {
                        // Yandex exposes a STATE_NONE token during process bootstrap. Road logs
                        // show that TransportControls.play() on that token is accepted locally but
                        // ignored indefinitely. Keep looking, then use the exact receiver/browser
                        // cold-start routes until a real PAUSED/STOPPED session exists.
                        ignoredYandexSessionState = playbackState;
                        ignoredYandexSessionActions = actions;
                        continue;
                    }
                    switch (command) {
                        case PLAY:
                            if (playing) {
                                return trace(Result.ALREADY_PLAYING,
                                        "route=session, activeSessions=" + activeSessionCount
                                                + ", sessions=" + sessionInventory
                                                + ", playbackState=" + playbackState
                                                + ", actions=" + actions);
                            }
                            controller.getTransportControls().play();
                            break;
                        case PLAY_PAUSE:
                            if (playing) controller.getTransportControls().pause();
                            else controller.getTransportControls().play();
                            break;
                        case PREVIOUS:
                            controller.getTransportControls().skipToPrevious();
                            break;
                        case NEXT:
                            controller.getTransportControls().skipToNext();
                            break;
                    }
                    if (YANDEX_MUSIC_PACKAGE.equals(target) && command == Command.PLAY) {
                        // An active exact-package token is stronger evidence than the OEM
                        // ActivityManager process inventory, which the road logs report as
                        // not_running even while Yandex exposes a usable MediaSession.
                        return trace(Result.SESSION_COMMAND,
                                "route=exact_session_play, process=" + targetProcessState
                                        + ", activeSessions=" + activeSessionCount
                                        + ", sessions=" + sessionInventory
                                        + ", playbackState=" + playbackState
                                        + ", actions=" + actions
                                        + ", repeated=" + yandexSessionPlayAttempted);
                    }
                    return trace(Result.SESSION_COMMAND,
                            "route=session, activeSessions=" + activeSessionCount
                                    + ", sessions=" + sessionInventory
                                    + ", playbackState=" + playbackState
                                    + ", actions=" + actions);
                }
            } catch (RuntimeException failure) {
                sessionError = failure.getClass().getSimpleName();
                // Explicit receiver fallback below also works without notification access.
            }
        } else {
            sessionError = "manager_unavailable";
        }

        boolean yandexColdPlay = coldStartEscalation
                && YANDEX_MUSIC_PACKAGE.equals(target) && command == Command.PLAY;
        String yandexBootstrap = "not_requested";
        if (yandexColdPlay && yandexBrowserBootstrapRequested) {
            // mSaver sends the receiver only once. While that first browser bind is alive, wait
            // for the exact MediaSession instead of flooding the same receiver every two seconds.
            if (requestYandexBrowserBootstrap) {
                yandexBootstrap = requestYandexBrowserIfUseful(context, target, command);
                if ("bootstrap_scheduled".equals(yandexBootstrap)) {
                    return trace(Result.BROWSER_BOOTSTRAP,
                            "route=exact_media_browser_retry, process="
                                    + targetProcessState
                                    + ", activeSessions=" + activeSessionCount
                                    + ", sessions=" + sessionInventory
                                    + ", sessionError=" + sessionError
                                    + ", browser=" + yandexBootstrap);
                }
            } else {
                yandexBootstrap = "already_requested";
            }
            return trace(Result.WAITING_FOR_SESSION,
                    "route=waiting_for_exact_session, process=" + targetProcessState
                            + ", activeSessions=" + activeSessionCount
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", browser=" + yandexBootstrap
                            + ", ignoredSessionState="
                            + ignoredSessionState(ignoredYandexSessionState)
                            + ", ignoredSessionActions=" + ignoredYandexSessionActions);
        }

        PackageManager packages = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(target);
        List<ResolveInfo> receivers;
        String receiverQueryError = "none";
        try {
            receivers = packages.queryBroadcastReceivers(query, 0);
        } catch (RuntimeException failure) {
            receiverQueryError = failure.getClass().getSimpleName();
            receivers = Collections.emptyList();
        }
        if (receivers == null) receivers = Collections.emptyList();
        int receiverCount = receivers.size();
        ComponentName known = knownReceiver(target);
        if (yandexColdPlay && known != null && isInstalled(packages, target)) {
            long keyUpDelayMs = keyUpDelayMillis(target, command);
            String dispatchError = sendKey(context, known, keyCodeWithoutSession(command),
                    keyUpDelayMs);
            if (!yandexBrowserBootstrapRequested && requestYandexBrowserBootstrap) {
                yandexBootstrap = requestYandexBrowserIfUseful(context, target, command);
            }
            return trace(receiverDispatchResult(dispatchError, yandexBootstrap),
                    "route=exact_receiver_browser_race, activeSessions="
                            + activeSessionCount
                            + ", process=" + targetProcessState
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", receiverCount=" + receiverCount
                            + ", receiverQueryError=" + receiverQueryError
                            + ", receiver=" + known.flattenToShortString()
                            + ", keyUpDelayMs=" + keyUpDelayMs
                            + ", browser=" + yandexBootstrap
                            + ", ignoredSessionState="
                            + ignoredSessionState(ignoredYandexSessionState)
                            + ", ignoredSessionActions=" + ignoredYandexSessionActions
                            + ", dispatchError=" + emptyAsNone(dispatchError));
        }
        for (ResolveInfo resolved : receivers) {
            if (resolved.activityInfo == null) continue;
            ComponentName receiver = new ComponentName(resolved.activityInfo.packageName,
                    resolved.activityInfo.name);
            long keyUpDelayMs = keyUpDelayMillis(target, command);
            String dispatchError = sendKey(context, receiver,
                    keyCodeWithoutSession(command), keyUpDelayMs);
            return trace(dispatchError.isEmpty()
                            ? Result.RECEIVER_COMMAND : Result.DISPATCH_FAILED,
                    "route=queried_receiver, activeSessions=" + activeSessionCount
                            + ", process=" + targetProcessState
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", receiverCount=" + receiverCount
                            + ", receiver=" + receiver.flattenToShortString()
                            + ", keyUpDelayMs=" + keyUpDelayMs
                            + ", browser=" + yandexBootstrap
                            + ", ignoredSessionState="
                            + ignoredSessionState(ignoredYandexSessionState)
                            + ", ignoredSessionActions=" + ignoredYandexSessionActions
                            + ", dispatchError=" + emptyAsNone(dispatchError));
        }

        if (known != null && isInstalled(packages, target)) {
            long keyUpDelayMs = keyUpDelayMillis(target, command);
            String dispatchError = sendKey(context, known, keyCodeWithoutSession(command),
                    keyUpDelayMs);
            return trace(dispatchError.isEmpty()
                            ? Result.RECEIVER_COMMAND : Result.DISPATCH_FAILED,
                    "route=known_receiver, activeSessions=" + activeSessionCount
                            + ", process=" + targetProcessState
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", receiverCount=" + receiverCount
                            + ", receiverQueryError=" + receiverQueryError
                            + ", receiver=" + known.flattenToShortString()
                            + ", keyUpDelayMs=" + keyUpDelayMs
                            + ", browser=" + yandexBootstrap
                            + ", ignoredSessionState="
                            + ignoredSessionState(ignoredYandexSessionState)
                            + ", ignoredSessionActions=" + ignoredYandexSessionActions
                            + ", dispatchError=" + emptyAsNone(dispatchError));
        }
        String browser = "not_requested".equals(yandexBootstrap)
                ? requestYandexBrowserIfUseful(context, target, command)
                : yandexBootstrap;
        if ("bootstrap_scheduled".equals(browser)) {
            return trace(Result.BROWSER_BOOTSTRAP,
                    "route=media_browser_bootstrap, activeSessions=" + activeSessionCount
                            + ", process=" + targetProcessState
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", receiverCount=" + receiverCount
                            + ", receiverQueryError=" + receiverQueryError
                            + ", browser=" + browser);
        }
        return trace(Result.NO_TARGET,
                "route=none, activeSessions=" + activeSessionCount
                        + ", process=" + targetProcessState
                        + ", sessions=" + sessionInventory
                        + ", sessionError=" + sessionError
                        + ", receiverCount=" + receiverCount
                        + ", receiverQueryError=" + receiverQueryError
                        + ", knownReceiver=" + (known != null)
                        + ", browser=" + browser);
    }

    /**
     * Without an active session PLAY_PAUSE is unsafe: a stale receiver can interpret it as pause
     * after process restoration. An explicit PLAY is idempotent and matches the user's intent.
     */
    private static int keyCodeWithoutSession(@NonNull Command command) {
        switch (command) {
            case PREVIOUS:
                return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case NEXT:
                return KeyEvent.KEYCODE_MEDIA_NEXT;
            case PLAY:
            case PLAY_PAUSE:
            default:
                return KeyEvent.KEYCODE_MEDIA_PLAY;
        }
    }

    private static boolean isUsablePlaySession(PlaybackState state) {
        if (state == null || state.getState() == PlaybackState.STATE_NONE) return false;
        if (state.getState() == PlaybackState.STATE_PLAYING) return true;
        long actions = state.getActions();
        return (actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0L;
    }

    @NonNull
    private static String ignoredSessionState(int state) {
        return state == Integer.MIN_VALUE ? "none" : Integer.toString(state);
    }

    @NonNull
    private static String sendKey(@NonNull Context context, @NonNull ComponentName receiver,
                                  int keyCode, long keyUpDelayMs) {
        long now = SystemClock.uptimeMillis();
        Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        keyCode, 0));
        try {
            context.sendBroadcast(down);
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            Context exactContext = app;
            Runnable keyUp = () -> {
                long releasedAt = SystemClock.uptimeMillis();
                Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON)
                        .setComponent(receiver)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, releasedAt,
                                KeyEvent.ACTION_UP, keyCode, 0));
                try {
                    exactContext.sendBroadcast(up);
                } catch (RuntimeException failure) {
                    PhoneConnectionJournal.append("media-auto-resume",
                            "trace event=receiver_key_up_failed, receiver="
                                    + receiver.flattenToShortString()
                                    + ", error=" + failure.getClass().getSimpleName());
                }
            };
            if (keyUpDelayMs > 0L) {
                if (!MAIN.postDelayed(keyUp, keyUpDelayMs)) return "key_up_schedule_rejected";
            } else {
                keyUp.run();
            }
            return "";
        } catch (RuntimeException failure) {
            return failure.getClass().getSimpleName();
        }
    }

    @NonNull
    private static DispatchTrace trace(@NonNull Result result, @NonNull String detail) {
        return new DispatchTrace(result, detail);
    }

    @NonNull
    private static Result receiverDispatchResult(@NonNull String dispatchError,
                                                 @NonNull String browser) {
        boolean receiverSent = dispatchError.isEmpty();
        boolean browserScheduled = "bootstrap_scheduled".equals(browser);
        if (receiverSent && browserScheduled) return Result.RECEIVER_AND_BROWSER_BOOTSTRAP;
        if (receiverSent) return Result.RECEIVER_COMMAND;
        if (browserScheduled) return Result.BROWSER_BOOTSTRAP;
        return Result.DISPATCH_FAILED;
    }

    @NonNull
    private static String emptyAsNone(@NonNull String value) {
        return value.isEmpty() ? "none" : value;
    }

    @NonNull
    private static String processState(@NonNull Context context,
                                       @NonNull String packageName) {
        ActivityManager activity = context.getSystemService(ActivityManager.class);
        if (activity == null) return "manager_unavailable";
        try {
            List<ActivityManager.RunningAppProcessInfo> processes =
                    activity.getRunningAppProcesses();
            if (processes == null) return "inventory_unavailable";
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process == null || process.pkgList == null) continue;
                for (String item : process.pkgList) {
                    if (packageName.equals(item)) return "running";
                }
            }
            return "not_running";
        } catch (RuntimeException failure) {
            return "query_" + failure.getClass().getSimpleName();
        }
    }

    private static ComponentName knownReceiver(@NonNull String packageName) {
        if (YANDEX_MUSIC_PACKAGE.equals(packageName)) {
            return new ComponentName(packageName,
                    "ru.yandex.music.common.service.player.DebugMediaButtonReceiver");
        }
        if ("com.spotify.music".equals(packageName)) {
            return new ComponentName(packageName,
                    "com.spotify.music.internal.receiver.MediaButtonReceiver");
        }
        return null;
    }

    private static boolean isInstalled(@NonNull PackageManager packages,
                                       @NonNull String packageName) {
        try {
            packages.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private static long keyUpDelayMillis(@NonNull String target,
                                         @NonNull Command command) {
        return YANDEX_MUSIC_PACKAGE.equals(target) && command == Command.PLAY
                ? YANDEX_PLAY_KEY_UP_DELAY_MS : 0L;
    }

    @NonNull
    private static String requestYandexBrowserIfUseful(@NonNull Context context,
                                                        @NonNull String target,
                                                        @NonNull Command command) {
        if (!YANDEX_MUSIC_PACKAGE.equals(target) || command != Command.PLAY) return "not_used";
        return YandexMusicBrowserStarter.requestPlay(context);
    }
}
