/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.os.Bundle;
import android.content.Intent;
import dezz.status.widget.settings.SettingsAppearance;
import dezz.status.widget.settings.SettingsEditSession;
import dezz.status.widget.settings.SettingsEditorLayout;
/** Settings-only profile host; the actual HOME activity keeps its own rendering policy. */
public abstract class SettingsLauncherProfileActivity extends LauncherProfileActivity {
    private SettingsEditSession settingsSession;
    private boolean settingsReady;
    @Override protected void onCreate(Bundle state){
        SettingsAppearance.configure(this);super.onCreate(state);
        settingsSession=SettingsEditSession.begin(this,getLastCustomNonConfigurationInstance());
    }
    @Override protected void onPostCreate(Bundle state){
        super.onPostCreate(state);
        settingsReady=true;installSettings();
    }
    private void installSettings(){
        SettingsEditorLayout.install(findViewById(android.R.id.content),getClass().getName());
        if(settingsSession!=null)settingsSession.install(this,this::flushSettingsDraft,this::recreate,()->super.finish());
        SettingsAppearance.attach(this);
    }
    @Override public void onContentChanged(){super.onContentChanged();if(settingsReady)getWindow().getDecorView().post(()->{if(!isDestroyed())installSettings();});}
    protected void flushSettingsDraft() {}
    @Override public Object onRetainCustomNonConfigurationInstance(){return settingsSession;}
    @Override public void finish(){
        if(settingsSession==null||!settingsSession.requestFinish(this,()->super.finish()))super.finish();
    }
    @Override public void startActivityForResult(Intent intent,int requestCode,Bundle options){
        SettingsEditSession.carry(this,intent);super.startActivityForResult(intent,requestCode,options);
    }
    @Override protected void onDestroy(){
        if(settingsSession!=null&&!isChangingConfigurations())settingsSession.cancel(this);
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)SettingsAppearance.apply(this,getWindow().getDecorView());}
}
