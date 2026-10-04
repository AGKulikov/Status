/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import dezz.status.widget.HudPanelSettingsActivity;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.NoCarIntegration;
import dezz.status.widget.hud.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsHudInteractionTest {
    @Before public void noVehicleCommands(){ReflectionHelpers.setStaticField(CarIntegrations.class,"instance",new NoCarIntegration());}
    @Test public void openingAndLeavingHudDoesNotWriteAConfig() {
        try(ActivityController<HudPanelSettingsActivity> controller=Robolectric.buildActivity(HudPanelSettingsActivity.class)){
            HudPanelSettingsActivity activity=controller.setup().visible().get();idle();
            SharedPreferences disk=durable(activity);Map<String,?> before=disk.getAll();
            assertFalse("Opening is not an edit",SettingsEditSession.find(activity).dirty());
            SettingsEditSession.find(activity).cancel(activity);controller.pause().stop();
            assertEquals("Late onStop must not save an abandoned config",before,disk.getAll());
        }
    }
    @Test public void appearancePreviewCanBeCancelledWithoutLosingTheOuterDraft() throws Exception {
        try(ActivityController<HudPanelSettingsActivity> controller=Robolectric.buildActivity(HudPanelSettingsActivity.class)){
            HudPanelSettingsActivity activity=controller.setup().visible().get();idle();
            SharedPreferences disk=durable(activity);Map<String,?> before=disk.getAll();
            HudPanelConfig config=ReflectionHelpers.getField(activity,"config");
            HudElementConfig item=new HudElementConfig("test_backdrop",HudElementType.BACKDROP);
            item.title="Подложка для проверки";config.elements.add(item);
            ReflectionHelpers.callInstanceMethod(activity,"persist",ClassParameter.from(boolean.class,false));
            String outer=new dezz.status.widget.Preferences(activity).hudPanelConfigJson.get();
            ReflectionHelpers.callInstanceMethod(activity,"editBackdrop",ClassParameter.from(HudElementConfig.class,item));
            AlertDialog dialog=(AlertDialog)ShadowAlertDialog.getLatestDialog();assertNotNull(dialog);
            View decor=dialog.getWindow().getDecorView();measure(decor);idle();measure(decor);
            find(decor,"Положение").performClick();measure(decor);
            SettingsSeekBar slider=findSlider(decor);assertNotNull(slider);
            int original=slider.getProgress();slider.setProgressFromUser(Math.min(slider.getMax(),original+1));
            decor.getViewTreeObserver().dispatchOnPreDraw();idle();measure(decor);
            assertNotEquals("Slider updates the live draft before Apply",outer,new dezz.status.widget.Preferences(activity).hudPanelConfigJson.get());
            assertEquals("Preview must not write the working preferences",before,disk.getAll());
            snapshot(decor,"hud-backdrop-position");
            SettingsEditSession outerSession=SettingsEditSession.find(activity);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();
            // Rollback rebuilds the editor; the destroyed Activity deliberately has no writable draft.
            assertTrue(SettingsEditSession.find(activity).isClosed());
            activity=controller.get();
            assertSame("Rebuilt editor retains its outer draft",outerSession,SettingsEditSession.find(activity));
            assertEquals("Nested Cancel restores the outer draft",outer,new dezz.status.widget.Preferences(activity).hudPanelConfigJson.get());
            SettingsEditSession.find(activity).cancel(activity);controller.pause().stop();
            assertEquals(before,disk.getAll());
        }
    }
    private static SharedPreferences durable(Context context){return context.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(context.getPackageName()+"_preferences",Context.MODE_PRIVATE);}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void measure(View view){view.measure(View.MeasureSpec.makeMeasureSpec(1728,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(572,View.MeasureSpec.EXACTLY));view.layout(0,0,1728,572);}
    private static Button find(View root,String text){
        if(root instanceof Button&&text.equals(((TextView)root).getText().toString()))return(Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;
    }
    private static SettingsSeekBar findSlider(View root){
        if(!root.isShown())return null;if(root instanceof SettingsSeekBar)return(SettingsSeekBar)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){SettingsSeekBar found=findSlider(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;
    }
    private static void snapshot(View view,String name)throws Exception{
        File directory=new File("build/settings-screenshots");assertTrue(directory.isDirectory()||directory.mkdirs());
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
    }
}
