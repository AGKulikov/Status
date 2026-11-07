/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.*;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Authenticated bootstrap of the paired Music mod; playback remains guarded by Natro. */
final class NatroMusicBootstrap {
    static final String AUTHORITY="ru.yandex.music.natro.bootstrap";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor IPC=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),r->new Thread(r,"natro-music-bootstrap"),new ThreadPoolExecutor.AbortPolicy());
    private static volatile Request current;
    static boolean available(Context context){
        try {
            PackageManager pm=context.getPackageManager();
            ProviderInfo p=pm.resolveContentProvider(AUTHORITY,0);
            return p!=null && p.enabled && p.exported && "ru.yandex.music".equals(p.packageName)
                    && pm.checkSignatures(context.getPackageName(),p.packageName)==PackageManager.SIGNATURE_MATCH;
        }catch(RuntimeException e){return false;}
    }
    static synchronized String request(Context context,BooleanSupplier permit){
        if(current!=null && current.active && current.permit.getAsBoolean()) return "bootstrap_scheduled";
        Request next=new Request(context.getApplicationContext(),permit);current=next;
        try { IPC.execute(next::prepare); }
        catch(RuntimeException busy){next.finish("worker_busy");return "schedule_rejected";}
        MAIN.postDelayed(()->next.finish("deadline"),15000);
        return "bootstrap_scheduled";
    }
    private static final class Request {
        final Context context; final BooleanSupplier permit; final String id=UUID.randomUUID().toString();
        final long deadline=SystemClock.elapsedRealtime()+15000;
        volatile boolean active=true, delivered;
        Request(Context c,BooleanSupplier p){context=c;permit=p;}
        boolean allowed(){return active && current==this && permit.getAsBoolean() && SystemClock.elapsedRealtime()<deadline;}
        void prepare(){
            if(!allowed()){finish("cancelled_before_prepare");return;}
            ResultReceiver callback=new ResultReceiver(MAIN){
                @Override protected void onReceiveResult(int code,Bundle data){
                    if(!allowed() || data==null || !id.equals(data.getString("id")))return;
                    if(code!=34201){finish("remote_"+data.getString("stage"));return;}
                    if(delivered)return;delivered=true;
                    try {
                        MediaSession.Token token=data.getParcelable("token");
                        if(token==null){finish("missing_token");return;}
                        IPC.execute(()->{
                            if(!allowed()){finish("cancelled_before_play");return;}
                            MediaResumeCommand.DispatchTrace trace=MediaAutoResumeController.guardedBrowserDispatch(
                                    context,this::allowed,()->MediaResumeCommand.playBrowserSession(context,token,"ru.yandex.music"));
                            finish(trace==null?"play_cancelled":"play_"+trace.result+":"+trace.detail);
                        });
                    }catch(RuntimeException e){finish("callback_"+e.getClass().getSimpleName());}
                }
            };
            Parcel parcel=Parcel.obtain();
            try {
                callback.writeToParcel(parcel,0);parcel.setDataPosition(0);
                Bundle args=new Bundle();args.putLong("deadline",deadline);
                args.putParcelable("callback",ResultReceiver.CREATOR.createFromParcel(parcel));
                journal("prepare");
                Bundle response=context.getContentResolver().call(Uri.parse("content://"+AUTHORITY),"prepare_v1",id,args);
                if(response==null || !response.getBoolean("accepted"))finish("prepare_rejected");
            }catch(RuntimeException e){finish("prepare_"+e.getClass().getSimpleName());}
            finally{parcel.recycle();}
        }
        void journal(String stage){DiagnosticJournal.infoAsync("music-bootstrap","request="+id+", stage="+stage);}
        void finish(String reason){if(!active)return;active=false;journal(reason);}
    }
}
