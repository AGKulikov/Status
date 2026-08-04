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
        assertTrue(source.contains("updateAncsPresenceLocked(false)"));
        assertTrue(source.contains("updateAncsPresenceLocked(ancsReady)"));
        assertTrue(source.contains("onAncsConnectionChanged(boolean connected)"));
        assertTrue(source.contains("hfpConnected = false"));
        assertTrue(source.contains("if (mapConnected) endMapSession(\"disconnected\")"));
    }

    @Test public void transportSessionBarrierPreventsStaleCallbacksAndDuplicateClients()
            throws IOException {
        String source = controller();
        String ensureGatt = between(source, "private void ensureGatt",
                "private void ensureLegacyBatteryGatt");

        assertTrue(source.contains("runIfCurrent(token, action)"));
        assertTrue(source.contains("generation == token"));
        assertTrue(source.contains("activeAncsTransportSession"));
        assertTrue(source.contains("transportSession != activeAncsTransportSession"));
        assertTrue(source.contains("ancsTransportStartPending"));
        assertTrue(source.contains("ancsTransport == null"));
        assertTrue(source.contains("activeAncsTransportSession = ++nextAncsTransportSession"));
        assertTrue(source.contains("new AncsTransportListener(token, transportSession)"));
        assertTrue(source.contains("dispatchAncsTransport(token, transportSession"));
        assertTrue(source.contains("closeAncsTransportOnMain(previous)"));
        assertTrue(ensureGatt.contains("ancsTransport != null"));
        assertTrue(ensureGatt.contains("ancsTransportStartPending"));
        assertFalse(ensureGatt.contains("selectedDevice.connectGatt("));
        assertFalse(ensureGatt.contains("scheduleConnectWatchdog("));
    }

    @Test public void batteryFallbackIsPreservedInBothAncsAndBatteryOnlyModes()
            throws IOException {
        String source = controller();
        String transport = transport();
        String batteryOnly = between(source, "private void ensureLegacyBatteryGatt",
                "private void startAncsTransportOnMain");
        String transportListener = between(source, "private final class AncsTransportListener",
                "private void dispatchAncsTransport");
        String services = between(transport, "private void handleServices",
                "private void subscribeServiceChangedIfAvailable");
        String changed = between(transport, "private void handleCharacteristicChanged",
                "private static boolean descriptorMatchesStage");

        assertTrue(transport.contains(
                "UUID.fromString(\"0000180f-0000-1000-8000-00805f9b34fb\")"));
        assertTrue(transport.contains(
                "UUID.fromString(\"00002a19-0000-1000-8000-00805f9b34fb\")"));
        assertFalse(transport.contains(
                "UUID.fromString(\"00002a1a-0000-1000-8000-00805f9b34fb\")"));
        assertTrue(transport.contains(
                "UUID.fromString(\"00002bed-0000-1000-8000-00805f9b34fb\")"));
        assertTrue(transport.contains("startOptionalBatteryRead("));
        assertTrue(transport.contains("startOptionalBatterySubscription("));
        assertTrue(transport.contains("batteryReadPendingUuid != null"));
        assertTrue(transport.contains("isBatteryDescriptorStage(descriptorStage)"));
        assertTrue(transport.contains("advanceBatteryBootstrapIfIdle()"));
        assertTrue(services.indexOf("prepareBatteryBootstrap(callbackGatt)")
                < services.indexOf("callbackGatt.getService(AncsProtocol.SERVICE)"));
        assertTrue(transport.contains(
                "subscribeServiceChangedIfAvailable(callbackGatt);\n"
                        + "        sendNextRequest();"));
        assertTrue(changed.contains("if (gattClientConnected && value != null)"));
        assertTrue(transport.contains(
                "listener.onBatteryCharacteristic(uuid, copy)"));
        assertTrue(transport.contains(
                "listener.onBatteryCharacteristic(uuid, value.clone())"));
        assertTrue(transport.contains("optional operation skipped, ANCS stays READY"));
        assertFalse(transport.contains("state(\"BAS OPERATION TIMEOUT"));
        assertFalse(source.contains("state.contains(\"BAS OPERATION TIMEOUT\")"));
        assertTrue(transportListener.contains("applyBatteryCharacteristic("));

        assertTrue(source.contains("ensureLegacyBatteryGatt(token)"));
        assertTrue(batteryOnly.contains("selectedDevice.connectGatt(context, autoConnect"));
        assertTrue(batteryOnly.contains("new SessionGattCallback(token)"));
        assertTrue(batteryOnly.contains("config == null || config.transportNeeded()"));
        assertTrue(source.contains("state.contains(\"IPHONE DISCONNECTED\")"));
        assertFalse(source.contains("state.contains(\"AUTO · ЖДУ SAVED PEER\")"));
    }

    @Test public void dedicatedTransportResolvesPrivateAddressAndRecoversInsideOneOwner()
            throws IOException {
        String source = controller();
        String transport = transport();
        String savedPeer = between(transport, "public boolean connectSavedIphone",
                "public boolean acceptIphoneCentral");
        String clientConnect = between(transport, "private void connectIphonePeripheral",
                "public void connect(Candidate candidate)");
        String disconnect = between(transport,
                "private void handleIphonePeripheralConnectionState",
                "private final BluetoothGattCallback gattCallback");
        String connectionCallback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "@Override\n        public void onServicesDiscovered");

        assertTrue(source.contains("created.connectSavedIphone(address)"));
        assertTrue(source.contains("final String address = PhoneBleRole.isIphoneCentral(current.bleRole)"));
        assertTrue(source.contains("? current.ancsDeviceAddress : current.deviceAddress"));
        assertTrue(source.contains("mainHandler.post(() -> startAncsTransportOnMain("));
        assertTrue(savedPeer.contains("adapter.getRemoteDevice(address.trim())"));
        assertFalse(savedPeer.contains("startGeelyAncsAdvertising()"));
        assertTrue(savedPeer.contains("return scheduleColdBackgroundAttach(device,"));
        assertTrue(savedPeer.contains("return startSavedPeerScan(device)"));
        assertTrue(savedPeer.contains("stopScan();"));
        assertTrue(savedPeer.contains("stopAdvertising();"));
        assertFalse(transport.contains(".setDeviceAddress(address)"));
        assertTrue(transport.contains(
                "scanner.startScan(Collections.emptyList(), settings, scanCallback)"));
        assertTrue(transport.contains("ScanSettings.SCAN_MODE_LOW_LATENCY"));
        assertTrue(transport.contains("matchesManagedSavedPeer("));
        assertTrue(transport.contains("AncsReconnectPolicy.candidateMayBeSelected("));
        assertTrue(transport.contains("connectToSavedAdvertisingIphone("));
        assertTrue(transport.contains(
                "connectToSavedAdvertisingIphone(result.getDevice(), solicitsAncs,"));
        assertTrue(transport.contains(
                "matchesManagedSavedPeer(expected, device, solicitsAncs,"));
        assertTrue(transport.contains(
                "connectIphonePeripheral(device, CONNECT_TIMEOUT_MS,"));
        assertTrue(transport.contains("CONNECT_TIMEOUT_MS = 35_000L"));
        assertTrue(clientConnect.contains(
                "device.connectGatt(context, false, gattCallback,"));
        assertTrue(clientConnect.contains("BluetoothDevice.TRANSPORT_LE"));
        assertTrue(clientConnect.contains("activeClientAutoConnect = false"));
        assertTrue(clientConnect.contains("main.postDelayed(connectTimeout, timeoutMs)"));
        assertFalse(clientConnect.contains("autoConnect=true"));
        assertTrue(disconnect.contains("establishedOwner"));
        assertTrue(disconnect.contains("awaitPersistentGattReconnect(callbackGatt"));
        assertTrue(disconnect.contains("closeClientGatt(callbackGatt)"));
        assertTrue(disconnect.contains("state(\"GPS-STYLE · IPHONE DISCONNECTED\")"));
        assertFalse(source.contains("state.contains(\"AUTO · ЖДУ SAVED PEER\")"));
        assertTrue(source.contains("state.contains(\"IPHONE DISCONNECTED\")"));
        assertTrue(source.contains("scheduleGattReconnect(token,"));
        assertTrue(transport.contains("scheduleManagedReconnect(value)"));
        assertTrue(transport.contains("managedReconnectTask != null"));
        assertTrue(transport.contains("LOCAL_LOGICAL_NAME = \"Geely_ANCS\""));
        assertTrue(transport.contains("REMOTE_LOGICAL_NAME = \"iPhone_ANCS\""));
        assertTrue(connectionCallback.contains("main.post(() ->"));
        assertFalse(connectionCallback.contains("postAtFrontOfQueue"));
    }

    @Test public void terminalGattFailuresAndExactAclLossUseOneTypedRetryPath()
            throws IOException {
        String source = controller();
        String transport = transport();
        String broadcast = between(source, "private void handleBluetoothBroadcast",
                "private static BluetoothDevice parcelableDevice");

        assertTrue(transport.contains("default void onRetryRequired(String reason)"));
        assertTrue(transport.contains("if (!closing && !retrySignalled"));
        assertTrue(transport.contains("requiresControllerRetry(value)"));
        assertTrue(transport.contains("listener.onRetryRequired(value)"));
        assertTrue(transport.contains("closing = true"));
        assertTrue(transport.contains("retrySignalled = false"));
        assertTrue(source.contains("@Override public void onRetryRequired(String reason)"));
        assertTrue(source.contains("handleAncsTransportFailure(token,"));
        assertTrue(broadcast.contains("if (!isSelected(device)) return;"));
        assertTrue(broadcast.contains("BluetoothDevice.ACTION_ACL_DISCONNECTED"));
        assertTrue(broadcast.contains("transport != BluetoothDevice.TRANSPORT_BREDR"));
        assertTrue(broadcast.contains("scheduleGattReconnect(token,"));
    }

    @Test public void logicalBleNamesNeverRenameOrRepairClassicBluetooth()
            throws IOException {
        String source = controller();
        String transport = transport();
        String advertising = between(transport,
                "private boolean startGeelyAncsAdvertising",
                "public void startIncomingConnectionTest");

        assertTrue(transport.contains("LOCAL_LOGICAL_NAME = \"Geely_ANCS\""));
        assertTrue(transport.contains("REMOTE_LOGICAL_NAME = \"iPhone_ANCS\""));
        assertTrue(advertising.contains(".addServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE))"));
        assertTrue(advertising.contains(".addServiceData(new ParcelUuid(DIAGNOSTIC_SERVICE)"));
        assertFalse(advertising.contains(".setIncludeDeviceName(true)"));
        assertFalse(transport.contains("adapter.setName("));
        assertTrue(source.contains("\"transport.ancs.local_name\""));
        assertTrue(source.contains("\"transport.ancs.remote_name\""));
        assertTrue(source.contains("current.ancsDeviceAddress"));
    }

    @Test public void protectedAncsSubscriptionsAreSerializedInsideTheTransport()
            throws IOException {
        String transport = transport();
        String services = between(transport, "private void handleServices",
                "private void subscribeServiceChangedIfAvailable");
        String descriptor = between(transport, "private void handleDescriptorWrite",
                "private void handleCharacteristicChanged");

        assertTrue(services.contains("AncsProtocol.DATA_SOURCE"));
        assertTrue(services.contains("AncsProtocol.NOTIFICATION_SOURCE"));
        assertTrue(services.contains("AncsProtocol.CONTROL_POINT"));
        assertTrue(services.contains("descriptorStage = DescriptorStage.DATA_SOURCE"));
        assertTrue(descriptor.contains("descriptorStage = DescriptorStage.NOTIFICATION_SOURCE"));
        assertTrue(descriptor.contains("gattReady = true"));
        assertTrue(descriptor.contains("state(\"ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ\")"));
        assertTrue(transport.contains("isAuthorizationError(status)"));
        assertTrue(transport.contains("requestBond(callbackGatt.getDevice())"));
        assertTrue(transport.contains("scheduleAncsRetryAfterBond("));
        assertTrue(transport.contains(
                "resetBatteryBootstrap();\n"
                        + "            log(\"Повторяю discovery/ANCS-подписку после bond"));
        assertTrue(transport.contains("Service Changed indication включена"));
        assertTrue(transport.contains("DESCRIPTOR_WRITE_TIMEOUT_MS = 15_000L"));
        assertTrue(transport.contains("BOND_TIMEOUT_MS = 90_000L"));
        assertTrue(transport.contains("scheduleDescriptorWriteTimeout(callbackGatt"));
        assertTrue(transport.contains("cancelDescriptorWriteTimeout()"));
        assertTrue(transport.contains("scheduleBondTimeout(device)"));
        assertTrue(transport.contains("cancelBondTimeout()"));
        assertTrue(transport.contains("state(\"CCCD_WRITE_TIMEOUT · \" + expectedStage)"));
        assertTrue(transport.contains("state(\"LE BOND TIMEOUT\")"));
        assertTrue(controller().contains("state.contains(\"CCCD_WRITE_TIMEOUT\")"));
        assertTrue(controller().contains("state.contains(\"LE BOND TIMEOUT\")"));
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

    @Test public void exactEcarxUuidAndBondEventsRefreshTheAncsGattCache()
            throws IOException {
        String bridge = javaSource("PhoneOemConnectionBridge.java");
        String controller = controller();

        assertTrue(bridge.contains("DeviceStateChange.UUIDS_UPDATED"));
        assertTrue(bridge.contains("DeviceStateChange.BOND_STATE_CHANGED"));
        assertTrue(bridge.contains("DeviceStateChange.PAIRED_DEVICES_CHANGED"));
        assertTrue(controller.contains("handleOemDeviceStateChange("));
        assertTrue(controller.contains("refreshGattCache(expected)"));
        assertTrue(controller.contains("oemGattRefreshTask"));
    }

    @Test public void presentationKeepsDisplayNameTitleAndMessageStrictlySeparated()
            throws IOException {
        String source = controller();
        String transport = transport();
        String incoming = between(source, "private void handleAncsTransportNotification",
                "private void handleAncsTransportAppName");
        String mirror = between(source, "private void mirrorAncsNotification",
                "private void mirrorSmsNotification");

        assertTrue(source.contains("current.notificationsEnabled\n"
                + "                || current.messagesEnabled && appleMessage"));
        assertTrue(incoming.contains("bounded(item.title, 4096)"));
        assertTrue(incoming.contains("bounded(item.message, 4096)"));
        assertTrue(incoming.contains("boolean hasDisplayName"));
        assertTrue(incoming.contains("new NotificationRecord(\n"
                + "                notification, item.categoryId, System.currentTimeMillis(), false,\n"
                + "                observedAtElapsedMs, iconObservation.iconWasCached)"));
        assertTrue(incoming.contains(
                "if (hasDisplayName) {\n"
                        + "            presentAncsNotification(token, record, true)"));
        assertTrue(incoming.contains("scheduleUnresolvedNotificationExpiry(token, record)"));
        assertTrue(source.contains("cacheAppDisplayName(appIdentifier, displayName)"));
        assertTrue(source.contains("if (!record.presented)"));
        assertTrue(source.contains("presentAncsNotification(token, record, false)"));
        assertTrue(source.contains("APP_DISPLAY_NAME_WAIT_TIMEOUT_MS = 15_000L"));
        assertTrue(source.contains("item.observedAtElapsedMs"));
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
        assertTrue(transport.contains("appDisplayNameRequest(activeRequest.appIdentifier)"));
        assertTrue(transport.contains("listener.onAppName(activeRequest.appIdentifier, displayName)"));
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

    @Test public void transportDropsPreExistingReplayBeforeAnyAttributeRequest()
            throws IOException {
        String transport = transport();
        String protocol = transportProtocol();
        String notificationSource = between(transport,
                "private void handleNotificationSource", "private void cancelQueuedNotificationRequests");
        String dataSource = between(transport,
                "private void handleDataSource", "private void finishRequest");

        assertTrue(protocol.contains("EVENT_FLAG_PRE_EXISTING = 0x04"));
        assertTrue(transport.contains("AncsProtocol.isPreExisting(event)"));
        assertTrue(notificationSource.contains("!realtimeAdmission.shouldRequest(event)"));
        assertTrue(notificationSource.contains("preExistingDropped++"));
        assertTrue(notificationSource.contains("realtimeAdmission.consumeRemoval(event.uid)"));
        assertTrue(dataSource.contains("listener.onNotification(new NotificationItem("));
        assertTrue(dataSource.contains("realtimeAdmission.markDelivered(result.uid)"));
        assertTrue(dataSource.indexOf("listener.onNotification(new NotificationItem(")
                < dataSource.indexOf("realtimeAdmission.markDelivered(result.uid)"));
    }

    @Test public void realtimeTransportExpiresQueuedItemsAndRefreshesDirtyInflightUids()
            throws IOException {
        String transport = transport();
        String notificationSource = between(transport,
                "private void handleNotificationSource", "private void updateQueuedNotificationAge");
        String send = between(transport,
                "private void sendNextRequest", "private void handleDataSource");
        String data = between(transport,
                "private void handleDataSource", "private void finishRequest");

        assertTrue(transport.contains("LIVE_NOTIFICATION_MAX_AGE_MS = 15_000L"));
        assertTrue(transport.contains("SystemClock.elapsedRealtime()"));
        assertTrue(transport.contains("eventObservedAtElapsedMs"));
        assertTrue(transport.contains("dirtyNotificationUids"));
        assertTrue(notificationSource.contains("dirtyNotificationUids.add(event.uid)"));
        assertTrue(notificationSource.contains(
                "eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs)"));
        assertTrue(send.contains("isExpiredNotification(candidate.observedAtElapsedMs)"));
        assertTrue(data.contains("dirtyNotificationUids.remove(result.uid)"));
        assertTrue(data.contains(
                "enqueuePriorityRequest(Request.notification(latestEvent, latestObservedAt))"));
        assertTrue(data.contains("finishRequest(\"refresh_queued\")"));
        assertTrue(data.contains("isExpiredNotification(observedAtElapsedMs)"));
        assertTrue(data.contains("observedAtElapsedMs));"));
    }

    @Test public void malformedOrTimedOutDataSourceForcesSessionResynchronization()
            throws IOException {
        String source = controller();
        String transport = transport();
        String send = between(transport,
                "private void sendNextRequest", "private void handleDataSource");
        String data = between(transport,
                "private void handleDataSource", "private void finishRequest");
        String abort = between(transport,
                "private void abortAncsRequestStream", "private void requestBond");

        assertTrue(send.contains("abortAncsRequestStream(\"timeout\")"));
        assertTrue(data.contains("abortAncsRequestStream(\"notification_malformed\")"));
        assertTrue(data.contains("abortAncsRequestStream(\"app_malformed\")"));
        assertTrue(abort.contains("clearAncsRuntime()"));
        assertTrue(abort.contains("state(\"ANCS DATA DESYNC · RECONNECT · \" + reason)"));
        assertFalse(abort.contains("sendNextRequest"));
        assertTrue(source.contains("state.contains(\"ANCS DATA DESYNC\")"));
    }

    @Test public void batteryNetworkSmsAndNotificationFallbacksFailClosed() throws IOException {
        String source = controller();
        assertTrue(source.contains("ACTION_DEVICE_BATTERY_LEVEL_CHANGED"));
        assertTrue(source.contains("PhoneConnectorPolicy.normalizeHfpBattery"));
        assertTrue(source.contains("BATTERY_LEVEL_STATUS"));
        assertTrue(source.contains("decodeBatteryLevelStatus"));
        assertFalse(source.contains("inferChargingFromLevelTrend"));
        assertFalse(source.contains("BATTERY_TREND_MAX_AGE_MS"));
        assertFalse(source.contains("batteryChargingSource = batteryTrendSource"));
        assertTrue(source.contains("batteryChargingEstimated = batteryCharging == null ? null : false"));
        assertFalse(source.contains("selectBasChargingState"));
        assertTrue(source.contains("the sole power-state authority"));
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

    @Test public void smsOnlyModeStillStartsExactDeviceAncs() throws IOException {
        String source = controller();
        assertTrue(source.contains("boolean ancsNeeded()"));
        assertTrue(source.contains("return notificationsEnabled || messagesEnabled"));
        assertTrue(source.contains("if (!current.transportNeeded())"));
        assertTrue(source.contains("ancsStatus = \"connecting\""));
        assertTrue(source.contains("created.connectSavedIphone(address)"));
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
        return javaSource("PhoneConnectorController.java");
    }

    private static String transport() throws IOException {
        return transportSource("IphoneAncsTransport.java");
    }

    private static String transportProtocol() throws IOException {
        return transportSource("AncsProtocol.java");
    }

    private static String transportSource(String name) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "phone", "transport", name);
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status",
                "widget", "phone", "transport", name);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
