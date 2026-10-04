/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
public class SettingsEditorInteractionTest {
    private ActivityController<AppCompatActivity> activity(){
        ActivityController<AppCompatActivity> controller=Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_StatusWidget);return controller.create().start().resume().visible();
    }
    @Test public void sectionsContainOriginalControlsAndPreserveConditionalVisibility(){
        try(ActivityController<AppCompatActivity> controller=activity()){
            Context context=controller.get();ScrollView scroll=new ScrollView(context);
            LinearLayout form=new LinearLayout(context);form.setOrientation(LinearLayout.VERTICAL);scroll.addView(form);
            TextView title=new TextView(context);title.setText("Название");form.addView(title);
            EditText name=new EditText(context);name.setText("Мой виджет");form.addView(name);
            Button color=new Button(context);color.setText("Цвет фона");form.addView(color);
            TextView width=new TextView(context);width.setText("Ширина");form.addView(width);
            SettingsSeekBar slider=new SettingsSeekBar(context,10,1," px");slider.setMax(90);form.addView(slider);
            Switch hidden=new Switch(context);hidden.setText("Цвет текста");hidden.setVisibility(View.GONE);form.addView(hidden);
            Button action=new Button(context);action.setText("Действие нажатия");form.addView(action);
            View layout=SettingsEditorLayout.wrap(context,scroll,"test");assertTrue(layout instanceof SettingsEditorLayout);
            controller.get().setContentView(layout);measure(layout);
            assertTrue(name.isShown());assertFalse(color.isShown());
            find(layout,"Оформление").performClick();measure(layout);
            assertTrue(color.isShown());assertFalse(name.isShown());assertFalse(hidden.isShown());
            find(layout,"Положение").performClick();measure(layout);
            assertTrue(slider.isShown());assertFalse(color.isShown());
            assertTrue(SettingsAppearance.focus(layout,"Цвет фона"));measure(layout);assertTrue(color.isShown());
            assertEquals("Мой виджет",name.getText().toString());
        }
    }
    @Test public void exactButtonsUseRealUnitsAndNotifyOwnersAsUserInput(){
        try(ActivityController<AppCompatActivity> controller=activity()){
            LinearLayout form=new LinearLayout(controller.get());form.setOrientation(LinearLayout.VERTICAL);
            SettingsSeekBar slider=new SettingsSeekBar(controller.get(),-40,.5," °C");slider.setMax(160);slider.setProgress(80);
            int[] calls={0,0,0};
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar bar){calls[0]++;}
                public void onProgressChanged(SeekBar bar,int value,boolean user){if(user)calls[1]++;}
                public void onStopTrackingTouch(SeekBar bar){calls[2]++;}
            });
            form.addView(slider);controller.get().setContentView(form);measure(form);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();measure(form);
            find(form,"+").performClick();assertEquals(81,slider.getProgress());
            assertNotNull(find(form,"0.5 °C"));assertArrayEquals(new int[]{1,1,1},calls);
            find(form,"−").performClick();assertEquals(80,slider.getProgress());
            slider.setEnabled(false);find(form,"+").performClick();assertEquals(80,slider.getProgress());
        }
    }
    private static void measure(View view){view.measure(View.MeasureSpec.makeMeasureSpec(1760,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(656,View.MeasureSpec.EXACTLY));view.layout(0,0,1760,656);}
    private static TextView find(View view,String label){
        if(view instanceof TextView&&label.equals(((TextView)view).getText().toString()))return(TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            TextView found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
}
