/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Application;
import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.card.MaterialCardView;
import dezz.status.widget.AllAppsSettingsActivity;
import dezz.status.widget.PassengerAllAppsSettingsActivity;
import dezz.status.widget.R;
import dezz.status.widget.launcher.LauncherAppCatalog;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
public class AllAppsThemeRegressionTest {
    @Test public void lateCatalogHasReadableThemeBeforeAnyLayoutCallback() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        for(int theme:new int[]{1,2}) for(Class<? extends AllAppsSettingsActivity> type:
                java.util.Arrays.asList(AllAppsSettingsActivity.class,PassengerAllAppsSettingsActivity.class)) {
            SettingsAppearance.preferences(RuntimeEnvironment.getApplication()).edit()
                    .putInt("theme",theme).putInt("textSp",26).commit();
            try(ActivityController<? extends AllAppsSettingsActivity> controller=Robolectric.buildActivity(type).setup()) {
                AllAppsSettingsActivity activity=controller.get();
                LauncherAppCatalog.App app=ReflectionHelpers.callConstructor(LauncherAppCatalog.App.class,
                        ReflectionHelpers.ClassParameter.from(String.class,"Test player"),
                        ReflectionHelpers.ClassParameter.from(String.class,"test.player"),
                        ReflectionHelpers.ClassParameter.from(ComponentName.class,new ComponentName("test.player","test.player.Main")),
                        ReflectionHelpers.ClassParameter.from(boolean.class,false));
                ReflectionHelpers.callInstanceMethod(activity,"renderApplications",
                        ReflectionHelpers.ClassParameter.from(List.class,Collections.singletonList(app)));
                LinearLayout rows=ReflectionHelpers.getField(activity,"applications");
                MaterialCardView card=(MaterialCardView)rows.getChildAt(0);
                int background=card.getCardBackgroundColor().getDefaultColor();
                assertEquals(ContextCompat.getColor(activity,R.color.settings_group_background),background);
                CompoundButton control=findSwitch(card);assertNotNull(control);
                assertTrue(ColorUtils.calculateContrast(control.getCurrentTextColor(),background)>=4.5);
                assertTrue(control.getTextSize()/activity.getResources().getDisplayMetrics().scaledDensity>=26);
            }
        }
    }
    private static CompoundButton findSwitch(View view) {
        if(view instanceof CompoundButton)return (CompoundButton)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            CompoundButton found=findSwitch(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
}
