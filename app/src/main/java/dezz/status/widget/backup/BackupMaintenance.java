/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import android.app.ActivityManager;
import android.app.Application;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Explicit, same-UID maintenance session. No application writer may run during file replacement. */
public final class BackupMaintenance implements AutoCloseable {
    private static final String ACTION = "ru.natro.statuswidget.BACKUP_FREEZE";
    private static volatile boolean restoredSession;
    private final Context context;
    private final RandomAccessFile lockFile;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private BackupMaintenance(Context context, RandomAccessFile lockFile, FileLock lock) {
        this.context=context.getApplicationContext();this.lockFile=lockFile;this.channel=lockFile.getChannel();this.lock=lock;
    }
    public static boolean isMaintenanceProcess() {
        String name=Application.getProcessName();return name!=null&&name.endsWith(":backup");
    }
    public static File control(Context context) {
        return new File(context.createDeviceProtectedStorageContext().getNoBackupFilesDir(),"natro-backup");
    }
    public static boolean suppressAutomaticPlayback() { return restoredSession; }

    /** Called before preferences, receivers, controllers and services initialize in each process. */
    public static boolean prepareApplication(Context context) {
        if(isMaintenanceProcess())return false;
        File control=control(context), lease=new File(control,"lease.json");
        try {
            BackupFiles.directory(control);
            try(RandomAccessFile file=new RandomAccessFile(new File(control,"maintenance.lock"),"rw")) {
                FileLock test;
                try {test=file.getChannel().tryLock();}catch(OverlappingFileLockException busy){test=null;}
                if(test==null) {android.os.Process.killProcess(android.os.Process.myPid());return false;}
                try {
                    File journal=new File(control,"restore/journal.json");
                    if(journal.isFile())new BackupStorage(context).transaction().recover();
                    if(lease.exists())BackupFiles.delete(lease);
                    File restored=new File(control,"restored-session");
                    if(restored.isFile()) {
                        restoredSession=true;
                        // Kept until the next explicit maintenance session acknowledges the restore.
                        // Both main and HUD must see the marker; one process must not consume it for the other.
                    }
                } finally {test.release();}
            }
            BroadcastReceiver freeze=new BroadcastReceiver() {
                @Override public void onReceive(Context receiverContext,Intent intent) {
                    if(intent.getIntExtra("pid",-1)!=android.os.Process.myPid())return;
                    PendingResult result=goAsync();
                    new Thread(()->{
                        try {
                            JSONObject leaseState=new JSONObject(new String(BackupFiles.read(lease,4096),StandardCharsets.UTF_8));
                            if(!leaseState.getString("token").equals(intent.getStringExtra("token")))throw new IOException("Invalid maintenance token");
                            flushPreferences(receiverContext);
                            result.setResultCode(android.app.Activity.RESULT_OK);result.finish();
                            android.os.Process.killProcess(android.os.Process.myPid());
                        } catch(Exception error) {result.setResultCode(android.app.Activity.RESULT_CANCELED);result.finish();}
                    },"backup-flush").start();
                }
            };
            String permission=context.getPackageName()+".permission.BACKUP_INTERNAL";
            context.registerReceiver(freeze,new IntentFilter(ACTION),permission,new Handler(Looper.getMainLooper()));
            return true;
        } catch(Exception failure) {
            // A damaged transaction cannot fall through to services reading a mixed generation.
            Intent recovery=new Intent(context,dezz.status.widget.FullBackupActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("recovery",true);
            context.startActivity(recovery);
            android.os.Process.killProcess(android.os.Process.myPid());return false;
        }
    }

    public static BackupMaintenance begin(Context context) throws Exception {
        if(!isMaintenanceProcess())throw new IOException("Backup requires the maintenance process");
        File root=control(context);BackupFiles.directory(root);
        RandomAccessFile file=new RandomAccessFile(new File(root,"maintenance.lock"),"rw");
        FileLock lock;
        try {lock=file.getChannel().tryLock();}catch(Exception busy){file.close();throw busy;}
        if(lock==null){file.close();throw new IOException("Другая операция с копией ещё выполняется");}
        BackupMaintenance session=new BackupMaintenance(context,file,lock);
        try {
            String token=UUID.randomUUID().toString();
            BackupFiles.atomicWrite(new File(root,"lease.json"),new JSONObject().put("token",token)
                    .put("pid",android.os.Process.myPid()).toString().getBytes(StandardCharsets.UTF_8));
            ActivityManager manager=context.getSystemService(ActivityManager.class);
            if(manager==null)throw new IOException("Cannot inspect application processes");
            List<ActivityManager.RunningAppProcessInfo> running=manager.getRunningAppProcesses();
            if(running==null)throw new IOException("Cannot inspect application processes");
            for(ActivityManager.RunningAppProcessInfo process:running) {
                if(process.uid!=android.os.Process.myUid()||process.pid==android.os.Process.myPid())continue;
                if(process.processName==null)throw new IOException("Unknown application process");
                if(!process.processName.equals(context.getPackageName())
                        &&!process.processName.startsWith(context.getPackageName()+":"))continue;
                CountDownLatch acknowledged=new CountDownLatch(1);int[] result={android.app.Activity.RESULT_CANCELED};
                Intent request=new Intent(ACTION).setPackage(context.getPackageName())
                        .putExtra("pid",process.pid).putExtra("token",token);
                context.sendOrderedBroadcast(request,context.getPackageName()+".permission.BACKUP_INTERNAL",new BroadcastReceiver(){
                    @Override public void onReceive(Context c,Intent i){result[0]=getResultCode();acknowledged.countDown();}
                },new Handler(Looper.getMainLooper()),android.app.Activity.RESULT_CANCELED,null,null);
                if(!acknowledged.await(20,TimeUnit.SECONDS)||result[0]!=android.app.Activity.RESULT_OK)
                    throw new IOException("Не удалось завершить сохранение настроек в процессе приложения");
                long deadline=SystemClock.uptimeMillis()+3000;
                while(processExists(manager,process.pid)&&SystemClock.uptimeMillis()<deadline)Thread.sleep(20);
                if(processExists(manager,process.pid))throw new IOException("Application writer is still running");
            }
            return session;
        } catch(Exception failure){session.close();throw failure;}
    }

    private static boolean processExists(ActivityManager manager,int pid) {
        List<ActivityManager.RunningAppProcessInfo> values=manager.getRunningAppProcesses();
        if(values==null)return true;
        for(ActivityManager.RunningAppProcessInfo process:values)if(process.pid==pid)return true;
        return false;
    }

    private static void flushPreferences(Context context)throws IOException {
        dezz.status.widget.RuntimeSnapshotPreferences.flushForBackup();
        for(Context storage:new Context[]{context.getApplicationContext(),context.createDeviceProtectedStorageContext()}) {
            File directory=new File(storage.getDataDir(),"shared_prefs");File[] files=directory.listFiles();
            if(files==null){if(directory.exists())throw new IOException("Cannot read preferences");continue;}
            for(File file:files)if(file.getName().endsWith(".xml")) {
                String name=file.getName().substring(0,file.getName().length()-4);
                // commit waits for earlier apply writes of this actual framework store.
                if(!storage.getSharedPreferences(name,Context.MODE_PRIVATE).edit().commit())
                    throw new IOException("Preference flush failed");
            }
        }
    }

    public static void markRestored(Context context)throws IOException {
        BackupFiles.atomicWrite(new File(control(context),"restored-session"),new byte[]{1});
        restoredSession=true;
    }
    public static void acknowledgePlayback(Context context)throws IOException {
        BackupFiles.delete(new File(control(context),"restored-session"));restoredSession=false;
    }
    @Override public void close()throws IOException {
        if(closed)return;closed=true;
        // Unfinished restore keeps its durable journal. The next startup recovers it before runtime.
        try {
            BackupTransaction transaction=new BackupStorage(context).transaction();
            if(transaction.hasUnfinishedRestore())transaction.recover();
            BackupFiles.delete(new File(control(context),"lease.json"));
        }catch(Exception failure){throw new IOException("Recovery must finish before application startup",failure);}
        finally {try{lock.release();}finally{channel.close();lockFile.close();}}
    }
}
