import android.content.Context;
import dezz.status.widget.adb.AdbConsoleSession;
import dezz.status.widget.adb.AdbShellResult;
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
        AdbTransport.reset();AdbTransport.root=true;
        try(AdbConsoleSession session=new AdbConsoleSession(new Context())) {
            session.connect();session.daemonRoot(true);
            check(AdbTransport.services.isEmpty(),"already-root must not restart adbd");
            check(session.endpoint().contains("UID=0"),"preflight records actual UID");
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
    static void partialFailure() throws Exception {
        for (int mode=0;mode<4;mode++) {
            AdbTransport.reset();
            try (AdbConsoleSession session=new AdbConsoleSession(new Context())) {
                session.connect();
                AdbTransport.partialOutput="QCONN\r\nПривет 🌍";
                AdbTransport.failAfterOutput=mode!=2;
                if(mode!=0) AdbTransport.afterOutput=()->session.cancel("Таймаут 60 секунд");
                if(mode==3) AdbTransport.afterOutput=()->{
                    try { Thread.sleep(1200); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                };
                CountDownLatch done=new CountDownLatch(1);
                AtomicReference<Exception> failure=new AtomicReference<>();
                AdbConsoleSession.Operation operation=s->{s.command("probe");throw new AssertionError("failure swallowed");};
                AdbConsoleSession.Listener listener=e->{failure.set(e);done.countDown();};
                if(mode==3) {
                    java.lang.reflect.Method submit=AdbConsoleSession.class.getDeclaredMethod("submitBounded",
                            AdbConsoleSession.Operation.class,AdbConsoleSession.Listener.class,int.class);
                    submit.setAccessible(true);submit.invoke(session,operation,listener,1);
                } else session.submit(operation,listener);
                check(done.await(2,TimeUnit.SECONDS),"failure delivered");
                check(failure.get() instanceof AdbShellResult.Failure,"typed output survives submit cancellation wrapper");
                AdbShellResult.Failure f=(AdbShellResult.Failure)failure.get();
                check(f.partial.output.equals("QCONN\r\nПривет 🌍"),"fragmented UTF8 preserved without framing");
                check(f.partial.exitCode==null&&!f.partial.success(),"partial is never success");
                check(f.getMessage().contains("Connect")&&f.getCause()!=null,"reason, recovery and cause preserved");
                if(mode!=0)check(f.getMessage().contains(mode==3?"Таймаут 1 секунд":"Таймаут 60 секунд"),"cancel reason preserved");
                check(!session.connected()&&!session.busy()&&AdbTransport.commands.size()==1,"disconnect without replay");
                AdbTransport.partialOutput=null;AdbTransport.afterOutput=null;AdbTransport.failAfterOutput=false;
                CountDownLatch reconnected=new CountDownLatch(1);
                AtomicReference<Exception> reconnectFailure=new AtomicReference<>();
                session.submit(s->{s.connect();check(s.command("id -u").success(),"explicit reconnect works");},
                        e->{reconnectFailure.set(e);reconnected.countDown();});
                check(reconnected.await(2,TimeUnit.SECONDS)&&reconnectFailure.get()==null,"new Connect operation clears cancellation");
            }
        }
        AdbShellResult capture=new AdbShellResult();
        byte[] huge=new byte[AdbShellResult.OUTPUT_LIMIT+4096];java.util.Arrays.fill(huge,(byte)'x');capture.accept(huge);
        AdbShellResult.Failure f=capture.failure("timeout",new Exception());
        check(f.partial.truncated&&f.partial.output.length()<AdbShellResult.OUTPUT_LIMIT+200,"partial output bounded");
        check(!f.getMessage().contains("дочитана до завершения"),"no false completion for truncated output");
        check(new AdbShellResult().failure(null,null).getMessage().contains("нет полученных данных"),"empty output explicit");
    }
    static void timedCapture() throws Exception {
        AdbTransport.reset();
        try(AdbConsoleSession session=new AdbConsoleSession(new Context())) {
            session.connect();
            check(session.commandTimed("id -u",128*1024,15_000L).success(),"timed shell result");
            check(AdbTransport.lastTimeout==15_000L,"explicit deadline reaches transport");
            try{session.commandTimed("id",128*1024,0);throw new AssertionError("unbounded allowed");}
            catch(IllegalArgumentException expected){}
        }
        Throwable interrupted=new InterruptedException();
        interrupted.addSuppressed(new java.io.IOException("ADB timeout at shell; command not repeated"));
        java.lang.reflect.Method method=AdbConsoleSession.class.getDeclaredMethod("failureReason",Throwable.class);
        method.setAccessible(true);String reason=(String)method.invoke(null,interrupted);
        check(reason.contains("InterruptedException")&&reason.contains("ADB timeout at shell"),"suppressed timeout retained");
    }
    public static void main(String[] args) throws Exception { root();cancellation();partialFailure();timedCapture();System.out.println("ADB session: PASS"); }
}
