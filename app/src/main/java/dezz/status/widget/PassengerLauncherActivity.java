/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import dezz.status.widget.launcher.PassengerHomeLauncher;

/** The full Natro HOME renderer/editor, with its own task, profile and display. */
public final class PassengerLauncherActivity extends LauncherActivity {
    private DisplayManager displays;
    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { checkDisplay(); }
        @Override public void onDisplayRemoved(int id) { checkDisplay(); }
        @Override public void onDisplayChanged(int id) { checkDisplay(); }
    };

    @Override public boolean isPassengerLauncherProfile() { return true; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (isFinishing()) return;
        displays = getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(listener,
                new Handler(Looper.getMainLooper()));
        checkDisplay();
    }

    private void checkDisplay() {
        if (!PassengerHomeLauncher.available(this)
                || getWindowManager().getDefaultDisplay().getDisplayId()
                != PassengerHomeLauncher.DISPLAY_ID) finishAndRemoveTask();
    }

    @Override protected void onDestroy() {
        if (displays != null) displays.unregisterDisplayListener(listener);
        super.onDestroy();
    }
}
