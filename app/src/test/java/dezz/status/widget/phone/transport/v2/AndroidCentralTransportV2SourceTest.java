/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level gate for Route A where host-side tests cannot load android.jar. */
public final class AndroidCentralTransportV2SourceTest {
    private static final Path SOURCE = sourcePath(
            "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                    + "AndroidCentralTransportV2.java");

    private static Path sourcePath(String relative) {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path direct = root.resolve(relative);
        if (Files.exists(direct) || root.getParent() == null) return direct;
        return root.getParent().resolve(relative);
    }

    @Test public void allFrameworkCallbacksUseConditionalMainDispatchAndCloneValues()
            throws Exception {
        String source = source();
        String scanCallbacks = between(source,
                "private final class ScanAttempt extends ScanCallback",
                "private final Context context");
        assertTrue(scanCallbacks.contains(
                "dispatchMain(() -> handleScanResult(this, result))"));
        assertFalse(scanCallbacks.contains("main.post("));

        String callbacks = between(source,
                "private final BluetoothGattCallback gattCallback",
                "private void handleConnectionState");
        assertTrue(callbacks.contains("dispatchMain(() -> handleConnectionState("));
        assertTrue(callbacks.contains("dispatchMain(() -> handleServicesDiscovered("));
        assertTrue(callbacks.contains("dispatchMain(() -> handleDescriptorWrite("));
        assertTrue(callbacks.contains("dispatchMain(() -> handleCharacteristicWrite("));
        assertTrue(callbacks.contains("dispatchMain(() -> handleCharacteristicChanged("));
        assertTrue(callbacks.contains("dispatchMain(() -> handleCharacteristicRead("));
        assertTrue(callbacks.contains(".clone()"));
        assertFalse(callbacks.contains("main.post("));

        String dispatcher = between(source,
                "private void dispatchMain(Runnable callbackBody)",
                "private boolean isCurrentScanAttempt");
        assertTrue(dispatcher.contains("Looper.myLooper() == main.getLooper()"));
        assertTrue(dispatcher.contains("callbackBody.run();"));
        assertTrue(dispatcher.contains("main.post(callbackBody);"));
    }

    @Test public void retiredBootstrapScanCallbackCannotRelabelFreshAttempt()
            throws Exception {
        String source = source();
        assertFalse(source.contains("private final ScanCallback scanCallback"));
        String start = between(source, "private void startBootstrapScan",
                "private void stopBootstrapScan");
        assertTrue(start.contains(
                "ScanAttempt attempt = new ScanAttempt(token, exactScanner)"));
        assertTrue(start.contains("exactScanner.startScan(filters, settings, attempt)"));

        String gate = between(source, "private boolean isCurrentScanAttempt",
                "private void retireScanAttempt");
        assertTrue(gate.contains("scanAttempt == attempt"));
        assertTrue(gate.contains("scanner == attempt.exactScanner"));
        assertTrue(gate.contains("scanToken.equals(attempt.token)"));
        String failed = between(source, "private void handleScanFailure",
                "private void handleScanResult");
        assertTrue(failed.contains("if (!isCurrentScanAttempt(attempt)) return;"));
        assertTrue(failed.contains("postRouteDeadline(token)"));
        assertFalse(failed.contains("postRouteDeadline(scanToken)"));
    }

    @Test public void selectedBondUsesOneActiveOwnerAndBootstrapIsExplicitDirect()
            throws Exception {
        String source = source();
        String create = between(source,
                "private void createGattOwner",
                "private void reassertSameGatt");
        assertTrue(create.contains("if (owner != null)"));
        assertTrue(create.contains("second BluetoothGatt wrapper forbidden"));
        assertTrue(create.contains("device.connectGatt("));
        assertTrue(create.contains("context, autoConnect, gattCallback"));
        assertEquals(1, count(create, ".connectGatt("));

        String selected = between(source,
                "private void connectSelectedBond",
                "private void connectMatchedBootstrap");
        assertTrue(selected.contains("selectedSystemBondFacade("));
        assertFalse(selected.contains("adapter.getRemoteDevice("));
        assertTrue(source.contains("adapter.getBondedDevices()"));
        assertTrue(source.contains("device.getBondState() == BluetoothDevice.BOND_BONDED"));
        assertTrue(source.contains("samePublicAddress(device.getAddress(), selectedAddress)"));
        assertTrue(source.contains("matches++;"));
        assertTrue(source.contains("if (selected == null) selected = device;"));
        assertTrue(selected.contains(
                "createGattOwner(token, selected, false, attribution)"));
        assertTrue(selected.indexOf("if (!attribution.mayProceedToEncryptedProof())")
                < selected.indexOf("createGattOwner(token, selected, false, attribution)"));
        String bootstrap = between(source,
                "private void connectMatchedBootstrap",
                "private void createGattOwner");
        assertTrue(bootstrap.contains(
                "createGattOwner(token, device, false, attribution)"));

        String reassert = between(source,
                "private void reassertSameGatt",
                "private void closeGattOwner");
        assertTrue(reassert.contains("owner.gatt.connect()"));
        assertFalse(reassert.contains("connectGatt("));
        assertFalse(reassert.contains("new GattOwner("));
    }

    @Test public void selectedBondPlatformEvidenceIsBoundedAddressFreeAndSameWrapperOnly()
            throws Exception {
        String source = source();
        String selected = between(source,
                "private void connectSelectedBond",
                "private void connectMatchedBootstrap");
        assertTrue(selected.contains("selected_bond unique="));
        assertTrue(selected.contains("matches="));
        assertTrue(selected.contains("bonded="));
        assertFalse(selected.contains("device.getAddress()"));
        assertFalse(selected.contains("startBootstrapScan("));
        assertFalse(selected.contains("stopBootstrapScan("));
        assertFalse(selected.contains("radioReset("));
        assertFalse(selected.contains("removeBond("));

        String connect = between(source,
                "private void acquireProcessGateAndConnect",
                "private void cancelWaitingGattOwner");
        assertTrue(connect.contains("process_gate result=queued"));
        assertTrue(connect.contains("process_gate result=acquired"));
        assertTrue(connect.contains("connect_gatt returned="));
        assertTrue(connect.contains("autoConnect="));
        assertTrue(connect.contains("transport=LE"));
        assertEquals(1, count(connect, ".connectGatt("));

        String reassert = between(source,
                "private void reassertSameGatt",
                "private void closeGattOwner");
        assertTrue(reassert.contains("same_wrapper_reassert result="));
        assertEquals(1, count(reassert, "owner.gatt.connect()"));
        assertFalse(reassert.contains("connectGatt("));

        String callback = between(source,
                "private void handleConnectionState",
                "private void handleServicesDiscovered");
        assertTrue(callback.contains("connect_gatt callback elapsedMs="));
        assertTrue(callback.contains("status="));
        assertTrue(callback.contains("newState="));

        String reporter = between(source,
                "private void reportPlatformDiagnostic",
                "private void assertMain");
        assertTrue(source.contains("PLATFORM_DIAGNOSTIC_LIMIT = 256"));
        assertTrue(reporter.contains("bounded.substring(0, PLATFORM_DIAGNOSTIC_LIMIT)"));
        assertTrue(reporter.contains("listener.onPlatformDiagnostic("));
    }

    @Test public void selectedBondAttributionNeverGuessesRotatedFacade() throws Exception {
        String source = source();
        String selected = between(source,
                "private void connectSelectedBond",
                "private void connectMatchedBootstrap");
        assertTrue(selected.contains("startRequest.selectedSystemBondAddress"));
        assertTrue(selected.contains("selectedBond.matches"));
        assertTrue(selected.contains("bondAttribution.begin("));

        String scan = between(source,
                "private void handleScanResult",
                "private final BluetoothGattCallback gattCallback");
        assertTrue(scan.contains("state.selectedSystemBondAddress"));
        assertTrue(scan.contains("state.helperInstallationId"));
        assertTrue(scan.contains("ROTATED_ADDRESS_BOOTSTRAP_UNPROVABLE"));
        assertTrue(scan.contains("ROTATED_ADDRESS_PUBLIC_IDENTITY_UNPROVABLE"));
        assertTrue(scan.contains("PEER_PROOF_REJECTED"));

        String proof = between(source,
                "private IphoneBlePeerProof decodePeerProof",
                "private void maybeCompleteTeardown");
        assertTrue(proof.contains("bondAttribution.complete("));
        assertTrue(proof.contains("owner.gatt != callbackGatt"));
        assertTrue(proof.contains("!owner.connected"));
        assertTrue(proof.contains("!ProcessGattRegistrationGateV2.owns(owner)"));
        assertTrue(proof.contains("selectedSystemBondMatchCount("));
        assertTrue(proof.contains("if (!attribution.proven)"));
        assertFalse(proof.contains("manager.getConnectedDevices("));
        assertTrue(source.contains("decodePeerProof(callbackGatt, value)"));
        assertTrue(proof.indexOf("owner.gatt != callbackGatt")
                < proof.indexOf("bondAttribution.complete("));
        assertTrue(proof.indexOf("!ProcessGattRegistrationGateV2.owns(owner)")
                < proof.indexOf("bondAttribution.complete("));
    }

    @Test public void controlOwnershipUsesIndicationsBeforeAncs() throws Exception {
        String source = source();
        String execute = between(source,
                "private void execute(BleRouteEffect effect)",
                "private void startBootstrapScan");
        assertTrue(execute.contains("case SUBSCRIBE_ROUTE_CONTROL:"));
        assertTrue(execute.contains(
                "IphoneBleProtocolV2.CONTROL_CHARACTERISTIC, true"));

        String inventory = between(source,
                "private IphoneGattInventoryV2 inventory",
                "private IphoneBlePeerProof decodePeerProof");
        assertTrue(inventory.contains("routeControl != null && writable(routeControl)"));
        assertTrue(inventory.contains("routeControl != null && indicatable(routeControl)"));
        assertFalse(inventory.contains("routeControl != null && notifiable(routeControl)"));
    }

    @Test public void telemetryUsesSerializedCccdExactCharacteristicAndSharedDecoder()
            throws Exception {
        String source = source();
        String execute = between(source,
                "private void execute(BleRouteEffect effect)",
                "private void startBootstrapScan");
        assertTrue(execute.contains("case SUBSCRIBE_TELEMETRY:"));
        assertTrue(execute.contains(
                "IphoneBleProtocolV2.TELEMETRY_CHARACTERISTIC, false"));

        String descriptor = between(source,
                "private void handleDescriptorWrite",
                "private void handleCharacteristicWrite");
        assertTrue(descriptor.contains("RawOperation.SUBSCRIBE_TELEMETRY"));
        assertTrue(descriptor.contains("pending.characteristic"));

        String changed = between(source,
                "private void handleTelemetryChanged",
                "private void handleInboundRoleControl");
        assertTrue(changed.contains("characteristic != telemetryCharacteristic"));
        assertTrue(changed.contains(
                "!exactSubscription.sameOwner(exactOwner.ownerToken)"));
        assertTrue(changed.contains(
                "AndroidCentralRoute.acceptsTelemetry(current, exactSubscription)"));
        assertTrue(changed.contains("IphoneTelemetryProtocolV2.decode(value)"));
        assertTrue(changed.contains("listener.onTelemetry(telemetry)"));

        String freeze = between(source, "@Override public void freezeIngress",
                "@Override public void transmitControl");
        assertTrue(freeze.contains("clearTelemetrySubscription()"));
        String reset = between(source, "case RESET_SESSION_STATE:", "case ARM_DEADLINE:");
        assertTrue(reset.contains("clearTelemetrySubscription()"));
    }

    @Test public void adapterHasNoNameMatchHiddenCacheRefreshOrTopologyFallback()
            throws Exception {
        String source = source();
        assertFalse(source.contains("getName("));
        assertFalse(source.contains("setName("));
        assertFalse(source.contains("refresh("));
        assertFalse(source.contains("removeBond("));
        assertFalse(source.contains("getDeclaredMethod("));
        assertFalse(source.contains("getMethod("));
        assertFalse(source.contains("ANDROID_PERIPHERAL_SERVICE"));
    }

    @Test public void freezeStopsAcquisitionAndRestorationDrainAllocatesNoGattOwner()
            throws Exception {
        String source = source();
        String freeze = between(source, "@Override public void freezeIngress",
                "@Override public void transmitControl");
        assertTrue(freeze.contains("ingressFrozen = true"));
        assertTrue(freeze.contains("cancelAllTimers()"));
        assertTrue(freeze.contains("stopBootstrapScanForFreeze()"));
        assertTrue(freeze.contains("owner == null && !scanRunning"));

        String prepare = between(source, "@Override public void prepareRestorationDrain",
                "@Override public void freezeIngress");
        assertFalse(prepare.contains("connectGatt"));
        assertFalse(prepare.contains("startScan"));
        assertTrue(source.contains("source.equals(restorationOwner)"));
        assertFalse(source.contains("source == restorationOwner"));
    }

    @Test public void frozenServiceChangedCannotEraseInflightCloseWrite()
            throws Exception {
        String source = source();
        String changed = between(source, "private void handleCharacteristicChanged",
                "private void handleInboundRoleControl");
        int serviceChanged = changed.indexOf("if (SERVICE_CHANGED.equals(uuid))");
        int frozen = changed.indexOf("if (ingressFrozen) return;", serviceChanged);
        int clear = changed.indexOf("pendingGatt = null;", serviceChanged);
        assertTrue(serviceChanged >= 0 && frozen > serviceChanged && frozen < clear);

        String write = between(source, "private void handleCharacteristicWrite",
                "private void handleCharacteristicChanged");
        assertTrue(write.contains(
                "ingressFrozen && pending.type != RawOperation.WRITE_ROUTE_CONTROL"));
    }

    @Test public void duplicateCloseAndMatchingAckRemainAcceptedDuringStopping()
            throws Exception {
        String source = source();
        String inbound = between(source, "private void handleInboundRoleControl",
                "private boolean acceptsFrozenControl");
        assertTrue(inbound.contains("ingressFrozen && !acceptsFrozenControl(control)"));
        String gate = between(source, "private boolean acceptsFrozenControl",
                "private static boolean readable");
        assertTrue(gate.contains("lastInboundCloseRequest.sameTransaction(control)"));
        assertTrue(gate.contains("lastOutboundControl.sameTransaction(control)"));
    }

    @Test public void learnedHelperIdentityWaitsForBoundedDurableSessionGate()
            throws Exception {
        String source = source();
        String read = between(source, "private void handleCharacteristicRead",
                "private void handleDescriptorWrite");
        assertTrue(read.contains("beginHelperIdentityCommit("));

        String begin = between(source, "private void beginHelperIdentityCommit",
                "private void finishHelperIdentityCommit");
        assertTrue(begin.contains("cancelRouteTimer(token)"));
        assertTrue(begin.contains("offerHelperInstallationId("));
        assertTrue(begin.contains("main.postDelayed(gate.deadline, IDENTITY_COMMIT_TIMEOUT_MS)"));

        String finish = between(source, "private void finishHelperIdentityCommit",
                "private void cancelHelperIdentityCommit");
        assertTrue(finish.contains("listener != gate.sessionListener"));
        assertTrue(finish.contains("current.expected.equals(gate.token)"));
        assertTrue(finish.indexOf("if (accepted)") < finish.indexOf("apply(gate.acceptedTransition)"));
    }

    @Test public void publicGattRegistrationIsQuarantinedAcrossRuntimeRecreation()
            throws Exception {
        String source = source();
        String acquire = between(source, "private void acquireProcessGateAndConnect",
                "private void cancelWaitingGattOwner");
        assertTrue(acquire.indexOf("ProcessGattRegistrationGateV2.tryAcquire(candidate)")
                < acquire.indexOf("candidate.device.connectGatt("));
        assertTrue(acquire.contains("ProcessGattRegistrationGateV2.whenFree(candidate"));
        assertTrue(acquire.contains("ProcessGattRegistrationGateV2.release(candidate)"));

        String close = between(source, "private void closeGattOwner",
                "private void retireRegisteredGattOwner");
        assertTrue(close.contains("owner.closing = true"));
        assertTrue(close.contains("OWNER_UNPROVABLE retained"));
        assertFalse(close.substring(close.indexOf("OWNER_UNPROVABLE retained"))
                .contains("owner = null"));

        String terminal = between(source, "private void finishGattClose",
                "private void failPendingRouteControl");
        assertTrue(terminal.contains("ProcessGattRegistrationGateV2.release(closing)"));

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
        assertTrue(teardown.contains("processGateDrainRetained = true"));
        assertTrue(teardown.contains(
                "ProcessGattRegistrationGateV2.releaseDrainReservation("));
        assertTrue(source.contains("ProcessGattRegistrationGateV2.radioReset()"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(SOURCE), StandardCharsets.UTF_8);
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("missing source markers: " + start + " / " + end);
        }
        return value.substring(from, to);
    }

    private static int count(String value, String needle) {
        int count = 0;
        int at = 0;
        while ((at = value.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
