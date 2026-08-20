/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.diagnostics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.Permissions;
import dezz.status.widget.Preferences;

/** Read-only capability probe for the expanded action recorder. */
public final class PrivilegedDiagnosticsAccess {
    public static final String READ_LOGS_PERMISSION = "android.permission.READ_LOGS";
    public static final String DUMP_PERMISSION = "android.permission.DUMP";
    public static final String USAGE_STATS_PERMISSION =
            "android.permission.PACKAGE_USAGE_STATS";

    private static final long ROOT_PROBE_TIMEOUT_MS = 1_500L;
    private static final long ROOT_CACHE_MS = 30_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "status-diagnostics-access");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile long rootProbeAt;
    private static volatile boolean cachedRoot;

    private PrivilegedDiagnosticsAccess() { }

    public interface Callback {
        void onResult(@NonNull State state);
    }

    public static final class State {
        public final boolean readLogs;
        public final boolean dump;
        public final boolean usageStatsPermission;
        public final boolean usageAccess;
        public final boolean root;
        public final boolean rootInputEnabled;

        State(boolean readLogs, boolean dump, boolean usageStatsPermission,
              boolean usageAccess, boolean root,
              boolean rootInputEnabled) {
            this.readLogs = readLogs;
            this.dump = dump;
            this.usageStatsPermission = usageStatsPermission;
            this.usageAccess = usageAccess;
            this.root = root;
            this.rootInputEnabled = rootInputEnabled;
        }

        public boolean standardCaptureReady() {
            return readLogs && dump;
        }
    }

    /** Performs the potentially blocking su probe away from the UI thread. */
    public static void inspectAsync(@NonNull Context context, @NonNull Callback callback) {
        Context appContext = context.getApplicationContext();
        PROBE_EXECUTOR.execute(() -> {
            State result = inspect(appContext, true);
            MAIN.post(() -> callback.onResult(result));
        });
    }

    @NonNull
    static State inspect(@NonNull Context context, boolean probeRoot) {
        Context appContext = context.getApplicationContext();
        boolean readLogs = granted(appContext, READ_LOGS_PERMISSION);
        boolean dump = granted(appContext, DUMP_PERMISSION);
        boolean usagePermission = granted(appContext, USAGE_STATS_PERMISSION);
        boolean usage = Permissions.isUsageAccessGranted(appContext);
        boolean root = probeRoot ? hasRoot() : cachedRoot;
        boolean rootInput = new Preferences(appContext).actionRecorderRootInputEnabled.get();
        return new State(readLogs, dump, usagePermission, usage, root, rootInput);
    }

    private static boolean granted(@NonNull Context context, @NonNull String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasRoot() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (rootProbeAt != 0L && now - rootProbeAt < ROOT_CACHE_MS) return cachedRoot;
        boolean result = probeRootProcess();
        cachedRoot = result;
        rootProbeAt = now;
        return result;
    }

    private static boolean probeRootProcess() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(ROOT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return false;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 1_024) {
                    output.append(line);
                }
            }
            return process.exitValue() == 0 && output.toString().contains("uid=0");
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroy();
        }
    }
}
