package dezz.status.widget.hud;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LatestStatusWriterTest {
    @Test public void blockedDiskDoesNotBlockSubmitAndOnlyNewestPendingSnapshotSurvives() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        LatestStatusWriter writer = new LatestStatusWriter(executor);
        try {
            writer.submit(() -> {
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                writes.add(0);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 1; i <= 1000; i++) { int value = i; writer.submit(() -> writes.add(value)); }
            writer.submit(() -> { writes.add(1001); done.countDown(); });
            release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(0, 1001), writes);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void failedWriteDoesNotPoisonLane() {
        LatestStatusWriter writer = new LatestStatusWriter(Runnable::run);
        writer.submit(() -> { throw new IllegalStateException("disk failure"); });
        List<String> writes = new ArrayList<>();
        writer.submit(() -> writes.add("recovered"));
        assertEquals(Collections.singletonList("recovered"), writes);
    }
}
