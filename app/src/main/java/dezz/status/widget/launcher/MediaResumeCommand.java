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
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;

/** Exact-player, MEDIA_PLAY-only command. It never sends PLAY_PAUSE to an arbitrary app. */
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
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) {
            try {
                if (audio.isMusicActive()) return Result.ALREADY_PLAYING;
            } catch (RuntimeException ignored) {}
        }

        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions != null) {
            try {
                ComponentName listener = new ComponentName(context,
                        MediaNotificationListener.class);
                List<MediaController> controllers = sessions.getActiveSessions(listener);
                if (controllers == null) controllers = Collections.emptyList();
                for (MediaController controller : controllers) {
                    if (!targetPackage.equals(controller.getPackageName())) continue;
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        return Result.ALREADY_PLAYING;
                    }
                    controller.getTransportControls().play();
                    return Result.SESSION_COMMAND;
                }
            } catch (RuntimeException ignored) {
                // Explicit receiver fallback below also works without notification access.
            }
        }

        PackageManager packages = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(targetPackage);
        List<ResolveInfo> receivers;
        try {
            receivers = packages.queryBroadcastReceivers(query, 0);
        } catch (RuntimeException ignored) {
            receivers = Collections.emptyList();
        }
        for (ResolveInfo resolved : receivers) {
            if (resolved.activityInfo == null) continue;
            sendPlay(context, new ComponentName(resolved.activityInfo.packageName,
                    resolved.activityInfo.name));
            return Result.RECEIVER_COMMAND;
        }

        ComponentName known = knownReceiver(targetPackage);
        if (known != null && isInstalled(packages, targetPackage)) {
            sendPlay(context, known);
            return Result.RECEIVER_COMMAND;
        }
        return Result.NO_TARGET;
    }

    private static void sendPlay(@NonNull Context context, @NonNull ComponentName receiver) {
        long now = SystemClock.uptimeMillis();
        Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                        | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                        | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_MEDIA_PLAY, 0));
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
