/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.Application;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class InstrumentPanelTaskLauncherTest {
    private static final class Caller extends ContextWrapper {
        Intent[] intents;
        Bundle options;
        boolean denied;
        Caller() { super(RuntimeEnvironment.getApplication()); }
        @Override public void startActivity(Intent intent, Bundle options) {
            fail("OEM package-move route must not handle the independent instrument task");
        }
        @Override public void startActivities(Intent[] intents, Bundle options) {
            if (denied) throw new SecurityException("display launch denied");
            this.intents=intents; this.options=options;
        }
    }

    @Test public void launchesOneAuthorizedInstrumentTaskWithExactDisplayOptions() {
        Caller caller=new Caller();
        InstrumentPanelTaskLauncher.start(caller,2,"one-use-token");
        assertEquals(1,caller.intents.length);
        Intent intent=caller.intents[0];
        assertEquals(caller.getPackageName(),intent.getComponent().getPackageName());
        assertEquals(InstrumentPanelActivity.class.getName(),intent.getComponent().getClassName());
        assertEquals(Intent.ACTION_MAIN,intent.getAction());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,intent.getFlags());
        assertEquals("one-use-token",intent.getStringExtra(InstrumentPanelStore.EXTRA_LAUNCH_TOKEN));
        assertEquals(1,intent.getExtras().size());
        assertEquals(2,caller.options.getInt("android.activity.launchDisplayId",-1));
        assertEquals(5,caller.options.getInt("android.activity.windowingMode",-1));
        assertEquals(0,caller.options.getInt("android.activity.SplitScreenShownPosition",-1));
    }

    @Test public void systemDenialPropagatesWithoutUnscopedFallback() {
        Caller caller=new Caller(); caller.denied=true;
        try { InstrumentPanelTaskLauncher.start(caller,2,"token"); fail(); }
        catch (SecurityException expected) { assertNull(caller.intents); }
    }

    @Test public void missingDisplayOrCapabilityNeverLaunches() {
        Caller caller=new Caller();
        for(String token:new String[]{null,""}) {
            try { InstrumentPanelTaskLauncher.start(caller,2,token); fail(); }
            catch(IllegalArgumentException expected) { assertNull(caller.intents); }
        }
        try { InstrumentPanelTaskLauncher.start(caller,-1,"token"); fail(); }
        catch(IllegalArgumentException expected) { assertNull(caller.intents); }
    }
}
