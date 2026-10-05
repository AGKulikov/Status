/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import dezz.status.widget.launcher.PassengerHomeLauncher;

/** One process-start decision, not a runtime visibility policy or a preference listener. */
final class PassengerHomeStartup {
    private boolean decided;

    synchronized void onUnlockedStart(Context context, boolean hudProcess) {
        if (decided || hudProcess || !StartupWorkCoordinator.isUserUnlocked(context)) return;
        decided = true;
        // This key is excluded from live preview; application context has no editor draft.
        Context app = context.getApplicationContext();
        if (new Preferences(app, false).passengerLauncherAutoStart.get()) {
            PassengerHomeLauncher.open(app);
        }
    }
}
