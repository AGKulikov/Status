/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

import dezz.status.widget.diagnostics.ActionRecorder;
import dezz.status.widget.diagnostics.ActionRecorderOverlayService;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.diagnostics.MainThreadWatchdog;
import dezz.status.widget.diagnostics.PrivilegedActionCollector;
import dezz.status.widget.launcher.EcarxSystemStatusBarPolicy;

/**
 * Installs a process-wide uncaught-exception handler that dumps the stacktrace to the cache
 * directory before letting the default handler (which kills the process) take over. On the next
 * launch {@code MainActivity} surfaces the file so users can copy or share the report.
 */
public class StatusWidgetApplication extends Application {
    /** Filename inside {@code getCacheDir()} holding the last crash report. */
    public static final String CRASH_FILE = "last_crash.txt";
    /** A HUD renderer failure is diagnostic only and must not masquerade as a main-process crash. */
    public static final String HUD_CRASH_FILE = "last_hud_crash.txt";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean hudProcess;
    private boolean unlockedRuntimeInitialized;
    private boolean firstUsefulSurfaceSeen;

    @Override
    public void onCreate() {
        super.onCreate();
        hudProcess = AppProcessPolicy.isHudProcess();
        StartupPerformanceTrace.beginProcess(AppProcessPolicy.currentProcessLabel());
        // Keep Application.onCreate minimal. Preferences, recorder recovery and vendor status-bar
        // calls begin from the first-surface event instead of delaying startup with a timer.
        DiagnosticJournal.initialize(this, false);
        installCrashHandler(hudProcess);
        // The main process is the sole coordinator writer. MODE_MULTI_PROCESS is read-through
        // compatibility for :hud, not a transactional cross-process state machine.
        if (!hudProcess) StartupWorkCoordinator.clearLegacyStartupDeferrals(this);
        if (!StartupWorkCoordinator.isUserUnlocked(this)) return;
        // Full diagnostics and the ECARX status-bar Binder are surface work. Launcher/settings
        // notify after their first traversal; a headless host notifies after startForeground.
    }

    public synchronized void ensureUnlockedRuntimeInitialized() {
        if (unlockedRuntimeInitialized || !firstUsefulSurfaceSeen
                || !StartupWorkCoordinator.isUserUnlocked(this)) return;
        unlockedRuntimeInitialized = true;
        Preferences preferences = new Preferences(this, false);
        completePreferenceMigrationsInBackground(preferences);
        DiagnosticJournal.initialize(this,
                !hudProcess && preferences.debugModeEnabled.get());
        if (hudProcess) {
            // HUD owns ImageReader, SurfaceFlinger and external-display callbacks in a dedicated
            // process. Do not duplicate the status-row bootstrap, recorder overlay or lifecycle
            // observers there. If vendor/native code terminates :hud, Android keeps WidgetService,
            // the driver panel and the status row alive in the untouched main process.
            return;
        }
        ActionRecorder.initialize(this);
        PrivilegedActionCollector.initialize(this);
        registerLifecycleJournal();
        MainThreadWatchdog.setEnabled(preferences.debugModeEnabled.get());
        if (preferences.actionRecorderOverlayVisible.get()) {
            ActionRecorderOverlayService.show(this);
        }
        EcarxSystemStatusBarPolicy.applyStored(this);
    }

    public static void ensureUnlockedRuntimeInitialized(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof StatusWidgetApplication) {
            ((StatusWidgetApplication) app).ensureUnlockedRuntimeInitialized();
        }
    }

    /** Begins non-essential process facilities only after a useful app surface already rendered. */
    public static void notifyFirstUsefulSurface(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof StatusWidgetApplication) {
            StatusWidgetApplication application = (StatusWidgetApplication) app;
            application.main.post(() -> {
                if (!application.firstUsefulSurfaceSeen) {
                    application.firstUsefulSurfaceSeen = true;
                }
                application.attemptSurfaceOwnedInitialization();
            });
        }
    }

    /** Rechecks a rendered surface when the integration host reports a real readiness transition. */
    public static void resumeSurfaceOwnedInitialization(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof StatusWidgetApplication) {
            StatusWidgetApplication application = (StatusWidgetApplication) app;
            application.main.post(application::attemptSurfaceOwnedInitialization);
        }
    }

    private void attemptSurfaceOwnedInitialization() {
        if (!firstUsefulSurfaceSeen || unlockedRuntimeInitialized) return;
        WidgetService host = WidgetService.getInstance();
        if (host != null && !host.isIntegrationRuntimeReadyForApplication()) {
            // The process diagnostics/privileged ECARX policy is not needed for the row. Avoid an
            // independent polling timer colliding with the serialized controller lane. The host's
            // integrations-ready transition calls resumeSurfaceOwnedInitialization() exactly once.
            return;
        }
        ensureUnlockedRuntimeInitialized();
        if (unlockedRuntimeInitialized) StartupPerformanceTrace.mark("application_runtime_ready");
    }

    @SuppressWarnings("deprecation")
    private static void completePreferenceMigrationsInBackground(
            @NonNull Preferences preferences) {
        // Use Android's process-shared serial executor instead of creating another startup pool or
        // persistent thread. Diagnostics can initialize from current values immediately; migration
        // I/O runs once at background priority and remains idempotent with the service's own pass.
        try {
            android.os.AsyncTask.SERIAL_EXECUTOR.execute(() -> {
                int tid = android.os.Process.myTid();
                int previousPriority;
                try {
                    previousPriority = android.os.Process.getThreadPriority(tid);
                } catch (RuntimeException ignored) {
                    previousPriority = android.os.Process.THREAD_PRIORITY_DEFAULT;
                }
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {
                }
                try {
                    preferences.completeDeferredStartupMigrations();
                } catch (RuntimeException failure) {
                    DiagnosticJournal.warn("application",
                            "Deferred preference migration failed: " + failure);
                } finally {
                    try {
                        android.os.Process.setThreadPriority(previousPriority);
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        } catch (RuntimeException rejected) {
            DiagnosticJournal.warn("application",
                    "Could not schedule deferred preference migration: " + rejected);
        }
    }

    private void installCrashHandler(boolean hudProcess) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                if (!hudProcess) {
                    ActionRecorder.record(ActionRecorder.SOURCE_SERVICE, "PROCESS_CRASH",
                            ActionRecorder.object(
                                    "thread", thread.getName(),
                                    "exception", throwable.getClass().getName()));
                }
                DiagnosticJournal.recordCrash(thread, throwable);
                writeCrashLog(thread, throwable, hudProcess);
            } catch (Throwable ignored) {
                // Never let the crash handler itself crash — that would kill the process without
                // delegating to the default handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable, boolean hudProcess)
            throws Exception {
        File file = new File(getCacheDir(), hudProcess ? HUD_CRASH_FILE : CRASH_FILE);
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println(hudProcess
                    ? "Natro isolated HUD crash report"
                    : "Natro crash report");
            out.println("Time: " + new Date());
            out.println("Thread: " + thread.getName());
            out.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            out.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            out.println("App version: " + VersionGetter.getAppVersionName(this));
            out.println("Process: " + AppProcessPolicy.currentProcessLabel());
            out.println("Action recorder active: " + ActionRecorder.isRecording());
            out.println();
            throwable.printStackTrace(out);
            out.println();
            out.println("Last diagnostic events:");
            out.println(DiagnosticJournal.tailText(160));
        }
    }

    private void registerLifecycleJournal() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity activity,
                                                    @Nullable Bundle state) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "CREATED",
                        activityDetails(activity));
                DiagnosticJournal.debug("activity",
                        "created " + activity.getClass().getName());
            }

            @Override public void onActivityStarted(@NonNull Activity activity) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "STARTED",
                        activityDetails(activity));
            }

            @Override public void onActivityResumed(@NonNull Activity activity) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "RESUMED",
                        activityDetails(activity));
                DiagnosticJournal.info("activity",
                        "resumed " + activity.getClass().getName());
            }

            @Override public void onActivityPaused(@NonNull Activity activity) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "PAUSED",
                        activityDetails(activity));
            }

            @Override public void onActivityStopped(@NonNull Activity activity) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "STOPPED",
                        activityDetails(activity));
            }

            @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                               @NonNull Bundle outState) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "SAVE_STATE",
                        activityDetails(activity));
            }

            @Override public void onActivityDestroyed(@NonNull Activity activity) {
                ActionRecorder.record(ActionRecorder.SOURCE_ACTIVITY, "DESTROYED",
                        activityDetails(activity));
            }
        });
    }

    @NonNull
    private static org.json.JSONObject activityDetails(@NonNull Activity activity) {
        Intent intent = activity.getIntent();
        String action = intent == null ? "" : intent.getAction();
        String component = intent == null || intent.getComponent() == null ? ""
                : intent.getComponent().flattenToShortString();
        String scheme = intent == null || intent.getData() == null
                || intent.getData().getScheme() == null ? ""
                : intent.getData().getScheme();
        int displayId = -1;
        try {
            android.view.Display display = activity.getWindow().getDecorView().getDisplay();
            if (display != null) displayId = display.getDisplayId();
        } catch (RuntimeException ignored) {
        }
        return ActionRecorder.object(
                "class", activity.getClass().getName(),
                "task_id", activity.getTaskId(),
                "display_id", displayId,
                "intent_action", action == null ? "" : action,
                "intent_component", component,
                "data_scheme", scheme,
                "finishing", activity.isFinishing());
    }
}
