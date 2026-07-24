/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Security/privacy contract for the one-phone connector.
 *
 * <p>Android's common SMS provider has no standard Bluetooth-device identity column. Therefore
 * rows from that provider can never prove that they came from the explicitly selected iPhone.
 * MAP, HFP and battery broadcasts, on the other hand, carry
 * {@code BluetoothDevice.EXTRA_DEVICE}; all of them must pass the exact-address gate before any
 * state is accepted.</p>
 */
public final class PhoneSelectedIphoneIsolationContractTest {
    @Test public void mapMessagesComeOnlyFromDeviceQualifiedBroadcasts() throws IOException {
        String source = controller();

        assertTrue(source.contains(
                "\"android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED\""));
        assertTrue(source.contains(
                "\"android.bluetooth.mapmce.profile.extra.MESSAGE_HANDLE\""));
        assertTrue(source.contains("filter.addAction(ACTION_MAP_MESSAGE_RECEIVED)"));
        assertTrue(source.contains("BluetoothDevice device = parcelableDevice(intent)"));
        assertTrue(source.contains("if (!isSelected(device)) return;"));
        assertTrue(source.contains("handleMapMessage(token, intent)"));
        assertTrue(source.contains(
                "if (!mapConnected || current == null || !current.messagesEnabled) return;"));
        assertTrue(source.contains(
                "BLUETOOTH_SENDER_PERMISSION =\n"
                        + "            \"android.permission.BLUETOOTH_PRIVILEGED\""));
        assertTrue(source.contains(
                "context.registerReceiver(receiver, filter, BLUETOOTH_SENDER_PERMISSION,"));

        // AOSP Telephony.Sms has no field that identifies the remote Bluetooth device.
        assertFalse(source.contains("content://sms"));
        assertFalse(source.contains("ContentObserver"));
        assertFalse(source.contains("ContentResolver"));
        assertFalse(source.contains("Manifest.permission.READ_SMS"));
    }

    @Test public void everyDeviceDataBroadcastPassesTheSameExactAddressGate()
            throws IOException {
        String method = method(controller(), "private void handleBluetoothBroadcast",
                "private void invalidateSelectedPhone");
        int device = method.indexOf("BluetoothDevice device = parcelableDevice(intent)");
        int gate = method.indexOf("if (!isSelected(device)) return;", device);

        assertTrue(device >= 0);
        assertTrue(gate > device);
        for (String action : new String[] {
                "BluetoothDevice.ACTION_ACL_CONNECTED",
                "BluetoothDevice.ACTION_ACL_DISCONNECTED",
                "ACTION_HFP_CONNECTION",
                "ACTION_HFP_AG_EVENT",
                "ACTION_MAP_CONNECTION",
                "ACTION_MAP_MESSAGE_RECEIVED",
                "ACTION_DEVICE_BATTERY_LEVEL_CHANGED"
        }) {
            assertTrue(action + " bypasses the selected-iPhone gate",
                    method.indexOf(action, gate) > gate);
        }
    }

    @Test public void gattAndInitialProfilesAreOpenedForTheSelectedDeviceOnly()
            throws IOException {
        String source = controller();

        assertTrue(source.contains("selectedDevice.connectGatt("));
        assertFalse(source.contains("adapter.getRemoteDevice("));
        assertTrue(source.contains("proxy.getConnectedDevices()"));
        assertTrue(source.contains("if (isSelected(device))"));
        assertTrue(source.contains("if (callbackGatt != gatt)"));
        assertTrue(source.contains("generation == token"));
    }

    @Test public void mapBackfillStartsOnlyAfterTheExactMapSessionBarrier()
            throws IOException {
        String method = method(controller(), "private void queryInitialProfileState",
                "private boolean requestUnreadMapMessages");
        int session = method.indexOf("if (!mapConnected) beginMapSession()");
        int backfill = method.indexOf("requestUnreadMapMessages(proxy, exactDevice)");

        assertTrue(session >= 0);
        assertTrue(backfill > session);
        assertTrue(method.contains(
                "requestBackfill = isCurrentLocked(token) && mapConnected"));
        assertTrue(method.contains(
                "if (requestBackfill) {\n"
                        + "                                requestUnreadMapMessages("));
    }

    @Test public void changingOrLosingTheSelectionClearsAllPhoneData()
            throws IOException {
        String source = controller();

        assertTrue(source.contains("stopLocked(next.enabled ? \"reconfigured\""));
        assertTrue(source.contains("clearRuntimeState(\"starting\")"));
        assertTrue(source.contains("mapMessageCache.clear()"));
        assertTrue(source.contains("notificationCache.clear()"));
        assertTrue(source.contains("batteryLevel = null"));
        assertTrue(source.contains("networkAvailable = null"));
        assertTrue(source.contains("updatePresenceLocked(false)"));
    }

    private static String method(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue("Missing method start: " + startNeedle, start >= 0);
        assertTrue("Missing method end: " + endNeedle, end > start);
        return source.substring(start, end);
    }

    private static String controller() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "phone", "PhoneConnectorController.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status",
                "widget", "phone", "PhoneConnectorController.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
