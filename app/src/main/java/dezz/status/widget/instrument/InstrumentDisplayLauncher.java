/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
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
    private static InstrumentPanelCloseCoordinator closing;
    private static volatile long panelEpoch;
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
            panelEpoch++;
            coordinator.cancel("quickboot");
            InstrumentPanelActivity.finishForDisplayRestore();
            finishStalePanelTask(app, -1);
        }
        InstrumentPanelStore store = new InstrumentPanelStore(app);
        if (store.isEnabled() && (!automatic || store.isAutostart())) {
            if (closing != null) closing.cancel();
            NavigationHudEndpointService.ensureClusterEndpointStarted(app);
        }
        coordinator.request(automatic, reassertDim, reason);
    }

    public static void close(@NonNull Context context) {
        Context app = applicationContext(context);
        onMain(() -> {
            owner = app;
            panelEpoch++;
            if (coordinator != null) coordinator.cancel("closed");
            stopDisplayWatch();
            if (closing == null) {
                closing = new InstrumentPanelCloseCoordinator(new CloseHost(app),
                        new InstrumentPanelLaunchCoordinator.Scheduler() {
                    @Override public long now() { return SystemClock.uptimeMillis(); }
                    @Override public void after(long delay, Runnable task) {
                        MAIN.postDelayed(task, delay);
                    }
                });
            }
            closing.close();
        });
    }

    private static final class CloseHost implements InstrumentPanelCloseCoordinator.Host {
        private final Context app;
        private volatile long ownerObservedAt;
        CloseHost(Context app) { this.app = app; }

        @Override public void finishPanel() {
            NavigationHudEndpointService.disableClusterProjection();
            InstrumentPanelActivity.finishForExplicitClose();
            // Synchronous task removal belongs to this MAIN event. An old worker must never
            // enumerate and remove a new task created after the next ON event.
            finishStalePanelTask(app, -1);
        }

        @Override public void revokeToken(BooleanSupplier current, Runnable done) {
            execute(() -> {
                if (!current.getAsBoolean()) return;
                try { new InstrumentPanelStore(app).revokeLaunchToken(); }
                catch (RuntimeException failure) {
                    trace("token revoke failed=" + failure.getClass().getSimpleName());
                }
                MAIN.post(done);
            }, () -> trace("close worker unavailable"));
        }

        @Override public void readOwner(BooleanSupplier current,
                Consumer<InstrumentPanelCloseCoordinator.Owner> done) {
            int display = new InstrumentPanelStore(app).load().displayId;
            InstrumentOwnerQuery query = new InstrumentOwnerQuery();
            // Same read-only ownership source as OEM navigation recovery. No task manipulation
            // or package stop is delegated to shell; unavailable ownership preserves DIM.
            dezz.status.widget.shell.PrivilegedShell.get(app).runCommand(
                    () -> {
                        if (!current.getAsBoolean()) return null;
                        return query.command(SystemClock.uptimeMillis());
                    }, (output, error) -> {
                if (!current.getAsBoolean()) return;
                InstrumentOwnerQuery.Observation observed = query.finish(output, error,
                        SystemClock.uptimeMillis(), display, app.getPackageName());
                ownerObservedAt = observed.observedAt;
                trace("close owner snapshot " + observed.description);
                done.accept(observed.owner);
            });
        }

        @Override public void restoreDim(BooleanSupplier current, Consumer<Boolean> done) {
            execute(() -> {
                if (!current.getAsBoolean() || new InstrumentPanelStore(app).isEnabled()) return;
                if (SystemClock.uptimeMillis() - ownerObservedAt > InstrumentOwnerQuery.MAX_AGE_MS) {
                    MAIN.post(() -> done.accept(false));
                    trace("close preserved DIM: owner snapshot expired in worker queue");
                    return;
                }
                boolean switched = switchDimMode(app, DIM_STOCK_MODE);
                Integer actual = readDimMode(app);
                if (!current.getAsBoolean() || new InstrumentPanelStore(app).isEnabled()) return;
                // mNavi's matching release for DIM(2,8,8,[1]); keep it on the same serial lane
                // as wake so a superseded OFF can never run after the next generation's wake.
                boolean released = Integer.valueOf(DIM_STOCK_MODE).equals(actual)
                        && sendDimPresentation(app, false);
                MAIN.post(() -> {
                    trace("panel closed dim accepted=" + switched + " readback=" + actual
                            + " release_sent=" + released + " physical_effect=unobserved");
                    done.accept(released);
                });
            }, () -> trace("close worker unavailable"));
        }

        @Override public void trace(String message) {
            InstrumentDisplayLauncher.trace(message);
            if (message.startsWith("close preserved DIM owner=UNKNOWN") || message.contains("timeout")
                    || message.equals("close DIM stock readback=false")) {
                dezz.status.widget.diagnostics.CausalDiagnostics.capture("instrument_close_unconfirmed", false);
                android.widget.Toast.makeText(app,
                        "Панель закрыта. Возврат штатной приборки не подтверждён; подробности в диагностике.",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Authorization is durable, single-use and off the main Looper, including onNewIntent. */
    static void authorize(Context context, String candidate, Consumer<Boolean> completion) {
        Context app = applicationContext(context);
        long admissionEpoch = panelEpoch;
        execute(() -> {
            boolean accepted;
            try { accepted = admissionEpoch == panelEpoch
                    && new InstrumentPanelStore(app).consumeLaunchToken(candidate); }
            catch (RuntimeException failure) {
                trace("authorization failed=" + failure.getClass().getSimpleName());
                accepted = false;
            }
            boolean result = accepted;
            MAIN.post(() -> completion.accept(result && admissionEpoch == panelEpoch));
        }, () -> completion.accept(false));
    }

    static void windowChanged() {
        onMain(() -> {
            if (coordinator != null) coordinator.windowChanged();
            InstrumentOemController.panelWindowChanged();
        });
    }

    /** Repair only the verified mode reset; do not recreate a healthy Activity or wake DIM. */
    static void restoreNavigationMode(Context app, BooleanSupplier allowed, Consumer<Boolean> done) {
        execute(() -> {
            if (!allowed.getAsBoolean() || !new InstrumentPanelStore(app).isEnabled()) {
                MAIN.post(() -> done.accept(false)); return;
            }
            Integer before = readDimMode(app);
            boolean accepted = false;
            if (Integer.valueOf(1).equals(before) && allowed.getAsBoolean()
                    && new InstrumentPanelStore(app).isEnabled())
                accepted = switchDimMode(app, DIM_NAVIGATION_MODE);
            Integer after = readDimMode(app);
            boolean verified = Integer.valueOf(3).equals(after);
            trace("navigation repair before=" + before + " accepted=" + accepted
                    + " after=" + after + " physical_effect=unobserved");
            MAIN.post(() -> done.accept(verified));
        }, () -> done.accept(false));
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
        @Override public void trace(String message) {
            InstrumentDisplayLauncher.trace(message);
            if (message.contains("window-not-ready") || message.contains("timeout"))
                dezz.status.widget.diagnostics.CausalDiagnostics.capture("instrument_launch_unconfirmed", false);
        }

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
                    try {
                        InstrumentPanelTaskLauncher.start(app, displayId, launchToken);
                        trace("instrument task submitted route=public-batch-single display=" + displayId);
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
        panelEpoch++;
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
        return dezz.status.widget.dim.DimInteractionAccess.menu(app);
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

    static Integer readDimMode(Context app) {
        try {
            dezz.status.widget.diagnostics.CausalDiagnostics.current().stage("dim_getter_started", "");
            Object menu = dimMenu(app);
            Object result = menu == null ? null : menu.getClass().getMethod("getNaviMode").invoke(menu);
            dezz.status.widget.diagnostics.CausalDiagnostics.current().stage("dim_getter_returned", "menu_present=" + (menu!=null) + ", number=" + (result instanceof Number));
            return result instanceof Number ? ((Number) result).intValue() : null;
        } catch (Throwable unavailable) {
            trace("DIM readback unavailable=" + dezz.status.widget.diagnostics.CausalDiagnostics.failure(unavailable));
            dezz.status.widget.diagnostics.CausalDiagnostics.current().stage("dim_getter_failed", "error=" + dezz.status.widget.diagnostics.CausalDiagnostics.failure(unavailable));
            return null;
        }
    }

    private static void sendDimWake(Context app) {
        sendDimPresentation(app, true);
    }

    private static boolean sendDimPresentation(Context app, boolean enabled) {
        try {
            Class<?> type = Class.forName("ecarx.dimprotocol.DIMProtocolManager");
            Object manager = type.getMethod("getInstance", Context.class).invoke(null, app);
            if (manager == null) return false;
            Method send = type.getMethod("sendMessageToDIM",
                    byte.class, byte.class, byte.class, byte[].class);
            send.invoke(manager, (byte) 2, (byte) 8, (byte) 8,
                    new byte[]{(byte) (enabled ? 1 : 0)});
            return true;
        } catch (Throwable unavailable) {
            trace("optional DIM presentation enabled=" + enabled + " unavailable="
                    + unavailable.getClass().getSimpleName());
            return false;
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
