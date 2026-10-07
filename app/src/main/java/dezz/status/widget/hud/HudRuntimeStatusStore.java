/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Tiny atomic cross-process status channel used only by the HUD settings diagnostics row. */
final class HudRuntimeStatusStore {
    private static final String FILE_NAME = "hud-runtime-status-v1.txt";
    private static final int MAX_BYTES = 2_048;
    private static final LatestStatusWriter WRITER = new LatestStatusWriter(
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "natro-hud-status-writer");
                thread.setDaemon(true);
                return thread;
            }));

    private HudRuntimeStatusStore() {}

    static void write(@NonNull Context context, @NonNull String raw) {
        String value = bounded(raw);
        Context app = context.getApplicationContext();
        Context exact = app == null ? context : app;
        WRITER.submit(() -> writeOnWorker(exact, value));
    }

    private static void writeOnWorker(Context context, String value) {
        AtomicFile file = new AtomicFile(statusFile(context));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            file.finishWrite(output);
        } catch (Exception failure) {
            dezz.status.widget.diagnostics.DiagnosticJournal.warn("hud-status",
                    "write_failed=" + failure.getClass().getSimpleName());
            if (output != null) {
                try { file.failWrite(output); }
                catch (RuntimeException ignoredAgain) {}
            }
        }
    }

    @NonNull
    static String read(@NonNull Context context) {
        AtomicFile file = new AtomicFile(statusFile(context));
        try (FileInputStream input = file.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream(256)) {
            byte[] buffer = new byte[512];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) > 0 && total < MAX_BYTES) {
                int accepted = Math.min(read, MAX_BYTES - total);
                output.write(buffer, 0, accepted);
                total += accepted;
            }
            String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? "HUD не запущен" : value;
        } catch (Exception ignored) {
            return "HUD не запущен";
        }
    }

    @NonNull
    private static File statusFile(@NonNull Context source) {
        Context app = source.getApplicationContext();
        Context context = (app == null ? source : app).createDeviceProtectedStorageContext();
        return new File(context.getFilesDir(), FILE_NAME);
    }

    @NonNull
    private static String bounded(@NonNull String raw) {
        String value = raw.replace('\u0000', ' ').trim();
        return value.length() <= 1_024 ? value : value.substring(0, 1_024);
    }
}
