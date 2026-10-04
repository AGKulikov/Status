/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** The driver's Android HOME task hosts the same renderer as the passenger overlay. */
public class LauncherActivity extends LauncherProfileActivity implements LauncherHomeSurface.Host {
    public static final String EXTRA_EDIT_MODE=LauncherHomeSurface.EXTRA_EDIT_MODE;
    public static final String EXTRA_SHOW_WIDGET_CATALOG=LauncherHomeSurface.EXTRA_SHOW_WIDGET_CATALOG;
    public static final String EXTRA_EDIT_NAVIGATION_CONTENT=LauncherHomeSurface.EXTRA_EDIT_NAVIGATION_CONTENT;
    public static final String EXTRA_EDIT_MEDIA_CONTENT=LauncherHomeSurface.EXTRA_EDIT_MEDIA_CONTENT;
    public static final String EXTRA_EDIT_ACTIONS_CONTENT=LauncherHomeSurface.EXTRA_EDIT_ACTIONS_CONTENT;
    private LauncherHomeSurface surface;
    @Override public boolean isPassengerLauncherProfile(){return false;}
    @Override protected void onCreate(Bundle state){super.onCreate(state);surface=new LauncherHomeSurface(this,false);surface.onCreate(state);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);surface.onNewIntent(intent);}
    @Override protected void onStart(){super.onStart();if(surface!=null)surface.onStart();}
    @Override protected void onResume(){super.onResume();if(surface!=null)surface.onResume();}
    @Override protected void onPause(){if(surface!=null)surface.onPause();super.onPause();}
    @Override protected void onStop(){if(surface!=null)surface.onStop();super.onStop();}
    @Override protected void onDestroy(){if(surface!=null)surface.onDestroy();super.onDestroy();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(surface!=null)surface.onWindowFocusChanged(focus);}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(surface!=null)surface.onTrimMemory(level);}
    @Override public void onBackPressed(){if(surface==null)super.onBackPressed();else surface.onBackPressed();}
    @Override public Object onRetainCustomNonConfigurationInstance(){return surface==null?null:surface.onRetainCustomNonConfigurationInstance();}
    @Override public Context context(){return this;}
    @Override public Window window(){return getWindow();}
    @Override public WindowManager windowManager(){return getWindowManager();}
    @Override public Intent intent(){return getIntent();}
    @Override public void intent(Intent intent){setIntent(intent);}
    @Override public void content(View view){setContentView(view);}
    @Override public void feature(int feature){requestWindowFeature(feature);}
    @Override public boolean overlay(){return false;}
    @Override public boolean finishing(){return isFinishing();}
    @Override public boolean destroyed(){return isDestroyed();}
    @Override public boolean changingConfiguration(){return isChangingConfigurations();}
    @Override public boolean taskRoot(){return isTaskRoot();}
    @Override public Object retained(){return getLastCustomNonConfigurationInstance();}
    @Override public void back(){super.onBackPressed();}
    @Override public void launchedExternal(){}
    @Override public void launch(Intent intent,int requestCode,Bundle options){super.startActivityForResult(intent,requestCode,options);}
}
