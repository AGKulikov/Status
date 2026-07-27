/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the fallback only when the user left its persisted opt-in switch enabled. */
public final class HudModeFallbackBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        HudModeFallbackService.startSaved(context, "boot:" + action);
    }
}
