/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import dezz.status.widget.BuildConfig;
import dezz.status.widget.phone.PhoneConnectionJournal;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixed allowlist only: never sweeps private files, preferences, credentials or backup WALs. */
public final class DiagnosticBundle {
    private DiagnosticBundle() {}
    public static File create(Context context) {
        File directory = new File(context.getCacheDir(), "exports");
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        File target = new File(directory, "Natro-diagnostics-" + System.currentTimeMillis() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            JSONObject manifest = new JSONObject().put("schema", 1).put("version", BuildConfig.VERSION_NAME)
                    .put("version_code", BuildConfig.VERSION_CODE).put("session", CausalDiagnostics.session())
                    .put("wall_ms", System.currentTimeMillis()).put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("uptime_ms", SystemClock.uptimeMillis()).put("debug_enabled", DiagnosticJournal.isEnabled())
                    .put("main_flush_complete", DiagnosticJournal.awaitPendingWrites())
                    .put("recorder_flush_complete", ActionRecorder.awaitPendingWrites())
                    .put("foreign_process_flush", "not_requested; complete persisted lines only; live tail may be missing")
                    .put("journal_writer", DiagnosticJournal.queueState()).put("action_writer", ActionRecorder.writerState())
                    .put("system_process_observer", SystemProcessDiagnostics.state())
                    .put("expanded_capture", PrivilegedActionCollector.state())
                    .put("phone_writer", PhoneConnectionJournal.writerState()).put("recorder_active", ActionRecorder.isRecording())
                    .put("read_logs_granted", granted(context, "android.permission.READ_LOGS"))
                    .put("dump_granted", granted(context, "android.permission.DUMP"))
                    .put("limits", "bounded tails; old events rotate; disabled debug cannot be reconstructed; no raw system logcat")
                    .put("interpretation", "last observed state has explicit ages; external process cause and physical effect may remain unknown");
            JSONArray channels = new JSONArray();
            File diagnosticDirectory = new File(context.getFilesDir(), "diagnostics");
            for (String name : new String[]{"journal.log", "journal-hud.log", "incidents.log", "incidents-hud.log", "crash.log", "crash-hud.log"}) {
                JSONObject entry = new JSONObject().put("name", name);
                File source = new File(diagnosticDirectory, name);
                if (!source.isFile()) entry.put("state", "missing; never written, cleared, or process not started");
                else try {
                    DiagnosticFileSnapshot snapshot = DiagnosticFileSnapshot.read(source, 1_600_000);
                    byte[] bytes = redactLines(snapshot.text).getBytes(StandardCharsets.UTF_8);
                    put(zip, name, bytes);
                    entry.put("state", bytes.length == 0 ? "empty" : "present").put("bytes", bytes.length)
                            .put("sha256", digest(bytes)).put("source_bytes", snapshot.sourceBytes)
                            .put("partial_tail_bytes_skipped", snapshot.partialTailBytes).put("truncated", snapshot.truncated)
                            .put("modified_wall_ms", source.lastModified()).put("malformed_complete_lines", malformedLines(snapshot.text));
                } catch (Exception failure) { entry.put("state", "read_failed").put("error", CausalDiagnostics.failure(failure)); }
                channels.put(entry);
            }
            add(zip, channels, "README.txt", "Natro diagnostic bundle\n"
                    + "journal*.log: wall_ms, elapsed_ms, level, component, message separated by TAB; escaped \\n inside messages.\n"
                    + "Correlate process session + trace/root/parent; do not compare uptime across reboots.\n"
                    + "incidents*.log retain state/thread snapshots separately from routine rotation.\n"
                    + "manifest.json lists permissions, writer losses, limits, missing channels and SHA-256.\n"
                    + "Snapshots are not atomic between processes; absent events are not proof of no event.\n"
                    + "Shell/API acknowledgement does not prove physical effect; old/missing samples are unknown.\n");
            add(zip, channels, "observations.txt", DiagnosticJournal.isEnabled() ? CausalDiagnostics.snapshot() : "debug_disabled\n");
            add(zip, channels, "phone.txt", PhoneConnectionJournal.tailText(1600));
            add(zip, channels, "actions-tail.txt", "Limit: last 64000 characters; latest session only\n" + ActionRecorder.latestTimeline(64000));
            manifest.put("channels", channels);
            put(zip, "manifest.json", manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            return target;
        } catch (Exception failure) {
            target.delete();
            DiagnosticJournal.warn("diagnostic-export", "failed error=" + CausalDiagnostics.failure(failure));
            return null;
        }
    }
    private static int malformedLines(String text) {
        int count=0;
        for(String line:text.split("\n")) {
            if(line.isEmpty())continue;
            String[] fields=line.split("\t",5);
            try { if(fields.length!=5)count++;else {Long.parseLong(fields[0]);Long.parseLong(fields[1]);DiagnosticJournal.Level.valueOf(fields[2]);} }
            catch(RuntimeException invalid){count++;}
        }
        return count;
    }
    static String redactLines(String text) {
        StringBuilder safe = new StringBuilder();
        for (String line : text.split("\n")) safe.append(DiagnosticJournal.redact(line)).append('\n');
        return safe.toString();
    }
    private static boolean granted(Context c, String permission) { return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private static void add(ZipOutputStream zip, JSONArray channels, String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); put(zip, name, bytes);
        channels.put(new JSONObject().put("name", name).put("state", bytes.length == 0 ? "empty" : "present")
                .put("bytes", bytes.length).put("sha256", digest(bytes)));
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
