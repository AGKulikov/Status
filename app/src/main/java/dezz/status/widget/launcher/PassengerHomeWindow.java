/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import dezz.status.widget.LauncherHomeSurface;
import dezz.status.widget.R;

/** The complete HOME renderer in a real display-scoped overlay, with no Activity/task. */
public final class PassengerHomeWindow extends Presentation implements LauncherHomeSurface.Host {
    interface Owner {
        void recreate(PassengerHomeWindow window);
        void closed(PassengerHomeWindow window);
        void launchedExternal(PassengerHomeWindow window);
        void displayInvalidated(PassengerHomeWindow window);
    }
    private final Owner owner;
    private Intent request;
    private Object retained;
    private LauncherHomeSurface surface;
    private boolean disposed, recreating, started;
    private final android.util.DisplayMetrics initialMetrics = new android.util.DisplayMetrics();
    private final int initialRotation;

    PassengerHomeWindow(Context context,Display display,Intent request,Object retained,Owner owner) {
        super(context,display,R.style.Theme_StatusWidget_Launcher);
        if(display.getDisplayId()!=PassengerHomeLauncher.DISPLAY_ID)
            throw new IllegalArgumentException("Passenger HOME requires display 3");
        this.owner=owner;this.request=new Intent(request);this.retained=retained;
        display.getMetrics(initialMetrics);initialRotation=display.getRotation();
        getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setOnDismissListener(dialog->owner.closed(this));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        surface=new LauncherHomeSurface(this,true);
        surface.onCreate(state);
    }
    @Override protected void onStart() {
        super.onStart();started=true;
        getWindow().setLayout(-1,-1);
        if(surface!=null){surface.onStart();surface.onResume();}
    }
    @Override protected void onStop() {
        if(started&&surface!=null){surface.onPause();surface.onStop();}
        started=false;super.onStop();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(surface!=null&&started){
            if(focused)surface.onResume();else surface.onPause();
            surface.onWindowFocusChanged(focused);
        }
    }
    @Override public void onBackPressed(){if(surface!=null)surface.onBackPressed();else dismiss();}
    boolean matches(Display display) {
        if(display==null||!display.isValid()||display.getState()==Display.STATE_OFF)return false;
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();display.getMetrics(metrics);
        return initialMetrics.equals(metrics)&&initialRotation==display.getRotation();
    }
    @Override public void cancel() {
        // Presentation cancels itself on display removal/resize. Preserve the editing owner
        // even if its framework listener runs before the service's DisplayListener.
        android.hardware.display.DisplayManager manager=getContext().getSystemService(android.hardware.display.DisplayManager.class);
        Display current=manager==null?null:manager.getDisplay(PassengerHomeLauncher.DISPLAY_ID);
        if(!disposed&&!matches(current)){owner.displayInvalidated(this);return;}
        super.cancel();
    }
    @Override public void dismiss(){
        if(disposed)return;
        super.dismiss();
        // Some WindowManager failures never reach onStop; shut down every listener/worker.
        if(started&&surface!=null){surface.onPause();surface.onStop();started=false;}
        if(surface!=null)surface.onDestroy();
        disposed=true;
    }
    Object retainAndRelease(){
        recreating=true;
        Object result=surface==null?retained:surface.onRetainCustomNonConfigurationInstance();
        dismiss();return result;
    }
    void open(Intent next){request=new Intent(next);if(surface!=null)surface.onNewIntent(request);}
    @Override public Context context(){return getContext();}
    @Override public Window window(){return getWindow();}
    @Override public WindowManager windowManager(){return (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);}
    @Override public Intent intent(){return request;}
    @Override public void intent(Intent value){request=value;}
    @Override public void content(View view){setContentView(view);}
    @Override public void feature(int feature){requestWindowFeature(feature);}
    @Override public boolean overlay(){return true;}
    @Override public boolean finishing(){return disposed;}
    @Override public boolean destroyed(){return disposed;}
    @Override public boolean changingConfiguration(){return recreating;}
    @Override public boolean taskRoot(){return true;}
    @Override public Object retained(){return retained;}
    @Override public void recreate(){if(!disposed)owner.recreate(this);}
    @Override public void finish(){dismiss();}
    @Override public void back(){dismiss();}
    @Override public void launchedExternal(){if(!disposed)owner.launchedExternal(this);}
    @Override public void launch(Intent intent,int requestCode,Bundle options){
        if(disposed)return;
        if(PassengerHomeLauncher.isHomeIntent(intent)){
            open(intent);return;
        }
        boolean internal=PassengerHomeLauncher.isOwnActivity(getContext(),intent);
        Intent target=PassengerHomeLauncher.profileIntent(getContext(),intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(target,PassengerHomeLauncher.targetOptions(getContext(),options,
                PassengerHomeLauncher.targetDisplay(getContext(),intent)));
        if(!internal)launchedExternal();
    }
}
