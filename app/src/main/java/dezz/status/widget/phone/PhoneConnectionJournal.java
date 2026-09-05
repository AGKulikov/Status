/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Always-on, privacy-filtered, bounded phone-connection journal.
 *
 * <p>This is separate from the opt-in full diagnostic journal: a user must be able to see the
 * events that explain a failed BLE/ANCS reconnect even after the process was replaced. It stores
 * connection state only, never notification content.</p>
 */
public final class PhoneConnectionJournal {
    private static final Object LOCK = new Object();
    private static final int MAX_LINES = 1_600;
    private static final long COMPACT_AT_BYTES = 2_000_000L;
    private static final String SESSION = Integer.toHexString(Process.myPid()) + "-"
            + Long.toHexString(SystemClock.elapsedRealtime());
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Pattern MAC = Pattern.compile(
            "(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|password|passwd|secret|authorization|bearer|key)"
                    + "\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern NOTIFICATION_CONTENT = Pattern.compile(
            "(?i)(notification attributes:).*|"
                    + "(app displayname:).*|"
                    + "(?:^|\\s)(?:title|message|body|sender)\\s*=.*");
    private static final Pattern RAW_PROTOCOL_FIELD = Pattern.compile(
            "(?i)\\s+(payload|hex|ascii|bytes|value)\\s*=.*");
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX_LINES);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NatroPhoneJournal");
        thread.setDaemon(true);
        return thread;
    });
    private static final JournalWriteQueue WRITE_QUEUE = new JournalWriteQueue(
            MAX_LINES, WRITER::execute, PhoneConnectionJournal::writeOperations);

    private static Context appContext;
    private static boolean loaded;
    private static boolean historyClearedInProcess;
    private static long revision;

    private PhoneConnectionJournal() {}

    public static void initialize(@NonNull Context context) {
        boolean opened = false;
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            appContext = app == null ? context : app;
            if (loaded) return;
            loaded = true;
            opened = true;
        }
        // Persistent history is loaded only when the user opens or exports diagnostics. Reading
        // 1,600 lines from cold flash here delayed both the boot receiver and ANCS by seconds.
        if (opened) append("session", "новый процесс Natro; Android=" + Build.VERSION.SDK_INT
                + "; журнал сохраняет каждую смену reducer/effect");
    }

    public static void append(@NonNull String component, @NonNull String message) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        String line = time + "  [s=" + SESSION + " #" + SEQUENCE.incrementAndGet()
                + "]  [" + sanitize(component) + "]  " + sanitize(message);
        synchronized (LOCK) {
            addLocked(line);
            revision++;
        }
        WRITE_QUEUE.append(line);
    }

    @NonNull
    public static String tailText(int maximumLines) {
        int keep = Math.max(1, maximumLines);
        File file;
        boolean ignoreDisk;
        List<String> live;
        synchronized (LOCK) {
            file = fileLocked();
            ignoreDisk = historyClearedInProcess;
            live = new ArrayList<>(LINES);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (!ignoreDisk && file != null) merged.addAll(readLines(file));
        merged.addAll(live);
        int skip = Math.max(0, merged.size() - keep);
        StringBuilder result = new StringBuilder();
        int index = 0;
        for (String line : merged) {
            if (index++ < skip) continue;
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }

    public static void clear() {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        String line = time + "  [s=" + SESSION + " #" + SEQUENCE.incrementAndGet()
                + "]  [journal]  журнал подключения очищен";
        synchronized (LOCK) {
            LINES.clear();
            addLocked(line);
            historyClearedInProcess = true;
            revision++;
        }
        WRITE_QUEUE.resetAndAppend(line);
    }

    public static long revision() {
        synchronized (LOCK) {
            return revision;
        }
    }

    @NonNull
    private static List<String> readLines(@NonNull File file) {
        List<String> read = new ArrayList<>();
        if (!file.isFile()) return read;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) read.add(sanitize(line));
        } catch (IOException ignored) {
            return read;
        }
        int start = Math.max(0, read.size() - MAX_LINES);
        return start == 0 ? read : new ArrayList<>(read.subList(start, read.size()));
    }

    private static void addLocked(@NonNull String line) {
        while (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(line);
    }

    private static void rewriteFileFromDisk(@NonNull File file) {
        ArrayDeque<String> retained = new ArrayDeque<>(MAX_LINES);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                while (retained.size() >= MAX_LINES) retained.removeFirst();
                retained.addLast(line);
            }
        } catch (IOException ignored) {
            return;
        }
        File temporary = new File(file.getParentFile(), "phone-connection.next");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            for (String line : retained) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            if (!temporary.renameTo(file)) {
                try (FileOutputStream direct = new FileOutputStream(file, false)) {
                    for (String line : retained) {
                        direct.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static void writeOperations(@NonNull List<JournalWriteQueue.Operation> operations) {
        if (operations.isEmpty()) return;
        File file;
        synchronized (LOCK) {
            file = fileLocked();
        }
        if (file == null) return;

        FileOutputStream output = null;
        try {
            for (JournalWriteQueue.Operation operation : operations) {
                if (operation.resetBefore) {
                    if (output != null) {
                        output.flush();
                        output.close();
                        output = null;
                    }
                    if (file.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
                if (output == null) output = new FileOutputStream(file, true);
                output.write((operation.line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (output != null) output.flush();
        } catch (IOException ignored) {
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (file.length() >= COMPACT_AT_BYTES) rewriteFileFromDisk(file);
    }

    private static File fileLocked() {
        if (appContext == null) return null;
        File directory = new File(appContext.getFilesDir(), "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        return new File(directory, "phone-connection.log");
    }

    @NonNull
    private static String sanitize(@NonNull String raw) {
        String clean = raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        clean = MAC.matcher(clean).replaceAll("**:**:**:**:**:**");
        clean = SECRET.matcher(clean).replaceAll("$1=<redacted>");
        if (NOTIFICATION_CONTENT.matcher(clean).find()) {
            int timestampEnd = clean.indexOf("  ");
            String timestamp = timestampEnd > 0 ? clean.substring(0, timestampEnd + 2) : "";
            clean = timestamp + "получены атрибуты уведомления; содержимое скрыто";
        } else {
            clean = RAW_PROTOCOL_FIELD.matcher(clean).replaceFirst(" $1=<redacted>");
        }
        return clean.length() <= 2_000 ? clean : clean.substring(0, 2_000) + "…";
    }
}
