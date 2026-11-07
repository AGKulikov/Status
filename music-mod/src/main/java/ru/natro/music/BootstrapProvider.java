/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.music;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Same-UID preparation. Modern Media3 uses a native binding, without legacy onGetRoot. */
public final class BootstrapProvider extends ContentProvider {
    private static final String CLIENT = "ru.natro.statuswidget";
    private static final String PLAYER = "ru.yandex.music";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Connection current;
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String id, Bundle data) {
        Context context = getContext();
        if (!authorized(context, Binder.getCallingUid())) throw new SecurityException("Natro signature required");
        Bundle answer = new Bundle(); answer.putInt("protocol", 1);
        if (!"prepare_v1".equals(method) || id == null || id.length() > 80 || data == null) return answer;
        long deadline = data.getLong("deadline");
        long remaining = deadline - SystemClock.elapsedRealtime();
        ResultReceiver callback = data.getParcelable("callback");
        if (callback == null || remaining <= 0 || remaining > 20000) return answer;
        main.post(() -> {
            if (SystemClock.elapsedRealtime() >= deadline) return;
            if (current != null) current.close("superseded");
            current = new Connection(context, id, deadline, callback);
            current.progressEnabled=data.getBoolean("progress_v2");
            current.start();
        });
        answer.putBoolean("accepted", true);
        return answer;
    }
    static boolean authorized(Context context, int uid) {
        try {
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(CLIENT)
                    && pm.checkSignatures(PLAYER, CLIENT) == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException e) { return false; }
    }
    /** Own-UID discovery: named native endpoints first; no external-caller permission filter. */
    static List<ComponentName> resolveEndpoints(PackageManager pm) {
        LinkedHashSet<ComponentName> endpoints=new LinkedHashSet<>();
        for(String name:new String[]{
            "com.yandex.music.shared.media.session.session.Media3LibraryService",
            "com.yandex.music.shared.media.session.session.Media3SessionService",
            "ru.yandex.music.common.media.mediabrowser.MusicBrowserService"}) {
            ComponentName component=new ComponentName(PLAYER,name);
            try { if(eligible(pm,pm.getServiceInfo(component,0)))endpoints.add(component); }
            catch(PackageManager.NameNotFoundException ignored){}
        }
        List<ResolveInfo> found=pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService").setPackage(PLAYER),0);
        if(found!=null)for(ResolveInfo item:found) {
            ServiceInfo service=item==null?null:item.serviceInfo;
            if(eligible(pm,service))endpoints.add(new ComponentName(service.packageName,service.name));
        }
        return new ArrayList<>(endpoints);
    }
    private static boolean eligible(PackageManager pm,ServiceInfo service) {
        if(service==null || !PLAYER.equals(service.packageName) || service.name==null)return false;
        int override=pm.getComponentEnabledSetting(new ComponentName(service.packageName,service.name));
        return override==PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (override==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && service.enabled);
    }
    final class Connection extends MediaBrowser.ConnectionCallback {
        final Context context; final String id; final long deadline; final ResultReceiver callback;
        MediaBrowser browser; boolean closed, delivered, progressEnabled, nativeProbePending;
        ServiceConnection nativeBinding, sessionBinding;
        ComponentName nativeComponent;
        String nativeStage="not_started";
        int generation, failures; String endpointEvidence="not_queried";
        Connection(Context c, String id, long deadline, ResultReceiver callback) {
            context=c;this.id=id;this.deadline=deadline;this.callback=callback;
        }
        void start() {
            if (closed) return;
            if (SystemClock.elapsedRealtime() >= deadline) { close("endpoint_timeout:"+endpointEvidence); return; }
            try {
                List<ComponentName> endpoints=resolveEndpoints(context.getPackageManager());
                endpointEvidence="count="+endpoints.size()+",candidates="+endpoints;
                if(endpoints.isEmpty()){main.postDelayed(this::start,250);return;}
                ComponentName selected=endpoints.get(failures % endpoints.size());
                final int attempt=++generation;
                if(selected.getClassName().startsWith("com.yandex.music.shared.media.session.session.Media3")) {
                    bindNative(selected,attempt);
                    return;
                }
                browser=new MediaBrowser(context,selected,new MediaBrowser.ConnectionCallback(){
                    @Override public void onConnected(){if(attempt==generation)Connection.this.onConnected();}
                    @Override public void onConnectionFailed(){if(attempt==generation)Connection.this.onConnectionFailed();}
                    @Override public void onConnectionSuspended(){if(attempt==generation)Connection.this.onConnectionSuspended();}
                },null);
                browser.connect();
                main.postDelayed(()->{if(!closed && !delivered && attempt==generation)
                    retryBeforeToken("connection_timeout");},Math.min(3000,Math.max(1,deadline-SystemClock.elapsedRealtime())));
            }catch(RuntimeException e){close("connect_"+e.getClass().getSimpleName());}
        }
        @Override public void onConnected() {
            if(closed || SystemClock.elapsedRealtime()>=deadline){close("late_connection");return;}
            try {
                deliver(browser.getSessionToken());
            }catch(RuntimeException e){close("token_unavailable");}
        }
        void progress(String stage) {
            nativeStage=stage;
            if(!progressEnabled || closed) return;
            Bundle result=new Bundle(); result.putString("id",id); result.putString("stage",stage);
            result.putInt("protocol",1); callback.send(34200,result);
        }
        void bindNative(ComponentName selected,int attempt) {
            nativeComponent=selected;
            progress("native_bind:"+selected.getClassName());
            ServiceConnection binding=new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                    if(closed || generation!=attempt) return;
                    progress("native_bound"); probeNative(attempt);
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    if(!closed && generation==attempt) close("native_disconnected");
                }
                @Override public void onNullBinding(ComponentName name) {
                    if(!closed && generation==attempt) retryBeforeToken("native_null_binding");
                }
                @Override public void onBindingDied(ComponentName name) {
                    if(!closed && generation==attempt) retryBeforeToken("native_binding_died");
                }
            };
            nativeBinding=binding;
            // The native binder does not synchronously request a library root. Keep the service
            // bound while its asynchronous initialization publishes the real player session.
            boolean bound=context.bindService(new Intent("androidx.media3.session.MediaSessionService")
                    .setComponent(selected),binding,Context.BIND_AUTO_CREATE);
            if(!bound) { nativeBinding=null; retryBeforeToken("native_bind_rejected"); return; }
            main.postDelayed(()->{
                if(!closed && !delivered && generation==attempt)
                    close("native_timeout:last="+nativeStage+","+endpointEvidence);
            },Math.max(1,deadline-SystemClock.elapsedRealtime()));
        }
        void probeNative(int attempt) {
            if(closed || delivered || generation!=attempt || nativeProbePending) return;
            if(SystemClock.elapsedRealtime()>=deadline) { close("native_timeout:last="+nativeStage); return; }
            try {
                NativeMusicSession ready=NativeMusicSession.find(context);
                if(ready==null) {
                    if(!"native_session_wait".equals(nativeStage)) progress("native_session_wait");
                    main.postDelayed(()->probeNative(attempt),100); return;
                }
                nativeProbePending=true;
                progress("native_looper_probe");
                if(!ready.handler.post(()->main.post(()->{
                    if(closed || delivered || generation!=attempt) return;
                    nativeProbePending=false;
                    if(SystemClock.elapsedRealtime()>=deadline) { close("native_late_ack"); return; }
                    try {
                        if(!ready.current()) { probeNative(attempt); return; }
                        progress("native_looper_ready"); attachNativeSession(ready,attempt);
                    }catch(ReflectiveOperationException | RuntimeException error) {
                        close("native_contract_"+error.getClass().getSimpleName());
                    }
                }))) { nativeProbePending=false; close("native_looper_stopped"); }
            }catch(ReflectiveOperationException | RuntimeException error) {
                close("native_contract_"+error.getClass().getSimpleName());
            }
        }
        void attachNativeSession(NativeMusicSession ready,int attempt) {
            if(closed || delivered || generation!=attempt) return;
            progress("native_session_attach");
            ServiceConnection binding=new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                    if(closed || delivered || generation!=attempt) return;
                    try {
                        if(!ready.current()) { close("native_session_replaced"); return; }
                        deliver(ready.token);
                    }catch(ReflectiveOperationException | RuntimeException error) {
                        close("native_contract_"+error.getClass().getSimpleName());
                    }
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    if(!closed && generation==attempt) close("native_session_disconnected");
                }
                @Override public void onNullBinding(ComponentName name) {
                    if(!closed && generation==attempt) close("native_session_attach_rejected");
                }
                @Override public void onBindingDied(ComponentName name) {
                    if(!closed && generation==attempt) close("native_session_binding_died");
                }
            };
            sessionBinding=binding;
            // This raw bind, after readiness, lets MediaSessionService attach its existing
            // session for notification/foreground lifecycle. It never sends browser.connect
            // and therefore never enters the blocking legacy onGetRoot/catalog callbacks.
            if(!context.bindService(new Intent("android.media.browse.MediaBrowserService")
                    .setComponent(nativeComponent),binding,Context.BIND_AUTO_CREATE)) {
                sessionBinding=null; close("native_session_bind_rejected");
            }
        }
        void deliver(android.media.session.MediaSession.Token token) {
            if(closed || delivered || SystemClock.elapsedRealtime()>=deadline || token==null) {
                close("invalid_or_late_token"); return;
            }
            Bundle result=new Bundle();result.putString("id",id);result.putString("stage","ready");
            result.putInt("protocol",1);result.putParcelable("token",token);
            delivered=true; callback.send(34201,result);
            main.postDelayed(()->close("lease_complete"),60000);
        }
        @Override public void onConnectionFailed(){retryBeforeToken("connection_failed");}
        @Override public void onConnectionSuspended(){retryBeforeToken("connection_suspended");}
        void retryBeforeToken(String reason){
            if(closed)return;
            if(delivered || SystemClock.elapsedRealtime()>=deadline){close(reason);return;}
            // Cold Application startup can reject the first bind. Retry only preparation,
            // within the original deadline; a delivered token never authorizes another PLAY.
            generation++;failures++;
            if(browser!=null)try{browser.disconnect();}catch(RuntimeException ignored){}
            browser=null;
            unbindNative(); nativeProbePending=false;
            main.postDelayed(this::start,500);
        }
        void unbindNative() {
            ServiceConnection binding=nativeBinding; nativeBinding=null;
            if(binding!=null)try{context.unbindService(binding);}catch(RuntimeException ignored){}
            binding=sessionBinding; sessionBinding=null;
            if(binding!=null)try{context.unbindService(binding);}catch(RuntimeException ignored){}
        }
        void close(String reason){
            if(closed)return;closed=true;
            if(browser!=null)try{browser.disconnect();}catch(RuntimeException ignored){}
            unbindNative();
            if(current==this)current=null;
            Bundle result=new Bundle();result.putString("id",id);result.putString("stage",reason);
            result.putInt("protocol",1);callback.send(34202,result);
        }
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
