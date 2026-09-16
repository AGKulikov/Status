/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.*;
import dezz.status.widget.adb.AdbShellResult;

/** Shared Natro key/transport; full bounded output and exit status instead of one ADB packet. */
public final class AdbTransport implements ShellTransport {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "service-mode-adb-deadline"); thread.setDaemon(true); return thread;
    });
    private final dezz.status.widget.shell.AdbTransport delegate;
    private AdbTransport(dezz.status.widget.shell.AdbTransport delegate) { this.delegate = delegate; }
    public static AdbTransport connect(Context context, String host, int port) throws Exception {
        return new AdbTransport(dezz.status.widget.shell.AdbTransport.connect(context, host, port));
    }
    public static boolean probe(String host, int port) {
        return dezz.status.widget.shell.AdbTransport.probe(host, port);
    }
    @Override public String describe() { return delegate.describe(); }
    @Override public String exec(String command) throws Exception {
        ScheduledFuture<?> deadline = DEADLINES.schedule(delegate::close, 30, TimeUnit.SECONDS);
        try {
            AdbShellResult framing = new AdbShellResult();
            delegate.execRaw(framing.wrap(command), framing::accept);
            AdbShellResult.Result result = framing.finish();
            if (!result.success()) throw new IOException(result.describe());
            return result.output;
        } finally { deadline.cancel(false); }
    }
    @Override public void close() { delegate.close(); }
}
