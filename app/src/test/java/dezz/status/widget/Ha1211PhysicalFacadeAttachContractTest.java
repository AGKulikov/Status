/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the HA1211 physical-target versus PAIR-authorization A/B. */
public final class Ha1211PhysicalFacadeAttachContractTest {
    @Test public void stablePeerKeepsImmutablePhysicalAndMutableAuthorizationFacades()
            throws Exception {
        String transport = transport();
        String peer = between(transport,
                "private static final class GattServerPeer",
                "/** Immutable authorization captured");
        assertTrue(peer.contains("final BluetoothDevice physicalLinkFacade;"));
        assertTrue(peer.contains("BluetoothDevice device;"));
        assertTrue(peer.contains("this.physicalLinkFacade = device;"));

        String fields = between(transport,
                "private BluetoothDevice incomingClientCandidate;",
                "private volatile boolean serverDiagnosticServicePublished;");
        assertTrue(fields.contains("incomingClientAttemptTransportFacade"));
        assertTrue(fields.contains("incomingClientAttemptPairFacade"));
        assertTrue(fields.contains("incomingClientAttemptServerPeer"));
        assertTrue(fields.contains("incomingReadyAttachLatchPhysicalFacade"));
        assertTrue(fields.contains("incomingReadyAttachLatchServerPeer"));
    }

    @Test public void anonymousAAndBondedBMergeOnlyInsideExactPairC() throws Exception {
        String transport = transport();
        String record = between(transport,
                "private boolean recordGattServerPeer",
                "private void bindServerPeerToCurrentSecurityEpoch");
        assertTrue(record.contains("Before PAIR, A anonymous and B bonded remain "
                + "distinct records"));
        assertFalse(record.contains("callbackPeer = soleAnonymous;"));
        assertTrue(record.contains("physicalLinkFacade"));
        assertTrue(record.contains("acceptedPhysicalRecordDisconnected"));
        assertTrue(record.contains("!pendingHandoff"));
        assertTrue(record.contains("resetIncomingSecurityAfterClientLoss(device,"));

        String bind = between(transport,
                "private AncsRecoveryPolicy.PairFacadeBindDecision "
                        + "bindExactPairRequestFacadeIfSafe",
                "private void handlePairWriteRequestOnMain");
        assertTrue(bind.contains("physicalFacadeTopologyDecision("));
        assertTrue(bind.contains("matchingCurrentPeer.connectedAtElapsedMs"));
        assertTrue(bind.contains("soleAnonymousAlias.connectedAtElapsedMs < 0L"));
        assertTrue(bind.contains("ANONYMOUS_BONDED_ALIAS_MAX_DELTA_MS"));
        int capture = bind.indexOf("BluetoothDevice physicalLinkFacade = "
                + "peer.physicalLinkFacade;");
        int fresh = bind.indexOf("beginFreshIncomingSecurityEpoch(device,", capture);
        int auth = bind.indexOf("peer.device = device;", fresh);
        assertTrue(capture >= 0);
        assertTrue(fresh > capture);
        assertTrue(auth > fresh);
        assertTrue(bind.contains("CREATE_FROM_PAIR_ATT"));
        assertTrue(bind.contains("new GattServerPeer(sessionGeneration, device)"));
        assertTrue(bind.contains("iterator.next().getValue() == redundantPeer"));
    }

    @Test public void onePostReadyHiddenCallTargetsPhysicalAAndKeepsPairC()
            throws Exception {
        String transport = transport();
        String attach = between(transport,
                "private void startIncomingDirectAttach",
                "/**\n     * Android 9 light-greylist overload");
        assertTrue(attach.contains("incomingReadyAttachLatchPhysicalFacade"));
        assertTrue(attach.contains("capturedServerPeer != serverLink"));
        assertTrue(attach.contains("findExactPhysicalServerPeer(physicalTargetFacade)"));
        assertTrue(attach.contains("BluetoothDevice pairFacade = serverLink.device;"));
        assertTrue(attach.contains("BluetoothDevice device = physicalTargetFacade;"));
        assertTrue(attach.contains("incomingClientCandidate = pairFacade;"));
        assertTrue(attach.contains("incomingClientAttemptTransportFacade = device;"));
        assertTrue(attach.contains("incomingClientAttemptPairFacade = pairFacade;"));
        assertTrue(attach.contains("incomingClientAttemptServerPeer = serverLink;"));
        assertTrue(attach.contains("connectGattOpportunisticOnPie(device)"));
        assertTrue(attach.contains("physicalObjectId="));
        assertTrue(attach.contains("pairObjectId="));
        assertTrue(attach.contains("sameAddress="));
        assertFalse(attach.contains("scheduleIncomingClientAttachRetry("));
        assertFalse(attach.contains("scheduleDirectFallback("));
    }

    @Test public void callbacksRequireExactPhysicalAndCurrentAuthorizationTuple()
            throws Exception {
        String transport = transport();
        String owner = between(transport,
                "private boolean ownsCurrentIncomingClientAttempt",
                "/**\n     * Shared post-READY barrier");
        assertTrue(owner.contains("callbackDevice == transportFacade"));
        assertTrue(owner.contains("attemptPeer == incomingPairAcceptedServerPeer"));
        assertTrue(owner.contains("attemptPeer.physicalLinkFacade == transportFacade"));
        assertTrue(owner.contains("attemptPeer.device == pairFacade"));
        assertTrue(owner.contains("findExactPhysicalServerPeer(transportFacade) "
                + "== attemptPeer"));
        assertTrue(owner.contains("findExactCurrentServerPeer(pairFacade) == attemptPeer"));
        assertTrue(owner.contains("incomingPairAcceptedSessionGeneration == "
                + "sessionGeneration"));
        assertTrue(owner.contains("incomingPairAcceptedSecurityEpoch == "
                + "incomingSecurityEpoch"));
        assertTrue(owner.contains("incomingPairAcceptedPublicationToken == "
                + "publicationToken"));
        assertTrue(owner.contains("currentSecureProof"));
        assertTrue(owner.contains("currentReadyProof"));
    }

    @Test public void terminalObserverLossCannotLeaveStalePendingHandoff()
            throws Exception {
        String transport = transport();
        String timeout = between(transport,
                "connectTimeout = () -> {",
                "main.postDelayed(connectTimeout, INCOMING_DIRECT_ATTACH_TIMEOUT_MS);");
        assertTrue(timeout.contains("serverFacadeLostWhilePending"));
        assertTrue(timeout.contains("unregisterNeverEstablishedOpportunisticGatt(expected)"));
        assertTrue(timeout.indexOf("unregisterNeverEstablishedOpportunisticGatt(expected)")
                < timeout.indexOf("resetIncomingSecurityAfterClientLoss("));
        assertTrue(timeout.contains("roleFacadeHandoffPending очищены без "
                + "disconnect/GATT-server close"));

        String helper = between(transport,
                "private boolean resetRetiredObserverAfterServerFacadeLoss",
                "private void cancelClientAttemptCallbacks");
        assertTrue(helper.contains("attemptPeer != incomingPairAcceptedServerPeer"));
        assertTrue(helper.contains("attemptPeer.connected"));
        assertTrue(helper.contains("resetIncomingSecurityAfterClientLoss("));
        assertFalse(helper.contains("disconnect("));

        String serverLoss = between(transport,
                "private void handleServerFacadeDisconnected",
                "/** Clears only per-link state");
        int established = serverLoss.indexOf("establishedClientOwnsPhysicalLink(device)");
        int pending = serverLoss.indexOf("pendingExactClientAttach(device)", established);
        int passive = serverLoss.indexOf("activeClientOpportunistic", pending);
        assertTrue(established >= 0);
        assertTrue(pending > established);
        assertTrue(passive > pending);
    }

    @Test public void managedPairUsesMtuSafeBinaryChallengeAndCarriesItInLineage()
            throws Exception {
        String transport = transport();
        assertTrue(transport.contains("MANAGED_PROOF_FRAME_BYTES = 17"));
        assertTrue(transport.contains("MANAGED_PAIR_OPCODE = 0x50"));
        assertTrue(transport.contains("MANAGED_LINK_BOUND_OPCODE = 0x4C"));
        assertTrue(transport.contains("MANAGED_ANCS_SUBSCRIBED_OPCODE = 0x41"));

        String pair = between(transport,
                "private void handlePairWriteRequestOnMain",
                "private void handleSecureReadRequestOnMain");
        assertTrue(pair.contains("managedProofChallenge(value, MANAGED_PAIR_OPCODE)"));
        assertTrue(pair.contains("managed CONTROL requires exact 17-byte binary P/Q"));
        assertTrue(pair.contains("!managedIncomingMode && !\"PAIR\".equals(command)"));

        String commit = between(transport,
                "private Boolean commitPairCommand",
                "/** UI, logging, bonding");
        assertTrue(commit.contains("Arrays.equals(incomingPairAcceptedChallenge, "
                + "pairChallenge)"));
        assertTrue(commit.contains("incomingPairAcceptedChallenge = pairChallenge.clone()"));
        assertTrue(transport.contains("incomingReadyAttachLatchChallenge"));
        assertTrue(transport.contains("incomingClientAttemptChallenge"));
        assertTrue(transport.contains("acceptedDiscoveryChallenge"));
        assertTrue(transport.contains("activeDescriptorWriteChallenge"));
        assertTrue(transport.contains("helperLinkBoundChallenge"));
    }

    @Test public void f05LinkBoundAcknowledgementPrecedesEveryAncsCccd()
            throws Exception {
        String transport = transport();
        String services = between(transport,
                "private void processDiscoveredServices",
                "private void subscribeServiceChangedIfAvailable");
        int exactF05 = services.indexOf("boolean exactF05");
        int f05Cccd = services.indexOf("startOptionalHelperTelemetrySubscription", exactF05);
        int linkBound = services.indexOf("startManagedLinkBoundProof", f05Cccd);
        int ancs = services.indexOf("getService(AncsProtocol.SERVICE)", linkBound);
        int ancsGate = services.indexOf("hasCurrentManagedLinkBoundProof", ancs);
        int notificationCccd = services.indexOf(
                "descriptorStage = DescriptorStage.NOTIFICATION_SOURCE", ancsGate);
        assertTrue(exactF05 >= 0);
        assertTrue(f05Cccd > exactF05);
        assertTrue(linkBound > f05Cccd);
        assertTrue(ancs > linkBound);
        assertTrue(ancsGate > ancs);
        assertTrue(notificationCccd > ancsGate);

        String start = between(transport,
                "private boolean startManagedLinkBoundProof",
                "private boolean handleManagedHelperProofWriteCallback");
        assertTrue(start.contains("managedProofFrame(MANAGED_LINK_BOUND_OPCODE, challenge)"));
        assertTrue(start.contains("WRITE_TYPE_DEFAULT"));
        assertTrue(start.contains("armHelperProofWriteOperation("));
        assertTrue(start.contains("terminalManagedLinkBindingFailure"));
        assertFalse(start.contains("scheduleHelperAncsReadyProofRetry"));

        String callback = between(transport,
                "private boolean handleManagedHelperProofWriteCallback",
                "private boolean startOptionalHelperTelemetrySubscription");
        assertTrue(callback.contains("stage == HelperProofWriteStage.LINK_BOUND"));
        assertTrue(callback.contains("helperLinkBoundAcknowledged = true"));
        assertTrue(callback.indexOf("helperLinkBoundAcknowledged = true")
                < callback.indexOf("continueDiscoveredServiceSetup(callbackGatt)"));
        assertTrue(callback.contains("terminalManagedLinkBindingFailure"));
        assertFalse(callback.contains("scheduleHelperAncsReadyProofRetry"));
    }

    @Test public void preBindF05DataHasZeroPayloadOrUiSideEffects() throws Exception {
        String transport = transport();
        String changed = between(transport,
                "private void handleCharacteristicChanged",
                "private static boolean descriptorMatchesStage");
        int gate = changed.indexOf("Pre-bind F05/B4 notification quarantined");
        int mutate = changed.indexOf("characteristic.setValue(callbackValue)");
        int payloadLog = changed.indexOf("AdvertisementParser.hex(value, 80)");
        int parse = changed.indexOf("IphoneHelperTelemetry.parse(value)");
        assertTrue(gate >= 0);
        assertTrue(mutate > gate);
        assertTrue(payloadLog > gate);
        assertTrue(parse > gate);

        String read = between(transport,
                "public void onCharacteristicRead",
                "public void onReadRemoteRssi");
        int readGate = read.indexOf("Pre-bind F05/B4 read callback quarantined");
        int readParse = read.indexOf("IphoneHelperTelemetry.parse(copy)");
        assertTrue(readGate >= 0);
        assertTrue(readParse > readGate);
    }

    @Test public void postCccdProofIsBinaryAqAndHasNoManagedRetry() throws Exception {
        String transport = transport();
        String proof = between(transport,
                "private boolean startHelperAncsReadyProof",
                "private void scheduleHelperAncsReadyProofRetry");
        assertTrue(proof.contains("hasCurrentManagedLinkBoundProof(callbackGatt)"));
        assertTrue(proof.contains("managedProofFrame("));
        assertTrue(proof.contains("MANAGED_ANCS_SUBSCRIBED_OPCODE"));
        assertTrue(proof.contains("HelperProofWriteStage.ANCS_SUBSCRIBED"));
        assertTrue(proof.contains("terminalManagedLinkBindingFailure"));
        assertFalse(proof.contains("scheduleHelperAncsReadyProofRetry"));
        assertFalse(proof.contains("\"ANCS-SUBSCRIBED\".getBytes"));
    }

    @Test public void serviceChangedInvalidatesF05BindingGeneration() throws Exception {
        String transport = transport();
        String changed = between(transport,
                "private void handleCharacteristicChanged",
                "private static boolean descriptorMatchesStage");
        assertTrue(changed.contains("Service Changed while old F05 raw callback slot "
                + "in flight"));
        assertTrue(changed.contains("managedF05DatabaseGeneration++"));
        assertTrue(changed.contains("clearManagedLinkBoundProof()"));
        assertTrue(changed.contains("Service Changed invalidated F05 handles/LINK-BOUND Q"));
    }

    @Test public void releaseIdentityWorkflowAndManifestAdvanceTogether() throws Exception {
        String build = rootProject("build.gradle");
        String workflow = project(".github/workflows/verify-ha1211.yml");
        String manifest = project("release-manifests/HA1211.md");
        assertTrue(build.contains("return 'v2.8.2-ha1211'"));
        assertTrue(workflow.contains("work/ha1211-physical-facade-ab"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1211'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021211'"));
        assertTrue(workflow.contains("Ha1211PhysicalFacadeAttachContractTest"));
        assertTrue(manifest.contains("v2.8.2-ha1211"));
        assertTrue(manifest.contains("208021211"));
        assertTrue(manifest.contains("physicalLinkFacade"));
        assertTrue(manifest.contains("Helper v45"));
        String helperWorkflow = project(".github/workflows/verify-helper-v45.yml");
        String helperProject = project("ios/KX11-iPhone-ANCS-Helper-v45/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        assertTrue(helperWorkflow.contains("ios/KX11-iPhone-ANCS-Helper-v45"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 45;"));
        assertTrue(helperProject.contains("MARKETING_VERSION = 45.0;"));
    }

    private static String transport() throws Exception {
        return project("app/src/main/java/dezz/status/widget/phone/transport/"
                + "IphoneAncsTransport.java");
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate) && Files.isDirectory(current.resolve("app"))) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
    }
}
