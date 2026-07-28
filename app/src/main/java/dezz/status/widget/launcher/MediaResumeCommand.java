/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

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
    enum Result {
        ALREADY_PLAYING,
        SESSION_COMMAND,
        RECEIVER_COMMAND,
        NO_TARGET
    }

    private MediaResumeCommand() {}

    @NonNull
    static Result play(@NonNull Context context, @NonNull String targetPackage) {
        return send(context, targetPackage, Command.PLAY);
    }

    @NonNull
    static Result playPause(@NonNull Context context, @NonNull String targetPackage) {
        return send(context, targetPackage, Command.PLAY_PAUSE);
    }

    @NonNull
    static Result previous(@NonNull Context context, @NonNull String targetPackage) {
        return send(context, targetPackage, Command.PREVIOUS);
    }

    @NonNull
    static Result next(@NonNull Context context, @NonNull String targetPackage) {
        return send(context, targetPackage, Command.NEXT);
    }

    private enum Command {
        PLAY,
        PLAY_PAUSE,
        PREVIOUS,
        NEXT
    }

    @NonNull
    private static Result send(@NonNull Context context, @NonNull String targetPackage,
                               @NonNull Command command) {
        String target = targetPackage.trim();
        if (target.isEmpty()) return Result.NO_TARGET;
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions != null) {
            try {
                ComponentName listener = new ComponentName(context,
                        MediaNotificationListener.class);
                List<MediaController> controllers = sessions.getActiveSessions(listener);
                if (controllers == null) controllers = Collections.emptyList();
                for (MediaController controller : controllers) {
                    if (!target.equals(controller.getPackageName())) continue;
                    PlaybackState state = controller.getPlaybackState();
                    boolean playing = state != null
                            && state.getState() == PlaybackState.STATE_PLAYING;
                    switch (command) {
                        case PLAY:
                            if (playing) return Result.ALREADY_PLAYING;
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
                    return Result.SESSION_COMMAND;
                }
            } catch (RuntimeException ignored) {
                // Explicit receiver fallback below also works without notification access.
            }
        }

        PackageManager packages = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(target);
        List<ResolveInfo> receivers;
        try {
            receivers = packages.queryBroadcastReceivers(query, 0);
        } catch (RuntimeException ignored) {
            receivers = Collections.emptyList();
        }
        if (receivers == null) receivers = Collections.emptyList();
        for (ResolveInfo resolved : receivers) {
            if (resolved.activityInfo == null) continue;
            sendKey(context, new ComponentName(resolved.activityInfo.packageName,
                    resolved.activityInfo.name), keyCodeWithoutSession(command));
            return Result.RECEIVER_COMMAND;
        }

        ComponentName known = knownReceiver(target);
        if (known != null && isInstalled(packages, target)) {
            sendKey(context, known, keyCodeWithoutSession(command));
            return Result.RECEIVER_COMMAND;
        }
        return Result.NO_TARGET;
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

    private static void sendKey(@NonNull Context context, @NonNull ComponentName receiver,
                                int keyCode) {
        long now = SystemClock.uptimeMillis();
        Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                        | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        keyCode, 0));
        Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                        | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_UP,
                        keyCode, 0));
        try {
            context.sendBroadcast(down);
            context.sendBroadcast(up);
        } catch (RuntimeException ignored) {}
    }

    private static ComponentName knownReceiver(@NonNull String packageName) {
        if ("ru.yandex.music".equals(packageName)) {
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
}
