/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.app.Application;
import android.widget.EditText;
import dezz.status.widget.LiveActivityApnsSettingsActivity;
import dezz.status.widget.phone.liveactivity.ApnsCredentialStore;
import dezz.status.widget.backup.TestAndroidKeyStore;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
public class ApnsDraftInteractionTest {
    @Test public void importRemovalAndMetadataEditsStayInDraftUntilTheWholeApply()throws Exception{
        try(TestAndroidKeyStore keys=new TestAndroidKeyStore()){
            ApnsCredentialStore runtime=new ApnsCredentialStore(RuntimeEnvironment.getApplication());
            byte[] pem=TestAndroidKeyStore.apnsPem();runtime.save("TEAM123456","KEY0123456",ApnsCredentialStore.DEFAULT_TOPIC,false,pem);
            try(ActivityController<LiveActivityApnsSettingsActivity> c=Robolectric.buildActivity(LiveActivityApnsSettingsActivity.class).setup()){
                ApnsCredentialStore draft=ReflectionHelpers.getField(c.get(),"credentials");
                SettingsEditSession session=SettingsEditSession.find(c.get());
                draft.clear();assertFalse(draft.isConfigured());assertTrue(runtime.isConfigured());
                session.cancel(c.get());assertTrue(runtime.isConfigured());
            }
            try(ActivityController<LiveActivityApnsSettingsActivity> c=Robolectric.buildActivity(LiveActivityApnsSettingsActivity.class).setup()){
                ((EditText)ReflectionHelpers.getField(c.get(),"keyId")).setText("NEXT123456");
                SettingsEditSession session=SettingsEditSession.find(c.get());assertTrue(session.flushChanges());
                assertEquals("KEY0123456",runtime.keyId());assertTrue(runtime.isConfigured());
                assertTrue(session.apply(c.get()));assertEquals("NEXT123456",runtime.keyId());assertTrue(runtime.isConfigured());session.cancel(c.get());
            }
            java.util.Arrays.fill(pem,(byte)0);
        }
    }
}
