/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import androidx.core.app.NotificationCompat;
import dezz.status.widget.PassengerLauncherSettingsActivity;
import dezz.status.widget.R;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Explicit HOME owner, independent from the driver's task and the passenger side panel. */
public final class PassengerHomeService extends Service implements DisplayManager.DisplayListener,PassengerHomeWindow.Owner {
    public static final String EXTRA_REQUEST="passenger_home_request";
    private static final String CHANNEL="PassengerHomeChannel";
    private static final int NOTIFICATION=1048;
    private final Handler main=new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private PassengerHomeWindow window;
    private Intent request;
    private Object retained;
    private boolean wanted,destroyed;
    private int retries;
    private long generation;
    private final Runnable retry=this::reconcile;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"HOME пассажира",NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,PassengerLauncherSettingsActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NOTIFICATION,new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_apps)
                .setContentTitle("HOME пассажира").setContentText("Независимое окно на экране пассажира")
                .setContentIntent(settings).setOngoing(true).build());
        displays=getSystemService(DisplayManager.class);
        if(displays!=null)displays.registerDisplayListener(this,main);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        DiagnosticJournal.infoAsync("passenger-home", "request start_id="+startId+", present="+(intent!=null)+", attached="+(window!=null));
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        Intent next=intent.getParcelableExtra(EXTRA_REQUEST);
        if(!(retained instanceof dezz.status.widget.settings.SettingsEditSession)
                || ((dezz.status.widget.settings.SettingsEditSession)retained).isClosed()
                || request==null || dezz.status.widget.LauncherHomeSurface.requestsAnyHomeEditor(next))
            request=next==null?new Intent(Intent.ACTION_MAIN):new Intent(next);
        wanted=true;retries=0;
        if(window!=null)window.open(request);else reconcile();
        return START_NOT_STICKY;
    }
    private void reconcile(){
        main.removeCallbacks(retry);
        DiagnosticJournal.infoAsync("passenger-home", "reconcile generation="+generation+", wanted="+wanted+", destroyed="+destroyed+", retries="+retries+", attached="+(window!=null));
        if(destroyed||!wanted)return;
        if(!Settings.canDrawOverlays(this)){
            DiagnosticJournal.warn("passenger-home","overlay_permission_missing");
            PassengerHomeLauncher.reportFailure(this,new SecurityException("Overlay permission"));
            wanted=false;release(false);stopSelf();return;
        }
        Display display=displays==null?null:displays.getDisplay(PassengerHomeLauncher.DISPLAY_ID);
        if(display==null||!display.isValid()||display.getState()==Display.STATE_OFF){
            DiagnosticJournal.infoAsync("passenger-home", "display_unavailable present="+(display!=null)+", state="+(display==null?-1:display.getState()));
            release(true);scheduleRetry();return;
        }
        if(window!=null)return;
        PassengerHomeWindow candidate=null;
        try{
            candidate=new PassengerHomeWindow(this,display,request,retained,this);
            window=candidate;candidate.show();retained=null;
            DiagnosticJournal.infoAsync("passenger-home","overlay_attached display=3; physical_pixels=unobserved");
        }catch(RuntimeException failure){
            window=null;if(candidate!=null)retained=candidate.retainAndRelease();
            DiagnosticJournal.warn("passenger-home","overlay_attach_failed="+failure.getClass().getSimpleName());
            scheduleRetry();
        }
    }
    private void scheduleRetry(){
        if(!destroyed&&wanted&&retries++<12){DiagnosticJournal.infoAsync("passenger-home","retry_scheduled attempt="+retries);main.postDelayed(retry,5000L);}
        else if(!destroyed&&wanted){DiagnosticJournal.warn("passenger-home","retries_exhausted; waiting_for_display_event_or_request");dezz.status.widget.diagnostics.CausalDiagnostics.capture("passenger_home_unavailable",false);}
    }
    private void release(boolean keepDraft){
        DiagnosticJournal.infoAsync("passenger-home", "release generation="+generation+", keep_draft="+keepDraft+", attached="+(window!=null));
        generation++;
        PassengerHomeWindow previous=window;window=null;
        if(previous!=null){if(keepDraft)retained=previous.retainAndRelease();else previous.dismiss();request=new Intent(previous.intent());}
        if(!keepDraft&&retained instanceof dezz.status.widget.settings.SettingsEditSession){
            ((dezz.status.widget.settings.SettingsEditSession)retained).cancel(this);retained=null;
        }
    }
    @Override public void recreate(PassengerHomeWindow source){
        long expected=generation;
        main.post(()->{if(destroyed||source!=window||expected!=generation)return;release(true);reconcile();});
    }
    @Override public void closed(PassengerHomeWindow source){
        if(source!=window)return;
        DiagnosticJournal.infoAsync("passenger-home", "closed_by_user");
        wanted=false;window=null;generation++;stopSelf();
    }
    @Override public void launchedExternal(PassengerHomeWindow source){
        if(source!=window)return;
        DiagnosticJournal.infoAsync("passenger-home", "launched_external");
        wanted=false;release(true);if(retained==null)stopSelf();
    }
    @Override public void displayInvalidated(PassengerHomeWindow source){
        if(source!=window)return;release(true);reconcile();
    }
    private void displayChanged(int id){
        if(id!=PassengerHomeLauncher.DISPLAY_ID)return;
        Display current=displays==null?null:displays.getDisplay(id);
        if(window!=null&&window.matches(current))return;
        retries=0;release(true);reconcile();
    }
    @Override public void onDisplayAdded(int id){displayChanged(id);}
    @Override public void onDisplayChanged(int id){displayChanged(id);}
    @Override public void onDisplayRemoved(int id){displayChanged(id);}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);release(true);reconcile();}
    @Override public void onDestroy(){destroyed=true;wanted=false;main.removeCallbacksAndMessages(null);if(displays!=null)displays.unregisterDisplayListener(this);release(false);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
