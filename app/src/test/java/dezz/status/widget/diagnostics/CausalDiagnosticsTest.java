/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class CausalDiagnosticsTest {
    private Context context;
    @Before public void setUp(){context=RuntimeEnvironment.getApplication();DiagnosticJournal.initializeEarly(context);DiagnosticJournal.initialize(context,true);MainThreadWatchdog.setEnabled(false);DiagnosticJournal.clear();}
    @After public void tearDown(){ActionRecorder.stop("test");ActionRecorder.awaitPendingWrites();DiagnosticJournal.setEnabled(context,false);}
    @Test public void explicitPropagationLinksAsyncChildWithoutLeakingToNextTask() throws Exception {
        CausalDiagnostics.Span parent=CausalDiagnostics.begin("va","safe",1000);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            CausalDiagnostics.Span child=worker.submit(()->parent.call(()->CausalDiagnostics.begin("shell","safe",1000))).get();
            assertEquals(parent.id,child.parent);assertEquals(parent.root,child.root);
            CausalDiagnostics.Span unrelated=worker.submit(()->CausalDiagnostics.begin("unrelated","",1000)).get();
            assertEquals("none",unrelated.parent);assertEquals(unrelated.id,unrelated.root);
            child.finish("done","");parent.finish("done","");unrelated.finish("done","");
        }finally{worker.shutdownNow();}
    }
    @Test public void overdueReportsLastPhaseOnceWithoutCancellingOperation() throws Exception {
        CausalDiagnostics.Span span=CausalDiagnostics.begin("white-bar","",500);span.stage("sdk_read_started","");
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis()+1000); // Wall changes must not be used by the deadline.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600));
        CausalDiagnostics.poll();CausalDiagnostics.poll();assertTrue(DiagnosticJournal.awaitPendingWrites());
        String text=DiagnosticJournal.tailText(1000);assertEquals(1,count(text,"stage=operation_overdue"));assertTrue(text.contains("last_stage=sdk_read_started"));
        span.finish("late_success","effect=unobserved");assertFalse(CausalDiagnostics.snapshot().contains("PENDING trace="+span.id));
    }
    @Test public void repeatInitializeDoesNotDiscardAnAcceptedQueue() {
        DiagnosticJournal.info("fixture","must-survive");DiagnosticJournal.initialize(context,true);DiagnosticJournal.awaitPendingWrites();
        assertTrue(DiagnosticJournal.tailText(100).contains("must-survive"));
    }
    @Test public void oldSpanCannotReappearAfterDebugIsDisabled() {
        CausalDiagnostics.Span span=CausalDiagnostics.begin("old","",1000);DiagnosticJournal.setEnabled(context,false);
        DiagnosticJournal.setEnabled(context,true);MainThreadWatchdog.setEnabled(false);span.stage("forbidden_old_stage","");DiagnosticJournal.awaitPendingWrites();
        assertFalse(DiagnosticJournal.tailText(1000).contains("forbidden_old_stage"));
    }
    @Test public void intentCorrelationRequiresLocallyIssuedTicketAndIsConsumedOnce() {
        CausalDiagnostics.Span root=CausalDiagnostics.begin("va","",1000);
        Intent intent=root.call(()->DiagnosticIntentTrace.attach(context,new Intent("fixture").setPackage(context.getPackageName())));
        List<String> roots=new ArrayList<>();
        DiagnosticIntentTrace.receive(intent,()->roots.add(CausalDiagnostics.current().root));
        DiagnosticIntentTrace.receive(intent,()->roots.add(CausalDiagnostics.current().root));
        assertEquals(root.root,roots.get(0));assertEquals("none",roots.get(1));
        Intent external=new Intent("fixture").setPackage("external"); assertSame(external,DiagnosticIntentTrace.attach(context,external));assertNull(external.getExtras());
    }
    @Test public void stoppedSessionRetainsAlreadyAcceptedEventsAndSecretsAreFiltered() throws Exception {
        ActionRecorder.initialize(context);ActionRecorder.start("test");ActionRecorder.record("fixture","ACCEPTED",ActionRecorder.object("token","secret-value","nested",ActionRecorder.object("password","private-value")));
        ActionRecorder.stop("done");assertTrue(ActionRecorder.awaitPendingWrites());
        String text=ActionRecorder.latestTimeline(64000);assertTrue(text.contains("ACCEPTED"));assertTrue(text.contains("SESSION_STOP"));assertFalse(text.contains("secret-value"));assertFalse(text.contains("private-value"));
    }
    @Test public void bundleIncludesIndependentHudAndPinnedLogsAndChecksums() throws Exception {
        File d=new File(context.getFilesDir(),"diagnostics");d.mkdirs();
        Files.write(new File(d,"journal-hud.log").toPath(),"1\t2\tINFO\tmap\tsession=hud, first_frame=true\n2\t3\tINFO\tpartial\ttail".getBytes(StandardCharsets.UTF_8));
        DiagnosticJournal.recordIncident("incident-state","retained-failure");DiagnosticJournal.awaitPendingWrites();
        dezz.status.widget.phone.PhoneConnectionJournal.initialize(context);dezz.status.widget.phone.PhoneConnectionJournal.append("connection","closed");
        File bundle=DiagnosticBundle.create(context);assertNotNull(bundle);
        try(ZipFile zip=new ZipFile(bundle)) {
            JSONObject manifest=new JSONObject(read(zip,"manifest.json"));assertEquals(1,manifest.getInt("schema"));
            assertTrue(read(zip,"incidents.log").contains("retained-failure"));assertTrue(read(zip,"phone.txt").contains("closed"));assertFalse(read(zip,"journal-hud.log").contains("tail"));
            JSONArray channels=manifest.getJSONArray("channels");for(int i=0;i<channels.length();i++){JSONObject c=channels.getJSONObject(i);if(c.has("sha256"))assertEquals(c.getString("sha256"),DiagnosticBundle.digest(read(zip,c.getString("name")).getBytes(StandardCharsets.UTF_8)));}
        }
    }
    @Test public void recorderProducerAndCrashPathDoNotWaitBehindDiskWriterMonitor()throws Exception{
        ActionRecorder.initialize(context);assertTrue(ActionRecorder.awaitPendingWrites());
        java.lang.reflect.Field lockField=ActionRecorder.class.getDeclaredField("DISK_LOCK");lockField.setAccessible(true);
        java.lang.reflect.Field journalLock=DiagnosticJournal.class.getDeclaredField("DISK_LOCK");journalLock.setAccessible(true);
        ExecutorService producer=Executors.newSingleThreadExecutor();
        try {
            synchronized(lockField.get(null)) {
                producer.submit(()->{ActionRecorder.start("blocked-disk");ActionRecorder.record("fixture","BEFORE_STOP",null);ActionRecorder.stop("done");}).get(2,TimeUnit.SECONDS);
            }
            assertTrue(ActionRecorder.awaitPendingWrites());assertTrue(ActionRecorder.latestTimeline(64000).contains("BEFORE_STOP"));
            synchronized(journalLock.get(null)) {
                producer.submit(()->DiagnosticJournal.recordCrash(Thread.currentThread(),new IllegalStateException("fixture"))).get(2,TimeUnit.SECONDS);
            }
            assertTrue(DiagnosticJournal.tailText(100).contains("uncaught exception"));
        }finally{producer.shutdownNow();}
    }
    @Test public void mainRotationDoesNotDiscardPinnedIncident()throws Exception{
        DiagnosticJournal.recordIncident("incident-state","pin-this-failure");assertTrue(DiagnosticJournal.awaitPendingWrites());
        File journal=new File(context.getFilesDir(),"diagnostics/journal.log");
        byte[] row=("1\t1\tINFO\tfixture\t"+new String(new char[1000]).replace('\0','x')+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(journal)){for(int i=0;i<1600;i++)out.write(row);}
        DiagnosticJournal.info("fixture","after-rotation");assertTrue(DiagnosticJournal.awaitPendingWrites());
        assertTrue(journal.length()<1500000);assertTrue(DiagnosticJournal.tailText(100).contains("after-rotation"));
        assertTrue(DiagnosticFileSnapshot.read(new File(context.getFilesDir(),"diagnostics/incidents.log"),256000).text.contains("pin-this-failure"));
    }
    private static String read(ZipFile zip,String name)throws Exception{try(InputStream in=zip.getInputStream(zip.getEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static int count(String s,String token){return s.split(java.util.regex.Pattern.quote(token),-1).length-1;}
}
