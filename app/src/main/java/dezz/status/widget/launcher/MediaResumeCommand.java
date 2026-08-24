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
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;

/**
 * Exact-player media command. It never sends a global key through {@code AudioManager}, because
 * that path may be owned by the paired phone instead of the Android player shown on HOME.
 */
final class MediaResumeCommand {
    private static final String YANDEX_MUSIC_PACKAGE = "ru.yandex.music";
    /** The Yandex receiver used by mSaver requires a real press rather than two adjacent frames. */
    private static final long YANDEX_PLAY_KEY_UP_DELAY_MS = 100L;

    enum Result {
        ALREADY_PLAYING,
        SESSION_COMMAND,
        RECEIVER_COMMAND,
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
     * here keeps each dispatch exclusive: a cooled-down browser bootstrap, an exact-session PLAY,
     * or a periodic exact receiver PLAY. No path opens an Activity or emits a global media key.
     */
    @NonNull
    static DispatchTrace playWithTrace(@NonNull Context context,
                                       @NonNull String targetPackage,
                                       boolean coldStartEscalation,
                                       boolean yandexBrowserBootstrapRequested,
                                       boolean yandexSessionPlayAttempted,
                                       boolean repeatYandexReceiver) {
        return sendWithTrace(context, targetPackage, Command.PLAY, coldStartEscalation,
                yandexBrowserBootstrapRequested, yandexSessionPlayAttempted,
                repeatYandexReceiver);
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
                                               boolean yandexBrowserBootstrapRequested,
                                               boolean yandexSessionPlayAttempted,
                                               boolean repeatYandexReceiver) {
        String target = targetPackage.trim();
        if (target.isEmpty()) return trace(Result.NO_TARGET, "target=empty");
        String targetProcessState = processState(context, target);
        String sessionError = "none";
        String sessionInventory = "[]";
        int activeSessionCount = -1;
        MediaController deferredYandexPlaySession = null;
        int deferredYandexPlaybackState = -1;
        long deferredYandexActions = 0L;
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
                    switch (command) {
                        case PLAY:
                            if (playing) {
                                return trace(Result.ALREADY_PLAYING,
                                        "route=session, activeSessions=" + activeSessionCount
                                                + ", sessions=" + sessionInventory
                                                + ", playbackState=" + playbackState
                                                + ", actions=" + actions);
                            }
                            if (YANDEX_MUSIC_PACKAGE.equals(target)) {
                                // Give Yandex's exported receiver the first configured-time chance.
                                // A Yandex token can survive after its process has died. STATE_NONE
                                // is therefore actionable only with positive process evidence.
                                deferredYandexPlaySession = controller;
                                deferredYandexPlaybackState = playbackState;
                                deferredYandexActions = actions;
                                if (coldStartEscalation
                                        && isUsablePlaySession(playbackState, actions,
                                        targetProcessState)) {
                                    controller.getTransportControls().play();
                                    return trace(Result.SESSION_COMMAND,
                                            "route=verified_exact_session_play, process="
                                                    + targetProcessState
                                                    + ", activeSessions=" + activeSessionCount
                                                    + ", sessions=" + sessionInventory
                                                    + ", playbackState=" + playbackState
                                                    + ", actions=" + actions
                                                    + ", repeated="
                                                    + yandexSessionPlayAttempted);
                                }
                                break;
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
                    if (deferredYandexPlaySession != null) break;
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

        // The first boot attempt always gives the exact exported receiver its configured-time
        // chance. A later attempt reaches here only after the controller observed no playback.
        // Bind Yandex's background MediaBrowser once to bootstrap its process/session, then poll
        // only for the exact session. Rebinding on every retry delayed recovery in the road logs.
        if (coldStartEscalation && YANDEX_MUSIC_PACKAGE.equals(target)
                && command == Command.PLAY && !repeatYandexReceiver) {
            String browser = yandexBrowserBootstrapRequested
                    ? "already_requested"
                    : requestYandexBrowserIfUseful(context, target, command);
            if ("bootstrap_scheduled".equals(browser)) {
                return trace(Result.BROWSER_BOOTSTRAP,
                        "route=verified_media_browser_bootstrap, process="
                                + targetProcessState
                                + ", activeSessions=" + activeSessionCount
                                + ", sessions=" + sessionInventory
                                + ", sessionError=" + sessionError
                                + ", deferredPlaybackState=" + deferredYandexPlaybackState
                                + ", deferredActions=" + deferredYandexActions
                                + ", browser=" + browser);
            }
            if (yandexBrowserBootstrapRequested) {
                return trace(Result.WAITING_FOR_SESSION,
                        "route=waiting_for_exact_session, process="
                                + targetProcessState
                                + ", activeSessions=" + activeSessionCount
                                + ", sessions=" + sessionInventory
                                + ", sessionError=" + sessionError
                                + ", deferredPlaybackState="
                                + deferredYandexPlaybackState
                                + ", deferredActions=" + deferredYandexActions
                                + ", browser=" + browser);
            }
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
        for (ResolveInfo resolved : receivers) {
            if (resolved.activityInfo == null) continue;
            ComponentName receiver = new ComponentName(resolved.activityInfo.packageName,
                    resolved.activityInfo.name);
            long keyUpDelayMs = keyUpDelayMillis(target, command);
            String dispatchError = sendKey(context, receiver,
                    keyCodeWithoutSession(command), keyUpDelayMs);
            // This individual attempt is exclusive. Boot attempt one uses only the receiver;
            // the controller may choose a verified background fallback on a later attempt.
            String browser = YANDEX_MUSIC_PACKAGE.equals(target) && command == Command.PLAY
                    ? "skipped_receiver_available" : "not_used";
            return trace(dispatchError.isEmpty()
                            ? Result.RECEIVER_COMMAND : Result.DISPATCH_FAILED,
                    "route=queried_receiver, activeSessions=" + activeSessionCount
                            + ", process=" + targetProcessState
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", receiverCount=" + receiverCount
                            + ", receiver=" + receiver.flattenToShortString()
                            + ", keyUpDelayMs=" + keyUpDelayMs
                            + ", browser=" + browser
                            + ", dispatchError=" + emptyAsNone(dispatchError));
        }

        ComponentName known = knownReceiver(target);
        if (known != null && isInstalled(packages, target)) {
            long keyUpDelayMs = keyUpDelayMillis(target, command);
            String dispatchError = sendKey(context, known, keyCodeWithoutSession(command),
                    keyUpDelayMs);
            String browser = YANDEX_MUSIC_PACKAGE.equals(target) && command == Command.PLAY
                    ? "skipped_receiver_available" : "not_used";
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
                            + ", browser=" + browser
                            + ", dispatchError=" + emptyAsNone(dispatchError));
        }
        String deferredSession = "not_used";
        if (deferredYandexPlaySession != null
                && isUsablePlaySession(deferredYandexPlaybackState,
                deferredYandexActions, targetProcessState)) {
            try {
                deferredYandexPlaySession.getTransportControls().play();
                deferredSession = "play_dispatched_state_" + deferredYandexPlaybackState
                        + "_actions_" + deferredYandexActions;
                // Outside the verified boot escalation, a discovered exact session remains the
                // exclusive fallback when the receiver is unavailable.
                return trace(Result.SESSION_COMMAND,
                        "route=session_fallback, activeSessions=" + activeSessionCount
                                + ", process=" + targetProcessState
                                + ", sessions=" + sessionInventory
                                + ", sessionError=" + sessionError
                                + ", receiverCount=" + receiverCount
                                + ", receiverQueryError=" + receiverQueryError
                                + ", sessionFallback=" + deferredSession
                                + ", browser=skipped_session_available");
            } catch (RuntimeException failure) {
                deferredSession = "play_" + failure.getClass().getSimpleName();
            }
        }
        String browser = requestYandexBrowserIfUseful(context, target, command);
        if ("bootstrap_scheduled".equals(browser)) {
            return trace(Result.BROWSER_BOOTSTRAP,
                    "route=media_browser_bootstrap, activeSessions=" + activeSessionCount
                            + ", process=" + targetProcessState
                            + ", sessions=" + sessionInventory
                            + ", sessionError=" + sessionError
                            + ", receiverCount=" + receiverCount
                            + ", receiverQueryError=" + receiverQueryError
                            + ", sessionFallback=" + deferredSession
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
                        + ", sessionFallback=" + deferredSession
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

    @NonNull
    private static String sendKey(@NonNull Context context, @NonNull ComponentName receiver,
                                  int keyCode, long keyUpDelayMs) {
        long now = SystemClock.uptimeMillis();
        Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                        | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        keyCode, 0));
        try {
            context.sendBroadcast(down);
            if (keyUpDelayMs > 0L) SystemClock.sleep(keyUpDelayMs);
            long releasedAt = SystemClock.uptimeMillis();
            Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(receiver)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                            | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, releasedAt,
                            KeyEvent.ACTION_UP, keyCode, 0));
            context.sendBroadcast(up);
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
    private static String emptyAsNone(@NonNull String value) {
        return value.isEmpty() ? "none" : value;
    }

    private static boolean isUsablePlaySession(int playbackState, long actions,
                                               @NonNull String processState) {
        boolean explicitPlay = (actions & PlaybackState.ACTION_PLAY) != 0L
                || (actions & PlaybackState.ACTION_PLAY_PAUSE) != 0L;
        if (!explicitPlay || playbackState < PlaybackState.STATE_NONE) return false;
        if (playbackState == PlaybackState.STATE_NONE) {
            return "running".equals(processState);
        }
        return !"not_running".equals(processState);
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
