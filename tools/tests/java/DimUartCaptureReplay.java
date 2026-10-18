package dezz.status.widget.diagnostics;
import android.content.Context;
import dezz.status.widget.shell.AdbTransport;
import java.io.*;
import java.util.concurrent.*;
public final class DimUartCaptureReplay {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Context context = new Context() {
            public File getFilesDir() { return new File(args[0], "files"); }
            public File getCacheDir() { return new File(args[0], "cache"); }
        };
        AdbTransport.reset();
        CountDownLatch receiving = new CountDownLatch(1), release = new CountDownLatch(1);
        AdbTransport.afterOutput = () -> {
            receiving.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        check(DimUartCapture.start(context, "fixture"));
        check(receiving.await(3, TimeUnit.SECONDS));
        check(!DimUartCapture.start(context, "duplicate"));
        try { DimUartCapture.export(context); throw new AssertionError(); } catch (IOException expected) {}
        try { DimUartCapture.delete(context); throw new AssertionError(); } catch (IOException expected) {}
        DimUartCapture.stop(); release.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (DimUartCapture.running() && System.nanoTime() < until) Thread.yield();
        check(!DimUartCapture.running() && !SystemFileDump.RUNNING.get());
        check(AdbTransport.commands.size() == 2 && AdbTransport.services.isEmpty());
        check(DimUartCapture.export(context).isFile());
        DimUartCapture.delete(context);
        check(!DimUartCapture.running());
        System.out.println("PASS single owner / cancel / export guard / no reconnect / no root");
    }
}
