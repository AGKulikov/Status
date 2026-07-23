/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dezz.status.widget.climate.ClimatePanelService;
import dezz.status.widget.climate.ScreenReservationStateStore;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (WidgetServiceStarter.ACTION_RETRY.equals(action)) {
            WidgetServiceStarter.retryFromAlarm(context,
                    intent.getIntExtra(WidgetServiceStarter.EXTRA_RETRY_ATTEMPT, -1));
            return;
        }
        if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            // Keystore-backed MQTT credentials can become readable only after unlock on some
            // OEM ROMs. This reconfigure is independent from status-window attachment.
            restoreStatusWidget(context, true);
            restoreClimateSafely(context);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.d(TAG, "System lifecycle event, restoring enabled services: "
                    + action);

            // Restore independently. A transient OEM rejection of the climate foreground service
            // must never prevent the status row from being started (or vice versa).
            restoreStatusWidget(context, false);
            restoreClimateSafely(context);
        }
    }

    private static void restoreStatusWidget(Context context, boolean forceReconfigure) {
        try {
            WidgetService current = WidgetService.getInstance();
            if (current != null) {
                if (forceReconfigure) current.applyPreferences();
                return;
            }
            WidgetServiceStarter.startIfNeededWithRetry(context);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore status widget", failure);
        }
    }

    private static void restoreClimateSafely(Context context) {
        try {
            Preferences prefs = new Preferences(context);
            // The permanent climate panel has its own lifecycle and does not depend on the main
            // status widget being enabled. apply() selects compact/reserved mode and restores the
            // saved display/geometry after every supported boot sequence.
            if (shouldReconcileClimate(context, prefs)) {
                Log.i(TAG, "Restoring permanent climate panel");
                ClimatePanelService.apply(context);
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore permanent climate panel", failure);
        }
    }

    private static boolean shouldReconcileClimate(Context context, Preferences preferences) {
        return preferences.climatePanelEnabled.get()
                || new ScreenReservationStateStore(context).hasManagedReservation();
    }
}
