/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.navigation.NavigationHudEndpointService;

/** Restores only the dedicated instrument task; the Natro process remains alive. */
public final class InstrumentDisplayLauncher {
    private static final String TAG = "InstrumentLauncher";
    private static final int DIM_NAVIGATION_MODE = 3;
    private static final int DIM_STOCK_MODE = 1;
    private static final long DIM_MODE_TO_WAKE_MS = 100L;
    private static final long DIM_WAKE_TO_TASK_RESET_MS = 200L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor DIM_LANE = createDimLane();
    // The coordinator and display subscription are owned by MAIN. The worker only reads its
    // cancellation predicate and posts results; it never changes launch admission state.
    private static InstrumentPanelLaunchCoordinator coordinator;
    private static DisplayManager watchedDisplays;
    private static int watchedDisplayId = -1;
    private static String watchedDisplayIdentity;
    private static Context owner;

    private static final DisplayManager.DisplayListener DISPLAY_LISTENER =
            new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { displayChanged(id); }
        @Override public void onDisplayChanged(int id) { displayChanged(id); }
        @Override public void onDisplayRemoved(int id) { displayChanged(id); }
    };

    private InstrumentDisplayLauncher() {}

    public static void reconcileAutomatic(@NonNull Context context) {
        reconcileAutomatic(context, "automatic");
    }

    public static void reconcileAutomatic(@NonNull Context context, @NonNull String reason) {
        Context app = applicationContext(context);
        onMain(() -> request(app, true, !"ui-bootstrap".equals(reason), reason));
    }

    public static void apply(@NonNull Context context) {
        if (new InstrumentPanelStore(context).isEnabled()) launch(context);
        else close(context);
    }

    public static void launch(@NonNull Context context) {
        Context app = applicationContext(context);
        onMain(() -> request(app, false, true, "manual"));
    }

    private static void request(Context app, boolean automatic, boolean reassertDim,
                                String reason) {
        owner = app;
        updateDisplayWatch(app);
        if (coordinator == null) {
            coordinator = new InstrumentPanelLaunchCoordinator(new LaunchHost(app),
                    new InstrumentPanelLaunchCoordinator.Scheduler() {
                @Override public long now() { return SystemClock.uptimeMillis(); }
                @Override public void after(long delay, Runnable task) {
                    MAIN.postDelayed(task, delay);
                }
            });
        }
        // A real power-cycle event supersedes work admitted against the previous vendor state.
        if (reason.contains("QUICKBOOT_POWERON")) {
            coordinator.cancel("quickboot");
            InstrumentPanelActivity.finishForDisplayRestore();
            finishStalePanelTask(app, -1);
        }
        InstrumentPanelStore store = new InstrumentPanelStore(app);
        if (store.isEnabled() && (!automatic || store.isAutostart())) {
            NavigationHudEndpointService.ensureClusterEndpointStarted(app);
        }
        coordinator.request(automatic, reassertDim, reason);
    }

    public static void close(@NonNull Context context) {
        Context app = applicationContext(context);
        onMain(() -> {
            owner = app;
            if (coordinator != null) coordinator.cancel("closed");
            stopDisplayWatch();
            // Revoke the producer generation first. Activity callbacks already queued on MAIN can
            // no longer republish it because enabled=false is persisted before this entry point.
            NavigationHudEndpointService.disableClusterProjection();
            InstrumentPanelActivity.finishForExplicitClose();
            Intent close = new Intent(InstrumentPanelStore.ACTION_CLOSE)
                    .setPackage(app.getPackageName());
            try { app.sendBroadcast(close); } catch (RuntimeException failure) {
                trace("close broadcast failed=" + failure.getClass().getSimpleName());
            }
            execute(() -> {
                try { new InstrumentPanelStore(app).revokeLaunchToken(); }
                catch (RuntimeException failure) {
                    trace("token revoke failed=" + failure.getClass().getSimpleName());
                }
                // The close receiver is registered only after authorization. Remove a pending or
                // otherwise stale task as well, without ever stopping the Natro package.
                finishStalePanelTask(app, -1);
                boolean switched = switchDimMode(app, DIM_STOCK_MODE);
                Integer actual = readDimMode(app);
                MAIN.post(() -> trace("panel closed dim accepted=" + switched
                        + " readback=" + actual + " "
                        + InstrumentPanelActivity.windowState()));
            }, () -> trace("close worker unavailable"));
        });
    }

    /** Authorization is durable, single-use and off the main Looper, including onNewIntent. */
    static void authorize(Context context, String candidate, Consumer<Boolean> completion) {
        Context app = applicationContext(context);
        execute(() -> {
            boolean accepted;
            try { accepted = new InstrumentPanelStore(app).consumeLaunchToken(candidate); }
            catch (RuntimeException failure) {
                trace("authorization failed=" + failure.getClass().getSimpleName());
                accepted = false;
            }
            boolean result = accepted;
            MAIN.post(() -> completion.accept(result));
        }, () -> completion.accept(false));
    }

    static void windowChanged() {
        onMain(() -> {
            if (coordinator != null) coordinator.windowChanged();
        });
    }

    static void trace(String message) {
        Log.i(TAG, message);
        DiagnosticJournal.recordEarly(DiagnosticJournal.Level.INFO, "instrument-panel", message);
    }

    private static final class LaunchHost implements InstrumentPanelLaunchCoordinator.Host {
        private final Context app;
        LaunchHost(Context app) { this.app = app; }

        @Override public InstrumentPanelLaunchCoordinator.Settings settings() {
            InstrumentPanelStore store = new InstrumentPanelStore(app);
            return new InstrumentPanelLaunchCoordinator.Settings(
                    store.isEnabled(), store.isAutostart(), store.load().displayId);
        }

        @Override public boolean displayAvailable(int id) { return usableDisplay(app, id); }
        @Override public InstrumentPanelLaunchCoordinator.WindowState window() {
            return InstrumentPanelActivity.windowState();
        }
        @Override public void reloadPanel() { InstrumentPanelActivity.requestReload(); }
        @Override public void trace(String message) { InstrumentDisplayLauncher.trace(message); }

        @Override public void prepareDim(BooleanSupplier allowed, Consumer<Boolean> completion) {
            execute(() -> {
                if (!allowed.getAsBoolean()) { MAIN.post(() -> completion.accept(false)); return; }
                boolean switched = switchDimMode(app, DIM_NAVIGATION_MODE);
                SystemClock.sleep(DIM_MODE_TO_WAKE_MS);
                if (!allowed.getAsBoolean()) { MAIN.post(() -> completion.accept(false)); return; }
                sendDimWake(app);
                SystemClock.sleep(DIM_WAKE_TO_TASK_RESET_MS);
                Integer actual = readDimMode(app);
                trace("dim prepared accepted=" + switched + " readback=" + actual);
                boolean ready = InstrumentPanelLaunchCoordinator.dimPrepared(switched, actual);
                MAIN.post(() -> completion.accept(ready));
            }, () -> completion.accept(false));
        }

        @Override public void startPanel(int displayId, BooleanSupplier current, BooleanSupplier allowed,
                                         Consumer<Boolean> completion) {
            if (!allowed.getAsBoolean() || !usableDisplay(app, displayId)) {
                completion.accept(false);
                return;
            }
            finishStalePanelTask(app, displayId);
            execute(() -> {
                if (!current.getAsBoolean()) {
                    MAIN.post(() -> completion.accept(false));
                    return;
                }
                final String launchToken;
                try { launchToken = new InstrumentPanelStore(app).issueLaunchToken(); }
                catch (RuntimeException failure) {
                    trace("token persistence failed=" + failure.getClass().getSimpleName());
                    MAIN.post(() -> completion.accept(false));
                    return;
                }
                MAIN.post(() -> {
                    // Check again after durable I/O: close, a new display or another request may
                    // have superseded this one while the worker was writing the capability.
                    if (!allowed.getAsBoolean() || !usableDisplay(app, displayId)) {
                        completion.accept(false);
                        return;
                    }
                    Intent intent = new Intent(app, InstrumentPanelActivity.class)
                            .setAction(Intent.ACTION_MAIN)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(InstrumentPanelStore.EXTRA_LAUNCH_TOKEN, launchToken);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(displayId);
                    Bundle launchOptions = options.toBundle();
                    launchOptions.putInt("android.activity.windowingMode", 5);
                    launchOptions.putInt("android.activity.SplitScreenShownPosition", 0);
                    try {
                        app.startActivity(intent, launchOptions);
                        completion.accept(true);
                    } catch (RuntimeException failure) {
                        trace("activity start failed=" + failure.getClass().getSimpleName()
                                + " display=" + displayId);
                        completion.accept(false);
                    }
                });
            }, () -> completion.accept(false));
        }
    }

    /** Keep a live task on its correct display. Reset only a dead, detached or misplaced panel. */
    private static void finishStalePanelTask(Context app, int displayId) {
        if (displayId >= 0) {
            InstrumentPanelLaunchCoordinator.WindowState state =
                    InstrumentPanelActivity.windowState();
            if (state.alive && state.started && state.attached
                    && state.displayId == displayId) return;
        }
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        if (manager == null) return;
        try {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                Intent baseIntent = info == null ? null : info.baseIntent;
                ComponentName component = baseIntent == null ? null : baseIntent.getComponent();
                if (component != null && app.getPackageName().equals(component.getPackageName())
                        && InstrumentPanelActivity.class.getName().equals(component.getClassName())) {
                    task.finishAndRemoveTask();
                    trace("removed stale instrument task id=" + info.id);
                }
            }
        } catch (RuntimeException failure) {
            trace("instrument task reset failed=" + failure.getClass().getSimpleName());
        }
    }

    private static boolean usableDisplay(Context app, int displayId) {
        DisplayManager manager = app.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        return display != null && display.isValid() && display.getState() != Display.STATE_OFF;
    }

    private static void updateDisplayWatch(Context app) {
        InstrumentPanelStore store = new InstrumentPanelStore(app);
        if (!store.isEnabled() || !store.isAutostart()) { stopDisplayWatch(); return; }
        DisplayManager manager = app.getSystemService(DisplayManager.class);
        int target = store.load().displayId;
        if (manager == watchedDisplays && target == watchedDisplayId) return;
        stopDisplayWatch();
        if (manager == null) return;
        watchedDisplays = manager;
        watchedDisplayId = target;
        watchedDisplayIdentity = displayIdentity(manager.getDisplay(target));
        manager.registerDisplayListener(DISPLAY_LISTENER, MAIN);
    }

    private static void stopDisplayWatch() {
        if (watchedDisplays != null) watchedDisplays.unregisterDisplayListener(DISPLAY_LISTENER);
        watchedDisplays = null;
        watchedDisplayId = -1;
        watchedDisplayIdentity = null;
    }

    private static void displayChanged(int displayId) {
        if (owner == null || watchedDisplays == null || displayId != watchedDisplayId) return;
        InstrumentPanelStore store = new InstrumentPanelStore(owner);
        if (!store.isEnabled() || !store.isAutostart()) { stopDisplayWatch(); return; }
        Display display = watchedDisplays.getDisplay(displayId);
        String identity = displayIdentity(display);
        if (Objects.equals(identity, watchedDisplayIdentity)) return;
        watchedDisplayIdentity = identity;
        trace("display changed id=" + displayId + " state="
                + (display == null ? "absent" : display.getState()));
        if (coordinator != null) coordinator.cancel("display-changed");
        if (usableDisplay(owner, displayId)) {
            // Power/hotplug can invalidate the real window while Java references survive.
            InstrumentPanelActivity.finishForDisplayRestore();
            finishStalePanelTask(owner, -1);
            request(owner, true, true, "display-ready");
        }
    }

    private static String displayIdentity(Display display) {
        if (display == null) return null;
        String identity = display.getName();
        try {
            Object unique = Display.class.getMethod("getUniqueId").invoke(display);
            if (unique != null) identity = String.valueOf(unique);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // getUniqueId is hidden from the public Android SDK.
        }
        return identity + ":" + display.getState();
    }

    private static Object dimMenu(Context app) throws Exception {
        Class<?> type = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
        Object interaction = type.getMethod("create", Context.class).invoke(null, app);
        return interaction == null ? null : type.getMethod("getDimMenuInteraction").invoke(interaction);
    }

    private static boolean switchDimMode(Context app, int mode) {
        try {
            Object menu = dimMenu(app);
            if (menu == null) return false;
            Object result = menu.getClass().getMethod("switchNaviMode", int.class).invoke(menu, mode);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable unavailable) {
            trace("DIM switch unavailable=" + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    private static Integer readDimMode(Context app) {
        try {
            Object menu = dimMenu(app);
            Object result = menu == null ? null : menu.getClass().getMethod("getNaviMode").invoke(menu);
            return result instanceof Number ? ((Number) result).intValue() : null;
        } catch (Throwable unavailable) {
            trace("DIM readback unavailable=" + unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private static void sendDimWake(Context app) {
        try {
            Class<?> type = Class.forName("ecarx.dimprotocol.DIMProtocolManager");
            Object manager = type.getMethod("getInstance", Context.class).invoke(null, app);
            if (manager == null) return;
            Method send = type.getMethod("sendMessageToDIM",
                    byte.class, byte.class, byte.class, byte[].class);
            send.invoke(manager, (byte) 2, (byte) 8, (byte) 8, new byte[]{1});
        } catch (Throwable unavailable) {
            trace("optional DIM wake unavailable=" + unavailable.getClass().getSimpleName());
        }
    }

    private static void execute(Runnable task, Runnable rejected) {
        try { DIM_LANE.execute(task); }
        catch (RejectedExecutionException saturated) {
            trace("instrument worker queue full");
            onMain(rejected);
        }
    }

    private static void onMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else MAIN.post(task);
    }

    private static Context applicationContext(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static ThreadPoolExecutor createDimLane() {
        ThreadPoolExecutor lane = new ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), task -> {
                    Thread worker = new Thread(task, "instrument-dim-launch");
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        lane.allowCoreThreadTimeOut(true);
        return lane;
    }
}
