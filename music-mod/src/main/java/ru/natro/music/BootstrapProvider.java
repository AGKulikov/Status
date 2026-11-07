/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.music;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Same-UID browser bootstrap. Never issues PLAY or opens an Activity. */
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
    final class Connection extends MediaBrowser.ConnectionCallback {
        final Context context; final String id; final long deadline; final ResultReceiver callback;
        MediaBrowser browser; boolean closed, delivered;
        Connection(Context c, String id, long deadline, ResultReceiver callback) {
            context=c;this.id=id;this.deadline=deadline;this.callback=callback;
        }
        void start() {
            if (closed) return;
            if (SystemClock.elapsedRealtime() >= deadline) { close("endpoint_timeout"); return; }
            try {
                List<ResolveInfo> found=context.getPackageManager().queryIntentServices(
                        new Intent("android.media.browse.MediaBrowserService").setPackage(PLAYER),0);
                List<ComponentName> endpoints=new ArrayList<>();
                for(ResolveInfo item:found) {
                    ServiceInfo s=item.serviceInfo;
                    if(s!=null && PLAYER.equals(s.packageName) && s.enabled
                            && (s.permission==null || s.permission.isEmpty()))
                        endpoints.add(new ComponentName(s.packageName,s.name));
                }
                // The native Application may still be switching legacy/Media3 components.
                if(endpoints.size()!=1){main.postDelayed(this::start,250);return;}
                browser=new MediaBrowser(context,endpoints.get(0),this,null);
                browser.connect();
                main.postDelayed(()->{if(!delivered)close("connection_timeout");},
                        Math.max(1,deadline-SystemClock.elapsedRealtime()));
            }catch(RuntimeException e){close("connect_"+e.getClass().getSimpleName());}
        }
        @Override public void onConnected() {
            if(closed || SystemClock.elapsedRealtime()>=deadline){close("late_connection");return;}
            try {
                Bundle result=new Bundle();result.putString("id",id);result.putString("stage","ready");
                result.putInt("protocol",1);result.putParcelable("token",browser.getSessionToken());
                delivered=true;callback.send(34201,result);
                // Keep the native session alive while Natro restores playback; no PLAY here.
                main.postDelayed(()->close("lease_complete"),60000);
            }catch(RuntimeException e){close("token_unavailable");}
        }
        @Override public void onConnectionFailed(){retryBeforeToken("connection_failed");}
        @Override public void onConnectionSuspended(){retryBeforeToken("connection_suspended");}
        void retryBeforeToken(String reason){
            if(closed)return;
            if(delivered || SystemClock.elapsedRealtime()>=deadline){close(reason);return;}
            // Cold Application startup can reject the first bind. Retry only preparation,
            // within the original deadline; a delivered token never authorizes another PLAY.
            if(browser!=null)try{browser.disconnect();}catch(RuntimeException ignored){}
            browser=null;
            main.postDelayed(this::start,500);
        }
        void close(String reason){
            if(closed)return;closed=true;
            if(browser!=null)try{browser.disconnect();}catch(RuntimeException ignored){}
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
