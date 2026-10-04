/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import dezz.status.widget.*;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.NoCarIntegration;
import dezz.status.widget.shade.SystemShadeSettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import static org.junit.Assert.*;

/** Real activity/view inflation, both settings themes, actual KX11 work area and largest font. */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsScreensInteractionTest {
    @ParameterizedRobolectricTestRunner.Parameters(name="{0}-theme{1}")
    public static Collection<Object[]> screens(){
        List<Object[]> result=new ArrayList<>();
        for(Class<? extends Activity> type:Arrays.asList(HudPanelSettingsActivity.class,InstrumentPanelSettingsActivity.class,
                LauncherSettingsActivity.class,PassengerLauncherSettingsActivity.class,MediaPanelSettingsActivity.class,
                NavigationPanelSettingsActivity.class,ClimatePanelSettingsActivity.class,VehicleInfoPanelSettingsActivity.class,
                InformationPanelSettingsActivity.class,DriverPanelSettingsActivity.class,PassengerPanelSettingsActivity.class,
                LauncherShortcutSettingsActivity.class,PanelElementSettingsActivity.class,SystemShadeSettingsActivity.class,
                NavigatorWindowSettingsActivity.class,DriverFavoritesSettingsActivity.class,PassengerFavoritesSettingsActivity.class))
            for(int theme:new int[]{1,2})result.add(new Object[]{type,theme});
        return result;
    }
    private final Class<? extends Activity> type;
    private final int theme;
    public SettingsScreensInteractionTest(Class<? extends Activity> type,int theme){this.type=type;this.theme=theme;}
    @Test(timeout=30000) public void screenAndRealSectionsRemainUsableAndCancelDoesNotWrite()throws Exception {
        // A marquee intentionally schedules frames forever. Do not auto-advance
        // vsync until it finishes: advance a finite interval for each observation.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        ReflectionHelpers.setStaticField(CarIntegrations.class,"instance",new NoCarIntegration());
        SettingsAppearance.preferences(RuntimeEnvironment.getApplication()).edit().putInt("theme",theme).putInt("textSp",26).commit();
        try(ActivityController<? extends Activity> controller=Robolectric.buildActivity(type)){
            System.out.println("EDITOR_BEGIN "+type.getSimpleName()+" theme="+theme);
            Activity activity=controller.setup().visible().get();System.out.println("EDITOR_CREATED "+type.getSimpleName());idle();
            View decor=activity.getWindow().getDecorView();measure(decor);idle();measure(decor);System.out.println("EDITOR_MEASURED "+type.getSimpleName());
            SettingsEditSession session=SettingsEditSession.find(activity);assertNotNull("Every layout editor has a draft",session);
            android.content.SharedPreferences disk=activity.createDeviceProtectedStorageContext().getSharedPreferences(activity.getPackageName()+"_preferences",0);
            Map<String,?> before=disk.getAll();
            snapshot(decor,type.getSimpleName()+"-"+(theme==1?"light":"dark"));
            Button apply=find(decor,"Применить");assertNotNull("Reachable Apply",apply);assertTrue(apply.isShown());
            assertNotNull("Reachable Cancel",find(decor,"Отмена"));
            for(SettingsSection section:SettingsSection.values()){
                Button tab=find(decor,section.title);if(tab==null||!tab.isShown())continue;
                tab.performClick();measure(decor);assertTrue(tab.isSelected());
            }
            session.cancel(activity);controller.pause().stop();idle();
            assertEquals("Cancellation and late callbacks keep working settings",before,disk.getAll());
        }
    }
    static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));}
    static void measure(View view){view.measure(View.MeasureSpec.makeMeasureSpec(1760,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(656,View.MeasureSpec.EXACTLY));view.layout(0,0,1760,656);}
    static Button find(View root,String text){
        if(root instanceof Button&&text.equals(((TextView)root).getText().toString()))return(Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button value=find(((ViewGroup)root).getChildAt(i),text);if(value!=null)return value;}
        return null;
    }
    static void snapshot(View view,String name)throws Exception{
        File directory=new File("build/settings-screenshots");assertTrue(directory.isDirectory()||directory.mkdirs());
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
    }
}
