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

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

import dezz.status.widget.launcher.EmbeddedNavigatorRuntime;
import dezz.status.widget.launcher.EmbeddedNavigatorContract;
import dezz.status.widget.launcher.MergedResourceInstaller;

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
        initializeStatusRuntime(this);
    }

    /**
     * Entry point injected into the merged mod's final NaviApplication after its own startup. It
     * deliberately accepts {@link Application}, so this class need not be the manifest
     * Application.
     */
    public static void initializeStatusRuntime(Application application) {
        if (EmbeddedNavigatorContract.isBundled(application)) {
            MergedResourceInstaller.install(application);
        }
        EmbeddedNavigatorRuntime.install(application);
        if (application instanceof StatusWidgetApplication) {
            ((StatusWidgetApplication) application).installCrashHandler();
        } else {
            installMergedCrashHandler(application);
        }
    }

    /** The merged Application is a NaviApplication, so it cannot use the instance writer below. */
    public static void installMergedCrashHandler(Application application) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File file = new File(application.getCacheDir(), CRASH_FILE);
                try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
                    out.println("Status Widget + Navigator crash report");
                    out.println("Time: " + new Date());
                    out.println("Thread: " + thread.getName());
                    out.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
                    out.println("Android: " + Build.VERSION.RELEASE
                            + " (SDK " + Build.VERSION.SDK_INT + ")");
                    out.println("App version: " + VersionGetter.getAppVersionName(application));
                    out.println();
                    throwable.printStackTrace(out);
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
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
            out.println();
            throwable.printStackTrace(out);
        }
    }
}
