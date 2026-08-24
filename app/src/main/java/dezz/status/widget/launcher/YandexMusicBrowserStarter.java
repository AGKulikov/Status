/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
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
    /** Cold Yandex restore on the KX11 can take more than five seconds after BOOT_COMPLETED. */
    private static final long CONNECTION_TIMEOUT_MS = 15_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Connection current;

    private YandexMusicBrowserStarter() {}

    /**
     * Returns immediately. A successful bind is only a process/session bootstrap; actual playback
     * is confirmed by {@link MediaAutoResumeController}, never inferred from this request.
     */
    @NonNull
    static String requestBootstrap(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            app.getPackageManager().getServiceInfo(SERVICE, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return "service_unavailable";
        }
        Context exactApp = app;
        try {
            MAIN.post(() -> startOrJoin(exactApp));
            return "bootstrap_scheduled";
        } catch (RuntimeException rejected) {
            return "schedule_" + rejected.getClass().getSimpleName();
        }
    }

    /** Kept for source compatibility with older HOME-control callers. */
    @NonNull
    static String requestPlay(@NonNull Context context) {
        return requestBootstrap(context);
    }

    private static void startOrJoin(@NonNull Context context) {
        if (current != null && !current.completed) {
            current.journal("connect_coalesced", "none");
            return;
        }
        current = new Connection(context);
        current.start();
    }

    private static final class Connection extends MediaBrowser.ConnectionCallback
            implements Runnable {
        private final Context context;
        private final long startedAt = SystemClock.elapsedRealtime();
        private MediaBrowser browser;
        private boolean completed;

        Connection(@NonNull Context context) {
            this.context = context;
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
            try {
                MediaController controller = new MediaController(
                        context, browser.getSessionToken());
                controller.getTransportControls().play();
                // This is a request on the browser token, not proof that audio started. The exact
                // package session is polled and verified by MediaAutoResumeController.
                finish("bootstrap_connected", "play_request_sent_unverified");
            } catch (RuntimeException failure) {
                finish("bootstrap_session_request_failed",
                        failure.getClass().getSimpleName());
            }
        }

        @Override public void onConnectionSuspended() {
            finish("connection_suspended", "none");
        }

        @Override public void onConnectionFailed() {
            finish("connection_failed", "none");
        }

        @Override public void run() {
            finish("connection_timeout", "none");
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
