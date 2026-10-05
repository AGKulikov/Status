/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.os.Bundle;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
public abstract class SettingsActivity extends AppCompatActivity implements SettingsPreviewProvider {
    private SettingsEditSession settingsSession;
    private boolean settingsReady;
    private long pausedRevision;
    private boolean wasPaused;
    @Override protected void onCreate(Bundle state){
        SettingsAppearance.configure(this);super.onCreate(state);
        settingsSession=SettingsEditSession.begin(this,getLastCustomNonConfigurationInstance());
    }
    @Override protected void onPostCreate(Bundle state){
        super.onPostCreate(state);
        settingsReady=true;installSettings();
    }
    private void installSettings(){
        if(settingsPreview()!=null)settingsPreview().setTag(SettingsEditorLayout.PREVIEW_TAG);
        if (usesEditorSections())
            SettingsEditorLayout.install(findViewById(android.R.id.content),getClass().getName());
        if(settingsSession!=null)settingsSession.install(this,this::flushSettingsDraft,this::recreate,()->super.finish());
        SettingsAppearance.attach(this);
    }
    @Override public void onContentChanged(){super.onContentChanged();if(settingsReady)getWindow().getDecorView().post(()->{if(!isDestroyed())installSettings();});}
    protected void flushSettingsDraft() {}
    /** Navigation hubs already own their layout; only actual forms need field grouping. */
    protected boolean usesEditorSections() { return true; }
    @Override public android.view.View settingsPreview(){return null;}
    @Override public Object onRetainCustomNonConfigurationInstance(){return settingsSession;}
    @Override public void finish(){
        if(settingsSession==null||!settingsSession.requestFinish(this,()->super.finish()))super.finish();
    }
    @Override public void startActivityForResult(Intent intent,int requestCode,Bundle options){
        SettingsEditSession.carry(this,intent);super.startActivityForResult(intent,requestCode,options);
    }
    @Override protected void onDestroy(){
        if(settingsSession!=null&&!isChangingConfigurations())settingsSession.cancel(this);
        SettingsEditSession.detach(this);
        super.onDestroy();
    }
    @Override protected void onPause(){
        if(settingsSession!=null){settingsSession.flushChanges();pausedRevision=settingsSession.revision();wasPaused=true;}
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();
        if(settingsSession!=null&&wasPaused&&settingsSession.revision()!=pausedRevision){
            wasPaused=false;settingsSession.invalidateOldControls();recreate();return;
        }
        wasPaused=false;
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)SettingsAppearance.apply(this,getWindow().getDecorView());}
}
