/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import dezz.status.widget.MediaNotificationListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** All OEM Binder reads and commands stay off the UI thread; no global media key or Activity. */
final class AliceMediaPlayback {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "alice-music-resume");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static void capture(Context context, Consumer<MediaController> reply) {
        try { WORKER.execute(() -> {
            MediaController selected = null;
            for (MediaController controller : sessions(context)) {
                if (NavigatorAliceAction.PACKAGE.equals(controller.getPackageName())) continue;
                if (state(controller) == PlaybackState.STATE_PLAYING) { selected = controller; break; }
            }
            MediaController result = selected;
            MAIN.post(() -> reply.accept(result));
        }); } catch (RuntimeException busy) { reply.accept(null); }
    }
    static void resume(Context context, MediaController original, BooleanSupplier permitted) {
        try { WORKER.execute(() -> {
            if (!permitted.getAsBoolean()) return;
            MediaController exact = null;
            for (MediaController controller : sessions(context)) {
                // A player already resumed, or the user started another player: do not interrupt it.
                if (state(controller) == PlaybackState.STATE_PLAYING) return;
                if (original.getSessionToken().equals(controller.getSessionToken())) exact = controller;
            }
            // A destroyed/replaced session is never bootstrapped. No toggle: PLAY only.
            if (exact == null || state(exact) != PlaybackState.STATE_PAUSED || !permitted.getAsBoolean()) return;
            try { exact.getTransportControls().play(); }
            catch (RuntimeException failure) { Log.w("NatroNavigatorAlice", "Music resume unavailable", failure); }
        }); } catch (RuntimeException busy) { Log.w("NatroNavigatorAlice", "Music worker busy"); }
    }
    private static int state(MediaController controller) {
        try { PlaybackState state = controller.getPlaybackState(); return state == null ? -1 : state.getState(); }
        catch (RuntimeException unavailable) { return -1; }
    }
    private static List<MediaController> sessions(Context context) {
        List<MediaController> result = new ArrayList<>();
        try { result.addAll(MediaNotificationListener.activeMediaNotificationControllers(context)); }
        catch (RuntimeException unavailable) { /* Manager may still supply the exact session. */ }
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            if (manager != null) {
                List<MediaController> active = manager.getActiveSessions(
                        new ComponentName(context, MediaNotificationListener.class));
                if (active != null) result.addAll(active);
            }
        } catch (RuntimeException unavailable) { /* Notification tokens are an independent source. */ }
        return result;
    }
}
