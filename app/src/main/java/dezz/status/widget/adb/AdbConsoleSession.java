/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import android.content.Context;
import dezz.status.widget.shell.AdbTransport;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One private console lane: never queues or retries a submitted shell command. */
public final class AdbConsoleSession implements AutoCloseable {
    public interface Operation { void run(AdbConsoleSession session) throws Exception; }
    public interface Listener { void completed(Exception error); }
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "natro-adb-console"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "natro-adb-deadline"));
    private final AtomicBoolean busy = new AtomicBoolean(), cancelled = new AtomicBoolean();
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private volatile AdbTransport transport;
    private volatile String endpoint = "Disconnected", stopReason = "Отменено";
    private volatile boolean closed;
    private int preferredPort = 5555;
    public AdbConsoleSession(Context context) { this.context = context.getApplicationContext(); }
    public String endpoint() { return endpoint; }
    public boolean connected() { return transport != null; }
    public boolean busy() { return busy.get(); }
    public synchronized boolean submit(Operation operation, Listener listener) {
        if (closed || !busy.compareAndSet(false, true)) return false;
        cancelled.set(false); stopReason = "Отменено";
        worker.execute(() -> {
            ScheduledFuture<?> deadline = timer.schedule(() -> cancel("Таймаут 60 секунд"), 60, TimeUnit.SECONDS);
            Exception failure = null;
            try { operation.run(this); checkCancelled(); }
            catch (Exception error) { failure = cancelled.get() ? new IOException(stopReason
                    + ". Команда не повторялась; проверьте её фактический результат.") : error; }
            finally { deadline.cancel(false); busy.set(false); listener.completed(failure); }
        });
        return true;
    }
    public void connect() throws Exception {
        disconnect();
        StringBuilder failures = new StringBuilder();
        int[] ports = {preferredPort, preferredPort == 5555 ? 7777 : 5555};
        for (int port : ports) {
            checkCancelled();
            try {
                if (!AdbTransport.probe("127.0.0.1", port, this::takeSocket))
                    throw new IOException("порт не отвечает по протоколу ADB");
                checkCancelled();
                AdbTransport candidate = AdbTransport.connect(context, "127.0.0.1", port, this::takeSocket);
                if (cancelled.get()) { candidate.close(); checkCancelled(); }
                transport = candidate; preferredPort = port; endpoint = "Connected · 127.0.0.1:" + port;
                return;
            } catch (Exception error) { failures.append(port).append(": ").append(error.getMessage()).append('\n'); }
        }
        throw new IOException("Disconnected\n" + failures + "Проверьте ADB и разрешение RSA-ключа Natro на головном устройстве.");
    }
    private void takeSocket(Socket next) {
        socket.set(next);
        if (cancelled.get()) try { next.close(); } catch (IOException ignored) {}
    }
    public AdbShellResult.Result command(String command) throws Exception {
        checkCancelled();
        if (transport == null) throw new IOException("no devices/emulators found — выполните Connect");
        AdbShellResult capture = new AdbShellResult();
        try { transport.execRaw(capture.wrap(command), chunk -> { capture.accept(chunk); }); }
        catch (Exception error) { disconnect(); throw error; }
        checkCancelled();
        AdbShellResult.Result result = capture.finish();
        if (result.exitCode == null) disconnect();
        return result;
    }
    /** Send exactly one daemon privilege request; reconnect only, then verify the actual uid. */
    public String daemonRoot(boolean root) throws Exception {
        checkCancelled();
        if (transport == null) throw new IOException("Сначала выполните Connect");
        java.io.ByteArrayOutputStream reply = new java.io.ByteArrayOutputStream();
        String requestFailure = "";
        try { transport.readService(root ? "root:" : "unroot:", chunk -> {
            int count = Math.min(chunk.length, 8192 - reply.size());
            if (count > 0) reply.write(chunk, 0, count);
        }); } catch (Exception failure) { requestFailure = failure.getMessage(); }
        finally { disconnect(); }
        String message = new String(reply.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        Exception last = null;
        // A daemon restart destroys this connection. Only CNXN/AUTH and the read-only uid probe
        // are retried, never root:/unroot: and never a previously submitted user command.
        long reconnectDeadline = android.os.SystemClock.elapsedRealtime() + 45_000L;
        for (int attempt = 0; android.os.SystemClock.elapsedRealtime() < reconnectDeadline; attempt++) {
            checkCancelled();
            if (attempt > 0) Thread.sleep(500);
            try {
                connect();
                AdbShellResult.Result identity = command("id -u");
                if (!identity.success()) throw new IOException(identity.describe());
                String uid = identity.output.trim();
                if (!uid.matches("[0-9]+")) throw new IOException("Некорректный ответ id -u: " + uid);
                if (root != uid.equals("0")) throw new IllegalStateException(message + "\nUID=" + uid
                        + (root ? ". adbd не получил root. Прошивка может запрещать adb root; su 0 проверяется отдельно."
                                : ". adbd остался root; unroot не подтверждён."));
                endpoint += root ? " · root (UID=0)" : " · shell (UID=" + uid + ")";
                return message + "\n" + (root ? "ADB root подтверждён: UID=0" : "ADB shell подтверждён: UID=" + uid);
            } catch (IllegalStateException rejected) { throw rejected; }
            catch (Exception error) { last = error; }
        }
        throw new IOException(message + "\nЗапрос отправлен один раз. Переподключение не удалось; состояние root неизвестно. "
                + requestFailure, last);
    }
    public void disconnect() {
        AdbTransport current = transport; transport = null; endpoint = "Disconnected";
        if (current != null) current.close();
        Socket pending = socket.getAndSet(null);
        if (pending != null) try { pending.close(); } catch (IOException ignored) {}
    }
    private void checkCancelled() throws IOException { if (cancelled.get() || closed) throw new IOException(stopReason); }
    public void cancel(String reason) {
        stopReason = reason; cancelled.set(true);
        // Socket.close does not join a worker and is safe from the UI thread.
        Socket pending = socket.get();
        if (pending != null) try { pending.close(); } catch (IOException ignored) {}
    }
    @Override public synchronized void close() {
        closed = true; cancel("Раздел ADB закрыт");
        worker.execute(this::disconnect); worker.shutdown(); timer.shutdownNow();
    }
}
