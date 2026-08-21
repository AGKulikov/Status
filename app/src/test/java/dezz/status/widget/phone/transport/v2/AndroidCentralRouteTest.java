/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public final class AndroidCentralRouteTest {
    private static final String BOND = "AA:BB:CC:DD:EE:FF";
    private static final String HELPER = "helper-installation-7";

    @Test public void selectedBondSilenceReassertsSameOwnerThenWaitsWithoutWrapperChurn() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(11L, 1L));
        BleRouteToken quiet = state.expected;
        BleRouteTransition<AndroidCentralRoute.State> firstConnect =
                AndroidCentralRoute.startupQuietElapsed(state, quiet, true);
        assertEquals(1, countEffects(firstConnect, BleRouteEffect.Type.CONNECT_SELECTED_BOND));
        assertTrue(firstConnect.effects.get(0).detail.contains("autoConnect=false"));
        state = firstConnect.state;
        assertEquals(AndroidCentralRoute.Phase.CONNECTING, state.phase);
        long soleOwner = state.expected.ownerId;

        for (int attempt = 0; attempt < 3; attempt++) {
            BleRouteTransition<AndroidCentralRoute.State> silence =
                    AndroidCentralRoute.deadline(state, state.expected);
            state = silence.state;
            assertEquals(AndroidCentralRoute.Phase.WAIT_REASSERT, state.phase);
            assertEquals(soleOwner, state.expected.ownerId);
            assertTrue(hasEffect(silence, BleRouteEffect.Type.ARM_RETRY));
            assertFalse(hasEffect(silence, BleRouteEffect.Type.CLOSE_GATT));
            assertFalse(hasEffect(silence, BleRouteEffect.Type.CONNECT_SELECTED_BOND));

            BleRouteTransition<AndroidCentralRoute.State> reassert =
                    AndroidCentralRoute.sameOwnerReassertElapsed(state, state.expected);
            state = reassert.state;
            assertEquals(AndroidCentralRoute.Phase.CONNECTING, state.phase);
            assertEquals(soleOwner, state.expected.ownerId);
            assertTrue(hasEffect(reassert, BleRouteEffect.Type.REASSERT_SAME_GATT));
        }

        BleRouteTransition<AndroidCentralRoute.State> exhausted =
                AndroidCentralRoute.deadline(state, state.expected);
        assertEquals(AndroidCentralRoute.Phase.WAIT_SYSTEM_CONNECTION, exhausted.state.phase);
        assertEquals(soleOwner, exhausted.state.activeOwnerId);
        assertFalse(hasEffect(exhausted, BleRouteEffect.Type.CLOSE_GATT));
        assertFalse(hasEffect(exhausted, BleRouteEffect.Type.CONNECT_SELECTED_BOND));
    }

    @Test public void exactClassicPresencePromptsRetainedOwnerWithoutWrapperReplacement() {
        AndroidCentralRoute.State state = waitSystemConnection(new BleRouteEpoch(11L, 2L));
        long soleOwner = state.activeOwnerId;

        BleRouteTransition<AndroidCentralRoute.State> prompt =
                AndroidCentralRoute.selectedPhonePresent(state);

        assertTrue(prompt.accepted);
        assertEquals(AndroidCentralRoute.Phase.CONNECTING, prompt.state.phase);
        assertEquals(soleOwner, prompt.state.activeOwnerId);
        assertEquals(soleOwner, prompt.state.expected.ownerId);
        assertTrue(hasEffect(prompt, BleRouteEffect.Type.REASSERT_SAME_GATT));
        assertTrue(hasEffect(prompt, BleRouteEffect.Type.ARM_DEADLINE));
        assertFalse(hasEffect(prompt, BleRouteEffect.Type.CLOSE_GATT));
        assertFalse(hasEffect(prompt, BleRouteEffect.Type.CONNECT_SELECTED_BOND));

        BleRouteTransition<AndroidCentralRoute.State> repeatedPresence =
                AndroidCentralRoute.selectedPhonePresent(prompt.state);
        assertTrue(repeatedPresence.accepted);
        assertEquals(soleOwner, repeatedPresence.state.activeOwnerId);
        assertTrue(hasEffect(repeatedPresence, BleRouteEffect.Type.REASSERT_SAME_GATT));
        assertFalse(hasEffect(repeatedPresence, BleRouteEffect.Type.CONNECT_SELECTED_BOND));
    }

    @Test public void enrolledLinkLossRetriesSavedFacadeActivelyWithoutIdentityScan() {
        AndroidCentralRoute.State state = startEnrolled(new BleRouteEpoch(11L, 3L));
        state = AndroidCentralRoute.startupQuietElapsed(
                state, state.expected, true).state;

        BleRouteTransition<AndroidCentralRoute.State> failed =
                AndroidCentralRoute.linkLost(state, state.expected,
                        "enrolled locator status failure; retry exact saved owner");
        assertEquals(AndroidCentralRoute.Phase.RETRY_DRAINING, failed.state.phase);
        assertTrue(hasEffect(failed, BleRouteEffect.Type.CLOSE_GATT));

        state = AndroidCentralRoute.attemptTeardownComplete(
                failed.state, failed.state.expected).state;
        BleRouteTransition<AndroidCentralRoute.State> retry =
                AndroidCentralRoute.retryElapsed(state, state.expected, true);

        assertEquals(AndroidCentralRoute.Phase.CONNECTING, retry.state.phase);
        assertTrue(hasEffect(retry, BleRouteEffect.Type.CONNECT_SELECTED_BOND));
        assertFalse(hasEffect(retry, BleRouteEffect.Type.START_SCAN));
        assertTrue(retry.effects.get(0).detail.contains("autoConnect=false"));
        assertTrue(retry.effects.get(0).detail.contains("every exact active attempt"));
    }

    @Test public void alphabeticSelectedBondCanonicalizesBeforeSingleOwnerAllocation() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(11L, 2L), " aa:bC:dE:f0:A1:b2 ", HELPER, true, 0L,
                IphoneAcquisitionModeV2.SELECTED_BOND);
        assertEquals("AA:BC:DE:F0:A1:B2", request.selectedSystemBondAddress);

        AndroidCentralRoute.State state = AndroidCentralRoute.start(request).state;
        BleRouteTransition<AndroidCentralRoute.State> connect =
                AndroidCentralRoute.startupQuietElapsed(state, state.expected, true);
        assertEquals("AA:BC:DE:F0:A1:B2", connect.state.selectedSystemBondAddress);
        assertEquals(1, countEffects(connect, BleRouteEffect.Type.CONNECT_SELECTED_BOND));
        assertFalse(hasEffect(connect, BleRouteEffect.Type.START_SCAN));
        assertFalse(hasEffect(connect, BleRouteEffect.Type.CLOSE_GATT));
    }

    @Test public void unprovableOwnerBlocksRoleSwitchAndNeverClaimsLocalTerminal() {
        AndroidCentralRoute.State state = waitSystemConnection(new BleRouteEpoch(12L, 1L));
        BleRouteTransition<AndroidCentralRoute.State> stop =
                AndroidCentralRoute.stop(state, state.epoch, "mode switch");

        assertEquals(AndroidCentralRoute.Phase.FAILED, stop.state.phase);
        assertTrue(hasEffect(stop, BleRouteEffect.Type.REPORT_ERROR));
        assertFalse(hasEffect(stop, BleRouteEffect.Type.CLOSE_GATT));
        assertFalse(hasEffect(stop, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));
    }

    @Test public void aRealFailureCallbackAllowsTerminalThenReplacementOwner() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(13L, 1L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        long oldOwner = state.expected.ownerId;

        BleRouteTransition<AndroidCentralRoute.State> failed =
                AndroidCentralRoute.connected(state, state.expected, false);
        state = failed.state;
        assertEquals(AndroidCentralRoute.Phase.RETRY_DRAINING, state.phase);
        assertTrue(hasEffect(failed, BleRouteEffect.Type.CLOSE_GATT));

        BleRouteToken drain = state.expected;
        state = AndroidCentralRoute.attemptTeardownComplete(state, drain).state;
        assertEquals(AndroidCentralRoute.Phase.RETRY_WAIT, state.phase);
        state = AndroidCentralRoute.retryElapsed(state, state.expected, true).state;
        assertEquals(AndroidCentralRoute.Phase.CONNECTING, state.phase);
        assertNotEquals(oldOwner, state.expected.ownerId);
    }

    @Test public void authenticatedControlThenTelemetryThenParserAndAncsBecomeReady() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(14L, 1L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, complete()).state;
        BleRouteTransition<AndroidCentralRoute.State> proof = AndroidCentralRoute.peerProof(
                state, state.expected, helperProof(HELPER), GattResultV2.SUCCESS);
        state = proof.state;

        assertEquals(AndroidCentralRoute.Phase.SUBSCRIBING_ROUTE_CONTROL, state.phase);
        BleRouteTransition<AndroidCentralRoute.State> control =
                AndroidCentralRoute.routeControlSubscribed(
                        state, state.expected, GattResultV2.SUCCESS);
        state = control.state;

        assertEquals(AndroidCentralRoute.Phase.SUBSCRIBING_TELEMETRY, state.phase);
        assertTrue(hasEffect(control, BleRouteEffect.Type.SUBSCRIBE_TELEMETRY));
        assertFalse(AndroidCentralRoute.acceptsTelemetry(state, state.expected));
        BleRouteToken telemetryToken = state.expected;
        BleRouteTransition<AndroidCentralRoute.State> telemetry =
                AndroidCentralRoute.telemetrySubscribed(
                        state, telemetryToken, GattResultV2.SUCCESS);
        state = telemetry.state;

        assertEquals(AndroidCentralRoute.Phase.SUBSCRIBING_NOTIFICATION_SOURCE, state.phase);
        assertTrue(indexOf(telemetry, BleRouteEffect.Type.ARM_ANCS_PARSER)
                < indexOf(telemetry, BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE));
        assertFalse(hasEffect(proof, BleRouteEffect.Type.SUBSCRIBE_TELEMETRY));
        assertTrue(AndroidCentralRoute.acceptsTelemetry(state, telemetryToken));
        assertFalse(AndroidCentralRoute.acceptsTelemetry(state,
                new BleRouteToken(IphoneBleMode.ANDROID_CENTRAL,
                        new BleRouteEpoch(99L, 1L), telemetryToken.ownerId,
                        telemetryToken.operationId)));

        state = AndroidCentralRoute.notificationSourceSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertEquals(AndroidCentralRoute.Phase.SUBSCRIBING_DATA_SOURCE, state.phase);
        state = AndroidCentralRoute.dataSourceSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertTrue(state.isReady());
        assertTrue(AndroidCentralRoute.acceptsTelemetry(state, telemetryToken));
    }

    @Test public void telemetryCccdFailureIsSerializedOptionalAndStaleCannotAdvanceRoute() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(14L, 2L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, complete()).state;
        state = AndroidCentralRoute.peerProof(
                state, state.expected, helperProof(HELPER), GattResultV2.SUCCESS).state;
        BleRouteToken controlToken = state.expected;
        state = AndroidCentralRoute.routeControlSubscribed(
                state, controlToken, GattResultV2.SUCCESS).state;
        BleRouteToken telemetryToken = state.expected;

        assertFalse(AndroidCentralRoute.telemetrySubscribed(
                state, controlToken, GattResultV2.SUCCESS).accepted);
        BleRouteTransition<AndroidCentralRoute.State> denied =
                AndroidCentralRoute.telemetrySubscribed(
                        state, telemetryToken, GattResultV2.AUTHORIZATION_DENIED);
        assertEquals(AndroidCentralRoute.Phase.SUBSCRIBING_NOTIFICATION_SOURCE,
                denied.state.phase);
        assertTrue(hasEffect(denied,
                BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE));
        assertTrue(hasEffect(denied, BleRouteEffect.Type.REPORT_ERROR));
    }

    @Test public void hotUpdateLateCallbackCannotAdvanceNewEpoch() {
        AndroidCentralRoute.State oldState = startSelected(new BleRouteEpoch(15L, 1L));
        oldState = AndroidCentralRoute.startupQuietElapsed(
                oldState, oldState.expected, true).state;
        BleRouteToken oldConnect = oldState.expected;

        AndroidCentralRoute.State replacement = startSelected(new BleRouteEpoch(16L, 1L));
        BleRouteTransition<AndroidCentralRoute.State> stale =
                AndroidCentralRoute.connected(replacement, oldConnect, true);
        assertFalse(stale.accepted);
        assertEquals(AndroidCentralRoute.Phase.STARTUP_QUIET, stale.state.phase);
    }

    @Test public void bootstrapScanRequiresRoleProtocolServiceAndBondAttribution() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(17L, 1L), BOND, "", true, 0L,
                IphoneAcquisitionModeV2.EXPLICIT_BOOTSTRAP_SCAN);
        AndroidCentralRoute.State state = AndroidCentralRoute.start(request).state;
        assertEquals(AndroidCentralRoute.Phase.SCANNING, state.phase);

        IphoneBleAdvertisement notAttributed = new IphoneBleAdvertisement(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.VERSION, BlePeerRole.IPHONE_HELPER_PERIPHERAL,
                true, false);
        assertFalse(AndroidCentralRoute.advertisement(
                state, state.expected, notAttributed).accepted);

        IphoneBleAdvertisement exact = new IphoneBleAdvertisement(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.VERSION, BlePeerRole.IPHONE_HELPER_PERIPHERAL,
                true, true);
        BleRouteTransition<AndroidCentralRoute.State> match =
                AndroidCentralRoute.advertisement(state, state.expected, exact);
        assertTrue(match.accepted);
        assertEquals(AndroidCentralRoute.Phase.CONNECTING, match.state.phase);
    }

    @Test public void productionBootstrapTargetsSelectedBondWithoutDependingOnScan() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(17L, 2L), BOND, "", true, 0L,
                IphoneAcquisitionModeV2.SELECTED_BOND);
        AndroidCentralRoute.State state = AndroidCentralRoute.start(request).state;
        assertEquals(AndroidCentralRoute.Phase.STARTUP_QUIET, state.phase);
        assertFalse(hasEffect(
                AndroidCentralRoute.start(request), BleRouteEffect.Type.START_SCAN));

        BleRouteTransition<AndroidCentralRoute.State> connect =
                AndroidCentralRoute.startupQuietElapsed(state, state.expected, true);
        assertEquals(AndroidCentralRoute.Phase.CONNECTING, connect.state.phase);
        assertTrue(hasEffect(connect, BleRouteEffect.Type.CONNECT_SELECTED_BOND));
        assertFalse(hasEffect(connect, BleRouteEffect.Type.START_SCAN));

        state = connect.state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, complete()).state;
        BleRouteTransition<AndroidCentralRoute.State> learned = AndroidCentralRoute.peerProof(
                state, state.expected, helperProof(HELPER), GattResultV2.SUCCESS);
        assertEquals(HELPER, learned.state.helperInstallationId);
        assertTrue(hasEffect(learned, BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED));
    }

    @Test public void bootstrapLearnsHelperIdOnlyOnEncryptedExactBondOwner() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(18L, 1L), BOND, "", true, 0L,
                IphoneAcquisitionModeV2.EXPLICIT_BOOTSTRAP_SCAN);
        AndroidCentralRoute.State state = AndroidCentralRoute.start(request).state;
        IphoneBleAdvertisement exact = new IphoneBleAdvertisement(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                2, BlePeerRole.IPHONE_HELPER_PERIPHERAL, true, true);
        state = AndroidCentralRoute.advertisement(state, state.expected, exact).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, complete()).state;

        IphoneBlePeerProof unencrypted = new IphoneBlePeerProof(2,
                IphoneBleMode.ANDROID_CENTRAL,
                BlePeerRole.IPHONE_HELPER_PERIPHERAL, HELPER, true, true, false);
        assertEquals(AndroidCentralRoute.Phase.RETRY_DRAINING,
                AndroidCentralRoute.peerProof(state, state.expected, unencrypted,
                        GattResultV2.SUCCESS).state.phase);

        // Rebuild the exact pre-proof state because reducers are immutable.
        AndroidCentralRoute.State again = AndroidCentralRoute.start(request).state;
        again = AndroidCentralRoute.advertisement(again, again.expected, exact).state;
        again = AndroidCentralRoute.connected(again, again.expected, true).state;
        again = AndroidCentralRoute.servicesDiscovered(again, again.expected, complete()).state;
        BleRouteTransition<AndroidCentralRoute.State> learned = AndroidCentralRoute.peerProof(
                again, again.expected, helperProof(HELPER), GattResultV2.SUCCESS);
        assertEquals(HELPER, learned.state.helperInstallationId);
        assertTrue(hasEffect(learned, BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED));
    }

    @Test public void absentAncsWaitsForOneServiceChangedRediscoveryWithoutOwnerReplacement() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(19L, 1L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        long owner = state.expected.ownerId;
        IphoneGattInventoryV2 noAncs = new IphoneGattInventoryV2(
                true, true, true, true, true,
                false, false, false, false, true);
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, noAncs).state;
        assertEquals(AndroidCentralRoute.Phase.SUBSCRIBING_SERVICE_CHANGED, state.phase);
        state = AndroidCentralRoute.serviceChangedSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        BleRouteToken proofToken = state.expected;
        state = AndroidCentralRoute.peerProof(state, proofToken, helperProof(HELPER),
                GattResultV2.SUCCESS).state;
        state = AndroidCentralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        state = AndroidCentralRoute.telemetrySubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertEquals(AndroidCentralRoute.Phase.WAIT_ANCS, state.phase);
        assertEquals(owner, state.activeOwnerId);

        BleRouteTransition<AndroidCentralRoute.State> indication =
                AndroidCentralRoute.serviceChanged(state, proofToken);
        assertEquals(AndroidCentralRoute.Phase.DISCOVERING, indication.state.phase);
        assertEquals(owner, indication.state.activeOwnerId);
        assertTrue(hasEffect(indication, BleRouteEffect.Type.DISCOVER_SERVICES));
        assertFalse(hasEffect(indication, BleRouteEffect.Type.CLOSE_GATT));
        assertEquals(AndroidCentralRoute.Phase.RETRY_DRAINING,
                AndroidCentralRoute.serviceChanged(
                        indication.state, proofToken).state.phase);
    }

    @Test public void absentAncsAndTelemetryFailureStillWaitsForServiceChanged() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(19L, 3L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        IphoneGattInventoryV2 noAncs = new IphoneGattInventoryV2(
                true, true, true, true, true,
                false, false, false, false, true);
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, noAncs).state;
        state = AndroidCentralRoute.serviceChangedSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        state = AndroidCentralRoute.peerProof(
                state, state.expected, helperProof(HELPER), GattResultV2.SUCCESS).state;
        state = AndroidCentralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;

        BleRouteTransition<AndroidCentralRoute.State> telemetryFailure =
                AndroidCentralRoute.telemetrySubscribed(
                        state, state.expected, GattResultV2.TRANSIENT_FAILURE);
        assertEquals(AndroidCentralRoute.Phase.WAIT_ANCS, telemetryFailure.state.phase);
        assertTrue(hasEffect(telemetryFailure, BleRouteEffect.Type.REPORT_DOWN));
        assertTrue(hasEffect(telemetryFailure, BleRouteEffect.Type.REPORT_ERROR));
        assertFalse(hasEffect(telemetryFailure,
                BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE));
    }

    @Test public void immediateSwitchWhileClientRegistrationUnknownFailsClosed() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(20L, 1L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        BleRouteTransition<AndroidCentralRoute.State> stop =
                AndroidCentralRoute.stop(state, state.epoch, "1ms mode switch");
        assertEquals(AndroidCentralRoute.Phase.FAILED, stop.state.phase);
        assertFalse(hasEffect(stop, BleRouteEffect.Type.CLOSE_GATT));
        assertFalse(hasEffect(stop, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));
    }

    @Test public void absentAncsWithoutServiceChangedRequiresFreshLink() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(19L, 2L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        IphoneGattInventoryV2 noAncsNoServiceChanged = new IphoneGattInventoryV2(
                true, true, true, true, true,
                false, false, false, false, false);
        state = AndroidCentralRoute.servicesDiscovered(
                state, state.expected, noAncsNoServiceChanged).state;
        state = AndroidCentralRoute.peerProof(
                state, state.expected, helperProof(HELPER), GattResultV2.SUCCESS).state;
        state = AndroidCentralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        BleRouteTransition<AndroidCentralRoute.State> blocked =
                AndroidCentralRoute.telemetrySubscribed(
                        state, state.expected, GattResultV2.SUCCESS);
        assertEquals(AndroidCentralRoute.Phase.NEEDS_FRESH_LINK, blocked.state.phase);
        assertTrue(hasEffect(blocked, BleRouteEffect.Type.REPORT_DOWN));
        assertFalse(hasEffect(blocked, BleRouteEffect.Type.DISCOVER_SERVICES));
        assertEquals(null, blocked.state.expected);
    }

    @Test public void lateInitialCallbackAfterSameOwnerReassertCompletesCurrentOperation() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(20L, 2L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        BleRouteToken initialOwnerToken = state.expected;
        state = AndroidCentralRoute.deadline(state, state.expected).state;
        state = AndroidCentralRoute.sameOwnerReassertElapsed(state, state.expected).state;
        assertTrue(state.expected.operationId > initialOwnerToken.operationId);
        BleRouteTransition<AndroidCentralRoute.State> lateSuccess =
                AndroidCentralRoute.connected(state, initialOwnerToken, true);
        assertTrue(lateSuccess.accepted);
        assertEquals(AndroidCentralRoute.Phase.DISCOVERING, lateSuccess.state.phase);
    }

    @Test public void authorizationWaitRetainsOwnerAndRetriesExactOperationOnlyOnce() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(20L, 3L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, complete()).state;
        BleRouteTransition<AndroidCentralRoute.State> denied = AndroidCentralRoute.peerProof(
                state, state.expected, null, GattResultV2.AUTHORIZATION_DENIED);
        state = denied.state;
        assertEquals(AndroidCentralRoute.Phase.WAIT_AUTHORIZATION, state.phase);
        assertFalse(hasEffect(denied, BleRouteEffect.Type.CLOSE_GATT));
        BleRouteToken blocked = state.expected;
        state = AndroidCentralRoute.authorizationChanged(state, blocked).state;
        assertEquals(AndroidCentralRoute.Phase.VERIFYING_PEER, state.phase);
        state = AndroidCentralRoute.peerProof(state, state.expected, null,
                GattResultV2.AUTHORIZATION_DENIED).state;
        assertEquals(AndroidCentralRoute.Phase.WAIT_AUTHORIZATION, state.phase);
        assertFalse(AndroidCentralRoute.authorizationChanged(
                state, state.expected).accepted);
    }

    @Test public void serviceChangedDuringRawOperationDrainsInsteadOfParallelDiscovery() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(20L, 4L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.connected(state, state.expected, true).state;
        state = AndroidCentralRoute.servicesDiscovered(state, state.expected, complete()).state;
        assertEquals(AndroidCentralRoute.Phase.VERIFYING_PEER, state.phase);
        BleRouteTransition<AndroidCentralRoute.State> changed =
                AndroidCentralRoute.serviceChanged(state, state.expected);
        assertEquals(AndroidCentralRoute.Phase.RETRY_DRAINING, changed.state.phase);
        assertTrue(hasEffect(changed, BleRouteEffect.Type.CLOSE_GATT));
        assertFalse(hasEffect(changed, BleRouteEffect.Type.DISCOVER_SERVICES));
    }

    @Test public void ownerCursorExhaustionFailsClosedWithoutAllocatingGatt() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(20L, 5L), BOND, HELPER, true, 0L,
                IphoneAcquisitionModeV2.EXPLICIT_BOOTSTRAP_SCAN);
        AndroidCentralRoute.State state = AndroidCentralRoute.start(request).state;
        state = AndroidCentralRoute.withCursorsForTesting(
                state, Long.MAX_VALUE, state.expected.operationId);
        IphoneBleAdvertisement exact = new IphoneBleAdvertisement(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.VERSION, BlePeerRole.IPHONE_HELPER_PERIPHERAL,
                true, true);
        BleRouteTransition<AndroidCentralRoute.State> exhausted =
                AndroidCentralRoute.advertisement(state, state.expected, exact);
        assertCounterFailure(exhausted, BleRouteEffect.Type.CONNECT_GATT);
    }

    @Test public void operationCursorExhaustionFailsClosedWithoutDiscovery() {
        AndroidCentralRoute.State state = startSelected(new BleRouteEpoch(20L, 6L));
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        state = AndroidCentralRoute.withCursorsForTesting(
                state, state.nextOwnerId, Long.MAX_VALUE - 1L);
        BleRouteTransition<AndroidCentralRoute.State> exhausted =
                AndroidCentralRoute.connected(state, state.expected, true);
        assertCounterFailure(exhausted, BleRouteEffect.Type.DISCOVER_SERVICES);
    }

    @Test public void lastOwnerIsMaxMinusOneAndMaxRemainsSentinelOnly() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(20L, 7L), BOND, HELPER, true, 0L,
                IphoneAcquisitionModeV2.EXPLICIT_BOOTSTRAP_SCAN);
        AndroidCentralRoute.State state = AndroidCentralRoute.start(request).state;
        state = AndroidCentralRoute.withCursorsForTesting(
                state, Long.MAX_VALUE - 1L, state.expected.operationId);
        IphoneBleAdvertisement exact = new IphoneBleAdvertisement(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.VERSION, BlePeerRole.IPHONE_HELPER_PERIPHERAL,
                true, true);
        state = AndroidCentralRoute.advertisement(state, state.expected, exact).state;
        assertEquals(Long.MAX_VALUE - 1L, state.expected.ownerId);
        assertEquals(Long.MAX_VALUE, state.nextOwnerId);
        BleRouteTransition<AndroidCentralRoute.State> exhausted =
                AndroidCentralRoute.connected(state, state.expected, false);
        assertCounterFailure(exhausted, BleRouteEffect.Type.CONNECT_GATT);
        for (BleRouteEffect effect : exhausted.effects) {
            assertNotEquals(Long.MAX_VALUE, effect.token.ownerId);
        }
    }

    private static AndroidCentralRoute.State startSelected(BleRouteEpoch epoch) {
        return AndroidCentralRoute.start(new IphoneTransportStartRequest(
                epoch, BOND, HELPER, true, 0L,
                IphoneAcquisitionModeV2.SELECTED_BOND)).state;
    }

    private static AndroidCentralRoute.State startEnrolled(BleRouteEpoch epoch) {
        return AndroidCentralRoute.start(new IphoneTransportStartRequest(
                epoch, BOND, HELPER, true, 0L,
                IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY)).state;
    }

    private static AndroidCentralRoute.State waitSystemConnection(BleRouteEpoch epoch) {
        AndroidCentralRoute.State state = startSelected(epoch);
        state = AndroidCentralRoute.startupQuietElapsed(state, state.expected, true).state;
        for (int index = 0; index < 3; index++) {
            state = AndroidCentralRoute.deadline(state, state.expected).state;
            state = AndroidCentralRoute.sameOwnerReassertElapsed(
                    state, state.expected).state;
        }
        return AndroidCentralRoute.deadline(state, state.expected).state;
    }

    private static IphoneGattInventoryV2 complete() {
        return new IphoneGattInventoryV2(true, true, true, true, true,
                true, true, true, true, false);
    }

    private static IphoneBlePeerProof helperProof(String helperId) {
        return new IphoneBlePeerProof(IphoneBleProtocolV2.VERSION,
                IphoneBleMode.ANDROID_CENTRAL,
                BlePeerRole.IPHONE_HELPER_PERIPHERAL, helperId,
                true, true, true);
    }

    private static boolean hasEffect(BleRouteTransition<?> transition,
                                     BleRouteEffect.Type type) {
        return indexOf(transition, type) >= 0;
    }

    private static int indexOf(BleRouteTransition<?> transition, BleRouteEffect.Type type) {
        for (int index = 0; index < transition.effects.size(); index++) {
            if (transition.effects.get(index).type == type) return index;
        }
        return -1;
    }

    private static int countEffects(BleRouteTransition<?> transition,
                                    BleRouteEffect.Type type) {
        int count = 0;
        for (BleRouteEffect effect : transition.effects) {
            if (effect.type == type) count++;
        }
        return count;
    }

    private static void assertCounterFailure(BleRouteTransition<?> transition,
                                             BleRouteEffect.Type forbiddenAllocation) {
        assertTrue(transition.accepted);
        assertEquals(AndroidCentralRoute.Phase.FAILED,
                ((AndroidCentralRoute.State) transition.state).phase);
        assertTrue(hasEffect(transition, BleRouteEffect.Type.REPORT_ERROR));
        assertFalse(hasEffect(transition, forbiddenAllocation));
        for (BleRouteEffect effect : transition.effects) {
            assertTrue(effect.token.ownerId > 0L);
            assertTrue(effect.token.operationId > 0L);
        }
    }
}
