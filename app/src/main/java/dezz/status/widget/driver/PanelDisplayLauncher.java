/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** Never silently redirect a passenger action to the driver's display. */
final class PanelDisplayLauncher {
    private PanelDisplayLauncher() {}
    static Context scoped(Context base, int displayId) {
        return new android.content.ContextWrapper(base) {
            @Override public void startActivity(Intent intent) { start(base, intent, displayId); }
        };
    }
    static void start(Context context, Intent intent, int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            context.startActivity(intent);
            return;
        }
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null || !display.isValid()) throw new IllegalStateException("Display unavailable");
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        context.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT), options.toBundle());
    }
}
