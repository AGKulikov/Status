/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the HA1202 cold-start service barrier and event-driven ANCS wait. */
public final class Ha1202ColdStartAuthorizationContractTest {
    @Test public void directClientCannotStartBeforeF04ServicePublication() throws Exception {
        String transport = transport();
        assertTrue(transport.contains(
                "private volatile boolean serverDiagnosticServicePublished;"));
        assertTrue(transport.contains(
                "private volatile BluetoothGattService pendingDiagnosticServicePublication;"));
        assertTrue(transport.contains(
                "private volatile long serverDiagnosticServicePublicationToken;"));

        String invalidate = between(transport,
                "private void invalidateDiagnosticServicePublication",
                "private void openGattServer");
        assertTrue(invalidate.contains("serverDiagnosticServicePublished = false;"));
        assertTrue(invalidate.contains("pendingDiagnosticServicePublication = null;"));
        assertTrue(invalidate.contains("publishedDiagnosticServicePublication = null;"));
        assertTrue(invalidate.contains("publishedDiagnosticServicePublicationToken = 0L;"));
        assertTrue(invalidate.contains("serverDiagnosticServicePublicationToken++;"));

        String open = between(transport,
                "private void openGattServer", "private void startPreparedAdvertising");
        int barrierClosed = open.indexOf("invalidateDiagnosticServicePublication();");
        int openServer = open.indexOf("manager.openGattServer(");
        int exactPending = open.indexOf("pendingDiagnosticServicePublication = service;");
        int pendingToken = open.indexOf(
                "pendingDiagnosticServicePublicationToken = "
                        + "serverDiagnosticServicePublicationToken;");
        int addService = open.indexOf("gattServer.addService(service)");
        assertTrue(barrierClosed >= 0);
        assertTrue(openServer > barrierClosed);
        assertTrue(exactPending > openServer);
        assertTrue(pendingToken > exactPending);
        assertTrue(addService > pendingToken);

        String adopt = between(transport,
                "private void adoptIncomingClientCandidate",
                "private boolean isVerifiedPeer");
        int saveCandidate = adopt.indexOf("incomingClientCandidate = device;");
        int barrierGuard = adopt.indexOf(
                "if (!isCurrentDiagnosticServicePublicationToken(");
        int startAfterBarrier = adopt.indexOf(
                "maybeStartIncomingClientAttachAfterServicePublished(reason);");
        assertTrue(saveCandidate >= 0);
        assertTrue(barrierGuard > saveCandidate);
        assertTrue(startAfterBarrier > barrierGuard);

        String direct = between(transport,
                "private void startIncomingDirectAttach", "private void scheduleDirectFallback");
        int hardGuard = direct.indexOf(
                "if (!isCurrentDiagnosticServicePublicationToken(publicationToken))");
        int spendAttempt = direct.indexOf("incomingClientAttachAttempt++;");
        int connectGatt = direct.indexOf("device.connectGatt(context, false, gattCallback");
        assertTrue(hardGuard >= 0);
        assertTrue(spendAttempt > hardGuard);
        assertTrue(connectGatt > spendAttempt);
    }

    @Test public void serviceAddedSuccessOpensBarrierBeforeSavedAttach() throws Exception {
        String transport = transport();
        String callback = between(transport,
                "public void onServiceAdded", "public void onConnectionStateChange");
        int exactObject = callback.indexOf("service != pending");
        int exactToken = callback.indexOf(
                "pendingToken != serverDiagnosticServicePublicationToken");
        int successBarrier = callback.indexOf("serverDiagnosticServicePublished = true;");
        int resume = callback.indexOf(
                "maybeStartIncomingClientAttachAfterServicePublished(");
        assertTrue(exactObject >= 0);
        assertTrue(exactToken > exactObject);
        assertTrue(successBarrier > exactToken);
        assertTrue(successBarrier >= 0);
        assertTrue(resume > successBarrier);

        String close = between(transport,
                "private void closeGattServer", "private void discoverServices");
        assertTrue(close.contains("invalidateDiagnosticServicePublication();"));
        String failure = between(callback, "if (status != GATT_SUCCESS)",
                "serverDiagnosticServicePublished = true;");
        assertTrue(failure.contains("invalidateDiagnosticServicePublication();"));
    }

    @Test public void missingAncsKeepsCurrentOwnerAndWaitsForServiceChanged() throws Exception {
        String transport = transport();
        String absent = between(transport,
                "if (managedIncomingMode) {\n"
                        + "            state(\"SAME-OWNER LINK · ЖДУ SERVICE CHANGED / ANCS\")",
                "if (iphonePeripheralMode && !helperBootstrapMode)");
        assertTrue(absent.contains("subscribeServiceChangedIfAvailable(callbackGatt);"));
        assertTrue(absent.contains("current BluetoothGatt owner/epoch сохранены"));
        assertFalse(absent.contains("discoverServices("));
        assertFalse(absent.contains("scheduleAutoAncsWaitTimeout("));
        assertFalse(absent.contains("disconnect("));
        assertFalse(absent.contains("close("));
        assertFalse(absent.contains("postDelayed("));
    }

    @Test public void provisionalCccdAuthorizationIsEventDrivenAndDoesNotDropOwner()
            throws Exception {
        String transport = transport();
        String wait = between(transport,
                "private void waitForIncomingAncsAuthorizationEvent",
                "private void scheduleMandatoryDescriptorStatus133Retry");
        assertTrue(wait.contains("subscribeServiceChangedIfAvailable(expected);"));
        assertTrue(wait.contains("exact BluetoothGatt owner и current epoch"));
        assertFalse(wait.contains("discoverServices("));
        assertFalse(wait.contains("scheduleAncsPermissionRetry("));
        assertFalse(wait.contains("scheduleAncsRetryAfterBond("));
        assertFalse(wait.contains("disconnect("));
        assertFalse(wait.contains("close("));
        assertFalse(wait.contains("postDelayed("));

        String descriptor = between(transport,
                "private void handleDescriptorWrite", "private void flushEarlyNotificationSourceFrames");
        assertTrue(descriptor.contains("if (managedIncomingMode)"));
        assertTrue(descriptor.contains("waitForIncomingAncsAuthorizationEvent(callbackGatt,"));
    }

    @Test public void b3ReadyAndCurrentEpochGatesRemainMandatory() throws Exception {
        String transport = transport();
        String gate = between(transport,
                "private void maybeStartIncomingAncsDiscovery",
                "private void startSamePeerAttach");
        assertTrue(gate.contains("secureAttConfirmed"));
        assertTrue(gate.contains("incomingAncsReadyGateOpen"));
        assertTrue(gate.contains("isCurrentDiagnosticServicePublicationToken(publicationToken)"));
        assertTrue(gate.contains("secureAttPublicationToken != publicationToken"));
        assertTrue(gate.contains("incomingAncsReadyPublicationToken != publicationToken"));
        assertTrue(gate.contains("sessionState.isCurrent(activeClientGeneration)"));
        assertTrue(gate.contains("activeClientProvenSecurityEpoch != incomingSecurityEpoch"));
        assertTrue(gate.contains("discoverServices(expected);"));
    }

    @Test public void staleOrPendingF04RequestsCannotMutatePairB3OrReady() throws Exception {
        String transport = transport();

        String pair = between(transport,
                "private void handlePairCommand", "private Boolean markSecureAttConfirmed");
        assertTrue(pair.contains(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)"));

        String secure = between(transport,
                "private Boolean markSecureAttConfirmed", "private void handleSecureAttSuccess");
        assertTrue(secure.contains(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)"));
        assertTrue(secure.contains("secureAttPublicationToken = publicationToken;"));

        String ready = between(transport,
                "private boolean canAcceptAncsReady", "private void scheduleSecureClientStart");
        assertTrue(ready.contains(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)"));
        assertTrue(ready.contains("secureAttPublicationToken == publicationToken"));
        assertTrue(ready.contains("incomingAncsReadyPublicationToken = publicationToken;"));

        String server = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "private void handleIphonePeripheralConnectionState");
        int readToken = server.indexOf(
                "currentDiagnosticServicePublicationToken(characteristic)");
        int b3Mutation = server.indexOf("issueCurrentLinkSecurityChallenge(device)");
        assertTrue(readToken >= 0);
        assertTrue(b3Mutation > readToken);
        assertTrue(server.contains("publicationToken == 0L"));
        assertTrue(server.contains("handlePairCommand(device, publicationToken)"));
        assertTrue(server.contains("canAcceptAncsReady(device, publicationToken)"));
        assertTrue(server.contains("confirmAncsReady(\n"
                + "                                        device, publicationToken)"));
    }

    @Test public void everyManagedDiscoveryEntryRechecksPublicationAndLinkProofs()
            throws Exception {
        String transport = transport();
        String discovery = between(transport,
                "private void discoverServices", "private boolean startIphonePeripheralSecurity");
        int publication = discovery.indexOf(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)");
        int b3 = discovery.indexOf("secureAttPublicationToken == publicationToken");
        int ready = discovery.indexOf(
                "incomingAncsReadyPublicationToken == publicationToken");
        int generation = discovery.indexOf("sessionState.isCurrent(expectedGeneration)");
        int epoch = discovery.indexOf(
                "activeClientProvenSecurityEpoch == incomingSecurityEpoch");
        int rawDiscovery = discovery.indexOf("callbackGatt.discoverServices()");
        assertTrue(publication >= 0);
        assertTrue(b3 > publication);
        assertTrue(ready > b3);
        assertTrue(generation > ready);
        assertTrue(epoch > generation);
        assertTrue(rawDiscovery > epoch);
        assertTrue(discovery.contains("gattClientConnected && activeClientEstablished"));
        assertTrue(discovery.contains("queued/stale вызов отброшен"));

        // refresh, bond-delay, Service Changed and retry helpers may call only this wrapper.
        assertTrue(occurrences(transport, ".discoverServices()") == 1);

        String invalidate = between(transport,
                "private void invalidateDiagnosticServicePublication",
                "private void openGattServer");
        assertTrue(invalidate.contains("publishedDiagnosticServicePublicationToken = 0L;"));
        assertTrue(invalidate.contains("secureAttPublicationToken = 0L;"));
        assertTrue(invalidate.contains("incomingAncsReadyPublicationToken = 0L;"));
    }

    @Test public void mandatoryDescriptorTimeoutClosesAmbiguousWrapperBeforeReplacement()
            throws Exception {
        String transport = transport();
        String ownership = between(transport,
                "private long armDescriptorWriteOperation", "private boolean subscribe");
        assertTrue(ownership.contains("activeDescriptorWrite = descriptor;"));
        assertTrue(ownership.contains("activeDescriptorWriteGatt = owner;"));
        assertTrue(ownership.contains("activeDescriptorWriteOperationGeneration"));
        assertTrue(ownership.contains("activeDescriptorWriteClientGeneration"));
        assertTrue(ownership.contains("activeDescriptorWriteSecurityEpoch"));
        assertTrue(ownership.contains("activeDescriptorWritePublicationToken"));
        assertTrue(ownership.contains("activeDescriptorWrite != descriptor"));
        assertTrue(ownership.contains("sessionState.isCurrent("));

        String callback = between(transport,
                "private void handleDescriptorWrite", "private void flushEarlyNotificationSourceFrames");
        int exactOwner = callback.indexOf("ownsDescriptorWriteOperation(");
        int uuidStage = callback.indexOf("descriptorMatchesStage(");
        int advance = callback.indexOf("descriptorStage == DescriptorStage.DATA_SOURCE");
        assertTrue(exactOwner >= 0);
        assertTrue(uuidStage > exactOwner);
        assertTrue(advance > uuidStage);
        assertTrue(callback.contains("late/stale onDescriptorWrite"));

        String timeout = between(transport,
                "private void scheduleDescriptorWriteTimeout",
                "private void scheduleBatteryDescriptorTimeout");
        int timeoutOwner = timeout.indexOf("ownsDescriptorWriteOperation(");
        int poison = timeout.indexOf("poisonMandatoryDescriptorChannelAndRecover(");
        assertTrue(timeoutOwner >= 0);
        assertTrue(poison > timeoutOwner);

        String recovery = between(transport,
                "private void poisonMandatoryDescriptorChannelAndRecover",
                "private boolean ownsServerFacadeHandoffProbe");
        int currentPublication = recovery.indexOf(
                "isCurrentDiagnosticServicePublicationToken(expectedPublicationToken)");
        int closeOld = recovery.indexOf("closeClientGatt(expected);");
        int replacement = recovery.indexOf("adoptIncomingClientCandidate(exactIncoming,");
        assertTrue(currentPublication >= 0);
        assertTrue(recovery.contains("expectedSecurityEpoch == incomingSecurityEpoch"));
        assertTrue(recovery.contains(
                "secureAttPublicationToken == expectedPublicationToken"));
        assertTrue(recovery.contains(
                "incomingAncsReadyPublicationToken == expectedPublicationToken"));
        assertTrue(closeOld > currentPublication);
        assertTrue(replacement > closeOld);
        assertTrue(recovery.contains("poisonedWrapperReplacementAttempt"));
        assertTrue(recovery.contains("resetIncomingSecurityAfterClientLoss("));
        assertTrue(recovery.contains("preserveManagedIncomingPublicationAfterLinkLoss("));
        assertFalse(recovery.contains("discoverServices("));
        assertFalse(recovery.contains("expected.connect()"));

        String discoveryGate = between(transport,
                "private void maybeStartIncomingAncsDiscovery",
                "private void startSamePeerAttach");
        assertFalse(discoveryGate.contains("poisonedWrapperReplacementAttempt = 0;"));
        assertTrue(recovery.contains("poisonedWrapperReplacementAttempt++"));
        assertTrue(recovery.contains(
                "< RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS"));

        String fullSuccess = between(callback,
                "descriptorStage == DescriptorStage.DATA_SOURCE",
                "private void waitForIncomingAncsAuthorizationEvent");
        assertTrue(fullSuccess.contains("gattReady = true;"));
        assertTrue(fullSuccess.contains("poisonedWrapperReplacementAttempt = 0;"));
    }

    @Test public void realMandatoryStatus133GetsOnlyOneSameOwnerRetry() throws Exception {
        String transport = transport();
        String callback = between(transport,
                "private void handleDescriptorWrite", "private void flushEarlyNotificationSourceFrames");
        assertTrue(callback.contains("status == STATUS_GATT_ERROR"));
        assertTrue(callback.contains("gattReady = false;"));
        assertTrue(callback.contains(
                "scheduleMandatoryDescriptorStatus133Retry(callbackGatt, failedStage);"));

        String retry = between(transport,
                "private void scheduleMandatoryDescriptorStatus133Retry",
                "private void flushEarlyNotificationSourceFrames");
        assertTrue(retry.contains("mandatoryDescriptorStatus133RetryCount >= 1"));
        assertTrue(retry.contains("mandatoryDescriptorStatus133RetryCount++;"));
        assertTrue(retry.contains("isCurrentDiagnosticServicePublicationToken("));
        assertTrue(retry.contains("incomingSecurityEpoch != expectedSecurityEpoch"));
        assertTrue(retry.contains("discoverServices(expected);"));
        assertTrue(occurrences(retry, "discoverServices(expected);") == 1);
        assertFalse(retry.contains("gattReady = true;"));
        assertFalse(retry.contains("closeClientGatt("));
        assertFalse(retry.contains("disconnect("));
    }

    @Test public void b4CallbacksCannotCrossF04PublicationBoundary() throws Exception {
        String transport = transport();
        String setter = between(transport,
                "private void setServerTelemetrySubscription",
                "private void cancelServerTelemetryWakePoll");
        int setterPublication = setter.indexOf(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)");
        int setterPeer = setter.indexOf("findCurrentServerPeer(device) == null");
        int setterMutation = setter.indexOf("peer.telemetrySubscribed = enabled;");
        int descriptorMutation = setter.indexOf("cccd.setValue(enabled");
        assertTrue(setterPublication >= 0);
        assertTrue(setterPeer > setterPublication);
        assertTrue(setterMutation > setterPeer);
        assertTrue(descriptorMutation > setterPeer);

        String server = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "private void handleIphonePeripheralConnectionState");
        String descriptorRead = between(server,
                "public void onDescriptorReadRequest",
                "public void onDescriptorWriteRequest");
        assertTrue(descriptorRead.contains(
                "currentDiagnosticServicePublicationToken(characteristic)"));
        assertTrue(descriptorRead.contains(
                "publicationToken == 0L || !isVerifiedPeer(device)"));

        String descriptorWrite = between(server,
                "public void onDescriptorWriteRequest",
                "public void onNotificationSent");
        int writeToken = descriptorWrite.indexOf(
                "currentDiagnosticServicePublicationToken(characteristic)");
        int writeReject = descriptorWrite.indexOf("publicationToken == 0L");
        int postedMutation = descriptorWrite.indexOf(
                "setServerTelemetrySubscription(device, enable, publicationToken)");
        assertTrue(writeToken >= 0);
        assertTrue(writeReject > writeToken);
        assertTrue(postedMutation > writeReject);

        String characteristicRead = between(server,
                "public void onCharacteristicReadRequest",
                "public void onDescriptorReadRequest");
        String b4Read = characteristicRead.substring(characteristicRead.indexOf(
                "if (serverTelemetryCharacteristicUuid.equals(uuid))"));
        assertTrue(b4Read.contains(
                "currentDiagnosticServicePublicationToken(characteristic)"));
        assertTrue(b4Read.contains(
                "publicationToken == 0L || !isVerifiedPeer(device)"));

        String characteristicWrite = between(server,
                "public void onCharacteristicWriteRequest",
                "};");
        assertTrue(characteristicWrite.contains(
                "serverSecureCharacteristic.equals(uuid)\n"
                + "                            || serverTelemetryCharacteristicUuid.equals(uuid)"));
        assertTrue(characteristicWrite.contains("if (publicationToken == 0L)"));
        int postedRecheck = characteristicWrite.indexOf(
                "isCurrentDiagnosticServicePublicationToken(publicationToken)");
        int telemetryMutation = characteristicWrite.indexOf(
                "listener.onHelperTelemetry(telemetry)");
        assertTrue(postedRecheck >= 0);
        assertTrue(telemetryMutation > postedRecheck);
        assertTrue(characteristicWrite.contains("findCurrentServerPeer(device) == null"));
    }

    @Test public void servicesCallbackNeedsExactCurrentDiscoveryLineage() throws Exception {
        String transport = transport();
        String ownership = between(transport,
                "private long armDiscoveryOperation",
                "private void discoverServices");
        assertTrue(ownership.contains("activeDiscoveryGatt = owner;"));
        assertTrue(ownership.contains("activeDiscoveryOperationGeneration"));
        assertTrue(ownership.contains("activeDiscoveryClientGeneration"));
        assertTrue(ownership.contains("activeDiscoverySecurityEpoch"));
        assertTrue(ownership.contains("activeDiscoveryPublicationToken"));
        assertTrue(ownership.contains("discoveryPending"));
        assertTrue(ownership.contains(
                "isCurrentDiagnosticServicePublicationToken("));
        assertTrue(ownership.contains(
                "secureAttPublicationToken == activeDiscoveryPublicationToken"));
        assertTrue(ownership.contains(
                "incomingAncsReadyPublicationToken == activeDiscoveryPublicationToken"));

        String submission = between(transport,
                "private void discoverServices",
                "private boolean startIphonePeripheralSecurity");
        int arm = submission.indexOf("armDiscoveryOperation(");
        int raw = submission.indexOf("callbackGatt.discoverServices()");
        int timeoutGatt = submission.indexOf("activeDiscoveryGatt != expected");
        int timeoutOperation = submission.indexOf(
                "activeDiscoveryOperationGeneration != discoveryOperation");
        int poison = submission.indexOf("poisonDiscoveryChannelAndRecover(");
        assertTrue(arm >= 0);
        assertTrue(raw > arm);
        assertTrue(timeoutGatt > raw);
        assertTrue(timeoutOperation > timeoutGatt);
        assertTrue(poison > timeoutOperation);

        String callback = between(transport,
                "private void handleServicesDiscoveredCallback",
                "private void continueDiscoveredServiceSetup");
        int exactRawSlot = callback.indexOf("boolean exactRawSlot");
        int callbackOwner = callback.indexOf("ownsDiscoveryOperation(");
        int accept = callback.indexOf("acceptDiscoveryLineage();");
        int mutate = callback.indexOf("processDiscoveredServices(callbackGatt, status)");
        assertTrue(exactRawSlot >= 0);
        assertTrue(callbackOwner > exactRawSlot);
        assertTrue(accept > callbackOwner);
        assertTrue(mutate > accept);
        assertTrue(callback.contains("late/stale onServicesDiscovered"));
        assertTrue(callback.contains("Discard-only onServicesDiscovered drained"));

        String resume = between(transport,
                "private void continueDiscoveredServiceSetup",
                "private void processDiscoveredServices");
        assertTrue(resume.contains("ownsAcceptedDiscoveryLineage(callbackGatt)"));
        assertTrue(resume.indexOf("ownsAcceptedDiscoveryLineage(callbackGatt)")
                < resume.indexOf("processDiscoveredServices(callbackGatt, GATT_SUCCESS)"));

        String recovery = between(transport,
                "private void poisonDiscoveryChannelAndRecover",
                "private boolean ownsServerFacadeHandoffProbe");
        int close = recovery.indexOf("closeClientGatt(expected);");
        int replace = recovery.indexOf("adoptIncomingClientCandidate(exactIncoming,");
        assertTrue(close >= 0);
        assertTrue(replace > close);
        assertFalse(recovery.contains("expected.discoverServices()"));

        String clear = between(transport,
                "private boolean clearAncsRuntime", "private void cancelConnectTimeout");
        int discoveryAbandon = clear.indexOf("boolean abandonsDiscovery");
        int descriptorAbandon = clear.indexOf("boolean abandonsDescriptor");
        int closeAmbiguous = clear.indexOf("closeClientGatt(ambiguousRawOwner);");
        int clearLineage = clear.indexOf("clearDiscoveryLineage();");
        assertTrue(discoveryAbandon >= 0);
        assertTrue(descriptorAbandon > discoveryAbandon);
        assertTrue(closeAmbiguous > descriptorAbandon);
        assertTrue(clearLineage > closeAmbiguous);
        assertTrue(clear.contains("clearDiscoveryLineage();"));

        String freshEpoch = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        assertTrue(freshEpoch.contains("clearAncsRuntime();"));

        String publicationReset = between(transport,
                "private void invalidateDiagnosticServicePublication",
                "private long currentDiagnosticServicePublicationToken");
        int publicationRawDiscovery = publicationReset.indexOf(
                "boolean abandonsDiscovery");
        int publicationRawDescriptor = publicationReset.indexOf(
                "boolean abandonsDescriptor");
        int publicationClose = publicationReset.indexOf(
                "closeClientGatt(ambiguousRawOwner);");
        int publicationRollover = publicationReset.indexOf(
                "serverDiagnosticServicePublicationToken++;");
        assertTrue(publicationRawDiscovery >= 0);
        assertTrue(publicationRawDescriptor > publicationRawDiscovery);
        assertTrue(publicationClose > publicationRawDescriptor);
        assertTrue(publicationRollover > publicationClose);
    }

    @Test public void everyDescriptorTimeoutPoisonsWrapperAndRecoveryDoesNotReuseIt()
            throws Exception {
        String transport = transport();
        String descriptorTimeout = between(transport,
                "private void scheduleDescriptorWriteTimeout",
                "private void scheduleBatteryDescriptorTimeout");
        assertTrue(occurrences(descriptorTimeout,
                "poisonMandatoryDescriptorChannelAndRecover(expectedGatt,") == 3);
        assertFalse(descriptorTimeout.contains(
                "continueAfterHelperTelemetrySubscription(expectedGatt)"));

        String batteryTimeout = between(transport,
                "private void scheduleBatteryDescriptorTimeout",
                "private void cancelDescriptorWriteTimeout");
        assertTrue(batteryTimeout.contains("expectedClientGeneration"));
        assertTrue(batteryTimeout.contains("expectedSecurityEpoch"));
        assertTrue(batteryTimeout.contains("expectedPublicationToken"));
        assertTrue(batteryTimeout.contains(
                "poisonMandatoryDescriptorChannelAndRecover(expectedGatt,"));
        assertFalse(batteryTimeout.contains("sendNextRequest();"));

        String restart = between(transport,
                "private void restartDiscoveryOnPersistentOwner",
                "private boolean startSavedPeerScan");
        int restartClear = restart.indexOf("boolean rawOwnerClosed = clearAncsRuntime();");
        int restartRecover = restart.indexOf(
                "recoverIncomingClientRole(\"ambiguous raw callback owner replaced");
        int restartReuse = restart.indexOf("discoverServices(expected);");
        assertTrue(restartClear >= 0);
        assertTrue(restartRecover > restartClear);
        assertTrue(restartReuse > restartRecover);

        String await = between(transport,
                "private void awaitIncomingBackgroundOwner",
                "private void cancelClientAttemptCallbacks");
        int awaitClear = await.indexOf("boolean rawOwnerClosed = clearAncsRuntime();");
        int awaitRecover = await.indexOf(
                "recoverIncomingClientRole(\"ambiguous raw callback owner replaced");
        int awaitReuse = await.indexOf("rearmPersistentGattOwner(");
        assertTrue(awaitClear >= 0);
        assertTrue(awaitRecover > awaitClear);
        assertTrue(awaitReuse > awaitRecover);
    }

    @Test public void establishedLossCannotFallThroughAfterRawOwnerWasClosed()
            throws Exception {
        String transport = transport();
        String recovery = between(transport,
                "private void recoverEstablishedIncomingClientAfterCallbackLoss",
                "private boolean confirmPendingServerFacadeHandoff");
        int exactFacade = recovery.indexOf("findConnectedServerPeer(device)");
        int exactClear = recovery.indexOf("boolean rawOwnerClosed = clearAncsRuntime();");
        int exactClosedBranch = recovery.indexOf("rawOwnerClosed || gatt != expected");
        int replacementBudget = recovery.indexOf(
                "poisonedWrapperReplacementAttempt\n"
                + "                        >= RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS");
        int replacementIncrement = recovery.indexOf(
                "poisonedWrapperReplacementAttempt++;");
        int attachReset = recovery.indexOf("incomingClientAttachAttempt = 0;");
        int boundedReplacement = recovery.indexOf("scheduleIncomingClientAttachRetry(");
        int retainedRearm = recovery.indexOf("awaitIncomingBackgroundOwner(expected");
        assertTrue(exactFacade >= 0);
        assertTrue(exactClear > exactFacade);
        assertTrue(exactClosedBranch > exactClear);
        assertTrue(replacementBudget > exactClosedBranch);
        assertTrue(replacementIncrement > replacementBudget);
        assertTrue(attachReset > replacementIncrement);
        assertTrue(boundedReplacement > attachReset);
        assertTrue(retainedRearm > boundedReplacement);

        int physicalReset = recovery.indexOf(
                "resetIncomingSecurityAfterClientLoss(device, reason)");
        int closedAfterReset = recovery.indexOf(
                "rawOwnerClosed || gatt != expected", exactClosedBranch + 1);
        int waitNextLink = recovery.indexOf(
                "preserveManagedIncomingPublicationAfterLinkLoss(", closedAfterReset);
        int physicalRearm = recovery.indexOf(
                "awaitIncomingBackgroundOwner(expected", retainedRearm + 1);
        assertTrue(physicalReset > retainedRearm);
        assertTrue(closedAfterReset > physicalReset);
        assertTrue(waitNextLink > closedAfterReset);
        assertTrue(physicalRearm > waitNextLink);

        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "private final BroadcastReceiver bondReceiver");
        assertTrue(occurrences(callback,
                "recoverEstablishedIncomingClientAfterCallbackLoss(callbackGatt,") == 2);
        assertFalse(callback.contains(
                "resetIncomingSecurityAfterClientLoss(callbackGatt.getDevice(),\n"
                + "                                    \"established same-peer"));
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
