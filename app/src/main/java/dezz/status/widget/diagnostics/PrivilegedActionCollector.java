/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.diagnostics;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import dezz.status.widget.Preferences;

/**
 * Session-scoped, passive system trace for actions hidden from normal Android callbacks.
 *
 * <p>READ_LOGS and DUMP are one-time development grants. The collector never changes a vehicle
 * property and never leaves a command running after the action-recorder session ends. Optional
 * root capture accepts only EV_KEY lines; touch coordinates and text input are deliberately
 * discarded.</p>
 */
public final class PrivilegedActionCollector {
    private static final int MAX_LOG_EVENTS = 2_500;
    private static final int MAX_ROOT_KEY_EVENTS = 2_500;
    private static final int MAX_EVENTS_PER_SECOND = 45;
    private static final int MAX_PERSISTED_LINE_CHARS = 900;
    private static final int MAX_DUMP_LINES = 80;
    private static final int MAX_DUMP_INPUT_LINES = 14_000;
    private static final long COMMAND_TIMEOUT_MS = 2_500L;

    private static final Pattern RELEVANT_LOG = Pattern.compile(
            "(?i)(ecarx.{0,80}(car|hvac|adas|pilot)|car.?signal|steer|cruise|g.?pilot|"
                    + "adas|limiter|speed.?limit|openhvac|hvac|climate|key.?event|"
                    + "inputdispatcher|inputreader|mcu.{0,40}(key|button)|"
                    + "button.{0,40}(acc|pilot|cruise))");
    private static final Pattern WINDOW_DUMP = Pattern.compile(
            "(?i)(mCurrentFocus|mFocusedApp|mObscuringWindow|ecarx\\.hvac|"
                    + "ru\\.natro\\.statuswidget|permission denial)");
    private static final Pattern ACTIVITY_DUMP = Pattern.compile(
            "(?i)(mResumedActivity|topResumedActivity|mFocusedActivity|ecarx\\.hvac|"
                    + "ru\\.natro\\.statuswidget|permission denial)");
    private static final Pattern INPUT_DUMP = Pattern.compile(
            "(?i)(FocusedWindow|FocusedApplication|KeyEvent|keyCode|scanCode|"
                    + "Input Dispatcher State|Input Reader State|Device [0-9]+:|"
                    + "Classes:|Sources:|permission denial)");
    private static final Pattern MEDIA_DUMP = Pattern.compile(
            "(?i)(MediaSession|active=|state=|package=|yandex|music|permission denial)");

    private static volatile PrivilegedActionCollector instance;

    private final Context appContext;
    private final Preferences preferences;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(
            runnable -> daemonThread(runnable, "status-system-snapshot"));
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean snapshotInFlight = new AtomicBoolean();
    private final ActionRecorder.RecordingListener recordingListener =
            this::onRecordingChanged;
    private final Object processLock = new Object();
    private final Object rateLock = new Object();

    @Nullable private Process logcatProcess;
    @Nullable private Process rootInputProcess;
    private int logEventCount;
    private int rootEventCount;
    private long rateWindowStarted;
    private int rateWindowEvents;
    @NonNull private String lastLogLine = "";
    private long lastLogLineAt;

    private PrivilegedActionCollector(@NonNull Context context) {
        appContext = context.getApplicationContext();
        preferences = new Preferences(appContext);
    }

    public static void initialize(@NonNull Context context) {
        if (instance != null) return;
        synchronized (PrivilegedActionCollector.class) {
            if (instance != null) return;
            PrivilegedActionCollector collector = new PrivilegedActionCollector(context);
            instance = collector;
            ActionRecorder.addRecordingListener(collector.recordingListener);
        }
    }

    /** Adds post-action state snapshots to the same current session as a user marker. */
    public static void captureMarkerSnapshot() {
        PrivilegedActionCollector collector = instance;
        if (collector != null) collector.requestSnapshots("marker");
    }

    private void onRecordingChanged(boolean recording) {
        if (recording) startSession();
        else stopSession();
    }

    private void startSession() {
        long currentGeneration = generation.incrementAndGet();
        stopProcesses();
        synchronized (rateLock) {
            logEventCount = 0;
            rootEventCount = 0;
            rateWindowStarted = 0L;
            rateWindowEvents = 0;
            lastLogLine = "";
            lastLogLineAt = 0L;
        }
        commandExecutor.execute(() -> {
            if (!isCurrent(currentGeneration)) return;
            PrivilegedDiagnosticsAccess.State access =
                    PrivilegedDiagnosticsAccess.inspect(appContext, true);
            ActionRecorder.record(ActionRecorder.SOURCE_SYSTEM_TRACE,
                    "EXPANDED_CAPTURE_STATUS", ActionRecorder.object(
                            "read_logs", access.readLogs,
                            "dump", access.dump,
                            "usage_access", access.usageAccess,
                            "root_available", access.root,
                            "root_input_enabled", access.rootInputEnabled,
                            "passive_only", true));
            if (!isCurrent(currentGeneration)) return;
            if (access.readLogs) startLogcat(currentGeneration);
            if (access.root && access.rootInputEnabled) startRootInput(currentGeneration);
            if (access.dump) captureSnapshots("session_start", currentGeneration);
        });
    }

    private void stopSession() {
        generation.incrementAndGet();
        stopProcesses();
        snapshotInFlight.set(false);
    }

    private void requestSnapshots(@NonNull String trigger) {
        if (!ActionRecorder.isRecording()
                || !PrivilegedDiagnosticsAccess.inspect(appContext, false).dump
                || !snapshotInFlight.compareAndSet(false, true)) return;
        long currentGeneration = generation.get();
        commandExecutor.execute(() -> {
            try {
                captureSnapshots(trigger, currentGeneration);
            } finally {
                snapshotInFlight.set(false);
            }
        });
    }

    private boolean isCurrent(long expectedGeneration) {
        return generation.get() == expectedGeneration && ActionRecorder.isRecording();
    }

    private void startLogcat(long expectedGeneration) {
        try {
            Process process = new ProcessBuilder(
                    "logcat", "-b", "main", "-b", "system", "-b", "events", "-b", "crash",
                    "-v", "threadtime", "-T", "1")
                    .redirectErrorStream(true)
                    .start();
            synchronized (processLock) {
                if (!isCurrent(expectedGeneration)) {
                    process.destroy();
                    return;
                }
                logcatProcess = process;
            }
            ActionRecorder.record(ActionRecorder.SOURCE_SYSTEM_TRACE,
                    "SYSTEM_LOG_CAPTURE_STARTED", ActionRecorder.object(
                            "buffers", "main,system,events,crash",
                            "filter", "vehicle/input/climate/ADAS",
                            "max_events", MAX_LOG_EVENTS));
            daemonThread(() -> readSystemLog(process, expectedGeneration),
                    "status-system-log-reader").start();
        } catch (IOException | RuntimeException failure) {
            ActionRecorder.record(ActionRecorder.SOURCE_SYSTEM_TRACE,
                    "SYSTEM_LOG_CAPTURE_FAILED", ActionRecorder.object(
                            "error", safeFailure(failure)));
        }
    }

    private void readSystemLog(@NonNull Process process, long expectedGeneration) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (isCurrent(expectedGeneration) && (line = reader.readLine()) != null) {
                String safe = bounded(line);
                if (!RELEVANT_LOG.matcher(safe).find() || !acceptSystemLine(safe)) continue;
                ActionRecorder.record(ActionRecorder.SOURCE_SYSTEM_TRACE,
                        "SYSTEM_LOG_EVENT", ActionRecorder.object("line", safe));
            }
        } catch (IOException ignored) {
            // Destroying the process at session stop closes this stream normally.
        } finally {
            synchronized (processLock) {
                if (logcatProcess == process) logcatProcess = null;
            }
            if (process.isAlive()) process.destroy();
        }
    }

    private boolean acceptSystemLine(@NonNull String line) {
        synchronized (rateLock) {
            if (logEventCount >= MAX_LOG_EVENTS || !acceptRateLocked()) return false;
            long now = SystemClock.elapsedRealtime();
            if (line.equals(lastLogLine) && now - lastLogLineAt < 750L) return false;
            lastLogLine = line;
            lastLogLineAt = now;
            logEventCount++;
            return true;
        }
    }

    private boolean acceptRateLocked() {
        long now = SystemClock.elapsedRealtime();
        if (rateWindowStarted == 0L || now - rateWindowStarted >= 1_000L) {
            rateWindowStarted = now;
            rateWindowEvents = 0;
        }
        if (rateWindowEvents >= MAX_EVENTS_PER_SECOND) return false;
        rateWindowEvents++;
        return true;
    }

    private void startRootInput(long expectedGeneration) {
        try {
            Process process = new ProcessBuilder("su", "-c", "getevent -lt")
                    .redirectErrorStream(true)
                    .start();
            synchronized (processLock) {
                if (!isCurrent(expectedGeneration)) {
                    process.destroy();
                    return;
                }
                rootInputProcess = process;
            }
            ActionRecorder.record(ActionRecorder.SOURCE_ROOT_INPUT,
                    "ROOT_KEY_CAPTURE_STARTED", ActionRecorder.object(
                            "events", "EV_KEY only",
                            "touch_coordinates", false,
                            "max_events", MAX_ROOT_KEY_EVENTS));
            daemonThread(() -> readRootInput(process, expectedGeneration),
                    "status-root-key-reader").start();
        } catch (IOException | RuntimeException failure) {
            ActionRecorder.record(ActionRecorder.SOURCE_ROOT_INPUT,
                    "ROOT_KEY_CAPTURE_FAILED", ActionRecorder.object(
                            "error", safeFailure(failure)));
        }
    }

    private void readRootInput(@NonNull Process process, long expectedGeneration) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (isCurrent(expectedGeneration) && (line = reader.readLine()) != null) {
                if (!line.toUpperCase(Locale.US).contains("EV_KEY")) continue;
                synchronized (rateLock) {
                    if (rootEventCount >= MAX_ROOT_KEY_EVENTS || !acceptRateLocked()) continue;
                    rootEventCount++;
                }
                ActionRecorder.record(ActionRecorder.SOURCE_ROOT_INPUT,
                        "ROOT_INPUT_KEY_EVENT", ActionRecorder.object(
                                "line", bounded(line)));
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (processLock) {
                if (rootInputProcess == process) rootInputProcess = null;
            }
            if (process.isAlive()) process.destroy();
        }
    }

    private void captureSnapshots(@NonNull String trigger, long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) return;
        captureDump(expectedGeneration, trigger, "WINDOW_STATE_SNAPSHOT", WINDOW_DUMP,
                "dumpsys", "window", "windows");
        captureDump(expectedGeneration, trigger, "ACTIVITY_STATE_SNAPSHOT", ACTIVITY_DUMP,
                "dumpsys", "activity", "activities");
        captureDump(expectedGeneration, trigger, "INPUT_STATE_SNAPSHOT", INPUT_DUMP,
                "dumpsys", "input");
        captureDump(expectedGeneration, trigger, "MEDIA_SESSION_SNAPSHOT", MEDIA_DUMP,
                "dumpsys", "media_session");
    }

    private void captureDump(long expectedGeneration, @NonNull String trigger,
                             @NonNull String event, @NonNull Pattern filter,
                             @NonNull String... command) {
        if (!isCurrent(expectedGeneration)) return;
        Process process = null;
        JSONArray matches = new JSONArray();
        boolean truncated = false;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process timeoutProcess = process;
            Thread timeout = daemonThread(() -> {
                try {
                    Thread.sleep(COMMAND_TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (timeoutProcess.isAlive()) timeoutProcess.destroy();
            }, "status-dumpsys-timeout");
            timeout.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int inputLines = 0;
                while ((line = reader.readLine()) != null) {
                    inputLines++;
                    if (filter.matcher(line).find() && matches.length() < MAX_DUMP_LINES) {
                        matches.put(bounded(line));
                    }
                    if (inputLines >= MAX_DUMP_INPUT_LINES) {
                        truncated = true;
                        process.destroy();
                        break;
                    }
                }
            }
            process.waitFor(250L, TimeUnit.MILLISECONDS);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            matches.put("capture failed: " + safeFailure(failure));
        } finally {
            if (process != null && process.isAlive()) process.destroy();
        }
        if (!isCurrent(expectedGeneration)) return;
        ActionRecorder.record(ActionRecorder.SOURCE_SYSTEM_TRACE, event, ActionRecorder.object(
                "trigger", trigger,
                "command", joinCommand(command),
                "lines", matches,
                "truncated", truncated));
    }

    private void stopProcesses() {
        synchronized (processLock) {
            if (logcatProcess != null) logcatProcess.destroy();
            if (rootInputProcess != null) rootInputProcess.destroy();
            logcatProcess = null;
            rootInputProcess = null;
        }
    }

    @NonNull
    private static Thread daemonThread(@NonNull Runnable runnable, @NonNull String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @NonNull
    private static String bounded(@Nullable String raw) {
        String value = DiagnosticJournal.redact(raw == null ? "" : raw.trim());
        return value.length() <= MAX_PERSISTED_LINE_CHARS
                ? value : value.substring(0, MAX_PERSISTED_LINE_CHARS) + "…";
    }

    @NonNull
    private static String safeFailure(@NonNull Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + bounded(message));
    }

    @NonNull
    private static String joinCommand(@NonNull String[] command) {
        StringBuilder result = new StringBuilder();
        for (String part : command) {
            if (result.length() > 0) result.append(' ');
            result.append(part);
        }
        return result.toString();
    }
}
