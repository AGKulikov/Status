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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import dezz.status.widget.VersionGetter;

/** Bounded, privacy-filtered cyclic journal. Only the exceptional crash path writes synchronously. */
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
            return time + "  " + level + "  [" + component + "]  [elapsed_ms=" + uptimeMs + "]  "
                    + message.replace("\\n", "\n");
        }
    }

    private static final Object LOCK = new Object();
    // Never acquire this from a normal producer or while holding LOCK. A slow write, rotation,
    // read or export must not block Android broadcast delivery or the application main thread.
    private static final Object DISK_LOCK = new Object();
    private static final long ROTATE_AT_BYTES = 1_500_000L;
    private static final int KEEP_TAIL_BYTES = 900_000;
    private static final long INCIDENT_ROTATE_BYTES = 256_000L;
    private static final int INCIDENT_KEEP_BYTES = 180_000;
    private static final long OPERATIONS_ROTATE_BYTES = 768_000L;
    private static final int OPERATIONS_KEEP_BYTES = 512_000;
    private static final int MAX_MESSAGE_CHARS = 16_000;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(token|password|passwd|secret|authorization|bearer|key)"
                    + "\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern INTENT_BEARER = Pattern.compile("(?i)\\.x[0-9a-f]{32}\\b");
    private static final Pattern MAC_ADDRESS = Pattern.compile(
            "(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern LONG_CREDENTIAL = Pattern.compile(
            "\\b[A-Za-z0-9_\\-+/=]{48,}\\b");

    @Nullable private static volatile Context appContext;
    private static volatile boolean enabled;
    private static final int MAX_EARLY_ENTRIES = 256;
    private static int earlyDropped;
    private static volatile boolean hudControlRegistered;
    private static final java.util.concurrent.atomic.AtomicBoolean crashWriting = new java.util.concurrent.atomic.AtomicBoolean();
    private static final ArrayDeque<Entry> earlyEntries = new ArrayDeque<>();
    private static boolean initialPreferencesRead;
    private static volatile long asyncGeneration;
    private static final java.util.concurrent.atomic.AtomicLong diskFailures = new java.util.concurrent.atomic.AtomicLong();
    private static volatile String lastDiskFailure = "none";
    private static volatile long lastPersistedElapsed;
    private static long reportedDrops;
    private static final java.util.concurrent.atomic.AtomicLong invalidatedWrites = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadPoolExecutor ASYNC = new ThreadPoolExecutor(0, 1,
            30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "status-journal-writer");
                thread.setDaemon(true);
                return thread;
            });
    private static final DiagnosticWriteQueue WRITES = new DiagnosticWriteQueue(256, 64, ASYNC::execute);

    private DiagnosticJournal() {
    }

    /** Installs the crash destination without preferences, disk reads or early journal writes. */
    public static void initializeEarly(@NonNull Context context) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
        }
        if (dezz.status.widget.AppProcessPolicy.isHudProcess() && !hudControlRegistered) {
            hudControlRegistered = true;
            androidx.core.content.ContextCompat.registerReceiver(context, new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context owner, android.content.Intent intent) {
                    if (intent != null && intent.getBooleanExtra("clear", false)) {
                        try { ASYNC.execute(DiagnosticJournal::clear); }
                        catch (java.util.concurrent.RejectedExecutionException busy) { warn("journal-control", "clear_queue_busy; retry_required"); }
                    }
                    else if (intent != null) setEnabled(owner, intent.getBooleanExtra("enabled", false));
                }
            }, new android.content.IntentFilter(context.getPackageName() + ".DIAGNOSTIC_STATE"),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    /** Small startup events survive deferred diagnostics; disabled debug never persists them. */
    public static void recordEarly(@NonNull Level level, @NonNull String component,
                                   @NonNull String message) {
        synchronized (LOCK) {
            if (enabled) {
                enqueueLocked(level, component, message,
                        System.currentTimeMillis(), SystemClock.elapsedRealtime());
            } else if (!initialPreferencesRead && appContext != null) {
                if (earlyEntries.size() == MAX_EARLY_ENTRIES) { earlyEntries.removeFirst(); earlyDropped++; }
                String safe = sanitize(message);
                if (safe.length() > 1_000) safe = safe.substring(0, 1_000);
                earlyEntries.addLast(new Entry(System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), level, sanitize(component), safe));
            }
        }
    }

    private static void finishEarlyEntriesLocked() {
        initialPreferencesRead = true;
        if (enabled) {
            if (earlyDropped > 0) enqueueLocked(Level.WARN, "runtime", "early_events_dropped=" + earlyDropped,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime());
            for (Entry entry : earlyEntries) {
                enqueueLocked(entry.level, entry.component, entry.message,
                        entry.timestamp, entry.uptimeMs);
            }
        }
        earlyEntries.clear();
    }

    public static void initialize(@NonNull Context context, boolean initiallyEnabled) {
        synchronized (LOCK) {
            if (initialPreferencesRead && enabled == initiallyEnabled) return;
            asyncGeneration++;
            appContext = context.getApplicationContext();
            enabled = initiallyEnabled;
            finishEarlyEntriesLocked();
            if (enabled) {
                enqueueLocked(Level.INFO, "runtime", "journal enabled; " + environmentLocked(),
                        System.currentTimeMillis(), SystemClock.elapsedRealtime());
            }
        }
        SteeringKeyDiagnostics.debugChanged(initiallyEnabled);
        dezz.status.widget.navigation.MapStartupDiagnostics.journalChanged(initiallyEnabled);
        CausalDiagnostics.debugChanged(initiallyEnabled);
        MainThreadWatchdog.setEnabled(initiallyEnabled);
    }

    public static void setEnabled(@NonNull Context context, boolean value) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            if (enabled == value && initialPreferencesRead) return;
            asyncGeneration++;
            enabled = value;
            finishEarlyEntriesLocked();
            if (value) {
                enqueueLocked(Level.INFO, "runtime", "journal enabled; " + environmentLocked(),
                        System.currentTimeMillis(), SystemClock.elapsedRealtime());
            }
        }
        SteeringKeyDiagnostics.debugChanged(value);
        dezz.status.widget.navigation.MapStartupDiagnostics.journalChanged(value);
        CausalDiagnostics.debugChanged(value);
        MainThreadWatchdog.setEnabled(value);
        if (!dezz.status.widget.AppProcessPolicy.isHudProcess()) context.sendBroadcast(
                new android.content.Intent(context.getPackageName() + ".DIAGNOSTIC_STATE")
                        .setPackage(context.getPackageName()).addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra("enabled", value));
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
        if (!enabled) { recordEarly(level, component, message); return; }
        CausalDiagnostics.journalEvent(component, message);
        synchronized (LOCK) {
            if (!enabled) return;
            enqueueLocked(level, component, message,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime());
        }
    }

    /** Kept for callers: every normal severity is now asynchronous, not just input observations. */
    public static void infoAsync(@NonNull String component, @NonNull String message) {
        record(Level.INFO, component, message);
    }

    /** Small separate tail keeps failure evidence when routine progress rotates the main log. */
    static void recordIncident(String component, String message) {
        if (!enabled) return;
        final long timestamp = System.currentTimeMillis(), uptime = SystemClock.elapsedRealtime();
        final String bounded = message.length() > MAX_MESSAGE_CHARS ? message.substring(0, MAX_MESSAGE_CHARS) : message;
        synchronized (LOCK) {
            final long generation = asyncGeneration;
            WRITES.submit(true, () -> {
                synchronized (DISK_LOCK) {
                    if (!enabled || generation != asyncGeneration) { invalidatedWrites.incrementAndGet(); return; }
                    appendLocked(Level.WARN, component, bounded, timestamp, uptime);
                    File journal = journalFileLocked();
                    if (journal == null) return;
                    File pinned = new File(journal.getParentFile(), processFile("incidents"));
                    rotateLocked(pinned, INCIDENT_ROTATE_BYTES, INCIDENT_KEEP_BYTES);
                    writeLine(pinned, Level.WARN, component, bounded, timestamp, uptime);
                }
            });
        }
    }

    /** Preserve a bounded operation failure beyond the high-frequency progress tail. */
    public static void operationFailure(String component, String message) {
        recordIncident(component, message);
    }

    public static String queueState() {
        return WRITES.state() + ", invalidated_writes=" + invalidatedWrites.get() + ", disk_failures=" + diskFailures.get()
                + ", last_disk_failure=" + lastDiskFailure + ", last_persisted_elapsed_ms=" + lastPersistedElapsed;
    }

    /** LOCK protects only admission/epochs; the disk worker never takes it. */
    private static void enqueueLocked(Level level, String component, String message,
                                      long timestamp, long uptime) {
        long generation = asyncGeneration;
        String bounded = message.length() > MAX_MESSAGE_CHARS
                ? message.substring(0, MAX_MESSAGE_CHARS) : message;
        WRITES.submit(level == Level.WARN || level == Level.ERROR, () -> {
            synchronized (DISK_LOCK) {
                if (!enabled || generation != asyncGeneration) { invalidatedWrites.incrementAndGet(); return; }
                long dropped = WRITES.dropped();
                if (dropped != reportedDrops) {
                    appendLocked(Level.WARN, "journal-writer", "diagnostic_queue_dropped_total=" + dropped + "; " + queueState());
                    reportedDrops = dropped;
                }
                appendLocked(level, component, bounded, timestamp, uptime);
            }
        });
    }

    /** Crash handlers call this even if normal debug mode was disabled. */
    public static void recordCrash(@NonNull Thread thread, @NonNull Throwable error) {
        // Never wait behind a stuck journal/export lock while the default crash handler waits.
        if (!crashWriting.compareAndSet(false, true)) return;
        try {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            File journal = journalFileLocked();
            if (journal == null) return;
            File crash = new File(journal.getParentFile(), processFile("crash"));
            writeLine(crash, Level.ERROR, "crash",
                    "uncaught exception on " + thread.getName() + "\n"
                            + environmentLocked() + "\n" + stack, System.currentTimeMillis(), SystemClock.elapsedRealtime(), false);
        } finally { crashWriting.set(false); }
    }

    @NonNull
    public static List<Entry> read() {
        {
            File file = journalFileLocked();
            if (file == null) return Collections.emptyList();
            ArrayList<Entry> result = new ArrayList<>();
            for (String name : new String[]{"journal.log", "journal-hud.log", "crash.log", "crash-hud.log"}) {
                result.addAll(readEntries(new File(file.getParentFile(), name)));
            }
            result.sort(java.util.Comparator.comparingLong(entry -> entry.timestamp));
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
        synchronized (DISK_LOCK) {
            synchronized (LOCK) {
                asyncGeneration++;
                WRITES.clear();
                // Queue the boundary before admitting new producers; the worker waits only on
                // DISK_LOCK until deletion finishes, never the other way around.
                if (enabled) enqueueLocked(Level.INFO, "runtime", "journal cleared",
                        System.currentTimeMillis(), SystemClock.elapsedRealtime());
            }
            File file = journalFileLocked();
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (file != null) {
                String[] names = dezz.status.widget.AppProcessPolicy.isHudProcess()
                        ? new String[]{"incidents-hud.log", "crash-hud.log", "operations-hud.log"}
                        : new String[]{"incidents.log", "crash.log", "journal-hud.log", "incidents-hud.log", "crash-hud.log", "operations.log", "operations-hud.log"};
                for (String name : names) { File old = new File(file.getParentFile(), name);
                    if (old.exists() && !old.delete()) { diskFailures.incrementAndGet(); lastDiskFailure="clear_failed"; }
                }
            }
        }
        Context owner = appContext;
        if (owner != null && !dezz.status.widget.AppProcessPolicy.isHudProcess()) owner.sendBroadcast(
                new android.content.Intent(owner.getPackageName() + ".DIAGNOSTIC_STATE")
                        .setPackage(owner.getPackageName()).addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra("clear", true));
    }

    @Nullable
    public static File copyForExport(@NonNull Context context) {
        String snapshot = enabled ? CausalDiagnostics.snapshot() : "debug_disabled; no live state collected";
        boolean flushed = awaitPendingWrites(); // Called by the export worker, never inside DISK_LOCK.
        {
            File source = journalFileLocked();
            if (source == null) return null;
            File directory = new File(context.getCacheDir(), "exports");
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            File target = new File(directory, "status-widget-debug.txt");
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                output.write(("Natro diagnostic export; session=" + CausalDiagnostics.session()
                        + "; pid=" + android.os.Process.myPid() + "; wall_ms=" + System.currentTimeMillis()
                        + "; elapsed_ms=" + SystemClock.elapsedRealtime() + "; uptime_ms=" + SystemClock.uptimeMillis()
                        + "; timezone=" + java.util.TimeZone.getDefault().getID()
                        + "; pending_writes_flushed=" + flushed + "; " + queueState()
                        + "\n=== CURRENT OBSERVATIONS (sample ages are explicit) ===\n" + snapshot
                        + "\n=== JOURNAL ===\n").getBytes(StandardCharsets.UTF_8));
                for (Entry entry : read()) {
                    output.write((entry.readable() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                for (String name : new String[]{"incidents.log", "incidents-hud.log"}) {
                    File pinned = new File(source.getParentFile(), name);
                    if (pinned.isFile()) {
                        output.write(("\n=== RETAINED INCIDENTS " + name + " (may also appear above) ===\n").getBytes(StandardCharsets.UTF_8));
                        for (Entry entry : readEntries(pinned)) output.write((entry.readable() + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                return target;
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    /** Best-effort bounded export barrier. Queue saturation cannot turn into CallerRuns disk IO. */
    public static boolean awaitPendingWrites() {
        CountDownLatch barrier = new CountDownLatch(1);
        synchronized (LOCK) { WRITES.submit(true, barrier::countDown); }
        try { return barrier.await(1500L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private static void appendLocked(@NonNull Level level, @NonNull String component,
                                     @NonNull String rawMessage) {
        appendLocked(level, component, rawMessage,
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    private static void appendLocked(@NonNull Level level, @NonNull String component,
                                     @NonNull String rawMessage, long timestamp, long uptimeMs) {
        File file = journalFileLocked();
        if (file == null) { diskFailures.incrementAndGet(); lastDiskFailure="destination_unavailable"; return; }
        rotateLocked(file);
        writeLine(file, level, component, rawMessage, timestamp, uptimeMs);
        if (retainsOperation(component)) {
            File operations = new File(file.getParentFile(), processFile("operations"));
            rotateLocked(operations, OPERATIONS_ROTATE_BYTES, OPERATIONS_KEEP_BYTES);
            writeLine(operations, level, component, rawMessage, timestamp, uptimeMs);
        }
    }

    /** Low-rate lifecycle evidence survives routine polling and remains opt-in. */
    private static boolean retainsOperation(String component) {
        return "hud-lca".equals(component) || "instrument-tsr".equals(component)
                || "launcher-navigation".equals(component) || "navigator-launch".equals(component)
                || "navigator-window".equals(component)
                || "ancs-lifecycle".equals(component) || "runtime".equals(component)
                || "startup".equals(component);
    }

    private static void writeLine(File file, Level level, String component, String rawMessage, long timestamp, long uptimeMs) {
        writeLine(file, level, component, rawMessage, timestamp, uptimeMs, true);
    }
    private static void writeLine(File file, Level level, String component, String rawMessage, long timestamp, long uptimeMs, boolean append) {
        String message = "session=" + CausalDiagnostics.session() + ", " + sanitize(rawMessage);
        String line = timestamp + "\t" + uptimeMs
                + "\t" + level.name() + "\t" + sanitize(component)
                + "\t" + message.replace("\r", "")
                .replace("\n", "\\n").replace("\t", " ") + "\n";
        try (FileOutputStream output = new FileOutputStream(file, append)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.flush();
            if (level == Level.ERROR || component.startsWith("incident")) output.getFD().sync();
            lastPersistedElapsed = SystemClock.elapsedRealtime();
        } catch (IOException failure) {
            diskFailures.incrementAndGet(); lastDiskFailure = failure.getClass().getSimpleName();
        }
    }

    @Nullable
    private static File journalFileLocked() {
        Context context = appContext;
        if (context == null) return null;
        File directory = new File(context.getFilesDir(), "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        return new File(directory, processFile("journal"));
    }

    static String processFile(String kind) {
        return kind + (dezz.status.widget.AppProcessPolicy.isHudProcess() ? "-hud" : "") + ".log";
    }

    private static List<Entry> readEntries(File file) {
        ArrayList<Entry> result = new ArrayList<>();
        if (!file.isFile()) return result;
        try {
            DiagnosticFileSnapshot snapshot = DiagnosticFileSnapshot.read(file, 1_600_000);
            for (String line : snapshot.text.split("\n")) {
                Entry entry = parse(line);
                if (entry != null) result.add(entry);
            }
        } catch (IOException unavailable) { diskFailures.incrementAndGet(); lastDiskFailure = unavailable.getClass().getSimpleName(); }
        return result;
    }

    private static void rotateLocked(@NonNull File file) {
        rotateLocked(file, ROTATE_AT_BYTES, KEEP_TAIL_BYTES);
    }

    private static void rotateLocked(@NonNull File file, long maximumBytes, int keepBytes) {
        if (!file.isFile() || file.length() < maximumBytes) return;
        File replacement = new File(file.getParentFile(), file.getName() + ".next");
        try (RandomAccessFile input = new RandomAccessFile(file, "r");
             FileOutputStream output = new FileOutputStream(replacement, false)) {
            long start = Math.max(0L, input.length() - keepBytes);
            input.seek(start);
            if (start > 0L) input.readLine();
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            output.flush();
        } catch (IOException failure) {
            diskFailures.incrementAndGet(); lastDiskFailure = failure.getClass().getSimpleName();
            //noinspection ResultOfMethodCallIgnored
            replacement.delete();
            return;
        }
        if (!replacement.renameTo(file)) {
            diskFailures.incrementAndGet(); lastDiskFailure = "rotation_rename_failed";
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
        value = INTENT_BEARER.matcher(value).replaceAll(".x<hidden>");
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
