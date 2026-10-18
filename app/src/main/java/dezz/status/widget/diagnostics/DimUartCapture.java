/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.content.Context;
import dezz.status.widget.adb.AdbConsoleSession;
import dezz.status.widget.adb.AdbShellResult;
import java.io.*;

/** Process owner, independent of Activity recreation. Only an explicit UI start connects. */
public final class DimUartCapture {
    private static AdbConsoleSession active;
    private static volatile boolean stopRequested;
    private static volatile String status = "Запись выключена. После перезапуска можно выгрузить предыдущую запись.";
    private DimUartCapture() {}
    private static File root(Context context) {
        return new File(context.getFilesDir(), "diagnostics/dim-uart");
    }
    public static String status() { return status; }
    public static synchronized boolean running() { return active != null; }
    public static synchronized void stop() {
        if (active != null) {
            stopRequested = true; status = "Останавливаю запись…";
            active.cancel("Остановка записи DIM пользователем");
        }
    }
    public static synchronized boolean start(Context context, String version) {
        if (active != null || !SystemFileDump.RUNNING.compareAndSet(false, true)) return false;
        Context app = context.getApplicationContext();
        AdbConsoleSession session = new AdbConsoleSession(app);
        active = session; stopRequested = false; status = "Подключение для записи DIM…";
        session.submitSystemDump(adb -> {
            // Refuse to silently discard earlier evidence. Three bounded sessions maximum.
            if (DimUartRecording.sessions(root(app)).length >= 3)
                throw new IOException("recording_storage_full");
            adb.connect();
            AdbShellResult.Result clock = adb.command("date +%s; cat /proc/uptime; cat /proc/sys/kernel/random/boot_id; getprop ro.build.fingerprint; settings get global NaviMode");
            try (DimUartRecording recording = new DimUartRecording(root(app),
                    "version=" + version + "\nclock_probe_exit=" + clock.exitCode + "\n" + clock.output + "\n")) {
                status = "Запись DIM идёт. Предел: 20 минут или 64 МиБ. Можно переключать приборку.";
                String reason = "stream_ended_unconfirmed";
                try {
                    adb.recordDimLog(chunk -> {
                        try { recording.append(chunk); }
                        catch (IOException failure) { throw new UncheckedIOException(failure); }
                    });
                } catch (UncheckedIOException failure) {
                    reason = "recording_size_limit".equals(failure.getCause().getMessage())
                            ? "size_limit" : "storage_failure";
                    throw failure;
                } catch (Exception failure) {
                    reason = "transport_or_deadline"; throw failure;
                } finally {
                    recording.finish(stopRequested ? "user_stop" : reason);
                }
            }
        }, error -> {
            session.close();
            synchronized (DimUartCapture.class) {
                active = null;
                status = error != null && "recording_storage_full".equals(error.getMessage())
                        ? "Сохранены три записи. Выгрузите их и удалите перед новым сбором."
                        : "Запись остановлена. Выгрузите ZIP; возможные обрыв и пропуски отмечены внутри.";
                SystemFileDump.RUNNING.set(false);
            }
        });
        return true;
    }
    private static synchronized void reserveStorage() throws IOException {
        if (active != null || !SystemFileDump.RUNNING.compareAndSet(false, true))
            throw new IOException("Сначала остановите сбор и дождитесь завершения");
    }
    public static File export(Context context) throws IOException {
        reserveStorage();
        try { return DimUartRecording.exportAll(root(context), new File(context.getCacheDir(), "exports")); }
        finally { SystemFileDump.RUNNING.set(false); }
    }
    public static void delete(Context context) throws IOException {
        reserveStorage();
        try { DimUartRecording.deleteAll(root(context)); status = "Сохранённые записи удалены. Запись выключена."; }
        finally { SystemFileDump.RUNNING.set(false); }
    }
}
