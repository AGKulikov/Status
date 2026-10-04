/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class DiagnosticWriteQueueTest {
    @Test public void ordinaryFloodReservesFailuresAndPreservesRetainedOrder() {
        List<Runnable> scheduled=new ArrayList<>(); List<String> output=new ArrayList<>();
        DiagnosticWriteQueue q=new DiagnosticWriteQueue(4,2,scheduled::add);
        assertTrue(q.submit(false,()->output.add("a"))); assertTrue(q.submit(false,()->output.add("b")));
        assertFalse(q.submit(false,()->output.add("dropped")));
        q.submit(true,()->output.add("error1")); q.submit(true,()->output.add("error2"));
        q.submit(true,()->output.add("error3"));
        assertTrue(output.isEmpty()); assertEquals(1,scheduled.size()); scheduled.get(0).run();
        assertEquals(Arrays.asList("b","error1","error2","error3"),output);
        assertEquals(2,q.dropped()); assertTrue(q.state().contains("critical_dropped=0"));
    }
    @Test public void blockedWriterNeverRunsOverflowOnProducer() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        DiagnosticWriteQueue q=new DiagnosticWriteQueue(8,2,worker::execute);
        try {
            q.submit(false,()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            String caller=Thread.currentThread().getName(); List<String> threads=Collections.synchronizedList(new ArrayList<>());
            for(int i=0;i<100;i++)q.submit(false,()->threads.add(Thread.currentThread().getName()));
            q.submit(true,done::countDown); assertTrue(threads.isEmpty()); release.countDown();
            assertTrue(done.await(2,TimeUnit.SECONDS)); assertFalse(threads.contains(caller)); assertTrue(q.dropped()>0);
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test public void rejectionAndWriteExceptionRemainObservableAndDoNotWedgeNextDrain() {
        List<Runnable> scheduled=new ArrayList<>(); int[] attempts={0};
        DiagnosticWriteQueue q=new DiagnosticWriteQueue(4,1,r->{if(attempts[0]++==0)throw new RejectedExecutionException();scheduled.add(r);});
        q.submit(false,()->{throw new IllegalStateException();});
        List<String> output=new ArrayList<>();q.submit(true,()->output.add("next"));scheduled.get(0).run();
        assertEquals(Collections.singletonList("next"),output);
        assertTrue(q.state().contains("schedule_errors=1"));assertTrue(q.state().contains("writer_errors=1"));
    }
    @Test public void clearAndAllCriticalOverflowAreExplicit() {
        List<Runnable> scheduled=new ArrayList<>(); DiagnosticWriteQueue q=new DiagnosticWriteQueue(2,1,scheduled::add);
        q.submit(true,()->{});q.submit(true,()->{});q.submit(true,()->{});
        assertTrue(q.state().contains("critical_dropped=1"));q.clear();
        assertTrue(q.state().contains("cleared=2")); scheduled.get(0).run();
        q.submit(false,()->{}); assertEquals(2,scheduled.size());
    }
}
