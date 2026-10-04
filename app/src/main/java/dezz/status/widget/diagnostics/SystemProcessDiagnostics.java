/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
/** Passive existing-permission observer; never grants permissions, runs root or restarts a process. */
public final class SystemProcessDiagnostics {
    private static volatile Context context;
    private static volatile Process reader;
    private static final AtomicBoolean running=new AtomicBoolean();
    private static volatile long generation, nextStart, lastEvent;
    private static volatile String state="not_initialized";
    private static volatile long rateDropped;
    private SystemProcessDiagnostics(){}
    public static void initialize(Context owner){context=owner.getApplicationContext();}
    static void disabled(){generation++;Process process=reader;if(process!=null)process.destroy();state="debug_disabled";}
    static void poll(){
        Context owner=context;long now=SystemClock.uptimeMillis();
        if(owner==null||!DiagnosticJournal.isEnabled()||now<nextStart)return;
        nextStart=now+15000;
        CausalDiagnostics.observe("system-process",state());
        if(running.get())return;
        if(owner.checkSelfPermission("android.permission.READ_LOGS")!=PackageManager.PERMISSION_GRANTED){state="read_logs_missing";return;}
        if(!running.compareAndSet(false,true))return;
        final long expected=++generation;state="starting";
        new Thread(()->read(expected),"diagnostics-system-process").start();
    }
    private static void read(long expected){
        Process process=null;long rateAt=0;int count=0;
        try{
            double started=System.currentTimeMillis()/1000d;
            process=new ProcessBuilder("logcat","-b","main","-b","system","-v","epoch","-T","1","ActivityManager:I","*:S").redirectErrorStream(true).start();
            reader=process;
            if(expected!=generation||!DiagnosticJournal.isEnabled())return;
            state="listening";
            DiagnosticJournal.infoAsync("system-process","observer_started; allowlisted_process_lifecycle_only=true");
            try(BufferedReader lines=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                String line;
                while(expected==generation&&DiagnosticJournal.isEnabled()&&(line=lines.readLine())!=null){
                    String trimmed=line.trim();int space=trimmed.indexOf(' ');if(space<0)continue;
                    double at;try{at=Double.parseDouble(trimmed.substring(0,space));}catch(NumberFormatException ignored){continue;}
                    if(at<started)continue;
                    String event=SystemProcessEvent.parse(line);if(event==null)continue;
                    long now=SystemClock.uptimeMillis();if(now-rateAt>=1000){rateAt=now;count=0;}
                    if(++count>20){rateDropped++;continue;}
                    lastEvent=now;
                    DiagnosticJournal.warn("system-process",event+", source_wall_ms="+(long)(at*1000)+", source_age_ms="+(System.currentTimeMillis()-(long)(at*1000)));
                    if(!event.contains("event=process_start"))CausalDiagnostics.capture("external_process_lifecycle",false);
                }
            }
            state="stream_closed";
        }catch(IOException|RuntimeException failure){state="reader_failed:"+CausalDiagnostics.failure(failure);}
        finally{
            if(process!=null)process.destroy();reader=null;running.set(false);
            DiagnosticJournal.warn("system-process","observer_stopped state="+state+", retry_max_ms=15000");
        }
    }
    public static String state(){return "state="+state+", running="+running.get()+", last_event_age_ms="+(lastEvent==0?-1:SystemClock.uptimeMillis()-lastEvent)+", rate_dropped="+rateDropped+", scope=ActivityManager_allowlist; missing_event_is_not_proof_of_alive";}
}
