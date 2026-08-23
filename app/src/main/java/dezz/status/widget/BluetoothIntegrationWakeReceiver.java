/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manifest wake boundary for Bluetooth transitions that happen while the app process is absent.
 * Preference admission remains centralized in {@link WidgetServiceWatchdogReceiver}.
 */
public final class BluetoothIntegrationWakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isWakeAction(intent.getAction())) return;
        WidgetServiceWatchdog.arm(context, WidgetServiceWatchdog.BLUETOOTH_WAKE_DELAY_MS);
    }

    static boolean isWakeAction(String action) {
        return BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                || BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                || "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                || "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                || "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
                .equals(action);
    }
}
