/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the anonymous/bonded facade inversion seen after an APK hot replacement. */
public final class Ha1206HotUpdateRecoveryContractTest {
    @Test public void preAdoptionBondedDisconnectRetiresTheWholeCurrentEpochAliasSet()
            throws Exception {
        String transport = transport();
        String retirement = between(transport,
                "private int retirePreAdoptionServerAliases",
                "private boolean hasConnectedServerPeer");
        assertTrue(retirement.contains("shouldRetirePreAdoptionAliases("));
        assertTrue(retirement.contains("isSelectedBondedIncomingDevice(disconnectedDevice)"));
        assertTrue(retirement.contains("peer.sessionGeneration != sessionGeneration"));
        assertTrue(retirement.contains("peer.securityEpoch != incomingSecurityEpoch"));
        assertTrue(retirement.contains("peer.connected = false;"));
        assertTrue(retirement.contains("peer.roleFacadeHandoff = false;"));
        assertTrue(retirement.contains("peer.roleFacadeHandoffPending = false;"));
        assertTrue(retirement.contains("peer.linkSecurityChallengeIssued = false;"));
        assertTrue(retirement.contains("peer.telemetrySubscribed = false;"));

        String callback = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "public void onCharacteristicReadRequest");
        int unverifiedDisconnect = callback.indexOf(
                "managedIncomingMode && getVerifiedPeer() == null");
        int retire = callback.indexOf(
                "retirePreAdoptionServerAliases(device)", unverifiedDisconnect);
        assertTrue(unverifiedDisconnect >= 0);
        assertTrue(retire > unverifiedDisconnect);

        String record = between(transport,
                "private boolean recordGattServerPeer",
                "private void bindServerPeerToCurrentSecurityEpoch");
        assertTrue(record.contains("existing.securityEpoch == incomingSecurityEpoch"));
    }

    @Test public void currentPairBindsZeroOrOneAnonymousAliasAndOnlyOnce()
            throws Exception {
        String transport = transport();
        String binding = between(transport,
                "private AncsRecoveryPolicy.PairFacadeBindDecision "
                        + "bindExactPairRequestFacadeIfSafe",
                "private void handlePairWriteRequestOnMain");
        assertTrue(binding.contains("isCurrentDiagnosticServicePublicationToken(publicationToken)"));
        assertTrue(binding.contains("isSelectedBondedIncomingDevice(device)"));
        assertTrue(binding.contains("conflictingVerifiedPeer"));
        assertTrue(binding.contains("incomingPairRequestFacadeBoundEpoch "
                + "== incomingSecurityEpoch"));
        assertTrue(binding.contains("safeBondState(peer.device) == BluetoothDevice.BOND_NONE"));
        assertTrue(binding.contains("pairFacadeBindDecision("));
        assertTrue(binding.contains("beginsFreshSecurityEpoch(decision)"));
        assertTrue(binding.contains("exactCurrentRawFacade"));
        assertTrue(binding.contains("beginFreshIncomingSecurityEpoch(device,"));
        assertTrue(binding.contains("peer.securityEpoch = incomingSecurityEpoch;"));
        assertTrue(binding.contains("incomingPairRequestFacadeBoundEpoch = incomingSecurityEpoch;"));
        assertFalse(binding.contains("secureAttConfirmed = true"));
        assertFalse(binding.contains("incomingAncsReadyGateOpen = true"));
        assertFalse(binding.contains("startIncomingDirectAttach("));

        String pair = between(transport,
                "private void handlePairWriteRequestOnMain",
                "private void handleSecureReadRequestOnMain");
        int token = pair.indexOf("currentDiagnosticServicePublicationToken(characteristic)");
        int bind = pair.indexOf("bindExactPairRequestFacadeIfSafe(device, publicationToken)");
        int claim = pair.indexOf("claimVerifiedPeer(device)", bind);
        int action = pair.indexOf("commitPairCommand(device, publicationToken)", claim);
        int response = pair.indexOf(
                "sendGattServerResponse(device, requestId, status, 0, null)", action);
        int finish = pair.indexOf("finishPairCommand(device, publicationToken", response);
        assertTrue(pair.contains("BIND_EXACT_REQUEST_FRESH_EPOCH"));
        assertTrue(pair.contains("BIND_SOLE_ANONYMOUS_ALIAS"));
        assertTrue(token >= 0);
        assertTrue(bind > token);
        assertTrue(claim > bind);
        assertTrue(action > claim);
        assertTrue(response > action);
        assertTrue(finish > response);

        String write = between(transport,
                "public void onCharacteristicWriteRequest",
                "private void handleIphonePeripheralConnectionState");
        int route = write.indexOf("handlePairWriteRequestOnMain(");
        int post = write.indexOf("main.post(pairTransaction)", route);
        int routeReturn = write.indexOf("return;", post);
        assertTrue(route >= 0);
        assertTrue(post > route);
        assertTrue(routeReturn > post);
        assertFalse(write.contains("pairTransaction.run()"));
        assertFalse(write.contains("Looper.myLooper() == main.getLooper()"));
    }

    @Test public void disconnectAndPairCallbacksShareOneFifoMainQueue() throws Exception {
        String transport = transport();
        String connection = between(transport,
                "public void onConnectionStateChange(BluetoothDevice device,",
                "public void onCharacteristicReadRequest");
        int connectionPost = connection.indexOf("main.post(() -> {");
        int record = connection.indexOf("recordGattServerPeer(");
        assertTrue(connectionPost >= 0);
        assertTrue(record >= 0);
        assertTrue(connectionPost < record);

        String write = between(transport,
                "public void onCharacteristicWriteRequest",
                "private void handleIphonePeripheralConnectionState");
        int control = write.indexOf("serverControlCharacteristic.equals(uuid)");
        int pairPost = write.indexOf("main.post(pairTransaction)", control);
        int pairReturn = write.indexOf("return;", pairPost);
        assertTrue(control >= 0);
        assertTrue(pairPost > control);
        assertTrue(pairReturn > pairPost);
        assertFalse(write.contains("pairTransaction.run()"));
    }

    @Test public void rejectionReasonIsLoggedOnlyAfterTheAttResponse()
            throws Exception {
        String pair = between(transport(),
                "private void handlePairWriteRequestOnMain",
                "private void handleSecureReadRequestOnMain");
        int reason = pair.indexOf("String rejection = null;");
        int response = pair.indexOf(
                "sendGattServerResponse(device, requestId, status, 0, null)", reason);
        int log = pair.indexOf("PAIR ATT REJECT POST-RESPONSE", response);
        assertTrue(reason >= 0);
        assertTrue(response > reason);
        assertTrue(log > response);
    }

    @Test public void missingFacadeClearsOldProofsBeforePairSuccessAndImmediateB3()
            throws Exception {
        String transport = transport();
        String binding = between(transport,
                "private AncsRecoveryPolicy.PairFacadeBindDecision "
                        + "bindExactPairRequestFacadeIfSafe",
                "private void handlePairWriteRequestOnMain");
        int freshEpoch = binding.indexOf("beginFreshIncomingSecurityEpoch(device,");
        int bindPeer = binding.indexOf("peer.securityEpoch = incomingSecurityEpoch;", freshEpoch);
        assertTrue(freshEpoch >= 0);
        assertTrue(bindPeer > freshEpoch);

        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        int epoch = fresh.indexOf("incomingSecurityEpoch++;");
        int secure = fresh.indexOf("secureAttConfirmed = false;", epoch);
        int ready = fresh.indexOf("incomingAncsReadyGateOpen = false;", secure);
        int challenge = fresh.indexOf("peer.linkSecurityChallengeIssued = false;", ready);
        assertTrue(epoch >= 0);
        assertTrue(secure > epoch);
        assertTrue(ready > secure);
        assertTrue(challenge > ready);

        String pair = between(transport,
                "private void handlePairWriteRequestOnMain",
                "private int retirePreAdoptionServerAliases");
        int bind = pair.indexOf("bindExactPairRequestFacadeIfSafe(device, publicationToken)");
        int response = pair.indexOf(
                "sendGattServerResponse(device, requestId, status, 0, null)", bind);
        assertTrue(response > bind);

        String readyGate = between(transport,
                "private boolean canAcceptAncsReady",
                "private IncomingReadyAttach commitAncsReady");
        assertTrue(readyGate.contains("secureAttConfirmed"));
        assertTrue(readyGate.contains("secureAttPublicationToken == publicationToken"));
    }

    @Test public void pairAliasBindingDoesNotWeakenB3OrReadyProofs() throws Exception {
        String transport = transport();
        String challenge = between(transport,
                "private boolean issueCurrentLinkSecurityChallenge",
                "private boolean recordGattServerPeer");
        assertTrue(challenge.contains("peer.linkSecurityChallengeIssued"));

        String read = between(transport,
                "private void handleSecureReadRequestOnMain",
                "private void handleSecureWriteRequestOnMain");
        int currentPeer = read.indexOf("!isVerifiedPeer(device)");
        int challengeCall = read.indexOf("issueCurrentLinkSecurityChallenge(device)");
        int statusFive = read.indexOf("STATUS_INSUFFICIENT_AUTHENTICATION", challengeCall);
        int secureCommit = read.indexOf("markSecureAttConfirmed(device, publicationToken)");
        assertTrue(currentPeer >= 0);
        assertTrue(challengeCall > currentPeer);
        assertTrue(statusFive > challengeCall);
        assertTrue(secureCommit > statusFive);

        String ready = between(transport,
                "private boolean canAcceptAncsReady",
                "private IncomingReadyAttach commitAncsReady");
        assertTrue(ready.contains("secureAttConfirmed"));
        assertTrue(ready.contains("secureAttPublicationToken == publicationToken"));
        assertTrue(ready.contains("isVerifiedPeer(device)"));
        assertTrue(ready.contains("exactServerPeer.device == rawFacade"));
    }

    private static String transport() throws Exception {
        return project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative).normalize();
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        if (from < 0 || to < 0 || to <= from) {
            throw new AssertionError("Missing source markers: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
