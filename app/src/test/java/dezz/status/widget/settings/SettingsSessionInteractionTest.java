/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class SettingsSessionInteractionTest {
    public static class Editor extends Activity {}
    private SharedPreferences durable(){return RuntimeEnvironment.getApplication().getSharedPreferences("interaction-test",0);}
    @Test public void childApplyIsStillCancelledWithItsParentAndLateWritesStayIgnored(){
        try(ActivityController<Editor> p=Robolectric.buildActivity(Editor.class).setup()){
            SettingsEditSession parent=SettingsEditSession.beginEditor(p.get(),null);parent.bind(()->{},()->{});
            Intent intent=new Intent(p.get(),Editor.class);SettingsEditSession.carry(p.get(),intent);
            try(ActivityController<Editor> c=Robolectric.buildActivity(Editor.class,intent).setup()){
                SettingsEditSession child=SettingsEditSession.beginEditor(c.get(),null);child.bind(()->{},()->{});
                SharedPreferences childPreferences=SettingsPreferences.wrap(c.get(),durable());
                childPreferences.edit().putString("geometry","moved").commit();
                assertFalse(durable().contains("geometry"));assertTrue(child.apply(c.get()));child.cancel(c.get());
                assertEquals("moved",SettingsPreferences.wrap(p.get(),durable()).getString("geometry",null));
                parent.cancel(p.get());childPreferences.edit().putString("geometry","late").commit();
                assertFalse(durable().contains("geometry"));
            }
        }
    }
    @Test public void childCancelKeepsEarlierParentChangesAndUnrelatedRuntimeWrites(){
        try(ActivityController<Editor> p=Robolectric.buildActivity(Editor.class).setup()){
            SettingsEditSession parent=SettingsEditSession.beginEditor(p.get(),null);parent.bind(()->{},()->{});
            SharedPreferences preferences=SettingsPreferences.wrap(p.get(),durable());preferences.edit().putInt("width",80).commit();
            Intent intent=new Intent(p.get(),Editor.class);SettingsEditSession.carry(p.get(),intent);
            try(ActivityController<Editor> c=Robolectric.buildActivity(Editor.class,intent).setup()){
                SettingsEditSession child=SettingsEditSession.beginEditor(c.get(),null);child.bind(()->{},()->{});
                SettingsPreferences.wrap(c.get(),durable()).edit().putInt("width",90).commit();
                durable().edit().putInt("runtime",42).commit();child.cancel(c.get());
                assertEquals(80,preferences.getInt("width",0));assertTrue(parent.apply(p.get()));parent.cancel(p.get());
                assertEquals(80,durable().getInt("width",0));assertEquals(42,durable().getInt("runtime",0));
            }
        }
    }
    @Test public void checkpointRollbackRejectsOldControlsButNewControlsCanEdit(){
        try(ActivityController<Editor> p=Robolectric.buildActivity(Editor.class).setup()){
            SettingsEditSession session=SettingsEditSession.beginEditor(p.get(),null);session.bind(()->{},()->{});
            SharedPreferences preferences=SettingsPreferences.wrap(p.get(),durable());preferences.edit().putInt("width",80).commit();
            SettingsEditSession.Savepoint checkpoint=session.checkpoint();preferences.edit().putInt("width",100).commit();
            checkpoint.finish();preferences.edit().putInt("width",111).commit();
            assertEquals(80,preferences.getInt("width",0));
            SettingsPreferences.wrap(p.get(),durable()).edit().putInt("width",85).commit();
            assertTrue(session.apply(p.get()));session.cancel(p.get());assertEquals(85,durable().getInt("width",0));
        }
    }
    @Test public void untouchedNormalizedDefaultsDoNotBecomeAWorkingWrite(){
        try(ActivityController<Editor> p=Robolectric.buildActivity(Editor.class).setup()){
            SettingsEditSession session=SettingsEditSession.beginEditor(p.get(),null);
            SharedPreferences preferences=SettingsPreferences.wrap(p.get(),durable());
            session.bind(()->preferences.edit().putString("default","normalized").commit(),()->{});
            assertFalse(session.dirty());assertTrue(session.apply(p.get()));session.cancel(p.get());
            assertFalse(durable().contains("default"));
        }
    }
}
