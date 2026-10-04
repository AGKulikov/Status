/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class SettingsResourceDraftTest {
    public static class Owner extends Activity {}
    private static final String PATH="ce_no_backup/draft-resource.bin";
    @Test public void nestedApplyAndCancelKeepResourceOffDisk()throws Exception {
        try(ActivityController<Owner> p=Robolectric.buildActivity(Owner.class).setup();
            ActivityController<Owner> c=Robolectric.buildActivity(Owner.class).setup()){
            SettingsEditSession parent=SettingsEditSession.beginEditor(p.get(),null);parent.bind(()->{},()->{});
            Intent intent=new Intent(p.get(),Owner.class);SettingsEditSession.carry(p.get(),intent);c.get().setIntent(intent);
            SettingsEditSession child=SettingsEditSession.beginEditor(c.get(),null);child.bind(()->{},()->{});
            SettingsEditSession.files(c.get()).stage(PATH,new byte[]{1,2});assertTrue(child.dirty());
            assertTrue(child.apply(c.get()));assertArrayEquals(new byte[]{1,2},SettingsEditSession.files(p.get()).read(PATH));
            assertNull(SettingsFileChange.read(p.get(),PATH));child.cancel(c.get());parent.cancel(p.get());
            assertNull(SettingsFileChange.read(p.get(),PATH));
        }
    }
    @Test public void savepointAndDetachedControlsCannotOverwriteAcceptedResource()throws Exception {
        try(ActivityController<Owner> c=Robolectric.buildActivity(Owner.class).setup()){
            SettingsEditSession s=SettingsEditSession.beginEditor(c.get(),null);s.bind(()->{},()->{});
            SettingsEditSession.FileDraft old=SettingsEditSession.files(c.get());old.stage(PATH,new byte[]{1});
            SettingsEditSession.Savepoint point=s.checkpoint();old.stage(PATH,new byte[]{2});point.finish();
            assertArrayEquals(new byte[]{1},SettingsEditSession.files(c.get()).read(PATH));
            try{old.stage(PATH,new byte[]{9});fail();}catch(java.io.IOException expected){}
            assertTrue(s.apply(c.get()));assertArrayEquals(new byte[]{1},SettingsFileChange.read(c.get(),PATH));
            SettingsEditSession.FileDraft current=SettingsEditSession.files(c.get());SettingsEditSession.detach(c.get());
            try{current.stage(PATH,new byte[]{9});fail();}catch(java.io.IOException expected){}
            try{SettingsEditSession.files(c.get()).stage(PATH,new byte[]{9});fail();}catch(java.io.IOException expected){}
            s.cancel(c.get());assertArrayEquals(new byte[]{1},SettingsFileChange.read(c.get(),PATH));
        }
    }
}
