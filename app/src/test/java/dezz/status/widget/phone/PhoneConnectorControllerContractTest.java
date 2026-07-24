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

/** Source contract for Android Bluetooth/GATT APIs that local JVM stubs cannot execute. */
public final class PhoneConnectorControllerContractTest {
    @Test public void onlyTheExplicitBondedAddressCanOwnAnyPhoneState() throws IOException {
        String source = controller();
        assertTrue(source.contains("if (configuredAddress.isEmpty())"));
        assertTrue(source.contains("\"no_configured_phone\""));
        assertTrue(source.contains("requested.equalsIgnoreCase(address)"));
        assertTrue(source.contains("if (!isSelected(device)) return;"));
        assertTrue(source.contains("matchesConfiguredAddress(changed)"));
        assertTrue(source.contains("callbackGatt != gatt"));
        assertFalse(source.contains("name.contains(\"iphone\")"));
        assertFalse(source.contains("bonded.size() == 1"));
    }

    @Test public void exactPresenceAggregatesAclHfpMapAndGattAcrossRestart() throws IOException {
        String source = controller();
        assertTrue(source.contains(
                "aclConnected || hfpConnected || mapConnected || gattConnected"));
        assertTrue(source.contains("PROFILE_HEADSET_CLIENT = 16"));
        assertTrue(source.contains("PROFILE_MAP_CLIENT = 18"));
        assertTrue(source.contains("proxy.getConnectedDevices()"));
        assertTrue(source.contains("if (isSelected(device))"));
        assertTrue(source.contains("PresenceSink"));
        assertTrue(source.contains("updatePresenceLocked(false)"));
        assertTrue(source.contains("hfpConnected = false"));
        assertTrue(source.contains("if (mapConnected) endMapSession(\"disconnected\")"));
    }

    @Test public void sessionBarrierAndSerializedGattPreventStaleOverwrite() throws IOException {
        String source = controller();
        assertTrue(source.contains("runIfCurrent(token, action)"));
        assertTrue(source.contains("generation == token"));
        assertTrue(source.contains("currentGattOperation != null"));
        assertTrue(source.contains("gattOperations.poll()"));
        assertTrue(source.contains("ANCS_DATA"));
        assertTrue(source.contains("ANCS_NOTIFICATION"));
        assertTrue(source.contains("ATTRIBUTE_TIMEOUT_MS"));
        assertTrue(source.contains("activeAncsRequestSequence"));
        assertTrue(source.contains("operation.requestSequence"));
        assertTrue(source.contains("MAX_NOTIFICATIONS = 50"));
        assertTrue(source.contains("clearAncsRuntime()"));
        assertTrue(count(source, "callbackGatt == gatt") >= 3);
        assertTrue(source.contains("operation.descriptor != callbackDescriptor"));
        assertTrue(source.contains("operation.characteristic != callbackCharacteristic"));
        assertTrue(source.contains("GATT operation timed out:"));
    }

    @Test public void ancsHandshakeHandlesImmediateEventsAndChangingGattServices()
            throws IOException {
        String source = controller();
        String eventHandler = between(source, "private void handleAncsEvent",
                "private void handleServiceChanged");
        assertTrue(source.contains("requestMtu(DESIRED_GATT_MTU)"));
        assertTrue(source.contains("callbackGatt != gatt || !mtuPending"));
        assertTrue(source.contains("if (!serviceDiscoveryStarted) return;"));
        assertTrue(source.contains("DESIRED_GATT_MTU = 512"));
        assertTrue(source.contains("GENERIC_ATTRIBUTE_SERVICE"));
        assertTrue(source.contains("SERVICE_CHANGED"));
        assertTrue(source.contains("ENABLE_INDICATION_VALUE"));
        assertTrue(source.contains("ancsNotificationListening = true"));
        assertTrue(eventHandler.contains("!ancsNotificationListening"));
        assertFalse(eventHandler.contains("!ancsReady"));
        assertTrue(source.contains("boolean autoConnect = ancsAuthorizedThisRun"));
        assertTrue(source.contains("forceDirectGatt = true"));
        assertTrue(source.contains("deviceRescanTask != null"));
        assertTrue(source.contains("gattReconnectTask != null"));
        assertTrue(source.contains("serviceChangedSubscribed ? \"ready\" : \"ready_degraded\""));
    }

    @Test public void privacyModeAndAppPresentationRemainSourceOnly() throws IOException {
        String source = controller();
        assertTrue(source.contains("notificationAttributeRequest(uid, includeText)"));
        assertTrue(source.contains("fullTextAttributeUids.contains(uid)"));
        assertTrue(source.contains("needsMessageTextFollowUp"));
        assertTrue(source.contains("current.notificationsEnabled\n"
                + "                || current.messagesEnabled && appleMessage"));
        assertTrue(source.contains("queueAppDisplayName(notification.appIdentifier)"));
        assertTrue(source.contains("new AncsProtocol.AppAttributeAccumulator(appIdentifier)"));
        assertTrue(source.contains("PhoneAppCatalog.iconResource("));
        assertTrue(source.contains("PhoneAppCatalog.iconKey("));
        assertTrue(source.contains("value.put(\"app_id\""));
        assertTrue(source.contains("value.put(\"app_name\""));
        assertTrue(source.contains("value.put(\"received_at\""));
        assertTrue(source.contains("\"diagnostics.last_app\""));
        assertTrue(source.contains("ANCS_INVALID_PARAMETER = 0xA2"));
        assertTrue(source.contains("basBatteryUpdatedAt >= hfpBatteryUpdatedAt"));
        assertTrue(source.contains("genericBatteryUpdatedAt"));
        assertTrue(source.contains("SystemClock.elapsedRealtime()"));
        assertTrue(source.contains("clearGenericBatteryData()"));
    }

    @Test public void batteryNetworkSmsAndNotificationFallbacksFailClosed() throws IOException {
        String source = controller();
        assertTrue(source.contains("ACTION_DEVICE_BATTERY_LEVEL_CHANGED"));
        assertTrue(source.contains("PhoneConnectorPolicy.normalizeHfpBattery"));
        assertTrue(source.contains("NETWORK_SIGNAL_STRENGTH"));
        assertTrue(source.contains("networkOperator = \"\""));
        assertTrue(source.contains("ACTION_MAP_MESSAGE_RECEIVED"));
        assertTrue(source.contains("handleMapMessage(token, intent)"));
        assertTrue(source.contains("newMessage && !ancsReady"));
        assertTrue(source.contains("isAppleMessagesApp"));
        assertTrue(source.contains("\"com.apple.messages\".equals(normalized)"));
        assertTrue(source.contains("current.includeNotificationText && text.isEmpty()"));
        assertTrue(source.contains("if (!ancsReady) ordered.addAll(mapMessageCache.values())"));
        assertFalse(source.contains("Manifest.permission.READ_SMS"));
        assertFalse(source.contains("ContentResolver"));
        assertTrue(source.contains("NotificationManager.IMPORTANCE_LOW"));
        assertTrue(source.contains("CHANNEL_ID = \"phone_mirror\""));
        assertTrue(source.contains("R.drawable.ic_status_bt_connected"));
        assertTrue(source.contains("cancelAllMirroredNotifications()"));
    }

    @Test public void registrySnapshotKeepsEveryStableResourceExplicit() throws IOException {
        String source = controller();
        for (String resource : new String[] {
                "connected", "battery.level", "battery.charging",
                "network.available", "network.operator", "network.type",
                "network.signal", "network.roaming", "notifications.count",
                "notifications.latest", "notifications.items", "messages.unread",
                "messages.latest", "diagnostics.device", "diagnostics.ancs",
                "diagnostics.sms", "diagnostics.last_app", "diagnostics.last_error"
        }) {
            assertTrue("Missing resource " + resource,
                    source.contains("\"" + resource + "\""));
        }
        assertTrue(source.contains("ConnectorType.PHONE, CONNECTOR_ID"));
        assertTrue(source.contains("SourceBinding.DEFAULT_CONNECTOR_ID"));
        assertTrue(source.contains("device.put(\"address\", maskedAddress(selectedAddress))"));
        assertFalse(source.contains("device.put(\"address\", selectedAddress)"));
    }

    @Test public void smsOnlyModeStillStartsExactDeviceAncs() throws IOException {
        String source = controller();
        assertTrue(source.contains("boolean ancsNeeded()"));
        assertTrue(source.contains("return notificationsEnabled || messagesEnabled"));
        assertTrue(source.contains("if (current.ancsNeeded()) ancsStatus = \"connecting\""));
        assertTrue(source.contains("if (config == null || !config.ancsNeeded())"));
        assertTrue(source.contains("isAppleMessagesApp(record.notification.appIdentifier)"));
        assertTrue(source.contains("current.notificationsEnabled\n"
                + "                || current.messagesEnabled && appleMessage"));
        assertTrue(source.contains("Notification.CATEGORY_MESSAGE"));
    }

    @Test public void mapSessionExistsBeforeUnreadBackfillIsRequested() throws IOException {
        String source = controller();
        int callback = source.indexOf("onServiceConnected(int connectedProfile");
        int begin = source.indexOf("if (!mapConnected) beginMapSession()", callback);
        int request = source.indexOf("requestUnreadMapMessages(proxy, exactDevice)", callback);
        assertTrue(begin > callback);
        assertTrue(request > begin);
    }

    private static int count(String value, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }

    private static String between(String source, String startNeedle, String endNeedle) {
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
