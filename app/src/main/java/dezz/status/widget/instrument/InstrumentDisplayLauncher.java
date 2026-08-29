/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Mirrors the verified ECARX DIM launch sequence without keeping a permanent worker thread. */
public final class InstrumentDisplayLauncher {
    private static final String TAG = "InstrumentLauncher";
    private static final int DIM_NAVIGATION_MODE = 3;
    private static final int DIM_STOCK_MODE = 1;
    private static final int MAX_DISPLAY_RETRIES = 10;
    private static final long DISPLAY_RETRY_MS = 1_500L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean LAUNCH_PENDING = new AtomicBoolean();
    private static final ThreadPoolExecutor DIM_LANE = createDimLane();

    private InstrumentDisplayLauncher() {}

    public static void reconcileAutomatic(@NonNull Context context) {
        Context app = applicationContext(context);
        InstrumentPanelStore store = new InstrumentPanelStore(app);
        if (store.isEnabled() && store.isAutostart()) {
            launch(app);
        }
    }

    public static void apply(@NonNull Context context) {
        Context app = applicationContext(context);
        InstrumentPanelStore store = new InstrumentPanelStore(app);
        if (store.isEnabled()) launch(app);
        else close(app);
    }

    public static void launch(@NonNull Context context) {
        Context app = applicationContext(context);
        if (!new InstrumentPanelStore(app).isEnabled()) return;
        if (InstrumentPanelActivity.isActive()) {
            InstrumentPanelActivity.requestReload();
            return;
        }
        if (!LAUNCH_PENDING.compareAndSet(false, true)) return;
        try {
            DIM_LANE.execute(() -> {
                try {
                    switchDimMode(app, DIM_NAVIGATION_MODE);
                    // This is the exact KX11 DIM protocol wake used by MConfig after mode 3.
                    SystemClock.sleep(100L);
                    sendDimWake(app);
                    postStart(app, 0, 100L);
                } catch (RuntimeException failure) {
                    LAUNCH_PENDING.set(false);
                    Log.w(TAG, "DIM launch sequence failed", failure);
                    postStart(app, 0, 0L);
                }
            });
        } catch (RejectedExecutionException saturated) {
            LAUNCH_PENDING.set(false);
            Log.w(TAG, "DIM launch queue is full", saturated);
        }
    }

    public static void close(@NonNull Context context) {
        Context app = applicationContext(context);
        LAUNCH_PENDING.set(false);
        MAIN.removeCallbacksAndMessages(app);
        Intent close = new Intent(InstrumentPanelStore.ACTION_CLOSE)
                .setPackage(app.getPackageName());
        try { app.sendBroadcast(close); } catch (RuntimeException ignored) {}
        try {
            DIM_LANE.execute(() -> switchDimMode(app, DIM_STOCK_MODE));
        } catch (RejectedExecutionException ignored) {}
    }

    private static void startPanel(@NonNull Context app, int attempt) {
        InstrumentPanelStore store = new InstrumentPanelStore(app);
        if (!store.isEnabled()) {
            LAUNCH_PENDING.set(false);
            return;
        }
        InstrumentPanelConfig config = store.load();
        DisplayManager displays = app.getSystemService(DisplayManager.class);
        if ((displays == null || displays.getDisplay(config.displayId) == null)
                && attempt < MAX_DISPLAY_RETRIES) {
            postStart(app, attempt + 1, DISPLAY_RETRY_MS);
            return;
        }
        Intent intent = new Intent(app, InstrumentPanelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(config.displayId);
        Bundle launchOptions = options.toBundle();
        // ECARX uses windowing mode 5 for an application-owned DIM page.
        launchOptions.putInt("android.activity.windowingMode", 5);
        try {
            app.startActivity(intent, launchOptions);
            LAUNCH_PENDING.set(false);
            DiagnosticJournal.info("instrument-panel",
                    "panel launched on display " + config.displayId + ", attempt=" + attempt);
        } catch (RuntimeException failure) {
            if (attempt < MAX_DISPLAY_RETRIES) {
                postStart(app, attempt + 1, DISPLAY_RETRY_MS);
            } else {
                LAUNCH_PENDING.set(false);
                Log.e(TAG, "Could not launch instrument panel", failure);
                DiagnosticJournal.error("instrument-panel",
                        "instrument display launch failed", failure);
            }
        }
    }

    private static void postStart(@NonNull Context app, int attempt, long delayMillis) {
        MAIN.postAtTime(() -> startPanel(app, attempt), app,
                SystemClock.uptimeMillis() + Math.max(0L, delayMillis));
    }

    private static void switchDimMode(@NonNull Context context, int mode) {
        try {
            Class<?> interactionClass = Class.forName(
                    "com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
            Object interaction = interactionClass.getMethod("create", Context.class)
                    .invoke(null, context);
            if (interaction == null) return;
            Object menu = interactionClass.getMethod("getDimMenuInteraction")
                    .invoke(interaction);
            if (menu != null) {
                menu.getClass().getMethod("switchNaviMode", int.class).invoke(menu, mode);
            }
        } catch (Throwable unavailable) {
            Log.w(TAG, "ECARX DimInteraction unavailable", unavailable);
        }
    }

    private static void sendDimWake(@NonNull Context context) {
        try {
            Class<?> managerClass = Class.forName("ecarx.dimprotocol.DIMProtocolManager");
            Object manager = managerClass.getMethod("getInstance", Context.class)
                    .invoke(null, context);
            if (manager == null) return;
            Method send = managerClass.getMethod("sendMessageToDIM",
                    byte.class, byte.class, byte.class, byte[].class);
            send.invoke(manager, (byte) 2, (byte) 8, (byte) 8, new byte[]{1});
        } catch (Throwable unavailable) {
            // Some firmware exposes only DimInteraction; the Activity launch remains valid there.
            Log.i(TAG, "Optional DIM protocol wake unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    @NonNull
    private static ThreadPoolExecutor createDimLane() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, 1, 10L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), task -> {
            Thread worker = new Thread(task, "instrument-dim-launch");
            worker.setDaemon(true);
            return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
