/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.content.Context;
import android.os.DropBoxManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only, bounded capture; never signals, stops or restarts the failing process. */
final class AnrEvidence {
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "natro-anr-evidence"); t.setDaemon(true); return t;
    });
    static void capture(Context context, String pkg, long eventWall) {
        if (context == null || !DiagnosticJournal.isEnabled()) return;
        if (!busy.compareAndSet(false, true)) {
            log(pkg,eventWall,"capture_busy; report_not_collected"); return;
        }
        log(pkg,eventWall,"capture_started; source=Android_DropBox; root_cause_unproven");
        worker.execute(() -> attempt(context.getApplicationContext(),pkg,eventWall,0));
    }
    private static void attempt(Context context,String pkg,long wall,int attempt) {
        boolean retry=false;
        try {
            if(!DiagnosticJournal.isEnabled())return;
            DropBoxManager box=(DropBoxManager)context.getSystemService(Context.DROPBOX_SERVICE);
            if(box==null){log(pkg,wall,"source_unavailable");return;}
            for(String tag:new String[]{"data_app_anr","system_app_anr"}) {
                long cursor=wall-5000;
                for(int count=0;count<16;count++) {
                    DropBoxManager.Entry entry=box.getNextEntry(tag,cursor);
                    if(entry==null)break;
                    try {
                        long time=entry.getTimeMillis();
                        if(time<=cursor || time>wall+120000)break;
                        cursor=time;
                        String report=entry.getText(262144);
                        if(report==null)continue;
                        boolean match=false;
                        for(String line:report.split("\n",40)) {
                            if(line.equals("Process: "+pkg)){match=true;break;}
                        }
                        if(!match)continue;
                        log(pkg,wall,"report_received; entry_wall_ms="+time+", tag="+tag
                                +", bounded_bytes=262144; may_be_truncated=true; root_cause_requires_analysis");
                        for(int offset=0;offset<report.length();offset+=10000)
                            log(pkg,wall,"report_chunk="+(offset/10000)+"\n"+report.substring(offset,Math.min(report.length(),offset+10000)));
                        log(pkg,wall,"capture_complete");return;
                    } finally {entry.close();}
                }
            }
            if(attempt<3){retry=true;worker.schedule(()->attempt(context,pkg,wall,attempt+1),5,TimeUnit.SECONDS);}
            else log(pkg,wall,"report_not_found_after_retries; stack_unavailable; cause_unknown");
        } catch(SecurityException denied){log(pkg,wall,"permission_denied; stack_unavailable; cause_unknown");}
        catch(RuntimeException failure){log(pkg,wall,"read_failed="+failure.getClass().getSimpleName());}
        finally {if(!retry)busy.set(false);}
    }
    private static void log(String pkg,long wall,String detail){
        DiagnosticJournal.warn("external-anr","package="+pkg+", event_wall_ms="+wall+", "+detail);
    }
}
