/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import dezz.status.widget.phone.PhoneConnectionJournal;

/** Bootstraps Yandex through its exported MediaBrowser service without opening any UI. */
final class YandexMusicBrowserStarter {
    private static final ComponentName SERVICE = new ComponentName(
            "ru.yandex.music",
            "ru.yandex.music.common.media.mediabrowser.MusicBrowserService");
    /** mSaver leaves the exact bind alive; this guard only retires a genuinely wedged callback. */
    private static final long CONNECTION_TIMEOUT_MS = 20_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Connection current;

    private YandexMusicBrowserStarter() {}

    /**
     * Returns immediately. A successful bind is only a process/session bootstrap; actual playback
     * is confirmed by {@link MediaAutoResumeController}, never inferred from this request.
     */
    @NonNull
    static String requestBootstrap(@NonNull Context context) {
        return request(context, true);
    }

    /** Starts only Yandex's exported media service; it never opens UI or issues PLAY. */
    @NonNull
    static String requestWarmup(@NonNull Context context) {
        return request(context, false);
    }

    @NonNull
    private static String request(@NonNull Context context, boolean playRequested) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            ServiceInfo service = app.getPackageManager().getServiceInfo(SERVICE, 0);
            if (!service.enabled || !service.exported) return "service_not_exported";
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return "service_unavailable";
        }
        Context exactApp = app;
        try {
            return MAIN.post(() -> startOrJoin(exactApp, playRequested))
                    ? (playRequested ? "bootstrap_scheduled" : "warmup_scheduled")
                    : "schedule_rejected";
        } catch (RuntimeException rejected) {
            return "schedule_" + rejected.getClass().getSimpleName();
        }
    }

    /** Kept for source compatibility with older HOME-control callers. */
    @NonNull
    static String requestPlay(@NonNull Context context) {
        return requestBootstrap(context);
    }

    private static void startOrJoin(@NonNull Context context, boolean playRequested) {
        if (current != null && !current.completed) {
            current.request(playRequested);
            return;
        }
        current = new Connection(context, playRequested);
        current.start();
    }

    private static final class Connection extends MediaBrowser.ConnectionCallback
            implements Runnable {
        private final Context context;
        private final long startedAt = SystemClock.elapsedRealtime();
        private MediaBrowser browser;
        private boolean completed;
        private boolean connected;
        private boolean playRequested;

        Connection(@NonNull Context context, boolean playRequested) {
            this.context = context;
            this.playRequested = playRequested;
        }

        void request(boolean requestPlay) {
            if (completed) return;
            if (requestPlay) playRequested = true;
            journal(requestPlay ? "play_joined" : "warmup_coalesced", "none");
            if (connected && playRequested) dispatchBrowserTokenAndFinish("connected_play");
        }

        void start() {
            if (completed) return;
            try {
                browser = new MediaBrowser(context, SERVICE, this, (Bundle) null);
                browser.connect();
                MAIN.postDelayed(this, CONNECTION_TIMEOUT_MS);
                journal("connect_started", "none");
            } catch (RuntimeException failure) {
                finish("connect_failed", failure.getClass().getSimpleName());
            }
        }

        @Override public void onConnected() {
            if (completed || browser == null) return;
            connected = true;
            if (playRequested) {
                dispatchBrowserTokenAndFinish("connected_play");
            } else {
                journal("warmup_connected", "none");
            }
        }

        @Override public void onConnectionSuspended() {
            finishOrDispatch("connection_suspended");
        }

        @Override public void onConnectionFailed() {
            // mSaver retries the exact active session even on this callback: the player process
            // can publish its session immediately before the browser reports failure.
            finishOrDispatch("connection_failed");
        }

        @Override public void run() {
            finishOrDispatch("connection_timeout");
        }

        private void finishOrDispatch(@NonNull String event) {
            if (playRequested) {
                dispatchExactSessionAndFinish(event);
            } else {
                finish(event, "warmup_only");
            }
        }

        private void dispatchExactSessionAndFinish(@NonNull String event) {
            if (completed) return;
            MediaResumeCommand.DispatchTrace trace =
                    MediaResumeCommand.playExactSessionOnly(context, SERVICE.getPackageName());
            MediaAutoResumeController.onYandexBrowserSessionDispatch(context, trace.result);
            finish(event, trace.result + ":" + trace.detail);
        }

        private void dispatchBrowserTokenAndFinish(@NonNull String event) {
            if (completed || browser == null) return;
            MediaResumeCommand.DispatchTrace trace;
            try {
                trace = MediaResumeCommand.playBrowserSession(
                        context, browser.getSessionToken(), SERVICE.getPackageName());
            } catch (RuntimeException failure) {
                trace = MediaResumeCommand.playExactSessionOnly(
                        context, SERVICE.getPackageName());
            }
            MediaAutoResumeController.onYandexBrowserSessionDispatch(context, trace.result);
            finish(event, trace.result + ":" + trace.detail);
        }

        private void finish(@NonNull String event, @NonNull String error) {
            if (completed) return;
            completed = true;
            MAIN.removeCallbacks(this);
            MediaBrowser exact = browser;
            browser = null;
            if (exact != null) {
                try {
                    exact.disconnect();
                } catch (RuntimeException ignored) {
                }
            }
            if (current == this) current = null;
            journal(event, error);
        }

        private void journal(@NonNull String event, @NonNull String error) {
            PhoneConnectionJournal.append("media-auto-resume",
                    "trace event=yandex_media_browser_" + event
                            + ", error=" + error
                            + ", elapsedMs="
                            + Math.max(0L, SystemClock.elapsedRealtime() - startedAt));
        }
    }
}
