/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the HA1205 B4 continuation and status=22 fresh-owner recovery. */
public final class Ha1205AncsRecoveryContractTest {
    @Test public void b4CompletionResumesAcceptedAncsWithoutParallelDiscovery()
            throws Exception {
        String transport = transport();
        String continuation = between(transport,
                "private void continueAfterHelperTelemetrySubscription",
                "/** Records a real B4 payload");
        assertTrue(continuation.contains("ownsAcceptedDiscoveryLineage(callbackGatt)"));
        assertTrue(continuation.contains("CONTINUE_ACCEPTED_SERVICES"));
        assertTrue(continuation.contains("iphoneHelperTelemetrySetupBypass = true;"));
        assertTrue(continuation.contains("continueDiscoveredServiceSetup(callbackGatt);"));
        assertFalse(continuation.contains("discoverServices(callbackGatt);"));

        String services = between(transport,
                "private void processDiscoveredServices",
                "private void subscribeServiceChangedIfAvailable");
        assertTrue(services.contains("&& !iphoneHelperTelemetrySetupBypass"));
        assertTrue(services.contains("DescriptorStage.NOTIFICATION_SOURCE"));

        String descriptor = between(transport,
                "private void handleDescriptorWrite",
                "private void flushEarlyNotificationSourceFrames");
        assertTrue(descriptor.contains("DescriptorStage.DATA_SOURCE"));
        assertTrue(descriptor.contains("gattReady = true;"));
    }

    @Test public void postSecureLatchBelongsToOneExactRunnableAndSerializesDiscovery()
            throws Exception {
        String transport = transport();
        String schedule = between(transport,
                "private void scheduleIphonePostSecureDiscovery",
                "private void scheduleAutoAncsWaitTimeout");
        int captureGeneration = schedule.indexOf(
                "long expectedClientGeneration = activeClientGeneration;");
        int captureToken = schedule.indexOf(
                "long scheduledToken = ++iphonePostSecureDiscoveryToken;");
        int tokenGate = schedule.indexOf(
                "if (scheduledToken != iphonePostSecureDiscoveryToken) return;");
        int releaseLatch = schedule.indexOf("iphonePostSecureDiscoveryScheduled = false;");
        int busyGate = schedule.indexOf("if (hasSerializedGattOperationInFlight())");
        int retry = schedule.indexOf("scheduleIphonePostSecureDiscovery(callbackGatt);",
                busyGate);
        int discovery = schedule.indexOf("discoverServices(callbackGatt);", retry);
        assertTrue(captureGeneration >= 0);
        assertTrue(captureToken > captureGeneration);
        assertTrue(tokenGate > captureToken);
        assertTrue(releaseLatch > tokenGate);
        assertTrue(busyGate > releaseLatch);
        assertTrue(retry > busyGate);
        assertTrue(discovery > retry);
        assertTrue(schedule.contains("expectedClientGeneration != activeClientGeneration"));
        assertTrue(schedule.contains("callbackGatt != gatt"));
        assertTrue(schedule.contains("helperBootstrapMode) return iphoneSecureConfirmed"));
        assertTrue(schedule.contains("activeClientEstablished"));
        assertTrue(schedule.contains(
                "sameDevice(activeClientTarget, callbackGatt.getDevice())"));
        assertTrue(schedule.contains("BluetoothDevice.BOND_BONDED"));
        String commonGate = between(schedule,
                "private boolean hasSerializedGattOperationInFlight",
                "private void cancelIphonePostSecureDiscovery");
        assertTrue(commonGate.contains("discoveryPending"));
        assertTrue(commonGate.contains("descriptorStage != DescriptorStage.NONE"));
        assertTrue(commonGate.contains("iphoneHelperTelemetryReadPending"));
        assertTrue(commonGate.contains("activeRequest != null"));
        assertTrue(commonGate.contains("batteryReadPendingUuid != null"));
    }

    @Test public void dailyWaitWatchdogUsesTheSameSerializedRouteGate()
            throws Exception {
        String auto = between(transport(),
                "private void scheduleAutoAncsWaitTimeout",
                "private void cancelAutoAncsWaitTimeout");
        int routeGate = auto.indexOf("canRunIphonePostSecureDiscovery(expected)");
        int generation = auto.indexOf(
                "long expectedClientGeneration = activeClientGeneration;");
        int token = auto.indexOf("long scheduledToken = ++autoAncsWaitToken;");
        int busy = auto.indexOf("if (hasSerializedGattOperationInFlight())");
        int retry = auto.indexOf(
                "scheduleAutoAncsWaitTimeout(expected, HELPER_TELEMETRY_BUSY_RETRY_MS)");
        int discovery = auto.indexOf("discoverServices(expected);");
        assertTrue(routeGate >= 0);
        assertTrue(generation > routeGate);
        assertTrue(token > generation);
        assertTrue(busy > token);
        assertTrue(retry > busy);
        assertTrue(discovery > retry);
        assertTrue(auto.contains("expectedClientGeneration != activeClientGeneration"));
        assertTrue(auto.contains("scheduledToken != autoAncsWaitToken"));
        assertTrue(auto.contains("B4/descriptor/request не прерываются"));
    }

    @Test public void status22WaitsForFreshEpochB3ReadyThenConsumesOneExactAttach()
            throws Exception {
        String transport = transport();
        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
        assertTrue(callback.contains(
                "status == STATUS_GATT_CONN_TERMINATE_LOCAL_HOST"));

        String recovery = between(transport,
                "private void recoverEstablishedIncomingClientAfterCallbackLoss",
                "private boolean confirmPendingServerFacadeHandoff");
        String status22 = between(recovery,
                "if (replaceAfterFreshSecurity)",
                "GattServerPeer connectedFacade");
        assertTrue(status22.contains("resetIncomingSecurityAfterClientLoss("));
        int closeRetired = status22.indexOf("closeClientGatt(expected);");
        assertTrue(closeRetired >= 0);
        assertTrue(closeRetired
                < status22.indexOf("incomingStaleEstablishedOwner = expected;"));
        assertTrue(status22.contains("incomingStaleEstablishedOwner = expected;"));
        assertTrue(status22.contains("incomingStaleOwnerAwaitingFreshEpoch = true;"));
        assertFalse(status22.contains("awaitIncomingBackgroundOwner("));
        assertFalse(status22.contains("scheduleIncomingClientAttachRetry("));

        String fresh = between(transport,
                "private void beginFreshIncomingSecurityEpoch",
                "private boolean resetIncomingSecurityAfterClientLoss");
        assertTrue(fresh.contains(
                "incomingStaleOwnerReplacementEpoch = incomingSecurityEpoch;"));
        assertTrue(fresh.contains("incomingStaleOwnerAwaitingFreshEpoch"));
        assertTrue(fresh.contains(
                "clearAncsRuntimeWithoutClientCommands(emitDiagnosticLogs);"));

        String replacement = between(transport,
                "private boolean replaceStaleEstablishedOwnerAfterFreshReady",
                "private void scheduleSecureClientStart");
        int exactFacade = replacement.indexOf("serverLink.device == exactIncomingDevice");
        int policy = replacement.indexOf("shouldReplaceStaleOwnerOnReady(");
        int consume = replacement.indexOf(
                "incomingFreshReplacementConsumedEpoch = incomingSecurityEpoch;");
        int attach = replacement.indexOf("startIncomingDirectAttach(");
        assertTrue(exactFacade >= 0);
        assertTrue(policy > exactFacade);
        assertTrue(consume > policy);
        assertTrue(attach > consume);
        assertTrue(replacement.contains("secureAttPublicationToken == publicationToken"));
        assertTrue(replacement.contains(
                "incomingAncsReadyPublicationToken == publicationToken"));
        assertTrue(replacement.contains(
                "fresh F04 epoch + B3 + ANCS-READY after status=22\", true"));
    }

    @Test public void oneShotFailureCannotRetryOrLoopWithinTheConsumedEpoch()
            throws Exception {
        String transport = transport();
        String direct = between(transport,
                "private void startIncomingDirectAttach",
                "private void scheduleDirectFallback");
        String nullFailure = between(direct,
                "if (created == null)", "BluetoothGatt expected = created;");
        assertTrue(nullFailure.contains("if (oneShotFreshReplacement)"));
        assertTrue(nullFailure.contains("public/direct fallback запрещён"));
        assertFalse(nullFailure.contains("scheduleIncomingClientAttachRetry("));

        String timeout = between(direct,
                "connectTimeout = () ->", "main.postDelayed(connectTimeout");
        assertTrue(timeout.contains("exactFreshReplacement"));
        assertTrue(timeout.contains("unregisterNeverEstablishedOpportunisticGatt(expected)"));
        assertFalse(timeout.contains("scheduleIncomingClientAttachRetry("));

        String retry = between(transport,
                "private void scheduleIncomingClientAttachRetry",
                "private void recoverIncomingClientRole");
        assertTrue(retry.contains("Same-tuple clientIf retry запрещён"));
        assertFalse(retry.contains("startSamePeerAttach("));

        String savedCandidate = between(transport,
                "private void maybeStartIncomingClientAttachAfterServicePublished",
                "private boolean isVerifiedPeer");
        assertTrue(savedCandidate.contains("zero pre-ready clientIf attempts"));
        assertFalse(savedCandidate.contains("startSamePeerAttach("));

        String connected = between(callback(transport),
                "newState == BluetoothProfile.STATE_CONNECTED",
                "newState == BluetoothProfile.STATE_DISCONNECTED");
        assertFalse(connected.contains("incomingFreshReplacementConsumedEpoch = 0L;"));
        assertTrue(connected.contains("epoch остаётся consumed"));

        String statusRecovery = between(transport,
                "private void recoverEstablishedIncomingClientAfterCallbackLoss",
                "private boolean confirmPendingServerFacadeHandoff");
        String status22 = between(statusRecovery,
                "if (replaceAfterFreshSecurity)",
                "GattServerPeer connectedFacade");
        assertFalse(status22.contains("incomingFreshReplacementConsumedEpoch = 0L;"));
        assertTrue(callback(transport).contains("if (callbackGatt != gatt) {"));
        assertTrue(callback(transport).contains("Stale reverse client callback ignored"));
    }

    @Test public void freshServerCallbackBeforeStatus22IsCoalescedNotErased()
            throws Exception {
        String transport = transport();
        String recovery = between(transport,
                "private void recoverEstablishedIncomingClientAfterCallbackLoss",
                "private boolean confirmPendingServerFacadeHandoff");
        String status22 = between(recovery,
                "if (replaceAfterFreshSecurity)",
                "GattServerPeer connectedFacade");
        int exactFacade = status22.indexOf(
                "GattServerPeer exactConnectedFacade = findConnectedServerPeer(device);");
        int inversionPolicy = status22.indexOf(
                "status22MayUseAlreadyConnectedFacade(");
        int preserveBranch = status22.indexOf("if (currentFacadeAlreadyArrived)");
        int clientOnlyClear = status22.indexOf("rawOwnerClosed = clearAncsRuntime();",
                preserveBranch);
        int resetBranch = status22.indexOf("} else {", clientOnlyClear);
        int physicalReset = status22.indexOf("resetIncomingSecurityAfterClientLoss(",
                resetBranch);
        int bindCurrentEpoch = status22.indexOf(
                "? incomingSecurityEpoch : 0L;");
        assertTrue(exactFacade >= 0);
        assertTrue(inversionPolicy > exactFacade);
        assertTrue(preserveBranch > inversionPolicy);
        assertTrue(clientOnlyClear > preserveBranch);
        assertTrue(resetBranch > clientOnlyClear);
        assertTrue(physicalReset > resetBranch);
        assertTrue(bindCurrentEpoch > physicalReset);
        assertFalse(status22.substring(preserveBranch, resetBranch)
                .contains("resetIncomingSecurityAfterClientLoss("));
        assertTrue(status22.contains("incomingStaleOwnerAwaitingFreshEpoch = true;"));
        assertTrue(status22.contains("secureAttConfirmed && incomingAncsReadyGateOpen"));
        assertTrue(status22.contains("replaceStaleEstablishedOwnerAfterFreshReady("));

        String facadeLoss = between(transport,
                "private void handleServerFacadeDisconnected",
                "/** Clears only per-link state");
        int pendingGuard = facadeLoss.indexOf("incomingStaleOwnerAwaitingFreshEpoch");
        int establishedPath = facadeLoss.indexOf("establishedClientOwnsPhysicalLink(device)");
        assertTrue(pendingGuard >= 0);
        assertTrue(establishedPath > pendingGuard);
        assertTrue(facadeLoss.substring(pendingGuard, establishedPath)
                .contains("return;"));
        assertFalse(facadeLoss.substring(pendingGuard, establishedPath)
                .contains("preserveManagedIncomingPublicationAfterLinkLoss("));
    }

    @Test public void status22CancelsAndGuardsEveryLateRssiRearmPath()
            throws Exception {
        String recovery = between(transport(),
                "private void recoverEstablishedIncomingClientAfterCallbackLoss",
                "private boolean confirmPendingServerFacadeHandoff");
        int status22 = recovery.indexOf("if (replaceAfterFreshSecurity)");
        int cancelProbe = recovery.indexOf("cancelAmbiguousAclProbe();", status22);
        int findFacade = recovery.indexOf("findConnectedServerPeer(device)", status22);
        int lateProbeGuard = recovery.indexOf(
                "incomingStaleEstablishedOwner == expected", findFacade);
        int genericFacade = recovery.indexOf(
                "GattServerPeer connectedFacade", lateProbeGuard);
        assertTrue(status22 >= 0);
        assertTrue(cancelProbe > status22);
        assertTrue(findFacade > cancelProbe);
        assertTrue(lateProbeGuard > findFacade);
        assertTrue(genericFacade > lateProbeGuard);
        String guard = recovery.substring(lateProbeGuard, genericFacade);
        assertTrue(guard.contains("return;"));
        assertFalse(guard.contains("awaitIncomingBackgroundOwner("));
        assertFalse(guard.contains("rearmPersistentGattOwner("));
    }

    private static String callback(String transport) {
        return between(transport,
                "private final BluetoothGattCallback gattCallback",
                "public void onServicesDiscovered");
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
