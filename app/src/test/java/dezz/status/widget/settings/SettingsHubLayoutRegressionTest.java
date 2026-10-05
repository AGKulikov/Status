/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import dezz.status.widget.SettingsHubActivity;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.NoCarIntegration;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsHubLayoutRegressionTest {
    @Test public void hubKeepsItsOwnCategoriesAndNeverAddsEditorRails() throws Exception {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        ReflectionHelpers.setStaticField(CarIntegrations.class,"instance",new NoCarIntegration());
        for(int theme : new int[]{1,2}) {
            SettingsAppearance.preferences(RuntimeEnvironment.getApplication()).edit()
                    .putInt("theme",theme).putInt("textSp",26).commit();
            try(ActivityController<SettingsHubActivity> controller=Robolectric.buildActivity(SettingsHubActivity.class)) {
                SettingsHubActivity activity=controller.setup().visible().get();
                View decor=activity.getWindow().getDecorView();
                SettingsScreensInteractionTest.measure(decor);
                SettingsScreensInteractionTest.idle();
                SettingsScreensInteractionTest.measure(decor);
                SettingsScreensInteractionTest.snapshot(decor,"SettingsHub-"+theme);
                assertNoEditorRail(activity.getWindow().getDecorView());
                Map<?,?> categories=ReflectionHelpers.getField(activity,"categoryViews");
                assertEquals(SettingsDestinationCatalog.Group.values().length,categories.size());
                for(Object category:categories.values()) {
                    View row=ReflectionHelpers.getField(category,"row");
                    assertEquals(View.VISIBLE,row.getVisibility());
                    row.performClick();
                    assertNoEditorRail(activity.getWindow().getDecorView());
                }
                controller.pause().resume();
                assertNoEditorRail(activity.getWindow().getDecorView());
            }
        }
    }
    private static void assertNoEditorRail(View view) {
        assertFalse("Hub sidebar and content must not be regrouped",view instanceof SettingsEditorLayout);
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            assertNoEditorRail(((ViewGroup)view).getChildAt(i));
    }
}
