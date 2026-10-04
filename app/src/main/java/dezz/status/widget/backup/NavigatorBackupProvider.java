/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import dezz.status.widget.navigation.NavigationBridgeCallerVerifier;

/** Runs in :backup. The wake broadcast carries no authority or data; the caller is checked here. */
public final class NavigatorBackupProvider extends ContentProvider {
    public static final String ACTION="ru.natro.navigation.BACKUP_REQUEST_V1";
    private static final Object LOCK=new Object();
    private static Bundle pending,result;
    private static CountDownLatch reply;
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String argument,Bundle extras) {
        Context context=getContext();
        if(context==null||!NavigationBridgeCallerVerifier.isTrustedNavigator(context,Binder.getCallingUid()))throw new SecurityException("Navigator identity mismatch");
        synchronized(LOCK) {
            if("request".equals(method))return pending==null?new Bundle():new Bundle(pending);
            if("reply".equals(method)&&pending!=null&&extras!=null&&pending.getString("request").equals(extras.getString("request"))) {
                result=new Bundle(extras);reply.countDown();return new Bundle();
            }
        }
        if("decision".equals(method)) {
            Bundle response=new Bundle();response.putString("decision",decision(context,argument));return response;
        }
        return new Bundle();
    }
    private static String decision(Context context,String token) {
        if(token==null||!token.matches("[a-f0-9-]{36}"))return "rollback";
        try {
            File control=BackupMaintenance.control(context),journal=new File(control,"restore/journal.json");
            if(journal.isFile()) {
                JSONObject value=new JSONObject(new String(BackupFiles.read(journal,16*1024*1024),StandardCharsets.UTF_8));
                JSONObject metadata=value.optJSONObject("replacement");
                if(metadata!=null&&token.equals(metadata.optString("navigatorToken")))
                    return "COMMITTED".equals(value.getString("phase"))?"commit":"pending";
            }
            // A live maintenance owner may be constructing the snapshot before its restore journal.
            try(RandomAccessFile file=new RandomAccessFile(new File(control,"maintenance.lock"),"rw")) {
                java.nio.channels.FileLock lock=null;
                try {lock=file.getChannel().tryLock();}catch(java.nio.channels.OverlappingFileLockException live){return "pending";}
                if(lock==null)return "pending";
                lock.release();
            }
        }catch(Exception failure){return "pending";} // Never guess a commit after damaged metadata.
        return "rollback";
    }
    public static Bundle request(Context context,String operation,String token,String values)throws Exception {
        if(!BackupMaintenance.isMaintenanceProcess())throw new IOException("Navigator backup must run in maintenance");
        CountDownLatch latch=new CountDownLatch(1);
        synchronized(LOCK) {
            if(pending!=null)throw new IOException("Navigator operation is already running");
            pending=new Bundle();pending.putString("request",UUID.randomUUID().toString());
            pending.putString("operation",operation);pending.putString("token",token);
            if(values!=null)pending.putString("values",values);
            reply=latch;result=null;
        }
        try {
            context.sendBroadcast(new Intent(ACTION).setPackage("ru.yandex.yandexnavi"));
            if(!latch.await(12,TimeUnit.SECONDS))throw new IOException("Откройте Навигатор из пары Natro 3.0.2: его данные пока недоступны");
            synchronized(LOCK) {
                if(result==null||!result.getBoolean("ok"))throw new IOException("Навигатор не подтвердил операцию копии");
                return new Bundle(result);
            }
        }finally{synchronized(LOCK){pending=null;result=null;reply=null;}}
    }
    @Override public Cursor query(Uri u,String[]p,String s,String[]a,String o){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[]a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[]a){throw new UnsupportedOperationException();}
}
