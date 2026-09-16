import android.content.Context;
import dezz.status.widget.adb.AdbConsoleSession;
import dezz.status.widget.shell.AdbTransport;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class AdbSessionReplay {
    static void check(boolean x,String why) { if(!x)throw new AssertionError(why); }
    static void root() throws Exception {
        AdbTransport.reset();
        try(AdbConsoleSession session=new AdbConsoleSession(new Context())) {
            session.connect();check(session.daemonRoot(true).contains("UID=0"),"root needs actual UID=0");
            check(AdbTransport.services.size()==1&&AdbTransport.services.get(0).equals("root:"),"one protocol request");
            check(AdbTransport.commands.stream().allMatch(s->s.contains("id -u")),"only read-only identity probe");
            session.daemonRoot(false);check(AdbTransport.services.get(1).equals("unroot:"),"real unroot service");
            check(session.endpoint().contains("UID=2000"),"unroot confirmed by shell UID");
        }
        AdbTransport.reset();AdbTransport.dropRootReply=true;
        try(AdbConsoleSession session=new AdbConsoleSession(new Context())) {
            session.connect();session.daemonRoot(true);
            check(AdbTransport.services.size()==1,"lost root reply must not resend privilege request");
        }
        AdbTransport.reset();AdbTransport.refuse=true;
        try(AdbConsoleSession session=new AdbConsoleSession(new Context())) {
            session.connect();
            try { session.daemonRoot(true);throw new AssertionError("production refusal accepted"); }
            catch(IllegalStateException expected) { check(expected.getMessage().contains("UID=2000"),"show actual refusal"); }
            check(AdbTransport.services.size()==1,"refusal not retried");
        }
    }
    static void cancellation() throws Exception {
        for(int i=0;i<100;i++) {
            AdbConsoleSession session=new AdbConsoleSession(new Context());
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
            AtomicInteger calls=new AtomicInteger();AtomicReference<Exception> failure=new AtomicReference<>();
            check(session.submit(s->{calls.incrementAndGet();started.countDown();release.await(2,TimeUnit.SECONDS);},e->{failure.set(e);done.countDown();}),"accepted");
            check(started.await(2,TimeUnit.SECONDS),"worker started");
            check(!session.submit(s->calls.incrementAndGet(),e->{}),"no unbounded command queue");
            session.close();session.close();release.countDown();
            check(done.await(2,TimeUnit.SECONDS),"completion always delivered after close");
            check(calls.get()==1&&failure.get()!=null&&!session.busy(),"no replay, cancelled result, lane released");
            check(!session.submit(s->{},e->{}),"closed owner rejects work");
        }
        for(int i=0;i<100;i++) {
            AdbConsoleSession session=new AdbConsoleSession(new Context());CountDownLatch done=new CountDownLatch(1);
            session.submit(s->{},e->done.countDown());session.close();session.close();
            check(done.await(2,TimeUnit.SECONDS),"close-before-schedule race callback");
        }
    }
    public static void main(String[] args) throws Exception { root();cancellation();System.out.println("ADB session: PASS"); }
}
