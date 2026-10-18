/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Launch only our token-authorized, separate instrument task. */
final class InstrumentPanelTaskLauncher {
    private InstrumentPanelTaskLauncher() {}

    static void start(Context app, int displayId, String launchToken) {
        if (displayId < 0 || launchToken == null || launchToken.isEmpty())
            throw new IllegalArgumentException("Instrument display and launch token are required");
        Intent intent = new Intent(app, InstrumentPanelActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(InstrumentPanelStore.EXTRA_LAUNCH_TOKEN, launchToken);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        Bundle launchOptions = options.toBundle();
        launchOptions.putInt("android.activity.windowingMode", 5);
        launchOptions.putInt("android.activity.SplitScreenShownPosition", 0);
        // KX11 Instrumentation.execStartActivity incorrectly treats display 2 as a
        // CSD/PSD package move when our settings are on display 0. The public batch
        // API reaches the normal ActivityStarter with the same caller and options;
        // one explicit component and its separate affinity keep settings in place.
        // See OEM_LAUNCH_332_RU.md: no hidden API, package stop or global dialog flag.
        app.startActivities(new Intent[]{intent}, launchOptions);
    }
}
