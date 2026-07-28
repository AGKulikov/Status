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
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

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

/**
 * Installs a process-wide uncaught-exception handler that dumps the stacktrace to the cache
 * directory before letting the default handler (which kills the process) take over. On the next
 * launch {@code MainActivity} surfaces the file so users can copy or share the report.
 */
public class StatusWidgetApplication extends Application {
    /** Filename inside {@code getCacheDir()} holding the last crash report. */
    public static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        Preferences preferences = new Preferences(this);
        DiagnosticJournal.initialize(this, preferences.debugModeEnabled.get());
        ActionRecorder.initialize(this);
        installCrashHandler();
        registerLifecycleJournal();
        MainThreadWatchdog.setEnabled(preferences.debugModeEnabled.get());
        if (preferences.actionRecorderOverlayVisible.get()) {
            ActionRecorderOverlayService.show(this);
        }
    }

    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                ActionRecorder.record(ActionRecorder.SOURCE_SERVICE, "PROCESS_CRASH",
                        ActionRecorder.object(
                                "thread", thread.getName(),
                                "exception", throwable.getClass().getName()));
                DiagnosticJournal.recordCrash(thread, throwable);
                writeCrashLog(thread, throwable);
            } catch (Throwable ignored) {
                // Never let the crash handler itself crash — that would kill the process without
                // delegating to the default handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) throws Exception {
        File file = new File(getCacheDir(), CRASH_FILE);
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("Status Widget crash report");
            out.println("Time: " + new Date());
            out.println("Thread: " + thread.getName());
            out.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            out.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            out.println("App version: " + VersionGetter.getAppVersionName(this));
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
