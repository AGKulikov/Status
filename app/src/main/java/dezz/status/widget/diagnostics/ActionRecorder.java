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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Structured, privacy-filtered action timeline used to reproduce head-unit behaviour.
 *
 * <p>Each event is flushed to JSONL and a photo-friendly TXT file immediately. A tiny active
 * marker lets the next process preserve and close an interrupted session after a crash or reboot.
 * The recorder deliberately accepts only caller-curated metadata: notification contents, view
 * text and arbitrary Intent extras must never be passed here.</p>
 */
public final class ActionRecorder {
    public static final String SOURCE_ACTIVITY = "activity";
    public static final String SOURCE_ACCESSIBILITY = "accessibility";
    public static final String SOURCE_STEERING_KEY = "steering_key";
    public static final String SOURCE_SERVICE = "service";
    public static final String SOURCE_OVERLAY = "overlay";
    public static final String SOURCE_ROOT_INPUT = "root_input";
    public static final String SOURCE_SYSTEM_TRACE = "system_trace";
    public static final String SOURCE_USER = "user";

    private static final Object LOCK = new Object();
    private static final String ACTIVE_MARKER = "recorder-active.json";
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Set<RecordingListener> RECORDING_LISTENERS =
            new CopyOnWriteArraySet<>();

    @Nullable private static Context appContext;
    @Nullable private static volatile Session activeSession;
    private static final AtomicInteger droppedAsync = new AtomicInteger();
    private static final ThreadPoolExecutor ASYNC = new ThreadPoolExecutor(0, 1,
            30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "status-action-journal");
                thread.setDaemon(true);
                return thread;
            }, (task, executor) -> droppedAsync.incrementAndGet());

    private ActionRecorder() {
    }

    public interface RecordingListener {
        void onRecordingChanged(boolean recording);
    }

    public static final class Session {
        @NonNull public final String id;
        public final long startedAt;

        Session(@NonNull String id, long startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
    }

    public static void initialize(@NonNull Context context) {
        synchronized (LOCK) {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
            recoverInterruptedSessionLocked();
        }
    }

    public static boolean isRecording() {
        synchronized (LOCK) {
            return activeSession != null;
        }
    }

    public static long startedAt() {
        synchronized (LOCK) {
            return activeSession == null ? 0L : activeSession.startedAt;
        }
    }

    @Nullable
    public static Session start(@NonNull String reason) {
        synchronized (LOCK) {
            if (activeSession != null) return activeSession;
            if (appContext == null) return null;
            long now = System.currentTimeMillis();
            String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date(now)) + "-" + Long.toHexString(SystemClock.elapsedRealtime());
            activeSession = new Session(id, now);
            SEQUENCE.set(0L);
            writeMarkerLocked(activeSession);
            appendLocked(SOURCE_USER, "SESSION_START", object(
                    "reason", DiagnosticJournal.redact(reason),
                    "session", id));
            DiagnosticJournal.info("recorder", "action session started: " + id);
            Session session = activeSession;
            notifyRecordingChanged(true);
            return session;
        }
    }

    @Nullable
    public static Session stop(@NonNull String reason) {
        synchronized (LOCK) {
            Session session = activeSession;
            if (session == null) return null;
            appendLocked(SOURCE_USER, "SESSION_STOP", object(
                    "reason", DiagnosticJournal.redact(reason),
                    "duration_ms", Math.max(0L,
                            System.currentTimeMillis() - session.startedAt)));
            activeSession = null;
            deleteMarkerLocked();
            DiagnosticJournal.info("recorder", "action session stopped: " + session.id);
            notifyRecordingChanged(false);
            return session;
        }
    }

    public static void addRecordingListener(@NonNull RecordingListener listener) {
        RECORDING_LISTENERS.add(listener);
        listener.onRecordingChanged(isRecording());
    }

    public static void removeRecordingListener(@NonNull RecordingListener listener) {
        RECORDING_LISTENERS.remove(listener);
    }

    public static void record(@NonNull String source, @NonNull String event,
                              @Nullable JSONObject safeDetails) {
        synchronized (LOCK) {
            if (activeSession == null) return;
            appendLocked(source, event, safeDetails == null ? new JSONObject() : safeDetails);
        }
    }

    /** Observer callbacks never wait for a recorder file, export or session transition. */
    public static void recordAsync(@NonNull String source, @NonNull String event,
                                   @Nullable JSONObject safeDetails) {
        Session expected = activeSession;
        if (expected == null) return;
        ASYNC.execute(() -> {
            synchronized (LOCK) {
                if (activeSession != expected) return;
                int dropped = droppedAsync.getAndSet(0);
                if (dropped > 0) appendLocked(SOURCE_SYSTEM_TRACE,
                        "RECORDER_QUEUE_DROPPED", object("count", dropped));
                appendLocked(source, event, safeDetails == null ? new JSONObject() : safeDetails);
            }
        });
    }

    public static void mark(@Nullable String comment) {
        record(SOURCE_USER, "MARK", object(
                "comment", DiagnosticJournal.redact(comment)));
        PrivilegedActionCollector.captureMarkerSnapshot();
    }

    /** Records one application-owned service launch without serialising arbitrary Intent extras. */
    public static void recordServiceIntent(@NonNull String component,
                                           @Nullable String action,
                                           int startId) {
        record(SOURCE_SERVICE, "ON_START_COMMAND", object(
                "component", component,
                "action", safeAction(action),
                "start_id", startId));
    }

    /** Records an overlay transition and its logical identifier. */
    public static void recordOverlay(@NonNull String overlay, @NonNull String state,
                                     @Nullable String reason) {
        record(SOURCE_OVERLAY, state, object(
                "overlay", overlay,
                "reason", DiagnosticJournal.redact(reason)));
    }

    @NonNull
    public static String latestTimeline(int maxChars) {
        final File latest;
        synchronized (LOCK) {
            latest = latestLocked(".txt");
        }
        if (latest == null) return "Сессий пока нет";
        return readTail(latest, Math.max(1_000, maxChars));
    }

    @Nullable
    public static File copyLatestForExport(@NonNull Context context, boolean json) {
        final File source;
        final long snapshotBytes;
        synchronized (LOCK) {
            source = latestLocked(json ? ".jsonl" : ".txt");
            if (source == null) return null;
            // Appends finish under the same lock, so this boundary ends at a complete event.
            snapshotBytes = source.length();
        }
        File directory = new File(context.getCacheDir(), "exports");
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        File snapshot = null;
        try {
            snapshot = File.createTempFile("action-export-", ".snapshot", directory);
            copy(source, snapshot, snapshotBytes);
            File target = new File(directory,
                    json ? "status-action-session.json" : "status-action-session.txt");
            if (json) {
                // Stream the envelope instead of retaining a whole long session as a JSONArray.
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new FileInputStream(snapshot), StandardCharsets.UTF_8));
                     BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(target, false), StandardCharsets.UTF_8))) {
                    output.write("{\"format\":\"status-widget-action-session-v1\",\"events\":[");
                    boolean first = true;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        JSONObject event = new JSONObject(line);
                        if (!first) output.write(",");
                        output.write("\n");
                        output.write(event.toString());
                        first = false;
                    }
                    output.write("\n]}");
                }
            } else {
                copy(snapshot, target, snapshotBytes);
            }
            return target;
        } catch (IOException | JSONException ignored) {
            return null;
        } finally {
            if (snapshot != null) {
                // Only our private temporary export snapshot; recorded sessions are never deleted.
                //noinspection ResultOfMethodCallIgnored
                snapshot.delete();
            }
        }
    }

    @NonNull
    public static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int index = 0; index + 1 < values.length; index += 2) {
            try {
                String key = String.valueOf(values[index]);
                Object value = values[index + 1];
                result.put(key, value == null ? JSONObject.NULL : value);
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    private static void appendLocked(@NonNull String source, @NonNull String event,
                                     @NonNull JSONObject details) {
        Session session = activeSession;
        if (session == null) return;
        appendToSessionLocked(session, source, event, details);
    }

    private static void notifyRecordingChanged(boolean recording) {
        for (RecordingListener listener : RECORDING_LISTENERS) {
            try {
                listener.onRecordingChanged(recording);
            } catch (RuntimeException error) {
                DiagnosticJournal.error("recorder", "recording-state listener failed", error);
            }
        }
    }

    private static void appendToSessionLocked(@NonNull Session session,
                                              @NonNull String source,
                                              @NonNull String event,
                                              @NonNull JSONObject rawDetails) {
        File directory = directoryLocked();
        if (directory == null) return;
        long now = System.currentTimeMillis();
        long uptime = SystemClock.elapsedRealtime();
        JSONObject details = redactedObject(rawDetails);
        JSONObject line = object(
                "sequence", SEQUENCE.incrementAndGet(),
                "timestamp", now,
                "uptime_ms", uptime,
                "source", DiagnosticJournal.redact(source),
                "event", DiagnosticJournal.redact(event),
                "details", details);
        File json = new File(directory, "actions-" + session.id + ".jsonl");
        File text = new File(directory, "actions-" + session.id + ".txt");
        String readable = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(now))
                + "  #" + line.optLong("sequence")
                + "  [" + DiagnosticJournal.redact(source) + "]  "
                + DiagnosticJournal.redact(event)
                + (details.length() == 0 ? "" : "  " + details) + "\n";
        try {
            write(json, line.toString() + "\n", true);
            write(text, readable, true);
        } catch (IOException failure) {
            DiagnosticJournal.error("recorder", "could not persist action event", failure);
        }
    }

    @NonNull
    private static JSONObject redactedObject(@NonNull JSONObject source) {
        JSONObject result = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                result.put(DiagnosticJournal.redact(key), redactValue(source.opt(key)));
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    @Nullable
    private static Object redactValue(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL
                || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof JSONObject) return redactedObject((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray result = new JSONArray();
            for (int index = 0; index < source.length(); index++) {
                result.put(redactValue(source.opt(index)));
            }
            return result;
        }
        return DiagnosticJournal.redact(String.valueOf(value));
    }

    private static void recoverInterruptedSessionLocked() {
        File directory = directoryLocked();
        if (directory == null) return;
        File marker = new File(directory, ACTIVE_MARKER);
        if (!marker.isFile()) return;
        try {
            JSONObject value = new JSONObject(read(marker));
            String id = value.optString("id", "");
            long startedAt = value.optLong("started_at", 0L);
            if (!id.isEmpty()) {
                Session interrupted = new Session(id, startedAt);
                SEQUENCE.set(Math.max(0L, countLines(
                        new File(directory, "actions-" + id + ".jsonl"))));
                appendToSessionLocked(interrupted, SOURCE_SERVICE, "SESSION_INTERRUPTED",
                        object("reason", "process restarted before explicit stop"));
                DiagnosticJournal.warn("recorder",
                        "preserved unfinished action session: " + id);
            }
        } catch (IOException | JSONException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        marker.delete();
    }

    private static void writeMarkerLocked(@NonNull Session session) {
        File directory = directoryLocked();
        if (directory == null) return;
        try {
            write(new File(directory, ACTIVE_MARKER), object(
                    "id", session.id,
                    "started_at", session.startedAt).toString(), false);
        } catch (IOException ignored) {
        }
    }

    private static void deleteMarkerLocked() {
        File directory = directoryLocked();
        if (directory == null) return;
        File marker = new File(directory, ACTIVE_MARKER);
        if (marker.exists()) {
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
        }
    }

    @Nullable
    private static File directoryLocked() {
        Context context = appContext;
        if (context == null) return null;
        File directory = new File(context.getFilesDir(), "diagnostics");
        return directory.isDirectory() || directory.mkdirs() ? directory : null;
    }

    @Nullable
    private static File latestLocked(@NonNull String suffix) {
        File directory = directoryLocked();
        if (directory == null) return null;
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("actions-") && name.endsWith(suffix));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private static void write(@NonNull File file, @NonNull String value, boolean append)
            throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, append)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static void copy(@NonNull File source, @NonNull File target, long limit) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[16_384];
            int read;
            long remaining = limit;
            while (remaining > 0 && (read = input.read(buffer, 0,
                    (int) Math.min(buffer.length, remaining))) > 0) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    @NonNull
    private static String read(@NonNull File file) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    @NonNull
    private static String readTail(@NonNull File file, int maxChars) {
        try {
            return BoundedUtf8Tail.read(file, maxChars);
        } catch (IOException ignored) {
            return "Не удалось прочитать сессию";
        }
    }

    private static long countLines(@NonNull File file) {
        if (!file.isFile()) return 0L;
        long count = 0L;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) count++;
        } catch (IOException ignored) {
        }
        return count;
    }

    @NonNull
    private static String safeAction(@Nullable String action) {
        if (action == null) return "";
        String bounded = action.length() > 240 ? action.substring(0, 240) : action;
        return DiagnosticJournal.redact(bounded);
    }
}
