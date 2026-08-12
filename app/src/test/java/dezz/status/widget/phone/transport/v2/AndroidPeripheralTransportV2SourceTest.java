/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source contract for Route B on host builds that do not provide android.jar. */
public final class AndroidPeripheralTransportV2SourceTest {
    private static final Path SOURCE = Paths.get(
            "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                    + "AndroidPeripheralTransportV2.java");

    @Test public void publishesUuidOnlyEncryptedWriteAndIndicateControlService()
            throws Exception {
        String source = source();
        String service = between(source, "private void addServerService",
                "private void startAdvertising");
        assertTrue(service.contains("PROPERTY_WRITE\n"
                + "                        | BluetoothGattCharacteristic.PROPERTY_INDICATE"));
        assertTrue(service.contains("PERMISSION_WRITE_ENCRYPTED"));
        assertFalse(service.contains("PROPERTY_NOTIFY"));

        String advertising = between(source, "private void startAdvertising",
                "private void stopAdvertising");
        assertTrue(advertising.contains("setIncludeDeviceName(false)"));
        assertTrue(advertising.contains("ANDROID_PERIPHERAL_SERVICE"));
        assertFalse(advertising.contains("getName("));
    }

    @Test public void allPlatformCallbacksUseConditionalMainAndCloneBytePayloads()
            throws Exception {
        String source = source();
        String serverCallbacks = between(source, "private final class AdvertisingAttempt",
                "private final Context context");
        assertTrue(serverCallbacks.contains("dispatchMain(() ->"));
        assertTrue(serverCallbacks.contains("exactValue = value == null ? null : value.clone()"));
        assertFalse(serverCallbacks.contains("main.post("));

        String reverseCallbacks = between(source,
                "private final BluetoothGattCallback reverseGattCallback",
                "private void handleReverseConnection");
        assertTrue(reverseCallbacks.contains("dispatchMain(() ->"));
        assertTrue(reverseCallbacks.contains("value.clone()"));
        assertFalse(reverseCallbacks.contains("main.post("));

        String dispatcher = between(source, "private void dispatchMain", "\n    }");
        assertTrue(dispatcher.contains("Looper.myLooper() == main.getLooper()"));
        assertTrue(dispatcher.contains("callbackBody.run()"));
    }

    @Test public void freezeFencesPublicationAndProvesRemoteAbsenceWithoutLocalOwnerCount()
            throws Exception {
        String source = source();
        String freeze = between(source, "@Override public void freezeIngress",
                "@Override public void transmitControl");
        assertTrue(freeze.contains("ingressFrozen = true"));
        assertTrue(freeze.indexOf("stopAdvertisingForFreeze()")
                < freeze.indexOf("boolean exactNoRemoteOwner"));
        assertTrue(freeze.contains("inboundPhysicalFacade == null"));
        assertTrue(freeze.contains("reverseGatt == null"));
        assertTrue(freeze.contains("observingReverseToken == null"));
        assertTrue(freeze.contains("pendingReverse == null"));
        assertFalse(freeze.contains("appOwnedOwnerCount() == 0"));

        String inbound = between(source, "private void handleInboundConnection",
                "private void cancelForeignConnection");
        assertTrue(inbound.contains("if (ingressFrozen)"));
        assertTrue(inbound.contains("cancelForeignConnection(device)"));

        String advertiserCallbacks = between(source,
                "private void handleAdvertisingStartSuccess",
                "private boolean isCurrentAdvertisingAttempt");
        assertTrue(advertiserCallbacks.contains("ingressFrozen"));
        assertTrue(advertiserCallbacks.contains("maybeCompleteTeardown()"));

        String completions = between(source, "private void postServerOpened",
                "private void disconnectInbound");
        assertTrue(completions.contains("if (!ingressFrozen && state != null)"));
    }

    @Test public void retiredAdvertiserAndServerCallbacksCannotEnterFreshGeneration()
            throws Exception {
        String source = source();
        assertTrue(source.contains(
                "private final class AdvertisingAttempt extends AdvertiseCallback"));
        assertTrue(source.contains(
                "private final class ServerAttempt"));
        assertFalse(source.contains(
                "private final AdvertiseCallback advertiseCallback"));
        assertFalse(source.contains(
                "private final BluetoothGattServerCallback serverCallback"));

        String advertising = between(source, "private void startAdvertising",
                "private void stopAdvertising");
        assertTrue(advertising.contains(
                "AdvertisingAttempt attempt = new AdvertisingAttempt(token, exactAdvertiser)"));
        assertTrue(advertising.contains(
                "exactAdvertiser.startAdvertising(settings, data, attempt)"));
        String advertisingGate = between(source,
                "private boolean isCurrentAdvertisingAttempt",
                "private void retireAdvertisingAttempt");
        assertTrue(advertisingGate.contains("advertisingAttempt == attempt"));
        assertTrue(advertisingGate.contains("advertiser == attempt.exactAdvertiser"));
        assertTrue(advertisingGate.contains("advertisingToken.equals(attempt.token)"));

        String open = between(source, "private void openServer",
                "private void addServerService");
        assertTrue(open.contains("ServerAttempt attempt = new ServerAttempt(token)"));
        assertTrue(open.contains("manager.openGattServer(context, attempt.callback)"));
        assertTrue(open.contains("attempt.openCompleted(opened)"));
        String serverGate = between(source, "private boolean isCurrentServerAttempt",
                "private void handleServiceAdded");
        assertTrue(serverGate.contains("serverAttempt == attempt"));
        assertTrue(serverGate.contains("server == attempt.exactServer"));
        assertTrue(serverGate.contains("serverOwnerToken.equals(attempt.ownerToken)"));

        String serviceAdded = between(source, "private void handleServiceAdded",
                "private void handleInboundConnection");
        assertTrue(serviceAdded.contains("attempt.pendingServiceAddToken"));
        assertTrue(serviceAdded.contains("current.expected.equals(token)"));
        String indication = between(source, "private void handleControlIndicationSent",
                "private void failPendingControlTransmit");
        assertTrue(indication.contains("pendingControlServerAttempt != attempt"));
        assertTrue(indication.contains("isCurrentServerAttempt(attempt)"));
    }

    @Test public void offlineStopDrainsLocalsAndConnectedStopWaitsForHelperFirst()
            throws Exception {
        String source = source();
        String stop = between(source, "@Override public void beginConfirmedModeSwitchStop",
                "@Override public int appOwnedOwnerCount");
        assertTrue(stop.contains("frozenSwitchResult == FreezeResult.FROZEN_WITH_REMOTE_CONTROL"));
        assertTrue(stop.contains("AndroidPeripheralRoute.switchStop"));
        assertTrue(stop.contains("frozenSwitchResult == FreezeResult.FROZEN_NO_REMOTE_OWNER"));
        assertTrue(stop.contains("AndroidPeripheralRoute.stop"));
        assertTrue(stop.contains("source.equals(frozenSwitchOwner)"));
    }

    @Test public void frozenServiceChangedCannotEraseControlOrStartRediscovery()
            throws Exception {
        String source = source();
        String changed = between(source, "private void handleReverseCharacteristicChanged",
                "private void beginAncsSession");
        int serviceChanged = changed.indexOf("if (SERVICE_CHANGED.equals(uuid))");
        int frozen = changed.indexOf("if (ingressFrozen) return;", serviceChanged);
        int clear = changed.indexOf("pendingReverse = null;", serviceChanged);
        assertTrue(serviceChanged >= 0 && frozen > serviceChanged && frozen < clear);
    }

    @Test public void frozenAncsWriteCallbackConsumesExactRawFifoSlot()
            throws Exception {
        String source = source();
        String write = between(source, "private void handleReverseCharacteristicWrite",
                "private void handleReverseCharacteristicChanged");
        int validate = write.indexOf("pending.type != ReverseOperation.WRITE_CONTROL_POINT");
        int clear = write.indexOf("pendingReverse = null;");
        int frozen = write.indexOf("if (ingressFrozen)");
        int closedCore = write.indexOf("ancs.controlPointWriteResult");
        assertTrue(validate >= 0 && clear > validate && frozen > clear && closedCore > frozen);
        assertTrue(write.substring(frozen, closedCore).contains("return;"));
    }

    @Test public void frozenReverseDiscoveryAndCccdCallbacksOnlyRetireExactSlots()
            throws Exception {
        String source = source();
        String services = between(source, "private void handleReverseServices",
                "private void handleReverseDescriptorWrite");
        assertTrue(services.indexOf("pendingReverse = null;")
                < services.indexOf("if (ingressFrozen) return;"));

        String descriptor = between(source, "private void handleReverseDescriptorWrite",
                "private void handleReverseCharacteristicWrite");
        assertTrue(descriptor.indexOf("pendingReverse = null;")
                < descriptor.indexOf("if (ingressFrozen) return;"));
        assertFalse(descriptor.substring(descriptor.indexOf("if (ingressFrozen) return;"))
                .contains("pendingReverse = null;"));
    }

    @Test public void restorationDrainAllocatesNoBleOwnerAndUsesValueIdentity()
            throws Exception {
        String source = source();
        String prepare = between(source, "@Override public void prepareRestorationDrain",
                "@Override public void freezeIngress");
        assertFalse(prepare.contains("openGattServer"));
        assertFalse(prepare.contains("startAdvertising"));
        assertFalse(prepare.contains("connectGatt"));
        assertTrue(source.contains("source.equals(restorationOwner)"));
        assertFalse(source.contains("source == restorationOwner"));
        assertFalse(source.contains("frozenSwitchOwner != source"));
    }

    @Test public void exactDuplicateCloseRemainsAllowedAfterNormalIngressFreeze()
            throws Exception {
        String source = source();
        String control = between(source, "private void handleControlWrite",
                "private void handleInboundPeerProof");
        assertTrue(control.contains("ingressFrozen && !acceptsFrozenControl(control)"));
        String gate = between(source, "private boolean acceptsFrozenControl",
                "private void completeControlTransmit");
        assertTrue(gate.contains("lastInboundCloseRequest.sameTransaction(control)"));
        assertTrue(gate.contains("lastOutboundControl.sameTransaction(control)"));
    }

    @Test public void indicationQueueReturnNeverReleasesSwitchBeforeExactConfirmation()
            throws Exception {
        String source = source();
        String send = between(source, "private void sendControlIndication",
                "private void handleControlIndicationSent");
        int queueSuccess = send.indexOf(
                "// Exact ATT indication confirmation owns completion");
        assertTrue(queueSuccess >= 0);
        assertFalse(send.substring(queueSuccess).contains(
                "ControlTransmitResult.ACCEPTED"));

        String confirmed = between(source, "private void handleControlIndicationSent",
                "private void failPendingControlTransmit");
        assertTrue(confirmed.contains("status == BluetoothGatt.GATT_SUCCESS"));
        assertTrue(confirmed.contains("success ? ControlTransmitResult.ACCEPTED"));
        assertTrue(confirmed.contains("ControlTransmit transmit = pendingControlTransmit"));
    }

    @Test public void peerProofAttSuccessWaitsForDurableExactSessionGate()
            throws Exception {
        String source = source();
        String peerProof = between(source, "private void handleInboundPeerProof",
                "private void commitAcceptedPeerProof");
        assertTrue(peerProof.contains("beginHelperIdentityCommit("));

        String begin = between(source, "private void beginHelperIdentityCommit",
                "private void finishHelperIdentityCommit");
        assertTrue(begin.contains("cancelRouteTimer(token)"));
        assertTrue(begin.contains("offerHelperInstallationId("));
        assertFalse(begin.contains("BluetoothGatt.GATT_SUCCESS"));

        String finish = between(source, "private void finishHelperIdentityCommit",
                "private void cancelHelperIdentityCommit");
        assertTrue(finish.contains("listener == gate.sessionListener"));
        assertTrue(finish.contains("inboundPhysicalFacade == gate.physicalFacade"));
        assertTrue(finish.contains("current.expected.equals(gate.token)"));
        assertTrue(finish.contains("commitAcceptedPeerProof("));

        String commit = between(source, "private void commitAcceptedPeerProof",
                "private void beginHelperIdentityCommit");
        assertTrue(commit.indexOf("state = transition.state")
                < commit.indexOf("BluetoothGatt.GATT_SUCCESS"));
    }

    @Test public void reverseClientAndRestorationShareProcessRegistrationGate()
            throws Exception {
        String source = source();
        String count = between(source, "public int appOwnedOwnerCount()",
                "@Override public void prepareRestorationDrain");
        assertTrue(count.contains("!ProcessGattRegistrationGateV2.isHeld()"));

        String prepare = between(source, "@Override public void prepareRestorationDrain",
                "private void completeRestorationPrepared");
        assertTrue(prepare.contains("ProcessGattRegistrationGateV2.whenFreeForDrain("));

        String complete = between(source, "private void completeRestorationPrepared",
                "@Override public void freezeIngress");
        assertTrue(complete.contains(
                "ProcessGattRegistrationGateV2.ownsDrainReservation("));

        String teardown = between(source, "private void maybeCompleteTeardown",
                "private void cancelAllTimers");
        assertTrue(teardown.contains(
                "ProcessGattRegistrationGateV2.ownsDrainReservation("));
        assertTrue(teardown.contains("ProcessGattRegistrationGateV2.whenFreeForDrain("));
        assertTrue(teardown.contains(
                "ProcessGattRegistrationGateV2.releaseDrainReservation("));
        assertTrue(source.contains("processGateDrainRetained = true"));
        assertTrue(source.contains("ProcessGattRegistrationGateV2.radioReset()"));
    }

    private static String source() throws Exception {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("missing source markers: " + start + " / " + end);
        }
        return value.substring(from, to);
    }
}
