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

/** Release gate for the event-driven, stable-identity ANCS connection architecture. */
public final class Ha1199ReferenceAncsConnectionContractTest {
    @Test public void androidUsesStableAnchorAndEventDrivenClientAttach() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");

        String publication = between(transport,
                "private boolean startGeelyAncsAdvertising",
                "/** Allocates one persistent namespace");
        assertTrue(publication.contains("useStaticDiagnosticNamespace();"));
        assertFalse(publication.contains("rotateManagedIncomingDiagnosticNamespace();"));

        String callback = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "private final BluetoothGattCallback gattCallback");
        assertTrue(callback.contains("attachAncsClientToIncomingOwner(device);"));
        assertFalse(callback.contains("scheduleManagedIncomingRestart("));

        String attach = between(transport,
                "private void attachAncsClientToIncomingOwner",
                "private boolean isVerifiedPeer");
        assertTrue(attach.contains("adoptIncomingClientCandidate(device,"));
        assertFalse(attach.contains("secureAttConfirmed = true"));
        assertFalse(attach.contains("connectGatt("));
        assertFalse(attach.contains("discoverServices("));
        assertFalse(attach.contains("handlePairCommand"));

        String adopt = between(transport,
                "private void adoptIncomingClientCandidate",
                "private void maybeStartIncomingClientAttachAfterServicePublished");
        int retainCandidate = adopt.indexOf("incomingClientCandidate = device;");
        int publicationBarrier = adopt.indexOf(
                "isCurrentDiagnosticServicePublicationToken(");
        int startAfterBarrier = adopt.indexOf(
                "maybeStartIncomingClientAttachAfterServicePublished(reason)");
        assertTrue(retainCandidate >= 0);
        assertTrue(publicationBarrier > retainCandidate);
        assertTrue(startAfterBarrier > publicationBarrier);

        String postPublication = between(transport,
                "private void maybeStartIncomingClientAttachAfterServicePublished",
                "private boolean isVerifiedPeer");
        int currentToken = postPublication.indexOf(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)");
        int currentFacade = postPublication.indexOf(
                "findConnectedServerPeer(candidate) == null");
        int directAttach = postPublication.indexOf("startSamePeerAttach(false,");
        assertTrue(currentToken >= 0);
        assertTrue(currentFacade > currentToken);
        assertTrue(directAttach > currentFacade);
    }

    @Test public void helperLeavesPendingAndSystemReconnectOwnedByCoreBluetooth()
            throws Exception {
        String helper = project(
                "ios/KX11-iPhone-ANCS-Helper-v36/KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("KX11 ANCS HELPER v36"));
        assertTrue(helper.contains("CBConnectPeripheralOptionRequiresANCS: true"));
        assertTrue(helper.contains("CBConnectPeripheralOptionEnableAutoReconnect"));
        assertTrue(helper.contains("retrieveConnectedPeripherals"));
        assertEquals(1, occurrences(helper,
                "centralManager.connect(peripheral, options: options)"));

        String connect = between(helper,
                "private func connectCentral", "private func beginCentralDiscovery");
        assertFalse(connect.contains("armCentralConnectTimeout"));
        assertFalse(connect.contains("cancelCentralConnectionSafely(peripheral"));

        String connected = between(helper,
                "private func continueCentralConnected", "private func startCentralRouteIfPossible");
        assertTrue(connected.contains("centralHelperConfirmed = true"));
        assertFalse(connected.contains("discoverServices"));
        assertFalse(connected.contains("discoverCharacteristics"));

        String disconnect = between(helper,
                "private func handleCentralDisconnect", "didUpdateANCSAuthorizationFor");
        assertTrue(disconnect.contains("if isReconnecting"));
        assertFalse(disconnect.contains("armCentralConnectTimeout"));
        assertFalse(disconnect.contains("resetCentralLink"));
    }

    @Test public void helperPreservesIdentityAndRestorationContract() throws Exception {
        String delegate = project(
                "ios/KX11-iPhone-ANCS-Helper-v36/KX11ANCSHelper/AppDelegate.swift");
        assertTrue(delegate.contains("_ = controller.view"));

        String project = project(
                "ios/KX11-iPhone-ANCS-Helper-v36/KX11ANCSHelper.xcodeproj/project.pbxproj");
        assertTrue(project.contains("CURRENT_PROJECT_VERSION = 36;"));
        assertTrue(project.contains("MARKETING_VERSION = 36.0;"));
        assertTrue(project.contains("PRODUCT_BUNDLE_IDENTIFIER = ru.natro.kx11ancshelper;"));
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
