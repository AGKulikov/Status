/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Passive capture continues after boot PLAY; never binds to or starts Music. */
public final class MusicWaveDiagnostics {
    private static volatile Context context;
    private static volatile Process process;
    private static final AtomicLong generation=new AtomicLong();
    private static final AtomicBoolean running=new AtomicBoolean();
    private static volatile long nextStart, dropped, captured, lastNativeElapsed=-1;
    private static volatile String status="not_initialized";
    private static final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private static long rateWindow;
    private static int rateCount;
    private static final Pattern PAYLOAD=Pattern.compile(
        "v=1 pid=[0-9]+ elapsed=[0-9]+ seq=[0-9]+( wall=[0-9]+)? event="
        + "(OFFLINEWAVE_START|OFFLINEWAVE_PREFETCH_TRIGGER|OFFLINEWAVE_ERROR|ONLINE_RETRY_FAILED|ONLINE_RESULT_SELECTED|ONLINE_MIGRATION_STARTED|OFFLINE_FALLBACK|ONLINE_WAIT|PREFETCH_PROBE_FAILED)"
        + " reason=(UNKNOWN|UNSPECIFIED_NATIVE_ERROR|Prolongation|SkipToOffline|CreateQueueWithSeeds|CreateQueueWithPassiveSession|LOW_RATIO|PREFETCH_FAILED|ABOVE_THRESHOLD|OFFLINE_UNAVAILABLE|RECOMMENDER_ERROR|TIMEOUT|KEEP_OFFLINE_RESULT|ONLINE_PROLONGATION_COMPLETED|ONLINE_SESSION_AVAILABLE|ONLINE_PROLONGATION_FAILED|OFFLINE_PROLONGATION_FAILED)"
        + "( decision=(SWITCHED|KEPT_ONLINE|UNKNOWN))?"
        + "( thresholdType=(RATIO|PREFETCHED_DURATION_MS|UNKNOWN))?"
        + "( (prefetchRatio|threshold|offlineBatchWaitMs)=[0-9]+)*"
        + "( error_kind=(UNKNOWN|DNS|TIMEOUT|CONNECT|TLS|CANCELLED))?");
    private MusicWaveDiagnostics() {}
    public static void initialize(Context owner){context=owner.getApplicationContext();}
    public static String state(){return "state="+status+", running="+running.get()+", captured="+captured
            +", rate_dropped="+dropped+", last_native_elapsed_ms="+lastNativeElapsed
            +", scope=native_music_hooks, tail_lines=2000, ring_rotation_loss_possible=true"
            +", transport=logcat_not_authenticated, no_events_is_not_online=true";}
    static void disabled(){generation.incrementAndGet();Process owner=process;if(owner!=null)owner.destroy();status="debug_disabled";}
    static void poll(){
        Context owner=context;
        if(owner==null || !DiagnosticJournal.isEnabled() || SystemClock.uptimeMillis()<nextStart)return;
        nextStart=SystemClock.uptimeMillis()+15000;
        if(running.get())return;
        if(owner.checkSelfPermission("android.permission.READ_LOGS")!=PackageManager.PERMISSION_GRANTED){status="read_logs_missing";return;}
        if(!running.compareAndSet(false,true))return;
        long expected=generation.incrementAndGet();status="starting";
        new Thread(()->read(expected),"diagnostics-music-wave").start();
    }
    static String parse(String line){
        if(line==null || line.length()>1000)return null;
        int at=line.indexOf("NatroMusicWave:");if(at<0)return null;
        String payload=line.substring(at+15).trim();
        return PAYLOAD.matcher(payload).matches()?payload:null;
    }
    /** Exact production ingestion path, also exercised by the ZIP export test. */
    static synchronized boolean captureLine(String line,long expected){
        if(expected!=generation.get() || !DiagnosticJournal.isEnabled())return false;
        String payload=parse(line);if(payload==null || !seen.add(payload))return false;
        if(seen.size()>2048)seen.remove(seen.iterator().next());
        long now=SystemClock.uptimeMillis();
        if(now-rateWindow>=1000 || now<rateWindow){rateWindow=now;rateCount=0;}
        if(++rateCount>20){dropped++;return false;}
        int start=payload.indexOf(" elapsed=")+9;
        try{lastNativeElapsed=Long.parseLong(payload.substring(start,payload.indexOf(' ',start)));}
        catch(NumberFormatException ignored){return false;}
        captured++;
        DiagnosticJournal.infoAsync("music-wave",payload);
        CausalDiagnostics.observe("music-wave",payload);
        return true;
    }
    static long captureGeneration(){return generation.get();}
    private static void read(long expected){
        Process owned=null;
        try{
            owned=new ProcessBuilder("logcat","-b","main","-v","epoch","-T","2000","NatroMusicWave:I","*:S").redirectErrorStream(true).start();
            process=owned;if(generation.get()!=expected || !DiagnosticJournal.isEnabled())return;
            status="listening";
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.UTF_8))){
                String line;
                while(expected==generation.get() && DiagnosticJournal.isEnabled() && (line=reader.readLine())!=null)
                    captureLine(line,expected);
            }
            if(generation.get()==expected)status="stream_closed";
        }catch(IOException|RuntimeException error){if(generation.get()==expected)status="reader_failed:"+error.getClass().getSimpleName();}
        finally{if(owned!=null)owned.destroy();process=null;running.set(false);}
    }
}
