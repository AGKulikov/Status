/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import android.content.Context;

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
import java.util.List;
import java.util.Locale;
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
    private static final int MAX_LINES = 600;
    private static final long COMPACT_AT_BYTES = 640_000L;
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

    private static Context appContext;
    private static boolean loaded;

    private PhoneConnectionJournal() {}

    public static void initialize(@NonNull Context context) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            if (loaded) return;
            loaded = true;
            loadLocked();
        }
    }

    public static void append(@NonNull String component, @NonNull String message) {
        synchronized (LOCK) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            String line = time + "  [" + sanitize(component) + "]  " + sanitize(message);
            addLocked(line);
            File file = fileLocked();
            if (file == null) return;
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException ignored) {
            }
            if (file.length() >= COMPACT_AT_BYTES) rewriteLocked(file);
        }
    }

    @NonNull
    public static String tailText(int maximumLines) {
        synchronized (LOCK) {
            int keep = Math.max(1, maximumLines);
            int skip = Math.max(0, LINES.size() - keep);
            StringBuilder result = new StringBuilder();
            int index = 0;
            for (String line : LINES) {
                if (index++ < skip) continue;
                if (result.length() > 0) result.append('\n');
                result.append(line);
            }
            return result.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            File file = fileLocked();
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            append("journal", "журнал подключения очищен");
        }
    }

    private static void loadLocked() {
        File file = fileLocked();
        if (file == null || !file.isFile()) return;
        List<String> read = new ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) read.add(sanitize(line));
        } catch (IOException ignored) {
            return;
        }
        int start = Math.max(0, read.size() - MAX_LINES);
        for (int index = start; index < read.size(); index++) addLocked(read.get(index));
    }

    private static void addLocked(@NonNull String line) {
        while (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(line);
    }

    private static void rewriteLocked(@NonNull File file) {
        File temporary = new File(file.getParentFile(), "phone-connection.next");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            for (String line : LINES) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            if (!temporary.renameTo(file)) {
                try (FileOutputStream direct = new FileOutputStream(file, false)) {
                    for (String line : LINES) {
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
