/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dezz.status.widget.phone.PhoneConnectionJournal;

/** Alarm endpoint that restores an unexpectedly killed integration host. */
public final class WidgetServiceWatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "WidgetServiceWatchdog";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !WidgetServiceWatchdog.ACTION_CHECK.equals(intent.getAction())) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;

        final boolean required;
        try {
            required = WidgetServiceStarter.requiresAutomaticIntegrationHost(
                    new Preferences(app, false));
        } catch (RuntimeException failure) {
            // Keep one delayed check alive after a transient device-protected-storage failure.
            Log.e(TAG, "Could not read integration-host preferences", failure);
            WidgetServiceWatchdog.arm(app);
            return;
        }
        if (!required) {
            WidgetServiceWatchdog.cancel(app);
            return;
        }

        // Rearm before starting: a transient OEM foreground-service rejection cannot make this
        // the final recovery attempt.
        WidgetServiceWatchdog.arm(app);
        if (WidgetService.isRunning()) return;

        PhoneConnectionJournal.initialize(app);
        PhoneConnectionJournal.append("service-watchdog",
                "integration host absent; immediate foreground restart requested");
        WidgetServiceStarter.startIfNeededWithRetry(app);
    }
}
