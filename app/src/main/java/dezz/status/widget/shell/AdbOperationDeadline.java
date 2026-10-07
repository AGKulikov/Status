/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shell;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.*;

/** Cancels the exact blocked operation, including adblib's monitor wait during OPEN. */
final class AdbOperationDeadline implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "natro-adb-io-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final Thread worker = Thread.currentThread();
    private final Socket socket;
    private final String stage;
    private final ScheduledFuture<?> timer;
    private boolean finished, expired;

    AdbOperationDeadline(Socket socket, long timeoutMs, String stage) {
        this.socket = socket;
        this.stage = stage;
        timer = timeoutMs > 0 ? TIMER.schedule(this::expire, timeoutMs, TimeUnit.MILLISECONDS) : null;
    }

    private synchronized void expire() {
        if (finished) return;
        expired = true;
        // Closing TCP releases reads/writes; interrupt also releases an OPEN wait whose
        // stream was never acknowledged. Never join adblib on the deadline thread.
        try { socket.close(); } catch (IOException ignored) {}
        worker.interrupt();
    }

    @Override public synchronized void close() throws IOException {
        finished = true;
        if (timer != null) timer.cancel(false);
        if (expired) {
            Thread.interrupted(); // consume only the interrupt this deadline delivered
            throw new IOException("ADB timeout at " + stage + "; command not repeated");
        }
    }
}
