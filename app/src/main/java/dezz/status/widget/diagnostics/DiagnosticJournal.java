/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import dezz.status.widget.VersionGetter;

/** Small crash-safe, privacy-filtered, cyclic journal shared by every runtime component. */
public final class DiagnosticJournal {
    public enum Level {
        DEBUG, INFO, WARN, ERROR;

        static Level parse(String raw) {
            try {
                return valueOf(raw);
            } catch (RuntimeException ignored) {
                return INFO;
            }
        }
    }

    public static final class Entry {
        public final long timestamp;
        public final long uptimeMs;
        @NonNull public final Level level;
        @NonNull public final String component;
        @NonNull public final String message;

        Entry(long timestamp, long uptimeMs, @NonNull Level level,
              @NonNull String component, @NonNull String message) {
            this.timestamp = timestamp;
            this.uptimeMs = uptimeMs;
            this.level = level;
            this.component = component;
            this.message = message;
        }

        @NonNull
        public String readable() {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date(timestamp));
            return time + "  " + level + "  [" + component + "]  "
                    + message.replace("\\n", "\n");
        }
    }

    private static final Object LOCK = new Object();
    private static final long ROTATE_AT_BYTES = 1_500_000L;
    private static final int KEEP_TAIL_BYTES = 900_000;
    private static final int MAX_MESSAGE_CHARS = 16_000;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(token|password|passwd|secret|authorization|bearer|key)"
                    + "\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern MAC_ADDRESS = Pattern.compile(
            "(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern LONG_CREDENTIAL = Pattern.compile(
            "\\b[A-Za-z0-9_\\-+/=]{48,}\\b");

    @Nullable private static Context appContext;
    private static volatile boolean enabled;

    private DiagnosticJournal() {
    }

    public static void initialize(@NonNull Context context, boolean initiallyEnabled) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            enabled = initiallyEnabled;
            if (enabled) {
                appendLocked(Level.INFO, "runtime",
                        "journal enabled; " + environmentLocked());
            }
        }
    }

    public static void setEnabled(@NonNull Context context, boolean value) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            if (enabled == value) return;
            enabled = value;
            if (value) {
                appendLocked(Level.INFO, "runtime",
                        "journal enabled; " + environmentLocked());
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void debug(@NonNull String component, @NonNull String message) {
        record(Level.DEBUG, component, message);
    }

    public static void info(@NonNull String component, @NonNull String message) {
        record(Level.INFO, component, message);
    }

    public static void warn(@NonNull String component, @NonNull String message) {
        record(Level.WARN, component, message);
    }

    public static void error(@NonNull String component, @NonNull String message) {
        record(Level.ERROR, component, message);
    }

    public static void error(@NonNull String component, @NonNull String message,
                             @Nullable Throwable error) {
        if (error == null) {
            record(Level.ERROR, component, message);
            return;
        }
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        record(Level.ERROR, component, message + "\n" + text);
    }

    public static void record(@NonNull Level level, @NonNull String component,
                              @NonNull String message) {
        if (!enabled) return;
        synchronized (LOCK) {
            appendLocked(level, component, message);
        }
    }

    /** Crash handlers call this even if normal debug mode was disabled. */
    public static void recordCrash(@NonNull Thread thread, @NonNull Throwable error) {
        synchronized (LOCK) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            appendLocked(Level.ERROR, "crash",
                    "uncaught exception on " + thread.getName() + "\n"
                            + environmentLocked() + "\n" + stack);
        }
    }

    @NonNull
    public static List<Entry> read() {
        synchronized (LOCK) {
            File file = journalFileLocked();
            if (file == null || !file.isFile()) return Collections.emptyList();
            ArrayList<Entry> result = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Entry entry = parse(line);
                    if (entry != null) result.add(entry);
                }
            } catch (IOException ignored) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(result);
        }
    }

    @NonNull
    public static String tailText(int maxEntries) {
        List<Entry> entries = read();
        int start = Math.max(0, entries.size() - Math.max(1, maxEntries));
        StringBuilder result = new StringBuilder();
        for (int i = start; i < entries.size(); i++) {
            if (result.length() > 0) result.append('\n');
            result.append(entries.get(i).readable());
        }
        return result.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            File file = journalFileLocked();
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (enabled) appendLocked(Level.INFO, "runtime", "journal cleared");
        }
    }

    @Nullable
    public static File copyForExport(@NonNull Context context) {
        synchronized (LOCK) {
            File source = journalFileLocked();
            if (source == null || !source.isFile()) return null;
            File directory = new File(context.getCacheDir(), "exports");
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            File target = new File(directory, "status-widget-debug.txt");
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                for (Entry entry : read()) {
                    output.write((entry.readable() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                return target;
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    private static void appendLocked(@NonNull Level level, @NonNull String component,
                                     @NonNull String rawMessage) {
        File file = journalFileLocked();
        if (file == null) return;
        rotateLocked(file);
        String message = sanitize(rawMessage);
        String line = System.currentTimeMillis() + "\t" + SystemClock.elapsedRealtime()
                + "\t" + level.name() + "\t" + sanitize(component)
                + "\t" + message.replace("\r", "")
                .replace("\n", "\\n").replace("\t", " ") + "\n";
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
        }
    }

    @Nullable
    private static File journalFileLocked() {
        Context context = appContext;
        if (context == null) return null;
        File directory = new File(context.getFilesDir(), "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        return new File(directory, "journal.log");
    }

    private static void rotateLocked(@NonNull File file) {
        if (!file.isFile() || file.length() < ROTATE_AT_BYTES) return;
        File replacement = new File(file.getParentFile(), "journal.next");
        try (RandomAccessFile input = new RandomAccessFile(file, "r");
             FileOutputStream output = new FileOutputStream(replacement, false)) {
            long start = Math.max(0L, input.length() - KEEP_TAIL_BYTES);
            input.seek(start);
            if (start > 0L) input.readLine();
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            output.flush();
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            replacement.delete();
            return;
        }
        if (!file.delete() || !replacement.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            replacement.delete();
        }
    }

    @Nullable
    private static Entry parse(@NonNull String line) {
        String[] values = line.split("\\t", 5);
        if (values.length != 5) return null;
        try {
            return new Entry(Long.parseLong(values[0]), Long.parseLong(values[1]),
                    Level.parse(values[2]), values[3], values[4]);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    private static String sanitize(@Nullable String raw) {
        String value = raw == null ? "" : raw;
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1=<hidden>");
        value = MAC_ADDRESS.matcher(value).replaceAll("**:**:**:**:**:**");
        value = LONG_CREDENTIAL.matcher(value).replaceAll("<hidden>");
        if (value.length() > MAX_MESSAGE_CHARS) {
            value = value.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        return value;
    }

    /** Applies the same privacy filter before another diagnostic component persists text. */
    @NonNull
    public static String redact(@Nullable String raw) {
        return sanitize(raw);
    }

    @NonNull
    private static String environmentLocked() {
        Context context = appContext;
        Runtime runtime = Runtime.getRuntime();
        long freeMb = runtime.freeMemory() / 1_048_576L;
        long totalMb = runtime.totalMemory() / 1_048_576L;
        String version = context == null ? "unknown"
                : VersionGetter.getAppVersionName(context);
        return "app=" + version + ", Android=" + Build.VERSION.RELEASE
                + "/SDK" + Build.VERSION.SDK_INT + ", device="
                + Build.MANUFACTURER + " " + Build.MODEL + ", uptime="
                + SystemClock.elapsedRealtime() + "ms, memory=" + freeMb + "/"
                + totalMb + "MiB";
    }
}
