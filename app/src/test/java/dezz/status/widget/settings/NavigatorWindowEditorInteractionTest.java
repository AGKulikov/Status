/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import dezz.status.widget.NavigatorWindowSettingsActivity;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.NoCarIntegration;
import dezz.status.widget.navigation.NavigationIntegrationConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class NavigatorWindowEditorInteractionTest {
    private static final String KEY="navigationIntegrationConfigJson";
    @Before public void isolateVehicleAndFrames(){
        ReflectionHelpers.setStaticField(CarIntegrations.class,"instance",new NoCarIntegration());
        ShadowChoreographer.setPaused(true);
    }
    private SharedPreferences disk(){
        Context app=RuntimeEnvironment.getApplication();
        return app.createDeviceProtectedStorageContext().getSharedPreferences(app.getPackageName()+"_preferences",0);
    }
    @Test public void emptyConfigOpensAndCancelDiscardsEditsAndLatePause(){
        try(ActivityController<NavigatorWindowSettingsActivity> c=Robolectric.buildActivity(NavigatorWindowSettingsActivity.class)){
            NavigatorWindowSettingsActivity a=c.setup().get();
            assertFalse(SettingsEditSession.find(a).dirty());
            CompoundButton enabled=ReflectionHelpers.getField(a,"enabled");enabled.setChecked(false);
            SettingsScreensInteractionTest.find(a.getWindow().getDecorView(),"Отмена").performClick();
            c.pause().stop();
            assertFalse(disk().contains(KEY));
        }
    }
    @Test public void footerAppliesControlsAndRetainsIndependentMapProfiles()throws Exception{
        NavigationIntegrationConfig config=new NavigationIntegrationConfig();
        config.hudMap.enabled=false;
        disk().edit().putString(KEY,config.toJson().toString()).commit();
        try(ActivityController<NavigatorWindowSettingsActivity> c=Robolectric.buildActivity(NavigatorWindowSettingsActivity.class)){
            NavigatorWindowSettingsActivity a=c.setup().get();
            CompoundButton enabled=ReflectionHelpers.getField(a,"enabled");enabled.setChecked(false);
            assertTrue(NavigationIntegrationConfig.fromJson(disk().getString(KEY,"")).mainFloatingWindow.enabled);
            SettingsScreensInteractionTest.find(a.getWindow().getDecorView(),"Применить").performClick();
            c.pause().stop();
            NavigationIntegrationConfig saved=NavigationIntegrationConfig.fromJson(disk().getString(KEY,""));
            assertFalse(saved.mainFloatingWindow.enabled);assertFalse(saved.hudMap.enabled);
        }
        try(ActivityController<NavigatorWindowSettingsActivity> c=Robolectric.buildActivity(NavigatorWindowSettingsActivity.class)){
            NavigatorWindowSettingsActivity a=c.setup().get();
            CompoundButton enabled=ReflectionHelpers.getField(a,"enabled");assertFalse(enabled.isChecked());
            assertFalse(SettingsEditSession.find(a).dirty());
        }
    }
    @Test public void unchangedApplyPreservesAbsentDefault(){
        try(ActivityController<NavigatorWindowSettingsActivity> c=Robolectric.buildActivity(NavigatorWindowSettingsActivity.class)){
            NavigatorWindowSettingsActivity a=c.setup().get();
            SettingsScreensInteractionTest.find(a.getWindow().getDecorView(),"Применить").performClick();
            c.pause().stop();assertFalse(disk().contains(KEY));
        }
    }
    @Test public void malformedOrFutureConfigCannotBeOverwrittenByOpeningOrApplying(){
        for(String raw:new String[]{"broken", "{\"schema\":999}"}){
            disk().edit().putString(KEY,raw).commit();
            try(ActivityController<NavigatorWindowSettingsActivity> c=Robolectric.buildActivity(NavigatorWindowSettingsActivity.class)){
                NavigatorWindowSettingsActivity a=c.setup().get();
                assertNull(ReflectionHelpers.getField(a,"enabled"));
                SettingsScreensInteractionTest.find(a.getWindow().getDecorView(),"Применить").performClick();
                c.pause().stop();assertEquals(raw,disk().getString(KEY,""));
            }
        }
    }
}
