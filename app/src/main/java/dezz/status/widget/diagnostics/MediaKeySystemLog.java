/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Optional read-only logcat observer. No permission grant, root, input-device or player calls. */
final class MediaKeySystemLog {
    private final Context context;
    private final Consumer<MediaKeyLogRecord> receiver;
    private final Consumer<String> report;
    private volatile String coverage = "inactive";
    private volatile long generation;
    private java.lang.Process process;
    private boolean starting;
    private long retryAfter;
    private volatile long received, rateDropped;

    MediaKeySystemLog(Context context, Consumer<MediaKeyLogRecord> receiver, Consumer<String> report) {
        this.context = context; this.receiver = receiver; this.report = report;
    }

    synchronized void start() {
        if (starting || process != null || SystemClock.elapsedRealtime() < retryAfter) return;
        if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            coverage = "read_logs_not_granted";
            return;
        }
        starting = true;
        long expected = ++generation;
        Thread reader = new Thread(() -> read(expected), "media-key-system-log");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized void stop() {
        generation++;
        starting = false;
        coverage = "inactive";
        java.lang.Process current = process;
        process = null;
        if (current != null) current.destroy();
    }

    String coverage() {
        return coverage + ", system_key_records=" + received + ", system_key_rate_dropped=" + rateDropped;
    }

    private void read(long expected) {
        java.lang.Process owned = null;
        try {
            owned = new ProcessBuilder("logcat", "-b", "main", "-b", "system",
                    "-v", "threadtime", "-T", "1", "MConfig:V", "KeysConfigReceiver:V",
                    "InputService:V", "KeyPolicyImpl:V", "XSFInputService:V",
                    "ECarXCarHardKeyService:V", "MediaSessionService:V",
                    "MediaSessionRecord:V", "AudioService:V", "*:S")
                    .redirectErrorStream(true).start();
            synchronized (this) {
                if (generation != expected) return;
                process = owned;
                starting = false;
                coverage = "listening_if_firmware_logs_keys";
            }
            report.accept("stage=system_log_started, read_only=true, key_fields_only=true");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    owned.getInputStream(), StandardCharsets.UTF_8))) {
                long window = 0;
                int inWindow = 0;
                String line;
                while (generation == expected && (line = input.readLine()) != null) {
                    MediaKeyLogRecord record = MediaKeyLogRecord.parse(line);
                    if (record == null) continue;
                    long now = SystemClock.uptimeMillis();
                    if (now - window >= 1000L) { window = now; inWindow = 0; }
                    if (++inWindow > 32) { rateDropped++; continue; }
                    if (generation != expected) break;
                    received++;
                    receiver.accept(record);
                }
            }
        } catch (Exception failure) {
            if (generation == expected) report.accept("stage=system_log_failed, reason="
                    + failure.getClass().getSimpleName());
        } finally {
            if (owned != null) owned.destroy();
            synchronized (this) {
                if (generation == expected) {
                    process = null;
                    starting = false;
                    coverage = "ended_retry_on_health_tick";
                    retryAfter = SystemClock.elapsedRealtime() + 30_000L;
                }
            }
        }
    }
}
