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
        if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            // Keystore-backed MQTT credentials can become readable only after unlock on some
            // OEM ROMs. Re-reading preferences restarts MQTT without touching either overlay.
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            Preferences prefs = new Preferences(context);
            if (shouldReconcileClimate(context, prefs)) ClimatePanelService.apply(context);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())
                || ACTION_QUICKBOOT_POWERON.equals(intent.getAction())) {
            Log.d(TAG, "Device boot completed, checking if widget service should auto-start");

            final Preferences prefs = new Preferences(context);
            // The permanent climate panel has its own lifecycle and does not depend on the main
            // status widget being enabled. apply() selects compact/reserved mode and restores the
            // saved display/geometry after every supported boot sequence.
            if (shouldReconcileClimate(context, prefs)) {
                Log.i(TAG, "Restoring permanent climate panel");
                ClimatePanelService.apply(context);
            }
            if (!prefs.widgetEnabled.get()) {
                Log.d(TAG, "Widget service is not enabled. Don't start it.");
                return;
            }

            if (WidgetService.isRunning()) {
                Log.d(TAG, "Widget service is already running. Don't start it again.");
                return;
            }

            Log.i(TAG, "Auto-starting widget service");
            Intent serviceIntent = new Intent(context, WidgetService.class);
            context.startForegroundService(serviceIntent);
        }
    }

    private static boolean shouldReconcileClimate(Context context, Preferences preferences) {
        return preferences.climatePanelEnabled.get()
                || new ScreenReservationStateStore(context).hasManagedReservation();
    }
}
