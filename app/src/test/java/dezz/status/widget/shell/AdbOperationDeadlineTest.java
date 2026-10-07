package dezz.status.widget.shell;

import org.junit.Test;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AdbOperationDeadlineTest {
    @Test public void deadlineReleasesMonitorWaitAndLeavesWorkerReusable() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Socket socket = new Socket();
        try {
            Future<Boolean> first = executor.submit(() -> {
                try (AdbOperationDeadline deadline = new AdbOperationDeadline(socket, 30, "open")) {
                    synchronized (socket) { socket.wait(); }
                    return false;
                } catch (InterruptedException | IOException expected) {
                    return socket.isClosed() && !Thread.currentThread().isInterrupted();
                }
            });
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted()).get());
        } finally { executor.shutdownNow(); socket.close(); }
    }
    @Test public void completedOperationCancelsItsDeadline() throws Exception {
        Socket socket = new Socket();
        try (AdbOperationDeadline deadline = new AdbOperationDeadline(socket, 30, "open")) {}
        new CountDownLatch(1).await(90, TimeUnit.MILLISECONDS);
        assertFalse(socket.isClosed());
        assertFalse(Thread.currentThread().isInterrupted());
        socket.close();
    }
}
