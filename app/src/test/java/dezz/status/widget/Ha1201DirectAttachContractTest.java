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
        assertFalse(candidate.contains("startSamePeerAttach("));
        assertFalse(candidate.contains("connectGatt("));
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
                "private IncomingReadyAttach commitAncsReady",
                "private void scheduleCapturedIncomingAttachAfterReady");
        assertTrue(ready.contains("canAcceptAncsReady"));
        assertTrue(ready.contains("incomingAncsReadyGateOpen = true;"));

        String gate = between(transport,
                "private void maybeStartIncomingAncsDiscovery",
                "private void startSamePeerAttach");
        assertTrue(gate.contains("secureAttConfirmed"));
        assertTrue(gate.contains("incomingAncsReadyGateOpen"));
        assertTrue(gate.contains("gattClientConnected"));
        assertTrue(gate.contains("activeClientEstablished"));
        assertTrue(gate.contains("activeClientProvenSecurityEpoch"));
        assertTrue(gate.contains("incomingSecurityEpoch"));
        assertTrue(gate.contains("discoverServices(expected);"));

        String discovery = between(transport,
                "private void discoverServices",
                "private boolean startIphonePeripheralSecurity");
        assertTrue(discovery.contains("incomingAncsReadyGateOpen"));
        assertTrue(discovery.contains(
                "incomingAncsReadyPublicationToken == publicationToken"));
        assertTrue(discovery.contains(
                "activeClientProvenSecurityEpoch == incomingSecurityEpoch"));
        int publicationProof = discovery.indexOf("boolean currentPublicationProof");
        int clientProof = discovery.indexOf("boolean currentClientProof");
        int blocked = discovery.indexOf(
                "if (!currentPublicationProof || !currentClientProof)");
        int arm = discovery.indexOf("armDiscoveryOperation(");
        int raw = discovery.indexOf("callbackGatt.discoverServices()");
        assertTrue(publicationProof >= 0);
        assertTrue(clientProof > publicationProof);
        assertTrue(blocked > clientProof);
        assertTrue(arm > blocked);
        assertTrue(raw > arm);
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
        assertTrue(record.contains("peer.roleFacadeHandoff = false;"));
        assertTrue(record.contains("peer.roleFacadeHandoffPending = pendingHandoff;"));
        assertFalse(record.contains("peer.roleFacadeHandoff = establishedHandoffCandidate;"));
        assertFalse(record.contains("&& !preserveLogicalOwner"));
        // A durable/pending client role must not suppress a fresh epoch when every actual server
        // facade was disconnected and a new CONNECTED callback arrives.
        String facadeScan = between(record,
                "boolean anotherFacadeOwnsCurrentLink", "GattServerPeer peer =");
        assertTrue(facadeScan.contains("existing.connected"));
        assertFalse(facadeScan.contains("existing.roleFacadeHandoff"));

        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        assertTrue(fresh.contains("incomingSecurityEpoch++;"));
        assertTrue(fresh.contains("secureAttConfirmed = false;"));
        assertTrue(fresh.contains("incomingAncsReadyGateOpen = false;"));
        assertTrue(fresh.contains("incomingClientAttachAttempt = 0;"));

        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        assertTrue(occurrences(callback,
                "recoverEstablishedIncomingClientAfterCallbackLoss(callbackGatt,") == 2);
        assertTrue(callback.contains("confirmPendingServerFacadeHandoff("));
    }

    @Test public void establishedServerFacadeLossRequiresBoundedClientLivenessProof()
            throws Exception {
        String transport = transport();
        String probe = between(transport,
                "private void scheduleServerFacadeHandoffProbe",
                "private void armServerFacadeHandoffProbeTimeout");
        assertTrue(probe.contains("expected.readRemoteRssi()"));
        assertTrue(probe.contains("linkProbeForServerFacadeHandoff = true;"));
        assertTrue(probe.contains("linkProbeSecurityEpoch = expectedSecurityEpoch;"));
        assertFalse(probe.contains("disconnect()"));
        assertFalse(probe.contains("close()"));

        String timeout = between(transport,
                "private void armServerFacadeHandoffProbeTimeout",
                "private void failServerFacadeHandoffProbe");
        assertTrue(timeout.contains("LINK_PROBE_TIMEOUT_MS"));
        assertTrue(timeout.contains("incomingSecurityEpoch != expectedSecurityEpoch"));
        assertTrue(timeout.contains("poisonRssiProbeChannelAndRearm("));
        assertFalse(timeout.contains("scheduleServerFacadeHandoffProbe("));

        String failure = between(transport,
                "private void failServerFacadeHandoffProbe",
                "private void scheduleIncomingEpochClientLivenessProbe");
        assertTrue(failure.contains(
                "recoverEstablishedIncomingClientAfterCallbackLoss(expected,"));

        String callback = between(transport,
                "public void onReadRemoteRssi",
                "private final BroadcastReceiver bondReceiver");
        int captureKind = callback.indexOf(
                "boolean serverFacadeProbe = linkProbeForServerFacadeHandoff;");
        int cancel = callback.indexOf("cancelAmbiguousAclProbe();");
        assertTrue(captureKind >= 0);
        assertTrue(cancel > captureKind);
        assertTrue(callback.contains("long securityEpoch = linkProbeSecurityEpoch;"));
        assertTrue(callback.contains("confirmPendingServerFacadeHandoff(serverDevice)"));
        assertTrue(callback.contains("failServerFacadeHandoffProbe(callbackGatt"));
    }

    @Test public void prePairEstablishedCandidateAlsoStartsFacadeProbe() throws Exception {
        String transport = transport();
        String serverCallback = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "private final BluetoothGattCallback gattCallback");
        int exactEstablished = serverCallback.indexOf(
                "managedIncomingMode\n"
                        + "                                && establishedClientOwnsPhysicalLink(device)");
        int verifiedOnly = serverCallback.indexOf("&& isVerifiedPeer(device)");
        assertTrue(exactEstablished >= 0);
        assertTrue(verifiedOnly > exactEstablished);
        String prePairBranch = serverCallback.substring(exactEstablished, verifiedOnly);
        assertTrue(prePairBranch.contains("handleServerFacadeDisconnected(device);"));
        assertFalse(prePairBranch.contains("secureAttConfirmed = true"));
        assertFalse(prePairBranch.contains("incomingAncsReadyGateOpen = true"));
    }

    @Test public void competingEpochTransitionAtomicallyCancelsOnlyItsExactProbe()
            throws Exception {
        String transport = transport();
        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        assertTrue(fresh.indexOf("prepareInFlightLinkProbeForFreshEpoch();")
                < fresh.indexOf("incomingSecurityEpoch++;"));
        assertTrue(fresh.indexOf("activeClientProvenSecurityEpoch = 0L;")
                > fresh.indexOf("incomingSecurityEpoch++;"));
        assertTrue(fresh.contains("poisonedWrapperReplacementAttempt = 0;"));

        String loss = between(transport,
                "private boolean resetIncomingSecurityAfterClientLoss",
                "private void recoverEstablishedIncomingClientAfterCallbackLoss");
        assertTrue(loss.indexOf("cancelAmbiguousAclProbe();")
                < loss.indexOf("incomingSecurityEpoch++;"));

        String ownership = between(transport,
                "private boolean ownsServerFacadeHandoffProbe",
                "/**\n     * A server DISCONNECTED callback");
        assertTrue(ownership.contains("linkProbeGatt == expected"));
        assertTrue(ownership.contains("linkProbeGeneration == expectedGeneration"));
        assertTrue(ownership.contains("linkProbeSecurityEpoch == expectedSecurityEpoch"));
        assertTrue(ownership.contains("linkProbeServerDevice == serverDevice"));
        assertFalse(ownership.contains("sameDevice(linkProbeServerDevice, serverDevice)"));

        String timeout = between(transport,
                "private void armServerFacadeHandoffProbeTimeout",
                "private void failServerFacadeHandoffProbe");
        assertTrue(timeout.contains("ownsServerFacadeHandoffProbe("));
        assertTrue(timeout.contains("cancelServerFacadeHandoffProbeIfOwned("));

        String generic = between(transport,
                "private void scheduleAmbiguousAclProbe",
                "private void cancelAmbiguousAclProbe");
        assertTrue(generic.contains("ownsGenericLinkProbe(expected, expectedGeneration)"));
        assertFalse(generic.contains("linkProbeGatt = null;\n"
                + "                if (closing"));
    }

    @Test public void priorRssiResultNeverUpgradesIntoPostDisconnectHandoffProof()
            throws Exception {
        String transport = transport();
        String facade = between(transport,
                "private void scheduleServerFacadeHandoffProbe",
                "private void armServerFacadeHandoffProbeTimeout");
        int serialize = facade.indexOf("if (linkProbeGatt == expected)");
        int queue = facade.indexOf("queueServerFacadeHandoffProbe(", serialize);
        int queueReturn = facade.indexOf("return;", queue);
        int submit = facade.indexOf("expected.readRemoteRssi()", queue);
        assertTrue(serialize >= 0);
        assertTrue(queue > serialize);
        assertTrue(queueReturn > queue);
        assertTrue(submit > queueReturn);
        assertFalse(facade.substring(serialize, queueReturn).contains(
                "linkProbeForServerFacadeHandoff = true;"));
        assertFalse(facade.contains("повышен"));

        String callback = between(transport,
                "public void onReadRemoteRssi",
                "private final BroadcastReceiver bondReceiver");
        int discard = callback.indexOf("if (discardResult)");
        int drain = callback.indexOf("drainQueuedServerFacadeProbeAfterGeneric(");
        int facadeResult = callback.indexOf("if (serverFacadeProbe)");
        int epochResult = callback.indexOf("if (incomingEpochProbe)");
        assertTrue(discard >= 0);
        assertTrue(drain > discard);
        assertTrue(facadeResult > drain);
        assertTrue(epochResult > facadeResult);

        String drainMethod = between(transport,
                "private boolean drainQueuedServerFacadeProbeAfterGeneric",
                "/** Logically cancels an old raw read");
        int clearOld = drainMethod.indexOf("cancelAmbiguousAclProbe();");
        int startNew = drainMethod.indexOf("scheduleServerFacadeHandoffProbe(");
        assertTrue(clearOld >= 0);
        assertTrue(startNew > clearOld);
        assertTrue(occurrences(transport, "readRemoteRssi()") >= 3);
    }

    @Test public void freshServerEpochSerializesProofAndBlocksDiscoveryUntilSuccess()
            throws Exception {
        String transport = transport();
        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        assertTrue(fresh.indexOf("prepareInFlightLinkProbeForFreshEpoch();")
                < fresh.indexOf("incomingSecurityEpoch++;"));
        assertTrue(fresh.contains("activeClientProvenSecurityEpoch = 0L;"));

        String serverCallback = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "private final BluetoothGattCallback gattCallback");
        int begin = serverCallback.indexOf("beginFreshIncomingSecurityEpoch(device,");
        int bind = serverCallback.indexOf("bindServerPeerToCurrentSecurityEpoch(device);");
        int liveness = serverCallback.indexOf(
                "scheduleIncomingEpochClientLivenessProbe(device,", bind);
        assertTrue(begin >= 0);
        assertTrue(bind > begin);
        assertTrue(liveness < 0);
        assertTrue(serverCallback.contains("defers retained-client RSSI proof until "));
        assertTrue(serverCallback.contains("current PAIR+B3+READY"));

        String callback = between(transport,
                "public void onReadRemoteRssi",
                "private final BroadcastReceiver bondReceiver");
        int discard = callback.indexOf("if (discardResult)");
        int proof = callback.indexOf("activeClientProvenSecurityEpoch = securityEpoch;");
        assertTrue(discard >= 0);
        assertTrue(proof > discard);
        String discarded = between(transport,
                "private void finishDiscardedRawProbeAndStartQueuedEpoch",
                "/**\n     * A server DISCONNECTED callback");
        assertTrue(discarded.indexOf("cancelAmbiguousAclProbe();")
                < discarded.indexOf("scheduleIncomingEpochClientLivenessProbe("));
        assertFalse(between(transport,
                "private void prepareInFlightLinkProbeForFreshEpoch",
                "private void armDiscardedRawProbeDrainTimeout")
                .contains("readRemoteRssi()"));

        String clientCallback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        assertTrue(clientCallback.contains(
                "activeClientProvenSecurityEpoch = incomingSecurityEpoch;"));
    }

    @Test public void postDisconnectFacadeProbeSupersedesEveryEarlierRssiPurpose()
            throws Exception {
        String transport = transport();
        String queue = between(transport,
                "private void queueServerFacadeHandoffProbe",
                "/**\n     * Finishes any operation");
        int cancelQueuedEpoch = queue.indexOf("incomingEpochProbeQueued = false;");
        int queueFacade = queue.indexOf("serverFacadeProbeQueued = true;");
        assertTrue(cancelQueuedEpoch >= 0);
        assertTrue(queueFacade > cancelQueuedEpoch);

        String incomingTimeout = between(transport,
                "private void scheduleIncomingEpochClientLivenessProbe",
                "/**\n     * Keeps the one successfully established Android GATT client alive");
        String incomingTimeoutBody = between(incomingTimeout,
                "linkProbeTimeout = () ->", "main.postDelayed(linkProbeTimeout");
        assertTrue(incomingTimeoutBody.contains("poisonRssiProbeChannelAndRearm("));
        assertFalse(incomingTimeoutBody.contains("scheduleServerFacadeHandoffProbe("));
        assertFalse(incomingTimeoutBody.contains("scheduleIncomingEpochClientLivenessProbe("));

        String callback = between(transport,
                "public void onReadRemoteRssi",
                "private final BroadcastReceiver bondReceiver");
        int drain = callback.indexOf("drainQueuedServerFacadeProbeAfterGeneric(");
        int epochBranch = callback.indexOf("if (incomingEpochProbe)");
        int facadeProof = callback.indexOf(
                "activeClientProvenSecurityEpoch = securityEpoch;");
        int facadeDiscovery = callback.indexOf(
                "maybeStartIncomingAncsDiscovery(callbackGatt,", facadeProof);
        assertTrue(drain >= 0);
        assertTrue(epochBranch > drain);
        assertTrue(facadeProof > drain);
        assertTrue(facadeDiscovery > facadeProof);

        String discarded = between(transport,
                "private void finishDiscardedRawProbeAndStartQueuedEpoch",
                "/**\n     * A server DISCONNECTED callback");
        int facadeQueued = discarded.indexOf("if (facadeQueued");
        int facadeStart = discarded.indexOf("scheduleServerFacadeHandoffProbe(");
        int epochStart = discarded.indexOf("scheduleIncomingEpochClientLivenessProbe(");
        assertTrue(facadeQueued >= 0);
        assertTrue(facadeStart > facadeQueued);
        assertTrue(epochStart > facadeStart);
    }

    @Test public void rawRssiTimeoutPoisonsWrapperAndNeverStartsSuccessor()
            throws Exception {
        String transport = transport();
        String poison = between(transport,
                "private void poisonRssiProbeChannelAndRearm",
                "private boolean ownsServerFacadeHandoffProbe");
        assertTrue(poison.contains("poisonedRssiProbeGatt = expected;"));
        assertTrue(poison.contains("cancelAmbiguousAclProbe();"));
        assertTrue(poison.contains("closeClientGatt(expected);"));
        assertTrue(poison.contains("poisonedWrapperReplacementAttempt++;"));
        assertFalse(poison.contains("incomingClientAttachAttempt = 0;"));
        assertTrue(poison.contains("activeClientProvenSecurityEpoch = 0L;"));
        assertTrue(poison.contains("adoptIncomingClientCandidate(exactIncoming,"));
        assertTrue(poison.contains("scheduleIncomingClientAttachRetry("));
        assertTrue(poison.indexOf("canStartIncomingClientAttach(")
                < poison.indexOf("closeClientGatt(expected);"));
        assertTrue(poison.contains("resetIncomingSecurityAfterClientLoss("));
        assertTrue(poison.contains("preserveManagedIncomingPublicationAfterLinkLoss("));
        assertFalse(poison.contains("awaitIncomingBackgroundOwner("));
        assertFalse(poison.contains("awaitPersistentGattReconnect("));
        assertFalse(poison.contains("readRemoteRssi()"));
        assertFalse(poison.contains("scheduleServerFacadeHandoffProbe("));
        assertFalse(poison.contains("scheduleIncomingEpochClientLivenessProbe("));
        String connectedReplacement = between(poison,
                "if (canReplaceOnCurrentIncomingLink)", "} else {");
        assertFalse(connectedReplacement.contains("beginFreshIncomingSecurityEpoch("));
        assertFalse(connectedReplacement.contains("incomingSecurityEpoch++;"));
        assertFalse(connectedReplacement.contains("secureAttConfirmed = false;"));
        assertFalse(connectedReplacement.contains("incomingAncsReadyGateOpen = false;"));

        String generic = between(transport,
                "private void scheduleAmbiguousAclProbe",
                "private void cancelAmbiguousAclProbe");
        String genericTimeout = between(generic,
                "linkProbeTimeout = () ->", "main.postDelayed(linkProbeTimeout");
        assertTrue(genericTimeout.contains("poisonRssiProbeChannelAndRearm("));
        assertFalse(genericTimeout.contains("drainQueuedServerFacadeProbeAfterGeneric("));
        assertFalse(genericTimeout.contains("scheduleServerFacadeHandoffProbe("));

        String discardTimeout = between(transport,
                "private void armDiscardedRawProbeDrainTimeout",
                "private void queueIncomingEpochProbeBehindDiscardedRead");
        assertTrue(discardTimeout.contains("poisonRssiProbeChannelAndRearm("));
        assertFalse(discardTimeout.contains("finishDiscardedRawProbeAndStartQueuedEpoch("));

        String facadeTimeout = between(transport,
                "private void armServerFacadeHandoffProbeTimeout",
                "private void failServerFacadeHandoffProbe");
        assertTrue(facadeTimeout.contains("poisonRssiProbeChannelAndRearm("));
        assertFalse(facadeTimeout.contains("scheduleServerFacadeHandoffProbe("));

        String incoming = between(transport,
                "private void scheduleIncomingEpochClientLivenessProbe",
                "/**\n     * Keeps the one successfully established Android GATT client alive");
        String incomingTimeout = between(incoming,
                "linkProbeTimeout = () ->", "main.postDelayed(linkProbeTimeout");
        assertTrue(incomingTimeout.contains("poisonRssiProbeChannelAndRearm("));
        assertFalse(incomingTimeout.contains("scheduleServerFacadeHandoffProbe("));

        String callback = between(transport,
                "public void onReadRemoteRssi",
                "private final BroadcastReceiver bondReceiver");
        assertTrue(callback.indexOf("if (callbackGatt != linkProbeGatt) return;")
                < callback.indexOf("long generation = linkProbeGeneration;"));

        String facade = between(transport,
                "private void scheduleServerFacadeHandoffProbe",
                "private void armServerFacadeHandoffProbeTimeout");
        assertTrue(facade.indexOf("isRssiProbeChannelPoisoned(expected)")
                < facade.indexOf("expected.readRemoteRssi()"));
        String epoch = between(transport,
                "private void scheduleIncomingEpochClientLivenessProbe",
                "/**\n     * Keeps the one successfully established Android GATT client alive");
        assertTrue(epoch.indexOf("isRssiProbeChannelPoisoned(expected)")
                < epoch.indexOf("expected.readRemoteRssi()"));
        String genericGuard = between(transport,
                "private void scheduleAmbiguousAclProbe",
                "private void cancelAmbiguousAclProbe");
        assertTrue(genericGuard.indexOf("isRssiProbeChannelPoisoned(expected)")
                < genericGuard.indexOf("expected.readRemoteRssi()"));
        assertTrue(genericGuard.contains("poisonRssiProbeChannelAndRearm(expected,"));

        String clientCallback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        assertFalse(clientCallback.contains("clearRssiProbePoisonAfterGattClosed("));
        assertTrue(occurrences(transport,
                "clearRssiProbePoisonAfterGattClosed(") == 3);
        assertTrue(transport.contains(
                "RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS = 1"));
        assertTrue(transport.contains(
                "poisonedWrapperReplacementAttempt = 0;"));
    }

    @Test public void oneDirectAttemptIsReservedUntilValidAncsReady() throws Exception {
        String transport = transport();
        String direct = between(transport,
                "private void startIncomingDirectAttach",
                "private void scheduleDirectFallback");
        assertTrue(direct.contains("canStartIncomingClientAttach(pairRawFacade"));
        assertTrue(direct.indexOf("canStartIncomingClientAttach(pairRawFacade")
                < direct.indexOf("incomingClientAttachAttempt++;"));
        assertFalse(direct.contains("INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS - 1"));

        String retry = between(transport,
                "private void scheduleIncomingClientAttachRetry",
                "private void recoverIncomingClientRole");
        assertTrue(retry.contains("canStartIncomingClientAttach(device, publicationToken)"));
        assertFalse(retry.contains("INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS - 1"));

        String ready = between(transport,
                "private IncomingReadyAttach commitAncsReady",
                "private void scheduleCapturedIncomingAttachAfterReady");
        assertTrue(ready.contains("incomingAncsReadyGateOpen = true;"));
        assertTrue(ready.contains("armIncomingReadyAttachLatch("));

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
