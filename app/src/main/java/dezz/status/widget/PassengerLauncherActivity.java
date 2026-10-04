/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.Activity;
import android.os.Bundle;
import dezz.status.widget.launcher.PassengerHomeLauncher;

/** Compatibility entry point; HOME lives in the overlay service, never in this task. */
public final class PassengerLauncherActivity extends Activity {
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);PassengerHomeLauncher.open(this,getIntent());finishAndRemoveTask();
    }
}
