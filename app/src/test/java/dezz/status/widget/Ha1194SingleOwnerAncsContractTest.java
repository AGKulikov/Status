/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the HA1194 + Helper v32 single-owner ANCS implementation. */
public final class Ha1194SingleOwnerAncsContractTest {
    @Test public void androidUsesExactIncomingDeviceWithoutHandoff() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String ready = between(transport,
                "private boolean canAcceptAncsReady",
                "private void scheduleSecureClientStart");
        String attach = between(transport,
                "private void startSamePeerAttach",
                "private void scheduleDirectFallback");

        assertFalse(transport.contains("ANCS-HANDOFF"));
        assertFalse(transport.contains("awaitingAncsHandoffReconnect"));
        assertTrue(ready.contains("findConnectedServerPeer(device) != null"));
        assertTrue(ready.contains("BluetoothDevice exactIncomingDevice = serverLink.device"));
        assertTrue(attach.contains("BluetoothDevice device = serverLink.device"));
        assertTrue(attach.contains("EXACT SAME VERIFIED BluetoothDevice"));
    }

    @Test public void ordinaryDisconnectPreservesPublicationAndNamespace() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String preserve = between(transport,
                "private void preserveManagedIncomingPublicationAfterLinkLoss",
                "private static String deviceKey");

        assertTrue(preserve.contains("resetVerifiedPeerSession()"));
        assertTrue(preserve.contains("managedIncomingMode = true"));
        assertTrue(preserve.contains("GATT server, реклама и namespace"));
        assertFalse(preserve.contains("stopAdvertising()"));
        assertFalse(preserve.contains("closeGattServer()"));
        assertFalse(preserve.contains("rotateManagedIncomingDiagnosticNamespace"));
        assertTrue(transport.contains("exactClientRoleOwnsPhysicalLink"));
        assertTrue(transport.contains("logical peer and B4 wake CCCD retained"));
    }

    @Test public void androidGreenNeedsB4SubscriptionAndValidBatteryNetwork() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String ready = between(transport,
                "public boolean isAncsReady()",
                "private boolean claimVerifiedPeer");
        String telemetry = between(transport,
                "private boolean acceptHelperTelemetryFrame",
                "private void finishAncsReadySetup");

        assertTrue(ready.contains("iphoneHelperTelemetrySubscribed"));
        assertTrue(ready.contains("iphoneHelperValidTelemetryReceived"));
        assertTrue(ready.contains("helperAncsReadyProofAcknowledged"));
        assertTrue(telemetry.contains("IphoneHelperTelemetry.Kind.SNAPSHOT"));
        assertTrue(telemetry.contains("telemetry.batteryLevel != null"));
        assertTrue(telemetry.contains("!telemetry.networkType.trim().isEmpty()"));
    }

    @Test public void helperHasOnePersistentRequiresAncsOwner() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v32/"
                + "KX11ANCSHelper/ViewController.swift");
        String connect = between(helper,
                "private func connectCentral",
                "private func beginCentralDiscovery");
        String observer = between(helper,
                "private func armCentralConnectTimeout",
                "private func scheduleCentralReconnect");
        String restored = between(helper,
                "guard let restored = preferred",
                "func centralManager(_ central: CBCentralManager, didDiscover peripheral");

        assertEquals(1, count(helper, "centralManager.connect(peripheral, options: options)"));
        assertTrue(connect.contains("CBConnectPeripheralOptionRequiresANCS: true"));
        assertTrue(connect.contains("CBConnectPeripheralOptionEnableAutoReconnect"));
        assertTrue(helper.contains("Data(\"ANCS-READY\".utf8)"));
        assertFalse(helper.contains("ANCS-HANDOFF"));
        assertFalse(helper.contains("centralLinkPhase"));
        assertTrue(observer.contains("pending owner не отменяю"));
        assertFalse(observer.contains("cancelPeripheralConnection"));
        assertTrue(restored.contains("geelyPeripheral = restored"));
        assertTrue(restored.contains("Central restore owner retained"));
        assertFalse(restored.contains("cancelPeripheralConnection(restored)"));
        assertTrue(helper.contains("peripheral.stable"));
        assertTrue(helper.contains("central.stable"));
        assertTrue(helper.contains("centralSystemAutoReconnectActive"));
        assertTrue(observer.contains("System AutoReconnect остаётся владельцем"));
        assertTrue(helper.contains("Do not adopt retrieveConnectedPeripherals here"));
        assertFalse(helper.contains("centralManager.retrieveConnectedPeripherals("));
    }

    @Test public void helperSubscribesToAndroidWakeForBackgroundTelemetry() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v32/"
                + "KX11ANCSHelper/ViewController.swift");
        String characteristics = between(helper,
                "private func useCurrentCentralCharacteristics",
                "private func centralErrorDescription");
        String wake = between(helper,
                "private func enableCentralWakeSubscription",
                "private func responseData");

        assertTrue(characteristics.contains("centralWakeUUID"));
        assertTrue(characteristics.contains("currentWake.properties.contains(.notify)"));
        assertTrue(wake.contains("peripheral.setNotifyValue(true, for: wake)"));
        assertTrue(wake.contains("CCCD callback timeout"));
        assertTrue(helper.contains("KX11 background wake poll"));
    }

    @Test public void helperGreenNeedsAllFiveProofsAndIsBuild32() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v32/"
                + "KX11ANCSHelper/ViewController.swift");
        String readiness = between(helper,
                "private func centralReadyForGreen",
                "private func refreshCentralReadiness");
        String helperProject = project("ios/KX11-iPhone-ANCS-Helper-v32/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");

        assertTrue(readiness.contains("centralHelperConfirmed"));
        assertTrue(readiness.contains("centralAncsCccdConfirmed"));
        assertTrue(readiness.contains("centralB4Subscribed"));
        assertTrue(readiness.contains("valid.battery"));
        assertTrue(readiness.contains("valid.network"));
        assertTrue(helperProject.contains("MARKETING_VERSION = 32.0"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 32"));
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1196'"));
    }

    private static int count(String source, String value) {
        int total = 0;
        for (int index = source.indexOf(value); index >= 0;
             index = source.indexOf(value, index + value.length())) {
            total++;
        }
        return total;
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
