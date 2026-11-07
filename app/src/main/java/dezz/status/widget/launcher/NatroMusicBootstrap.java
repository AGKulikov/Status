/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.content.ContentProviderClient;
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
    static boolean blocksColdPlay() {
        Request request = current;
        // A visible native session is not proof that onGetRoot has returned.
        // Expiry is not readiness either: stay closed until a fresh request returns a token.
        return request != null && !request.delivered && request.permit.getAsBoolean();
    }
    static boolean available(Context context){
        try {
            PackageManager pm=context.getPackageManager();
            ProviderInfo p=pm.resolveContentProvider(AUTHORITY,0);
            return p!=null && p.enabled && p.exported && "ru.yandex.music".equals(p.packageName)
                    && pm.checkSignatures(context.getPackageName(),p.packageName)==PackageManager.SIGNATURE_MATCH;
        }catch(RuntimeException e){return false;}
    }
    static synchronized String request(Context context,BooleanSupplier permit){
        if (!permit.getAsBoolean()) return "permit_revoked";
        Request previous=current;
        if(previous!=null && previous.allowed()) return "bootstrap_scheduled";
        int attempt=previous!=null && previous.permit.getAsBoolean()?previous.attempt+1:1;
        if(attempt>3) return "attempt_limit";
        // A blocked Binder cannot be interrupted safely. Do not queue expired requests behind it.
        if(IPC.getActiveCount()!=0 || !IPC.getQueue().isEmpty()) return "worker_busy";
        if(previous!=null)previous.finish("superseded");
        Request next=new Request(context.getApplicationContext(),permit,attempt);current=next;
        try { IPC.execute(next::prepare); }
        catch(RuntimeException busy){next.finish("worker_busy");return "schedule_rejected";}
        MAIN.postDelayed(()->next.finish("deadline"),45000);
        return "bootstrap_scheduled";
    }
    private static final class Request {
        final Context context; final BooleanSupplier permit; final String id=UUID.randomUUID().toString();
        final long started=SystemClock.elapsedRealtime();
        final long deadline=started+45000;
        final int attempt;
        volatile boolean active=true, delivered;
        Request(Context c,BooleanSupplier p,int attempt){context=c;permit=p;this.attempt=attempt;}
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
                                    context,Request.this::allowed,()->MediaResumeCommand.playBrowserSession(context,token,"ru.yandex.music"));
                            finish(trace==null?"play_cancelled":"play_"+trace.result+":"+trace.detail);
                        });
                    }catch(RuntimeException e){finish("callback_"+e.getClass().getSimpleName());}
                }
            };
            Parcel parcel=Parcel.obtain();
            ContentProviderClient client=null;
            try {
                callback.writeToParcel(parcel,0);parcel.setDataPosition(0);
                journal("acquire_started");
                client=context.getContentResolver().acquireUnstableContentProviderClient(Uri.parse("content://"+AUTHORITY));
                if(!allowed()){finish("cancelled_after_acquire");return;}
                if(client==null){finish("provider_unavailable");return;}
                journal("acquired");
                // Provider acquisition includes cold Application startup. Start its short
                // preparation budget only now; never extend the overall request/boot permit.
                Bundle args=new Bundle();args.putLong("deadline",Math.min(deadline,SystemClock.elapsedRealtime()+15000));
                args.putParcelable("callback",ResultReceiver.CREATOR.createFromParcel(parcel));
                journal("prepare");
                Bundle response=client.call("prepare_v1",id,args);
                if(response==null || !response.getBoolean("accepted"))finish("prepare_rejected");
                else journal("prepare_accepted");
            }catch(RemoteException | RuntimeException e){finish("prepare_"+e.getClass().getSimpleName());}
            finally{parcel.recycle();if(client!=null)client.close();}
        }
        void journal(String stage){DiagnosticJournal.infoAsync("music-bootstrap","request="+id+", attempt="+attempt+", elapsed_ms="+(SystemClock.elapsedRealtime()-started)+", stage="+stage);}
        synchronized void finish(String reason){if(!active)return;active=false;journal(reason);}
    }
}
