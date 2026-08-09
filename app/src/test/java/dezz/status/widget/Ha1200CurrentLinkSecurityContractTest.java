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

/** Release gate for the stable-owner PAIR/B3/ANCS-READY recovery architecture. */
public final class Ha1200CurrentLinkSecurityContractTest {
    @Test public void incomingLinkCannotBypassCurrentAclSecurityProof() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");

        String publication = between(transport,
                "private boolean startGeelyAncsAdvertising",
                "/** Allocates one persistent namespace");
        assertTrue(publication.contains("useStaticDiagnosticNamespace();"));
        assertFalse(publication.contains("rotateManagedIncomingDiagnosticNamespace();"));

        String candidate = between(transport,
                "private void attachAncsClientToIncomingOwner",
                "private boolean isVerifiedPeer");
        assertTrue(candidate.contains("ЖДУ PAIR/B3"));
        assertFalse(candidate.contains("secureAttConfirmed = true"));
        assertFalse(candidate.contains("scheduleSecureClientStart();"));
        assertFalse(candidate.contains("claimVerifiedPeer(device)"));

        String ready = between(transport,
                "private void confirmAncsReady",
                "private void scheduleSecureClientStart");
        assertTrue(ready.contains("canAcceptAncsReady"));
        assertTrue(ready.contains("scheduleSecureClientStart();"));
        assertTrue(transport.contains("issueCurrentLinkSecurityChallenge(device)"));
        assertTrue(transport.contains("STATUS_INSUFFICIENT_AUTHENTICATION"));
    }

    @Test public void missingAncsWaitsForServiceChangedWithoutDiscoveryPolling()
            throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String absent = between(transport,
                "if (iphonePeripheralMode && helperBootstrapMode && !iphoneSecureConfirmed)",
                "private void subscribeServiceChangedIfAvailable");
        assertTrue(absent.contains("subscribeServiceChangedIfAvailable(callbackGatt);"));
        assertFalse(absent.contains("scheduleHelperTelemetryRecovery"));
        assertFalse(absent.contains("discoverServices(callbackGatt)"));

        String recovery = between(transport,
                "private void scheduleHelperTelemetryRecovery",
                "private boolean startHelperTelemetryRead");
        assertTrue(recovery.contains("жду Service Changed"));
        assertFalse(recovery.contains("discoverServices(expectedGatt)"));
    }

    @Test public void helperPublishesF05ThenRunsStableF04Handshake() throws Exception {
        String helper = project(
                "ios/KX11-iPhone-ANCS-Helper-v37/KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("KX11 ANCS HELPER v37"));
        assertTrue(helper.contains("CBConnectPeripheralOptionRequiresANCS: true"));
        assertTrue(helper.contains("CBConnectPeripheralOptionEnableAutoReconnect"));
        assertTrue(helper.contains("pendingConnect=system-owned/no-timeout"));
        assertEquals(1, occurrences(helper,
                "centralManager.connect(peripheral, options: options)"));

        String connected = between(helper,
                "private func continueCentralConnected", "private func startCentralRouteIfPossible");
        assertTrue(connected.contains("centralSecureLinkReady = false"));
        assertTrue(connected.contains("centralHelperConfirmed = false"));
        assertTrue(connected.contains("beginCentralDiscovery(peripheral)"));

        String discovery = between(helper,
                "private func beginCentralDiscovery", "private func stopCentralRoute");
        assertTrue(discovery.contains("peripheral.discoverServices([serviceUUID])"));
        assertTrue(helper.contains("Data(\"PAIR\".utf8)"));
        assertTrue(helper.contains("READ CURRENT LINK B3"));
        assertTrue(helper.contains("Data(\"ANCS-READY\".utf8)"));

        String route = between(helper,
                "private func startCentralRouteIfPossible", "private func startCentralScan");
        assertTrue(route.contains("servicePublished"));
        assertTrue(route.contains("publishedServiceUUID == telemetryRelayServiceUUID"));
    }

    @Test public void helperAcceptsProofOnlyFromCurrentSubscribedOwner() throws Exception {
        String helper = project(
                "ios/KX11-iPhone-ANCS-Helper-v37/KX11ANCSHelper/ViewController.swift");
        String writes = between(helper,
                "didReceiveWrite requests", "}\n}");
        assertTrue(writes.contains(
                "request.central.identifier == geelyPeripheral?.identifier"));
        assertTrue(writes.contains(
                "telemetrySubscribers.contains(request.central.identifier)"));
        assertTrue(helper.contains("mutable.subscribedCentrals"));

        String xcode = project(
                "ios/KX11-iPhone-ANCS-Helper-v37/KX11ANCSHelper.xcodeproj/project.pbxproj");
        assertTrue(xcode.contains("CURRENT_PROJECT_VERSION = 37;"));
        assertTrue(xcode.contains("MARKETING_VERSION = 37.0;"));
        assertTrue(xcode.contains("PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;"));
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

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
