/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the fallback only when its persisted opt-in switch is still enabled. */
public final class HudModeFallbackBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        HudModeFallbackStore.Config config = HudModeFallbackStore.read(context);
        if (config.enabled && config.isValid()) {
            HudModeFallbackService.start(context, "boot:" + action);
        }
    }
}
