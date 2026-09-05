/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import dezz.status.widget.launcher.MediaAutoResumeController;

/**
 * Manifest wake boundary for Bluetooth transitions that happen while the app process is absent.
 * Preference admission remains centralized in {@link WidgetServiceWatchdogReceiver}.
 */
public final class BluetoothIntegrationWakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isWakeAction(intent.getAction())) return;
        String action = intent.getAction();
        int profileState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || profileState == BluetoothProfile.STATE_CONNECTED) {
            MediaAutoResumeController.onAudioRouteReady(context, action == null ? "" : action);
        }
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
