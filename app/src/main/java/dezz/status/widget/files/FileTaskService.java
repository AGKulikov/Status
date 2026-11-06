/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import dezz.status.widget.FileManagerActivity;
import java.util.concurrent.*;

/** One foreground operation survives closing the manager; process death never replays mutations. */
public final class FileTaskService extends Service {
    public interface Task {String run(FileOperations.Control control)throws Exception;}
    private static final String CHANNEL="natro_files";
    private static final int ID=8434;
    private static Task pending;
    private static volatile FileOperations.Control control;
    public static volatile boolean busy;
    public static volatile String status="Готово к работе";
    public static volatile long revision;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private long lastNotice;
    private int latestStartId;
    public static synchronized boolean submit(Context context,String title,Task task) {
        if(busy)return false;busy=true;status=title;pending=task;revision++;
        try{context.startForegroundService(new Intent(context,FileTaskService.class));return true;}
        catch(RuntimeException error){busy=false;pending=null;status="Не удалось запустить операцию";revision++;return false;}
    }
    public static void cancel(){FileOperations.Control current=control;if(current!=null)current.cancelled=true;}
    @Override public void onCreate(){super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Передача файлов",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,FileManagerActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,FileTaskService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Natro · Файлы").setContentText(status).setContentIntent(open).setOngoing(busy)
                .setOnlyAlertOnce(true).addAction(0,"Отменить",stop).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        latestStartId=startId;
        if(intent!=null&&"cancel".equals(intent.getAction())){cancel();return START_NOT_STICKY;}
        startForeground(ID,notification());
        Task task;synchronized(FileTaskService.class){task=pending;pending=null;}
        if(task==null){if(!busy){stopForeground(true);stopSelf();}return START_NOT_STICKY;}
        control=new FileOperations.Control((name,bytes)->{
            status=name+" · "+android.text.format.Formatter.formatFileSize(this,bytes);
            long now=SystemClock.elapsedRealtime();if(now-lastNotice>500){lastNotice=now;getSystemService(NotificationManager.class).notify(ID,notification());}
        });
        FileOperations.Control current=control;
        worker.execute(()->{
            try{status=task.run(current);}catch(Exception failure){status="Не завершено: "+safeMessage(failure);}
            finally{
                new Handler(Looper.getMainLooper()).post(()->{
                    synchronized(FileTaskService.class){busy=false;control=null;revision++;}
                    stopForeground(true);stopSelf(latestStartId);
                });
            }
        });
        return START_NOT_STICKY;
    }
    public static String safeMessage(Throwable error) {
        // Do not leak signed URLs, cookie values or server response bodies through generic errors.
        if(error instanceof YandexFiles.LoginRequired)return error.getMessage();
        if(error instanceof org.json.JSONException)return "Изменился формат ответа хранилища";
        if(error instanceof SecurityException)return "Доступ не разрешён";
        String text=error.getMessage();if(text==null||text.contains("://")||text.contains("Cookie")||text.contains("sk="))return "Ошибка доступа или соединения";
        return text.length()>220?text.substring(0,220):text;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){cancel();worker.shutdownNow();super.onDestroy();}
}
