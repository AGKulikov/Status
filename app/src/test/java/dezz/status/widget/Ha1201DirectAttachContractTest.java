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
