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

/** Source contract for phone selection, profile data and presentation behavior. */
public final class PhoneConnectorControllerContractTest {
    @Test public void onlyTheExplicitBondedAddressCanOwnAnyPhoneState() throws IOException {
        String source = controller();
        assertTrue(source.contains("if (configuredAddress.isEmpty())"));
        assertTrue(source.contains("\"no_configured_phone\""));
        assertTrue(source.contains("requested.equalsIgnoreCase(address)"));
        assertTrue(source.contains("if (!isSelected(device)) return;"));
        assertTrue(source.contains("matchesConfiguredAddress(changed)"));
        assertFalse(source.contains("name.contains(\"iphone\")"));
        assertFalse(source.contains("bonded.size() == 1"));
    }

    @Test public void exactPresenceAggregatesAclA2dpHfpMapAndGattAcrossRestart()
            throws IOException {
        String source = controller();
        assertTrue(source.contains(
                "aclConnected || a2dpConnected || hfpConnected\n"
                        + "                || mapConnected || gattConnected"));
        assertTrue(source.contains("PROFILE_HEADSET_CLIENT = 16"));
        assertTrue(source.contains("PROFILE_MAP_CLIENT = 18"));
        assertTrue(source.contains("proxy.getConnectedDevices()"));
        assertTrue(source.contains("if (isSelected(device))"));
        assertTrue(source.contains("PresenceSink"));
        assertTrue(source.contains("updatePresenceLocked(false)"));
        assertTrue(source.contains("updateAncsPresenceLocked(false)"));
        assertTrue(source.contains("updateAncsPresenceLocked(ancsReady)"));
        assertTrue(source.contains("onAncsConnectionChanged(boolean connected)"));
        assertTrue(source.contains("hfpConnected = false"));
        assertTrue(source.contains("if (mapConnected) endMapSession(\"disconnected\")"));
    }

    @Test public void stockEcarxConnectionIsBestEffortAndNeverUnpairs() throws IOException {
        String source = javaSource("PhoneOemConnectionBridge.java");
        assertTrue(source.contains(
                "\"com.ecarx.xui.adaptapi.device.Device\""));
        assertTrue(source.contains("getMethod(\"reqBtPair\", String.class)"));
        assertTrue(source.contains("getMethod(\"reqBtPairedDevices\")"));
        assertTrue(source.contains("getMethod(\"getAddress\")"));
        assertTrue(source.contains("PSDBluetoothManager.requestConnect()"));
        assertTrue(source.contains("PHONE_NOT_REGISTERED"));
        assertTrue(source.contains("RequestResult"));
        assertFalse(source.contains("reqBtUnpair"));
        String controller = controller();
        assertTrue(controller.contains("beginStockConnectionRequest(token, selectedAddress)"));
        assertTrue(controller.contains(
                "PhoneOemConnectionBridge.requestStockConnection(context, address)"));
        assertTrue(controller.contains("stockConnectionRequestInProgress"));
        assertTrue(controller.contains(
                "PhoneConnectorPolicy.stockConnectionSettleMillis()"));
        assertTrue(controller.contains(
                "PhoneConnectorPolicy.stockConnectionMaxAttempts()"));
        assertTrue(controller.contains(
                "|| stockConnectionRequestInProgress) return;"));
    }

    @Test public void missingAndroidIphoneBatteryFallsBackToExactEcarxHeadsetPower()
            throws IOException {
        String bridge = javaSource("PhoneOemConnectionBridge.java");
        String controller = controller();

        assertTrue(bridge.contains("observeHeadsetPower("));
        assertTrue(bridge.contains("\"registerBtCallback\""));
        assertTrue(bridge.contains("\"unregisterBtCallback\""));
        assertTrue(bridge.contains("\"getHeadsetPower\""));
        assertTrue(bridge.contains("\"onDevicePowerUpdated\""));
        assertTrue(bridge.contains("address.equalsIgnoreCase(deviceAddress(device))"));
        assertTrue(bridge.contains("deliverCurrentPower(extension, getPower, address, device"));
        assertTrue(bridge.contains("unregister.invoke(extension, callback)"));
        assertFalse(bridge.contains("setAudioAttributes"));
        assertFalse(bridge.contains("setUsage(31)"));

        assertTrue(controller.contains("startOemPowerObservation(token, selectedAddress)"));
        assertTrue(controller.contains("PhoneOemConnectionBridge.observeHeadsetPower("));
        assertTrue(controller.contains("applyOemHeadsetPower("));
        assertTrue(controller.contains("PhoneConnectorPolicy.normalizeHfpBattery(rawPower)"));
        assertTrue(controller.contains("replaceOemPowerObservation(null)"));
        assertTrue(controller.contains("closeOemObservation(oldOemObservation)"));
    }

    @Test public void presentationKeepsDisplayNameTitleAndMessageStrictlySeparated()
            throws IOException {
        String source = controller();
        String incoming = between(source, "private void handleAncsNotificationFields",
                "private void handleAncsTransportAppName");
        String mirror = between(source, "private void mirrorAncsNotification",
                "private void mirrorSmsNotification");

        assertTrue(source.contains("config.notificationsEnabled\n"
                + "                || config.messagesEnabled && appleMessage"));
        assertTrue(incoming.contains("bounded(title, 4096)"));
        assertTrue(incoming.contains("bounded(message, 4096)"));
        assertTrue(incoming.contains("boolean hasDisplayName"));
        assertTrue(incoming.contains("new NotificationRecord(\n"
                + "                notification, categoryId, System.currentTimeMillis(), false,\n"
                + "                observedAtElapsedMs, iconObservation.iconWasCached)"));
        assertTrue(incoming.contains(
                "if (hasDisplayName) {\n"
                        + "            presentAncsNotification(token, record, true)"));
        assertTrue(incoming.contains("scheduleUnresolvedNotificationExpiry(token, record)"));
        assertTrue(source.contains("cacheAppDisplayName(appIdentifier, displayName)"));
        assertTrue(source.contains("if (!record.presented)"));
        assertTrue(source.contains("presentAncsNotification(token, record, false)"));
        assertTrue(source.contains("APP_DISPLAY_NAME_WAIT_TIMEOUT_MS = 15_000L"));
        assertTrue(source.contains("observedAtElapsedMs"));
        assertTrue(source.contains("isUnresolvedNotificationExpired(record)"));
        assertTrue(source.contains("APP_DISPLAY_NAME_WAIT_TIMEOUT_MS - age"));
        assertTrue(source.contains("NotificationRecord current = notificationCache.get(uid)"));
        assertTrue(source.contains("if (current != expected || current.presented) return"));
        assertTrue(source.contains("notificationCache.remove(uid)"));
        assertTrue(mirror.contains(".setSubText(appName)"));
        assertTrue(mirror.contains(".setContentTitle(record.notification.title)"));
        assertTrue(mirror.contains(".setContentText(record.notification.message)"));
        assertTrue(mirror.contains(".bigText(record.notification.message)"));
        assertFalse(mirror.contains("firstNonEmpty("));
        assertTrue(source.contains("PhoneAppCatalog.iconResource("));
        assertTrue(source.contains("PhoneAppCatalog.iconKey("));
        assertTrue(source.contains("value.put(\"app_id\""));
        assertTrue(source.contains("value.put(\"app_name\""));
        assertTrue(source.contains("value.put(\"application\", application)"));
        assertTrue(source.contains("value.put(\"topic\", item.notification.title)"));
        assertTrue(source.contains("value.put(\"text\", item.notification.message)"));
        assertTrue(source.contains("value.put(\"received_at\""));
        assertTrue(source.contains("\"diagnostics.last_app\""));
        assertTrue(source.contains("helperPowerUpdatedAtElapsed > 0L"));
        assertTrue(source.contains("PhoneBatteryLevelPolicy.resolve("));
        assertTrue(source.contains("batteryLevelSource = reading.source"));
        assertFalse(source.contains("batteryLevelSource = \"iphone_helper\""));
        assertTrue(source.contains(
                "String effectiveNetworkType = helperNetworkUpdatedAtElapsed > 0L"));
        assertTrue(source.contains("SystemClock.elapsedRealtime()"));
        assertTrue(source.contains("clearGenericBatteryData()"));
    }

    @Test public void batteryNetworkSmsAndNotificationFallbacksFailClosed() throws IOException {
        String source = controller();
        assertTrue(source.contains("ACTION_DEVICE_BATTERY_LEVEL_CHANGED"));
        assertTrue(source.contains("PhoneConnectorPolicy.normalizeHfpBattery"));
        assertFalse(source.contains("inferChargingFromLevelTrend"));
        assertFalse(source.contains("BATTERY_TREND_MAX_AGE_MS"));
        assertFalse(source.contains("batteryChargingSource = batteryTrendSource"));
        assertTrue(source.contains(
                "batteryChargingEstimated = batteryCharging == null ? null : false"));
        assertFalse(source.contains("selectBasChargingState"));
        assertTrue(source.contains("Cable/charging state remains Helper-only"));
        assertFalse(source.contains("METADATA_MAIN_CHARGING = 19"));
        assertTrue(source.contains("HELPER_TELEMETRY_TIMEOUT_MS"));
        assertFalse(source.contains("decodeBluetoothChargingMetadata"));
        assertFalse(source.contains("batteryChargingSource = \"android_metadata\""));
        assertTrue(source.contains("batteryChargingSource = \"iphone_helper\""));
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
                "device.name", "profiles.hfp", "profiles.map", "profiles.ble",
                "profiles.ancs", "battery.level_source",
                "battery.charging_estimated", "battery.charging_source",
                "battery.external_power", "battery.charge_state",
                "battery.charge_level", "call.active", "call.state",
                "call.direction", "call.multiparty", "call.audio",
                "call.audio_state", "call.audio_wideband",
                "voice_assistant.active", "ringtone.in_band",
                "network.available", "network.operator", "network.type",
                "network.signal", "network.roaming", "telemetry.stale",
                "telemetry.updated_at", "notifications.count",
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
        assertTrue(source.contains("device.put(\"stock_connection\""));
        assertTrue(source.contains("device.put(\"ancs_setup\""));
        assertFalse(source.contains("device.put(\"address\", selectedAddress)"));
    }

    @Test public void hfpInitialSnapshotAndLiveEventsExposePrivacySafeCallState()
            throws IOException {
        String source = controller();
        assertTrue(source.contains("ACTION_HFP_AUDIO_STATE"));
        assertTrue(source.contains("ACTION_HFP_CALL_CHANGED"));
        assertTrue(source.contains("\"getCurrentAgEvents\""));
        assertTrue(source.contains("\"getCurrentCalls\""));
        assertTrue(source.contains("\"getAudioState\""));
        assertTrue(source.contains("applyInitialHfpState("));
        assertTrue(source.contains("reflectedInt(rawCall, \"getState\")"));
        assertTrue(source.contains("reflectedBoolean(rawCall, \"isOutgoing\")"));
        assertTrue(source.contains("reflectedBoolean(rawCall, \"isMultiParty\")"));
        assertFalse(source.contains("reflected(rawCall, \"getNumber\")"));
    }

    @Test public void messageModeKeepsAppleMessageEligibilityStrict() throws IOException {
        String source = controller();
        assertTrue(source.contains("boolean ancsNeeded()"));
        assertTrue(source.contains("return notificationsEnabled || messagesEnabled"));
        assertTrue(source.contains("isAppleMessagesApp(record.notification.appIdentifier)"));
        assertTrue(source.contains("config.notificationsEnabled\n"
                + "                || config.messagesEnabled && appleMessage"));
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

    private static String between(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue("Missing method start: " + startNeedle, start >= 0);
        assertTrue("Missing method end: " + endNeedle, end > start);
        return source.substring(start, end);
    }

    private static String controller() throws IOException {
        return javaSource("PhoneConnectorController.java");
    }

    private static String javaSource(String name) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "phone", name);
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status",
                "widget", "phone", name);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
