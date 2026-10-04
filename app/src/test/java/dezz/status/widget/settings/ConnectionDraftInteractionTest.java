/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import android.widget.EditText;
import dezz.status.widget.MqttSettingsActivity;
import dezz.status.widget.Preferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
public class ConnectionDraftInteractionTest {
    public static class Parent extends Activity {}
    private static void text(Activity activity,String name,String value){
        ((EditText)ReflectionHelpers.getField(activity,name)).setText(value);
    }
    @Test public void typingConnectionDoesNotChangeRuntimeAndCancelDiscardsEveryValue(){
        Preferences runtime=new Preferences(RuntimeEnvironment.getApplication());
        String original=runtime.mqttHost.get();boolean enabled=runtime.mqttEnabled.get();
        try(ActivityController<MqttSettingsActivity> c=Robolectric.buildActivity(MqttSettingsActivity.class).setup()){
            MqttSettingsActivity activity=c.get();SettingsEditSession session=SettingsEditSession.find(activity);
            assertNotNull(session);text(activity,"host","broker.example");text(activity,"port","1884");text(activity,"username","draft");
            ((CheckBox)ReflectionHelpers.getField(activity,"enabled")).setChecked(!enabled);
            assertTrue(session.flushChanges());assertTrue(session.dirty());
            assertEquals(original,runtime.mqttHost.get());assertEquals(enabled,runtime.mqttEnabled.get());
            assertEquals("broker.example",new Preferences(activity).mqttHost.get());
            session.cancel(activity);c.pause().stop().destroy();
            assertEquals(original,runtime.mqttHost.get());assertEquals(enabled,runtime.mqttEnabled.get());
        }
    }
    @Test public void invalidNumberBlocksApplyAndDoesNotCrashPauseOrReplaceItWithADefault(){
        Preferences runtime=new Preferences(RuntimeEnvironment.getApplication());int before=runtime.mqttPort.get();
        try(ActivityController<MqttSettingsActivity> c=Robolectric.buildActivity(MqttSettingsActivity.class).setup()){
            SettingsEditSession session=SettingsEditSession.find(c.get());text(c.get(),"port","70000");
            assertFalse(session.apply(c.get()));assertEquals(before,runtime.mqttPort.get());
            c.pause();assertFalse(session.isClosed());assertTrue(session.dirty());
            text(c.get(),"port","1884");assertTrue(session.apply(c.get()));
            assertEquals(1884,runtime.mqttPort.get());session.cancel(c.get());
        }
    }
    @Test public void childConnectionApplyCannotLeakThroughAVisualParentsPreview(){
        Preferences runtime=new Preferences(RuntimeEnvironment.getApplication());String before=runtime.mqttHost.get();
        try(ActivityController<Parent> p=Robolectric.buildActivity(Parent.class).setup()){
            SettingsEditSession parent=SettingsEditSession.beginEditor(p.get(),null);parent.bind(()->{},()->{});
            Intent intent=new Intent(p.get(),MqttSettingsActivity.class);SettingsEditSession.carry(p.get(),intent);
            try(ActivityController<MqttSettingsActivity> c=Robolectric.buildActivity(MqttSettingsActivity.class,intent).setup()){
                text(c.get(),"host","nested.example");SettingsEditSession child=SettingsEditSession.find(c.get());
                assertTrue(child.apply(c.get()));assertTrue(child.isNested());
                assertEquals("nested.example",new Preferences(p.get()).mqttHost.get());
                assertEquals("Connection stays unchanged until the outer Apply",before,runtime.mqttHost.get());
                child.cancel(c.get());parent.cancel(p.get());assertEquals(before,runtime.mqttHost.get());
            }
        }
    }
    @Test public void applyKeepsUnknownKeysAndClosingControlsCannotOverwriteIt(){
        Application app=RuntimeEnvironment.getApplication();new Preferences(app);
        SharedPreferences raw=app.createDeviceProtectedStorageContext().getSharedPreferences(app.getPackageName()+"_preferences",0);
        raw.edit().putLong("future-connector-key",9L).commit();
        try(ActivityController<MqttSettingsActivity> c=Robolectric.buildActivity(MqttSettingsActivity.class).setup()){
            SettingsEditSession session=SettingsEditSession.find(c.get());text(c.get(),"host","saved.example");
            assertTrue(session.apply(c.get()));session.cancel(c.get());text(c.get(),"host","late.example");
            c.pause().stop().destroy();
            assertEquals("saved.example",new Preferences(app).mqttHost.get());assertEquals(9L,raw.getLong("future-connector-key",0));
        }
    }
}
