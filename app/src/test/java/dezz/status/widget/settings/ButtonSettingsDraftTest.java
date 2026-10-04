/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.app.Application;
import android.content.SharedPreferences;
import dezz.status.widget.MediaButtonsSettingsActivity;
import dezz.status.widget.media.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
public class ButtonSettingsDraftTest {
    @Test public void inactiveAssignmentsAndUnknownRuntimeValuesSurviveApplyAndCancel(){
        SharedPreferences disk=RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext().getSharedPreferences("vehicle_buttons",0);
        disk.edit().putInt("drive.selected",3).putString("future-setting","preserve").commit();
        try(ActivityController<MediaButtonsSettingsActivity> c=Robolectric.buildActivity(MediaButtonsSettingsActivity.class).setup()){
            ButtonSettingsDraft draft=new ButtonSettingsDraft(c.get());SettingsEditSession session=SettingsEditSession.find(c.get());
            draft.saveBinding("va","single",new ButtonBinding(ButtonAction.BACK.id,"","","",""));
            assertEquals(ButtonAction.BACK,draft.binding("va","single").action);
            assertFalse(disk.contains("binding.va.single.action"));session.cancel(c.get());
            assertFalse(disk.contains("binding.va.single.action"));
        }
        try(ActivityController<MediaButtonsSettingsActivity> c=Robolectric.buildActivity(MediaButtonsSettingsActivity.class).setup()){
            ButtonSettingsDraft draft=new ButtonSettingsDraft(c.get());SettingsEditSession session=SettingsEditSession.find(c.get());
            draft.saveBinding("va","single",new ButtonBinding(ButtonAction.BACK.id,"","","",""));
            disk.edit().putInt("drive.selected",5).commit();assertTrue(session.apply(c.get()));session.cancel(c.get());
            assertEquals(ButtonAction.BACK.id,disk.getInt("binding.va.single.action",-1));assertFalse(disk.getBoolean("va.enabled",false));
            assertEquals(5,disk.getInt("drive.selected",0));assertEquals("preserve",disk.getString("future-setting",null));
        }
    }
}
