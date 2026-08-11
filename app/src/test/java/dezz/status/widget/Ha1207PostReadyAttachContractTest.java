/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the HA1207 exact post-READY reverse client allocation. */
public final class Ha1207PostReadyAttachContractTest {
    @Test public void publicationConnectedPairAndFreshEpochIssueNoClientCommand()
            throws Exception {
        String transport = transport();
        String candidate = between(transport,
                "private void attachAncsClientToIncomingOwner",
                "private boolean isVerifiedPeer");
        assertFalse(candidate.contains("connectGatt("));
        assertFalse(candidate.contains("startSamePeerAttach("));
        assertFalse(candidate.contains("closeClientGatt("));
        assertFalse(candidate.contains("readRemoteRssi("));
        assertTrue(candidate.contains("zero pre-ready clientIf attempts"));

        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        assertTrue(fresh.contains(
                "clearAncsRuntimeWithoutClientCommands(emitDiagnosticLogs);"));
        assertTrue(fresh.contains("if (staleOpportunisticObserver != null "
                + "&& activeClientOpportunistic)"));
        assertTrue(fresh.contains("closeClientGatt(staleOpportunisticObserver);"));
        assertTrue(fresh.indexOf("closeClientGatt(")
                == fresh.lastIndexOf("closeClientGatt("));
        assertFalse(fresh.contains("disconnect("));
        assertFalse(fresh.contains("startSamePeerAttach("));
        assertFalse(fresh.contains("readRemoteRssi("));
        assertFalse(fresh.contains("scheduleIncomingEpochClientLivenessProbe("));

        String logicalClear = between(transport,
                "private boolean clearAncsRuntimeWithoutClientCommands",
                "private void cancelConnectTimeout");
        assertTrue(logicalClear.contains("clearAncsRuntime(false, emitDiagnosticLogs)"));
        assertTrue(logicalClear.contains("if (allowClientWrapperClose"));
        assertTrue(logicalClear.contains("client wrapper close "));
        assertTrue(logicalClear.contains("deferred until captured READY"));
    }

    @Test public void pairB3ReadyUseOneMainFifoAndRespondBeforeSlowWork()
            throws Exception {
        String transport = transport();
        String serverRead = between(transport,
                "public void onCharacteristicReadRequest",
                "public void onDescriptorReadRequest");
        int b3Post = serverRead.indexOf("main.post(() -> handleSecureReadRequestOnMain(");
        int readRawLog = serverRead.indexOf("GATT SERVER READ raw");
        assertTrue(b3Post >= 0);
        assertTrue(readRawLog > b3Post);

        String serverWrite = between(transport,
                "public void onCharacteristicWriteRequest",
                "private void handleIphonePeripheralConnectionState");
        int pairPost = serverWrite.indexOf("main.post(pairTransaction)");
        int readyPost = serverWrite.indexOf("handleAncsReadyWriteRequestOnMain(");
        int writeRawLog = serverWrite.indexOf("GATT SERVER WRITE raw");
        assertTrue(pairPost >= 0);
        assertTrue(readyPost > pairPost);
        assertTrue(writeRawLog > readyPost);

        String pair = between(transport,
                "private void handlePairWriteRequestOnMain",
                "private void handleSecureReadRequestOnMain");
        int pairRequireResponse = pair.indexOf("!responseNeeded");
        int pairCommit = pair.indexOf("commitPairCommand(device, publicationToken)");
        int pairResponse = pair.indexOf(
                "sendGattServerResponse(device, requestId, status, 0, null)", pairCommit);
        int pairFinish = pair.indexOf("finishPairCommand(device, publicationToken", pairResponse);
        assertTrue(pairRequireResponse >= 0);
        assertTrue(pairCommit > pairRequireResponse);
        assertTrue(pairResponse > pairCommit);
        assertFalse(pair.substring(0, pairResponse).contains("log("));
        assertFalse(pair.substring(0, pairResponse).contains("state("));
        assertFalse(pair.substring(0, pairResponse).contains("listener."));
        assertTrue(pairFinish > pairResponse);

        String b3 = between(transport,
                "private void handleSecureReadRequestOnMain",
                "private void handleSecureWriteRequestOnMain");
        int b3Commit = b3.indexOf("markSecureAttConfirmed(device, publicationToken)");
        int b3Response = b3.indexOf("sendGattReadResponse(device, requestId, offset", b3Commit);
        int b3Finish = b3.indexOf("finishSecureAttSuccess(", b3Response);
        assertTrue(b3Commit >= 0);
        assertTrue(b3Response > b3Commit);
        assertTrue(b3Finish > b3Response);

        String ready = between(transport,
                "private void handleAncsReadyWriteRequestOnMain",
                "private void rollbackSecureAttProof");
        int requireResponse = ready.indexOf("!responseNeeded");
        int readyCommit = ready.indexOf("commitAncsReady(device, publicationToken)");
        int readyResponse = ready.indexOf(
                "sendGattServerResponse(device, requestId, status, 0, null)", readyCommit);
        int schedule = ready.indexOf("scheduleCapturedIncomingAttachAfterReady(captured)",
                readyResponse);
        int readyFinish = ready.indexOf("finishAncsReadyCommit(captured)", schedule);
        assertTrue(requireResponse >= 0);
        assertTrue(readyCommit > requireResponse);
        assertTrue(readyResponse > readyCommit);
        assertTrue(schedule > readyResponse);
        assertTrue(readyFinish > schedule);
    }

    @Test public void oneCheckedReadyArmsOneDelayedCapturedAttach() throws Exception {
        String transport = transport();
        String commit = between(transport,
                "private IncomingReadyAttach commitAncsReady",
                "private void finishAncsReadyCommit");
        assertTrue(commit.contains("armIncomingReadyAttachLatch("));
        assertTrue(commit.contains("firstReadyProof"));
        assertTrue(commit.contains("if (firstReadyProof) incomingClientAttachAttempt = 0;"));
        assertFalse(commit.contains("connectGatt("));
        assertFalse(commit.contains("listener.onVerifiedPeerAddress"));
        assertFalse(commit.contains("state("));
        assertFalse(commit.contains("log("));

        String schedule = between(transport,
                "private void scheduleCapturedIncomingAttachAfterReady",
                "/** Runs one main turn after READY proof commit");
        assertTrue(schedule.contains("incomingReadyAttachTask != this"));
        assertTrue(schedule.contains("hasCurrentIncomingReadyAttachLatch("));
        assertTrue(schedule.contains("main.postDelayed(task, SECURE_TO_CLIENT_CONNECT_DELAY_MS)"));

        String captured = between(transport,
                "private void startCapturedIncomingAttachAfterReady",
                "private boolean replaceStaleEstablishedOwnerAfterFreshReady");
        assertTrue(captured.contains("captured.sessionGeneration != sessionGeneration"));
        assertTrue(captured.contains("captured.securityEpoch != incomingSecurityEpoch"));
        assertTrue(captured.contains("captured.publicationToken"));
        assertTrue(captured.contains("currentPeer != captured.serverPeer"));
        assertTrue(captured.contains("currentPeer.device != captured.rawFacade"));
        assertTrue(captured.contains("!currentPeer.connected"));
        assertTrue(captured.contains("canStartIncomingClientAttach("));
        assertTrue(captured.contains(
                "activeIncomingFirstAttachAuthorization = captured;"));
        assertTrue(captured.contains("finally"));
        assertTrue(captured.contains(
                "activeIncomingFirstAttachAuthorization = null;"));
        assertTrue(captured.indexOf("canStartIncomingClientAttach(")
                < captured.indexOf("startIncomingDirectAttach("));
    }

    @Test public void duplicatePairReadyAndResponseFailurePreserveBoundedBudget()
            throws Exception {
        String transport = transport();
        String pairCommit = between(transport,
                "private Boolean commitPairCommand",
                "private void finishPairCommand");
        assertTrue(pairCommit.contains("currentPeer == incomingPairAcceptedServerPeer"));
        assertTrue(pairCommit.contains("currentPeer.device == incomingPairAcceptedFacade"));
        assertTrue(pairCommit.contains(
                "incomingPairAcceptedSessionGeneration == sessionGeneration"));
        assertTrue(pairCommit.contains(
                "incomingPairAcceptedSecurityEpoch == incomingSecurityEpoch"));
        assertTrue(pairCommit.contains(
                "incomingPairAcceptedPublicationToken == publicationToken"));
        assertTrue(pairCommit.contains("incomingPairAcceptedServerPeer = exactPairPeer;"));
        assertTrue(pairCommit.contains("if (firstPairProof) incomingClientAttachAttempt = 0;"));

        String clearPair = between(transport,
                "private void clearIncomingPairProof",
                "private void clearIncomingReadyAttachLatch");
        assertTrue(clearPair.contains("incomingPairAcceptedFacade = null;"));
        assertTrue(clearPair.contains("incomingPairAcceptedServerPeer = null;"));

        String latch = between(transport,
                "private boolean armIncomingReadyAttachLatch",
                "private void clearIncomingClientAttemptLineage");
        assertTrue(latch.contains(
                "if (hasCurrentIncomingReadyAttachLatch(rawFacade, publicationToken)) return false;"));

        String ready = between(transport,
                "private void handleAncsReadyWriteRequestOnMain",
                "private void rollbackSecureAttProof");
        assertTrue(ready.contains("if (!captured.attachTaskArmed)"));
        assertTrue(ready.contains("already scheduled/consumed"));
        assertTrue(ready.contains("clearIncomingReadyAttachLatch();"));

        String pairRollback = between(transport,
                "private void rollbackNonIdempotentPairTranscript",
                "private int retirePreAdoptionServerAliases");
        assertTrue(pairRollback.contains("clearIncomingPairProof();"));
        assertTrue(pairRollback.contains("clearIncomingClientAttemptLineage();"));
        assertTrue(pairRollback.contains("secureAttPublicationToken = 0L;"));
        assertTrue(pairRollback.contains("incomingAncsReadyGateOpen = false;"));
        assertTrue(pairRollback.contains("incomingAncsReadyPublicationToken = 0L;"));
        assertTrue(pairRollback.contains("cancelClientAttemptCallbacks();"));
    }

    @Test public void inboundAttUsesStablePeerButClientCallbackLineageStaysIdentityStrict()
            throws Exception {
        String transport = transport();
        String pairProof = between(transport,
                "private boolean hasCurrentIncomingPairProof",
                "private boolean canStartIncomingClientAttach");
        assertTrue(pairProof.contains("acceptsInboundAttTranscriptCallback("));
        assertTrue(pairProof.contains("callbackPeer == acceptedPeer"));
        assertTrue(pairProof.contains("isSelectedBondedIncomingDevice(callbackDevice)"));
        assertTrue(pairProof.contains("acceptedPeer.device == rawFacade"));
        assertFalse(pairProof.contains("rawFacade == callbackDevice"));

        String challenge = between(transport,
                "private boolean issueCurrentLinkSecurityChallenge",
                "private void resetCurrentLinkSecurityChallenge");
        assertTrue(challenge.contains("peer != acceptedPeer"));
        assertTrue(challenge.contains("sameDevice(peer.device, device)"));
        assertFalse(challenge.contains("device == incomingPairAcceptedFacade"));

        String bond = between(transport,
                "private final BroadcastReceiver bondReceiver",
                "private static String pairingVariantLabel");
        assertTrue(bond.contains("state == BluetoothDevice.BOND_NONE && managedIncomingMode"));
        assertTrue(bond.contains("findCurrentServerPeer(device) == acceptedPeer"));
        assertTrue(bond.contains("beginFreshIncomingSecurityEpoch(device"));
        assertTrue(bond.indexOf("beginFreshIncomingSecurityEpoch(device")
                < bond.indexOf("bindServerPeerToCurrentSecurityEpoch(device)"));
        assertTrue(bond.contains("fresh PAIR and status-5/encrypted B3 required"));

        String binding = between(transport,
                "private AncsRecoveryPolicy.PairFacadeBindDecision "
                        + "bindExactPairRequestFacadeIfSafe",
                "private void handlePairWriteRequestOnMain");
        assertTrue(binding.contains("exactCurrentRawFacade"));
        assertTrue(binding.contains("beginsFreshSecurityEpoch(decision)"));
        assertTrue(binding.contains(
                "\"exact current-F04 PAIR request facade recovery\", false)"));
        assertFalse(binding.contains("matchingCurrentPeer.device = device"));
        assertTrue(binding.contains("retireCurrentAnonymousAliasesExcept(device)"));

        String record = between(transport,
                "private boolean recordGattServerPeer",
                "private void bindServerPeerToCurrentSecurityEpoch");
        assertTrue(record.contains("pairRawFacadeChanged"));
        assertTrue(record.contains("invalidateIncomingTupleForRawFacadeChange(device"));
        assertTrue(record.contains("acceptedPairOtherFacade"));

        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        int activeWrapper = callback.indexOf("if (callbackGatt != gatt)");
        int staleCommandGate = callback.indexOf(
                "!canIssueManagedIncomingTupleCommand()", activeWrapper);
        int staleClose = callback.indexOf("closeClientGatt(callbackGatt)", activeWrapper);
        int tuple = callback.indexOf("!ownsCurrentIncomingClientAttempt(callbackGatt)");
        int stateLog = callback.indexOf("onConnectionStateChange status=", tuple);
        assertTrue(activeWrapper >= 0);
        assertTrue(staleCommandGate > activeWrapper);
        assertTrue(staleClose > staleCommandGate);
        assertTrue(tuple > activeWrapper);
        assertTrue(stateLog > tuple);
        assertTrue(callback.contains("isQuarantinedRetainedEstablishedOwner(callbackGatt)"));
        int retained = callback.indexOf(
                "isQuarantinedRetainedEstablishedOwner(callbackGatt)");
        int status22 = callback.indexOf(
                "status == STATUS_GATT_CONN_TERMINATE_LOCAL_HOST", retained);
        int drainLatch = callback.indexOf(
                "latchQuarantinedRetainedStatus22(callbackGatt)", status22);
        int retainedNoOp = callback.indexOf(
                "Retained established-owner callback quarantined/no-op", retained);
        assertTrue(status22 > retained);
        assertTrue(drainLatch > status22);
        assertTrue(retainedNoOp > drainLatch);
        assertTrue(callback.contains("closed/no-op"));

        String invertedStatus22 = between(transport,
                "private void latchQuarantinedRetainedStatus22",
                "/** Coalesces the one Android-9 anonymous alias");
        assertTrue(invertedStatus22.contains("incomingStaleEstablishedOwner = expected;"));
        assertTrue(invertedStatus22.contains("incomingStaleOwnerAwaitingFreshEpoch = true;"));
        assertTrue(invertedStatus22.contains(
                "incomingStaleOwnerReplacementEpoch = currentFacadeAlreadyArrived"));
        assertFalse(invertedStatus22.contains("closeClientGatt("));
        assertFalse(invertedStatus22.contains("connectGatt("));
        assertFalse(invertedStatus22.contains("readRemoteRssi("));
        assertFalse(invertedStatus22.contains("discoverServices("));

        String status22Consume = between(transport,
                "private boolean replaceStaleEstablishedOwnerAfterFreshReady",
                "private void scheduleSecureClientStart");
        int exactRetainedSlot = status22Consume.indexOf(
                "gatt == null || gatt == staleOwner");
        int capturedClose = status22Consume.indexOf("closeClientGatt(staleOwner)");
        int oneShotAttach = status22Consume.indexOf("startIncomingDirectAttach(");
        assertTrue(exactRetainedSlot >= 0);
        assertTrue(capturedClose > exactRetainedSlot);
        assertTrue(oneShotAttach > capturedClose);
    }

    @Test public void managedB3WriteCannotBypassStatusFiveReadChallenge() throws Exception {
        String transport = transport();
        String write = between(transport,
                "private void handleSecureWriteRequestOnMain",
                "private void handleAncsReadyWriteRequestOnMain");
        int policy = write.indexOf("allowsB3WriteProof(managedIncomingMode)");
        int commit = write.indexOf("markSecureAttConfirmed(device, publicationToken)");
        assertTrue(policy >= 0);
        assertTrue(commit > policy);
        assertTrue(write.contains("BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED"));
    }

    @Test public void manualAndRecoveryRearmNeedTheFullPostReadyAttemptTuple()
            throws Exception {
        String transport = transport();
        String publicConnect = between(transport,
                "public void connect(Candidate candidate)",
                "public void requestBond()");
        assertTrue(publicConnect.contains("awaitIncomingBackgroundOwner("));
        assertFalse(publicConnect.contains("incomingClientAttachAttempt = 0"));

        String recover = between(transport,
                "private void recoverIncomingClientRole",
                "/** Keeps one incoming-route clientIf alive");
        int recoveryGate = recover.indexOf("!canIssueManagedIncomingRearm(owner)");
        int recoveryClose = recover.indexOf("closeClientGatt(owner)");
        int recoveryAwait = recover.indexOf("awaitIncomingBackgroundOwner(owner");
        assertTrue(recoveryGate >= 0);
        assertTrue(recoveryAwait > recoveryGate);
        assertTrue(recoveryClose > recoveryGate);

        String await = between(transport,
                "private void awaitIncomingBackgroundOwner",
                "private void cancelClientAttemptCallbacks");
        int awaitGate = await.indexOf("!canIssueManagedIncomingRearm(expected)");
        int awaitClose = await.indexOf("closeClientGatt(expected)");
        int awaitRearm = await.indexOf("rearmPersistentGattOwner(expected");
        assertTrue(awaitGate >= 0);
        assertTrue(awaitClose > awaitGate);
        assertTrue(awaitRearm > awaitGate);

        String rawRearm = between(transport,
                "private void rearmPersistentGattOwner",
                "/** Re-discovers changed services");
        int rawGate = rawRearm.indexOf("!canIssueManagedIncomingRearm(expected)");
        int rawConnect = rawRearm.indexOf("expected.connect()");
        assertTrue(rawGate >= 0);
        assertTrue(rawConnect > rawGate);

        String persistent = between(transport,
                "private void awaitPersistentGattReconnect",
                "/**\n     * Reuses the already registered Android GATT owner");
        assertTrue(persistent.indexOf("!canIssueManagedIncomingRearm(expected)")
                < persistent.indexOf("clearAncsRuntime()"));
        String rediscovery = between(transport,
                "private void restartDiscoveryOnPersistentOwner",
                "private boolean startSavedPeerScan");
        assertTrue(rediscovery.indexOf("!canIssueManagedIncomingRearm(expected)")
                < rediscovery.indexOf("clearAncsRuntime()"));
    }

    @Test public void pendingReadyBarrierAndFirstAttachCapabilityBlockEveryBypass()
            throws Exception {
        String transport = transport();
        String direct = between(transport,
                "private void startIncomingDirectAttach",
                "private void scheduleDirectFallback");
        int commandPolicy = direct.indexOf(
                "AncsRecoveryPolicy.mayIssueReverseClientCommand(");
        int issued = direct.indexOf("incomingFirstAttachIssuedForCurrentTuple = true;");
        int rawConnect = direct.indexOf("connectGattOpportunisticOnPie(device)");
        assertTrue(commandPolicy >= 0);
        assertTrue(direct.contains("!incomingFirstAttachIssuedForCurrentTuple"));
        assertTrue(direct.contains("ownsCapturedFirstAttachAuthorization("));
        assertTrue(issued > commandPolicy);
        assertTrue(rawConnect > issued);

        String retry = between(transport,
                "private void scheduleIncomingClientAttachRetry",
                "private void recoverIncomingClientRole");
        assertTrue(retry.contains("Same-tuple clientIf retry запрещён"));
        assertFalse(retry.contains("startSamePeerAttach("));

        String rearmGate = between(transport,
                "private boolean canIssueManagedIncomingRearm",
                "private boolean ownsCapturedFirstAttachAuthorization");
        assertTrue(rearmGate.contains("ownsCurrentIncomingClientAttempt(expected)"));
        assertTrue(rearmGate.contains("canIssueManagedIncomingTupleCommand()"));

        String tupleCommandGate = between(transport,
                "private boolean canIssueManagedIncomingTupleCommand",
                "/** The only gate allowed to reach BluetoothGatt.connect()");
        assertTrue(tupleCommandGate.contains("canStartIncomingClientAttach("));
        assertTrue(tupleCommandGate.contains("incomingReadyAttachTask != null"));
        assertTrue(tupleCommandGate.contains("!incomingFirstAttachIssuedForCurrentTuple"));
        assertTrue(tupleCommandGate.contains("ownsCapturedFirstAttachAuthorization("));
        assertTrue(tupleCommandGate.contains(
                "AncsRecoveryPolicy.mayIssueReverseClientCommand("));

        String state = between(transport,
                "private void state(String value)",
                "/**\n     * Keeps transient status");
        assertTrue(state.contains("incomingClientAttachAttempt = 0;"));
        assertFalse(state.contains("incomingFirstAttachIssuedForCurrentTuple = false"));
    }

    @Test public void staleOperationCallbacksAndRssiCannotCrossTupleOrPublication()
            throws Exception {
        String transport = transport();
        String services = between(transport,
                "private void handleServicesDiscoveredCallback",
                "private void continueDiscoveredServiceSetup");
        assertTrue(services.contains("acceptsCurrentManagedIncomingCallback("));
        String descriptor = between(transport,
                "private void handleDescriptorWrite",
                "private void waitForIncomingAncsAuthorizationEvent");
        assertTrue(descriptor.contains("acceptsCurrentManagedIncomingCallback("));
        String changed = between(transport,
                "private void handleCharacteristicChanged",
                "private static boolean descriptorMatchesStage");
        assertTrue(changed.contains("acceptsCurrentManagedIncomingCallback("));

        String publication = between(transport,
                "private void invalidateDiagnosticServicePublication",
                "/** Returns the accepted token");
        int discardProbe = publication.indexOf("prepareInFlightLinkProbeForFreshEpoch()");
        int tokenRollover = publication.indexOf("publishedDiagnosticServicePublicationToken = 0L");
        assertTrue(discardProbe >= 0);
        assertTrue(tokenRollover > discardProbe);

        String facadeOwner = between(transport,
                "private boolean ownsServerFacadeHandoffProbe",
                "private void cancelServerFacadeHandoffProbeIfOwned");
        String epochOwner = between(transport,
                "private boolean ownsIncomingEpochProbe",
                "private void cancelIncomingEpochProbeIfOwned");
        assertTrue(facadeOwner.contains("linkProbeServerDevice == serverDevice"));
        assertTrue(epochOwner.contains("linkProbeServerDevice == serverDevice"));
        assertFalse(facadeOwner.contains("sameDevice(linkProbeServerDevice"));
        assertFalse(epochOwner.contains("sameDevice(linkProbeServerDevice"));
    }

    @Test public void legacyForwardB3RouteDoesNotRequireManagedPairTuple()
            throws Exception {
        String transport = transport();
        String read = between(transport,
                "private void handleSecureReadRequestOnMain",
                "private void handleSecureWriteRequestOnMain");
        assertTrue(read.contains("managedIncomingMode"));
        assertTrue(read.contains("!hasCurrentIncomingPairProof(device, publicationToken)"));
        String write = between(transport,
                "private void handleSecureWriteRequestOnMain",
                "private void handleAncsReadyWriteRequestOnMain");
        assertTrue(write.contains("managedIncomingMode"));
        assertTrue(write.contains("!hasCurrentIncomingPairProof(device, publicationToken)"));
    }

    private static String transport() throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(
                    "app/src/main/java/dezz/status/widget/phone/transport/"
                            + "IphoneAncsTransport.java").normalize();
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("IphoneAncsTransport.java not found");
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
