/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for direct incoming-link adoption and the independent ANCS discovery gates. */
public final class Ha1201DirectAttachContractTest {
    @Test public void exactBondedIncomingFacadeGetsOneBoundedDirectVirtualOpen()
            throws Exception {
        String transport = transport();
        String candidate = between(transport,
                "private void attachAncsClientToIncomingOwner",
                "private boolean isVerifiedPeer");
        assertTrue(candidate.contains("BOND_BONDED"));
        assertTrue(candidate.contains("adoptIncomingClientCandidate"));
        assertTrue(candidate.contains("startSamePeerAttach(false"));
        assertFalse(candidate.contains("secureAttConfirmed = true"));
        assertFalse(candidate.contains("discoverServices("));

        String direct = between(transport,
                "private void startIncomingDirectAttach",
                "private void scheduleDirectFallback");
        assertTrue(direct.contains("incomingClientCandidate"));
        assertTrue(direct.contains("serverLink.device"));
        assertTrue(direct.contains(
                "device.connectGatt(context, false, gattCallback"));
        assertTrue(direct.contains("INCOMING_DIRECT_ATTACH_TIMEOUT_MS"));
        assertTrue(direct.contains("closeClientGatt(expected);"));
        assertTrue(direct.contains("scheduleIncomingClientAttachRetry"));
        assertFalse(direct.contains("rearmPersistentGattOwner"));
    }

    @Test public void readyAndClientAttachmentAreIndependentDiscoveryGates()
            throws Exception {
        String transport = transport();
        String ready = between(transport,
                "private void confirmAncsReady",
                "private void scheduleSecureClientStart");
        assertTrue(ready.contains("canAcceptAncsReady"));
        assertTrue(ready.contains("incomingAncsReadyGateOpen = true;"));

        String gate = between(transport,
                "private void maybeStartIncomingAncsDiscovery",
                "private void startSamePeerAttach");
        assertTrue(gate.contains("secureAttConfirmed"));
        assertTrue(gate.contains("incomingAncsReadyGateOpen"));
        assertTrue(gate.contains("gattClientConnected"));
        assertTrue(gate.contains("activeClientEstablished"));
        assertTrue(gate.contains("discoverServices(expected);"));

        String discovery = between(transport,
                "private void discoverServices",
                "private boolean startIphonePeripheralSecurity");
        assertTrue(discovery.contains(
                "managedIncomingMode && !incomingAncsReadyGateOpen"));
        assertTrue(discovery.contains("return;"));
    }

    @Test public void reconnectUsesGattConnectOnlyAfterFirstConnectedCallback()
            throws Exception {
        String transport = transport();
        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        assertTrue(callback.contains("if (!activeClientEstablished)"));
        assertTrue(callback.contains("closeClientGatt(callbackGatt);"));
        assertTrue(callback.contains("scheduleIncomingClientAttachRetry("));
        assertTrue(callback.contains("activeClientEstablished = true;"));
        assertTrue(callback.contains("maybeStartIncomingAncsDiscovery(callbackGatt"));

        String persistent = between(transport,
                "private void awaitIncomingBackgroundOwner",
                "private void cancelClientAttemptCallbacks");
        assertTrue(persistent.contains("if (!activeClientEstablished)"));
        assertTrue(persistent.contains("closeClientGatt(expected);"));
        assertTrue(persistent.contains("scheduleIncomingClientAttachRetry(reason);"));
        assertTrue(persistent.contains("rearmPersistentGattOwner("));
    }

    @Test public void serverFacadeStateCannotCarrySecurityProofAcrossPhysicalLinks()
            throws Exception {
        String transport = transport();
        String record = between(transport,
                "private boolean recordGattServerPeer",
                "private void bindServerPeerToCurrentSecurityEpoch");
        assertTrue(record.contains("peer.connected = false;"));
        assertTrue(record.contains("peer.roleFacadeHandoff = establishedHandoff;"));
        assertTrue(record.contains("peer.roleFacadeHandoffPending = pendingHandoff;"));
        assertFalse(record.contains("&& !preserveLogicalOwner"));
        // A durable/pending client role must not suppress a fresh epoch when every actual server
        // facade was disconnected and a new CONNECTED callback arrives.
        String facadeScan = between(record,
                "boolean anotherFacadeOwnsCurrentLink", "GattServerPeer peer =");
        assertTrue(facadeScan.contains("existing.connected"));
        assertFalse(facadeScan.contains("existing.roleFacadeHandoff"));

        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private void resetIncomingSecurityAfterClientLoss");
        assertTrue(fresh.contains("incomingSecurityEpoch++;"));
        assertTrue(fresh.contains("secureAttConfirmed = false;"));
        assertTrue(fresh.contains("incomingAncsReadyGateOpen = false;"));
        assertTrue(fresh.contains("incomingClientAttachAttempt = 0;"));

        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        int establishedLoss = callback.indexOf(
                "resetIncomingSecurityAfterClientLoss(callbackGatt.getDevice(),");
        int backgroundRearm = callback.indexOf(
                "awaitIncomingBackgroundOwner(callbackGatt,", establishedLoss);
        assertTrue(establishedLoss >= 0);
        assertTrue(backgroundRearm > establishedLoss);
        assertTrue(callback.contains("confirmPendingServerFacadeHandoff("));
    }

    @Test public void oneDirectAttemptIsReservedUntilValidAncsReady() throws Exception {
        String transport = transport();
        String direct = between(transport,
                "private void startIncomingDirectAttach",
                "private void scheduleDirectFallback");
        assertTrue(direct.contains("INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS - 1"));
        assertTrue(direct.contains("FINAL ATTEMPT RESERVED FOR ANCS-READY"));

        String retry = between(transport,
                "private void scheduleIncomingClientAttachRetry",
                "private void recoverIncomingClientRole");
        assertTrue(retry.contains("INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS - 1"));
        assertTrue(retry.contains("final direct attach зарезервирован"));

        String ready = between(transport,
                "private void confirmAncsReady",
                "private void scheduleSecureClientStart");
        assertTrue(ready.contains("incomingAncsReadyGateOpen = true;"));
        assertTrue(ready.contains("scheduleSecureClientStart();"));

        String timeout = between(direct,
                "connectTimeout = () ->", "main.postDelayed(connectTimeout");
        assertTrue(timeout.contains("findConnectedServerPeer(expected.getDevice())"));
        assertTrue(timeout.contains("resetIncomingSecurityAfterClientLoss"));
        assertTrue(timeout.contains("preserveManagedIncomingPublicationAfterLinkLoss"));
    }

    private static String transport() throws Exception {
        return project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
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
