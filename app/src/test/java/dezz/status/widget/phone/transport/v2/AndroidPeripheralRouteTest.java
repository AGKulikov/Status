/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AndroidPeripheralRouteTest {
    private static final String BOND = "aa:bb:cc:dd:ee:ff";
    private static final String HELPER = "helper-installation-7";

    @Test public void fullRouteUsesOneExactReverseOwnerAndNormativeAncsOrder() {
        AndroidPeripheralRoute.State state = start(new BleRouteEpoch(21L, 1L));
        state = AndroidPeripheralRoute.serverOpened(state, state.expected, true).state;
        state = AndroidPeripheralRoute.serviceAdded(state, state.expected, true).state;
        BleRouteTransition<AndroidPeripheralRoute.State> advertising =
                AndroidPeripheralRoute.advertisingStarted(state, state.expected, true);
        state = advertising.state;
        assertEquals(AndroidPeripheralRoute.Phase.ADVERTISING, state.phase);

        state = AndroidPeripheralRoute.inboundConnected(state, state.serverOwner).state;
        assertEquals(AndroidPeripheralRoute.Phase.WAITING_CONTROL_SUBSCRIPTION, state.phase);
        state = AndroidPeripheralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertEquals(AndroidPeripheralRoute.Phase.WAITING_PEER_PROOF, state.phase);
        BleRouteToken inbound = state.inboundOwner;
        state = AndroidPeripheralRoute.peerProof(state, state.expected,
                helperProof()).state;
        assertTrue(AndroidPeripheralRoute.acceptsTelemetry(state, inbound));

        long reverseOwner = state.expected.ownerId;
        state = AndroidPeripheralRoute.reverseOwnerObserved(
                state, state.expected, true, true).state;
        assertEquals(reverseOwner, state.reverseOwner.ownerId);
        state = AndroidPeripheralRoute.ancsDiscovered(state, state.expected, complete()).state;
        assertEquals(AndroidPeripheralRoute.Phase.SUBSCRIBING_NOTIFICATION_SOURCE, state.phase);
        state = AndroidPeripheralRoute.notificationSourceSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertEquals(AndroidPeripheralRoute.Phase.SUBSCRIBING_DATA_SOURCE, state.phase);
        state = AndroidPeripheralRoute.dataSourceSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertTrue(state.isReady());
        assertEquals(reverseOwner, state.reverseOwner.ownerId);
    }

    @Test public void wrongRouteModeFailsClosedAndCannotStartReplacementBeforeTerminal() {
        AndroidPeripheralRoute.State state = throughInbound(new BleRouteEpoch(22L, 1L));
        state = AndroidPeripheralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        IphoneBlePeerProof wrong = new IphoneBlePeerProof(
                IphoneBleProtocolV2.VERSION, IphoneBleMode.ANDROID_CENTRAL,
                BlePeerRole.IPHONE_HELPER_CENTRAL, HELPER, true, true, true);
        BleRouteTransition<AndroidPeripheralRoute.State> rejected =
                AndroidPeripheralRoute.peerProof(state, state.expected, wrong);
        state = rejected.state;

        assertEquals(AndroidPeripheralRoute.Phase.RETRY_DRAINING, state.phase);
        assertTrue(hasEffect(rejected, BleRouteEffect.Type.CLOSE_GATT_SERVER));
        assertTrue(hasEffect(rejected, BleRouteEffect.Type.DISCONNECT_INBOUND_PEER));
        assertFalse(hasEffect(rejected, BleRouteEffect.Type.OPEN_GATT_SERVER));

        BleRouteToken drain = state.expected;
        assertFalse(AndroidPeripheralRoute.retryElapsed(
                state, drain, true).accepted);
        state = AndroidPeripheralRoute.attemptTeardownComplete(state, drain).state;
        long oldServer = rejected.state.serverOwner.ownerId;
        state = AndroidPeripheralRoute.retryElapsed(
                state, state.expected, true).state;
        assertEquals(AndroidPeripheralRoute.Phase.OPENING_SERVER, state.phase);
        assertNotEquals(oldServer, state.serverOwner.ownerId);
    }

    @Test public void reverseOwnerMustBeUniqueAndMatchCapturedPhysicalFacade() {
        AndroidPeripheralRoute.State state = throughPeerProof(
                new BleRouteEpoch(23L, 1L));
        assertEquals(AndroidPeripheralRoute.Phase.RETRY_DRAINING,
                AndroidPeripheralRoute.reverseOwnerObserved(
                        state, state.expected, false, true).state.phase);

        state = throughPeerProof(new BleRouteEpoch(23L, 2L));
        assertEquals(AndroidPeripheralRoute.Phase.RETRY_DRAINING,
                AndroidPeripheralRoute.reverseOwnerObserved(
                        state, state.expected, true, false).state.phase);
    }

    @Test public void noCallbackIsBoundedAndLateOldEpochCannotMutateHotUpdate() {
        AndroidPeripheralRoute.State old = start(new BleRouteEpoch(24L, 1L));
        BleRouteToken oldOpen = old.expected;
        BleRouteTransition<AndroidPeripheralRoute.State> timeout =
                AndroidPeripheralRoute.deadline(old, oldOpen);
        assertEquals(AndroidPeripheralRoute.Phase.RETRY_DRAINING, timeout.state.phase);
        assertFalse(hasEffect(timeout, BleRouteEffect.Type.OPEN_GATT_SERVER));

        AndroidPeripheralRoute.State replacement = start(
                new BleRouteEpoch(25L, 1L));
        BleRouteTransition<AndroidPeripheralRoute.State> stale =
                AndroidPeripheralRoute.serverOpened(replacement, oldOpen, true);
        assertFalse(stale.accepted);
        assertEquals(AndroidPeripheralRoute.Phase.OPENING_SERVER, stale.state.phase);
    }

    @Test public void radioCycleRequiresFreshEpochAndBuildsFreshOwners() {
        AndroidPeripheralRoute.State state = throughPeerProof(
                new BleRouteEpoch(26L, 1L));
        long oldServer = state.serverOwner.ownerId;
        BleRouteTransition<AndroidPeripheralRoute.State> off =
                AndroidPeripheralRoute.radioOff(state, state.epoch);
        assertEquals(AndroidPeripheralRoute.Phase.WAIT_RADIO, off.state.phase);
        assertTrue(hasEffect(off, BleRouteEffect.Type.CLOSE_GATT_SERVER));
        assertTrue(hasEffect(off, BleRouteEffect.Type.CLOSE_REVERSE_CLIENT));

        AndroidPeripheralRoute.State restarted = start(
                new BleRouteEpoch(26L, 2L));
        assertNotEquals(state.epoch, restarted.epoch);
        // Owner counters may restart because the globally unique epoch is part of every token.
        assertEquals(oldServer, restarted.serverOwner.ownerId);
        assertNotEquals(state.serverOwner, restarted.serverOwner);
    }

    @Test public void stopNeedsExactTeardownProofBeforeLocalTerminal() {
        AndroidPeripheralRoute.State state = throughPeerProof(
                new BleRouteEpoch(27L, 1L));
        BleRouteTransition<AndroidPeripheralRoute.State> stopping =
                AndroidPeripheralRoute.stop(state, state.epoch, "switch");
        assertEquals(AndroidPeripheralRoute.Phase.STOPPING, stopping.state.phase);
        assertFalse(hasEffect(stopping, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));

        BleRouteToken stale = new BleRouteToken(IphoneBleMode.ANDROID_PERIPHERAL,
                new BleRouteEpoch(27L, 2L), 1L, 1L);
        assertFalse(AndroidPeripheralRoute.localTeardownComplete(
                stopping.state, stale).accepted);
        BleRouteTransition<AndroidPeripheralRoute.State> terminal =
                AndroidPeripheralRoute.localTeardownComplete(
                        stopping.state, stopping.state.expected);
        assertEquals(AndroidPeripheralRoute.Phase.STOPPED, terminal.state.phase);
        assertTrue(hasEffect(terminal, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));
    }

    @Test public void absentAncsWaitsForServiceChangedOnSameReverseOwner() {
        AndroidPeripheralRoute.State state = throughPeerProof(
                new BleRouteEpoch(28L, 1L));
        state = AndroidPeripheralRoute.reverseOwnerObserved(
                state, state.expected, true, true).state;
        long owner = state.reverseOwner.ownerId;
        IphoneGattInventoryV2 noAncs = new IphoneGattInventoryV2(
                false, false, false, false, false,
                false, false, false, false, true);
        state = AndroidPeripheralRoute.ancsDiscovered(
                state, state.expected, noAncs).state;
        assertEquals(AndroidPeripheralRoute.Phase.SUBSCRIBING_SERVICE_CHANGED, state.phase);
        BleRouteToken serviceChangedOwner = state.expected;
        state = AndroidPeripheralRoute.serviceChangedSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        assertEquals(AndroidPeripheralRoute.Phase.WAIT_ANCS, state.phase);

        BleRouteTransition<AndroidPeripheralRoute.State> indication =
                AndroidPeripheralRoute.serviceChanged(state, serviceChangedOwner);
        assertEquals(AndroidPeripheralRoute.Phase.DISCOVERING_ANCS, indication.state.phase);
        assertEquals(owner, indication.state.reverseOwner.ownerId);
        assertTrue(hasEffect(indication, BleRouteEffect.Type.DISCOVER_ANCS));
        assertFalse(hasEffect(indication, BleRouteEffect.Type.CLOSE_REVERSE_CLIENT));
        assertEquals(AndroidPeripheralRoute.Phase.RETRY_DRAINING,
                AndroidPeripheralRoute.serviceChanged(
                        indication.state, serviceChangedOwner).state.phase);
    }

    @Test public void absentAncsWithoutServiceChangedRequiresFreshLink() {
        AndroidPeripheralRoute.State state = throughPeerProof(
                new BleRouteEpoch(29L, 1L));
        state = AndroidPeripheralRoute.reverseOwnerObserved(
                state, state.expected, true, true).state;
        IphoneGattInventoryV2 noAncsNoServiceChanged = new IphoneGattInventoryV2(
                false, false, false, false, false,
                false, false, false, false, false);
        BleRouteTransition<AndroidPeripheralRoute.State> blocked =
                AndroidPeripheralRoute.ancsDiscovered(
                        state, state.expected, noAncsNoServiceChanged);
        assertEquals(AndroidPeripheralRoute.Phase.NEEDS_FRESH_LINK, blocked.state.phase);
        assertTrue(hasEffect(blocked, BleRouteEffect.Type.REPORT_DOWN));
        assertFalse(hasEffect(blocked, BleRouteEffect.Type.DISCOVER_ANCS));
        assertEquals(null, blocked.state.expected);
    }

    @Test public void inboundLossFromReadyDrainsEveryOwnerBeforeRepublish() {
        AndroidPeripheralRoute.State ready = ready(
                new BleRouteEpoch(30L, 1L));
        BleRouteTransition<AndroidPeripheralRoute.State> lost =
                AndroidPeripheralRoute.inboundDisconnected(
                        ready, ready.inboundOwner, "server disconnected");
        assertAllOwnersDrain(lost);
        assertFalse(AndroidPeripheralRoute.retryElapsed(
                lost.state, lost.state.expected, true).accepted);
    }

    @Test public void reverseLossFromReadyDrainsEveryOwnerAndRejectsStaleOwner() {
        AndroidPeripheralRoute.State ready = ready(
                new BleRouteEpoch(31L, 1L));
        BleRouteToken stale = new BleRouteToken(IphoneBleMode.ANDROID_PERIPHERAL,
                ready.epoch, ready.reverseOwner.ownerId + 1L, 1L);
        assertFalse(AndroidPeripheralRoute.reverseOwnerLost(
                ready, stale, "stale").accepted);

        BleRouteTransition<AndroidPeripheralRoute.State> lost =
                AndroidPeripheralRoute.reverseOwnerLost(
                        ready, ready.reverseOwner, "observer disconnected");
        assertAllOwnersDrain(lost);
        AndroidPeripheralRoute.State waiting =
                AndroidPeripheralRoute.attemptTeardownComplete(
                        lost.state, lost.state.expected).state;
        assertEquals(AndroidPeripheralRoute.Phase.RETRY_WAIT, waiting.phase);
        assertEquals(AndroidPeripheralRoute.Phase.OPENING_SERVER,
                AndroidPeripheralRoute.retryElapsed(
                        waiting, waiting.expected, true).state.phase);
    }

    @Test public void confirmedSwitchWaitsForHelperCentralToCloseFirst() {
        AndroidPeripheralRoute.State ready = ready(new BleRouteEpoch(32L, 1L));
        BleRouteTransition<AndroidPeripheralRoute.State> waiting =
                AndroidPeripheralRoute.switchStop(ready, ready.epoch, "confirmed C/A");
        assertEquals(AndroidPeripheralRoute.Phase.SWITCH_WAIT_INBOUND_TERMINAL,
                waiting.state.phase);
        assertTrue(hasEffect(waiting, BleRouteEffect.Type.RESET_SESSION_STATE));
        assertTrue(hasEffect(waiting, BleRouteEffect.Type.STOP_ADVERTISING));
        assertFalse(hasEffect(waiting, BleRouteEffect.Type.DISCONNECT_INBOUND_PEER));
        assertFalse(hasEffect(waiting, BleRouteEffect.Type.CLOSE_REVERSE_CLIENT));
        assertFalse(hasEffect(waiting, BleRouteEffect.Type.CLOSE_GATT_SERVER));
        assertFalse(hasEffect(waiting, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));

        BleRouteToken stale = new BleRouteToken(IphoneBleMode.ANDROID_PERIPHERAL,
                ready.epoch, ready.inboundOwner.ownerId + 1L, 1L);
        assertFalse(AndroidPeripheralRoute.inboundDisconnected(
                waiting.state, stale, "stale").accepted);
        BleRouteTransition<AndroidPeripheralRoute.State> helperClosed =
                AndroidPeripheralRoute.inboundDisconnected(
                        waiting.state, ready.inboundOwner, "Helper first close");
        assertEquals(AndroidPeripheralRoute.Phase.STOPPING, helperClosed.state.phase);
        assertTrue(hasEffect(helperClosed, BleRouteEffect.Type.CLOSE_REVERSE_CLIENT));
        assertTrue(hasEffect(helperClosed, BleRouteEffect.Type.CLOSE_GATT_SERVER));
        assertFalse(hasEffect(helperClosed, BleRouteEffect.Type.DISCONNECT_INBOUND_PEER));
        assertFalse(hasEffect(helperClosed, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));

        BleRouteTransition<AndroidPeripheralRoute.State> terminal =
                AndroidPeripheralRoute.localTeardownComplete(
                        helperClosed.state, helperClosed.state.expected);
        assertTrue(hasEffect(terminal, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));
    }

    @Test public void passiveSwitchTimeoutFailsClosedWithoutClaimingTerminal() {
        AndroidPeripheralRoute.State ready = ready(new BleRouteEpoch(33L, 1L));
        AndroidPeripheralRoute.State waiting = AndroidPeripheralRoute.switchStop(
                ready, ready.epoch, "confirmed C/A").state;
        BleRouteTransition<AndroidPeripheralRoute.State> timeout =
                AndroidPeripheralRoute.deadline(waiting, waiting.expected);
        assertEquals(AndroidPeripheralRoute.Phase.FAILED, timeout.state.phase);
        assertTrue(hasEffect(timeout, BleRouteEffect.Type.REPORT_ERROR));
        assertFalse(hasEffect(timeout, BleRouteEffect.Type.DISCONNECT_INBOUND_PEER));
        assertFalse(hasEffect(timeout, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));
    }

    @Test public void explicitBootstrapLearnsHelperOnlyAfterEncryptedHProof() {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                new BleRouteEpoch(34L, 1L), BOND, "", true, 0L,
                IphoneAcquisitionModeV2.EXPLICIT_BOOTSTRAP_SCAN);
        AndroidPeripheralRoute.State state = AndroidPeripheralRoute.start(request).state;
        state = AndroidPeripheralRoute.serverOpened(state, state.expected, true).state;
        state = AndroidPeripheralRoute.serviceAdded(state, state.expected, true).state;
        state = AndroidPeripheralRoute.advertisingStarted(
                state, state.expected, true).state;
        state = AndroidPeripheralRoute.inboundConnected(state, state.serverOwner).state;
        state = AndroidPeripheralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        BleRouteTransition<AndroidPeripheralRoute.State> learned =
                AndroidPeripheralRoute.peerProof(state, state.expected, helperProof());
        assertEquals(HELPER, learned.state.helperInstallationId);
        assertTrue(hasEffect(learned, BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED));
    }

    @Test public void ownerCursorExhaustionFailsClosedWithoutAdvertiserAllocation() {
        AndroidPeripheralRoute.State state = start(new BleRouteEpoch(35L, 1L));
        state = AndroidPeripheralRoute.serverOpened(state, state.expected, true).state;
        state = AndroidPeripheralRoute.withCursorsForTesting(
                state, Long.MAX_VALUE, state.expected.operationId);
        BleRouteTransition<AndroidPeripheralRoute.State> exhausted =
                AndroidPeripheralRoute.serviceAdded(state, state.expected, true);
        assertCounterFailure(exhausted, BleRouteEffect.Type.START_ADVERTISING);
    }

    @Test public void operationCursorExhaustionFailsClosedWithoutServiceAllocation() {
        AndroidPeripheralRoute.State state = start(new BleRouteEpoch(35L, 2L));
        state = AndroidPeripheralRoute.withCursorsForTesting(
                state, state.nextOwnerId, Long.MAX_VALUE - 1L);
        BleRouteTransition<AndroidPeripheralRoute.State> exhausted =
                AndroidPeripheralRoute.serverOpened(state, state.expected, true);
        assertCounterFailure(exhausted, BleRouteEffect.Type.ADD_V2_SERVER_SERVICE);
    }

    @Test public void lastOwnerIsMaxMinusOneAndMaxRemainsSentinelOnly() {
        AndroidPeripheralRoute.State state = start(new BleRouteEpoch(35L, 3L));
        state = AndroidPeripheralRoute.serverOpened(state, state.expected, true).state;
        state = AndroidPeripheralRoute.withCursorsForTesting(
                state, Long.MAX_VALUE - 1L, state.expected.operationId);
        state = AndroidPeripheralRoute.serviceAdded(state, state.expected, true).state;
        assertEquals(Long.MAX_VALUE - 1L, state.expected.ownerId);
        assertEquals(Long.MAX_VALUE, state.nextOwnerId);
        BleRouteTransition<AndroidPeripheralRoute.State> exhausted =
                AndroidPeripheralRoute.advertisingStarted(state, state.expected, true);
        assertCounterFailure(exhausted, BleRouteEffect.Type.ARM_DEADLINE);
        for (BleRouteEffect effect : exhausted.effects) {
            assertNotEquals(Long.MAX_VALUE, effect.token.ownerId);
        }
    }

    private static AndroidPeripheralRoute.State throughInbound(BleRouteEpoch epoch) {
        AndroidPeripheralRoute.State state = start(epoch);
        state = AndroidPeripheralRoute.serverOpened(state, state.expected, true).state;
        state = AndroidPeripheralRoute.serviceAdded(state, state.expected, true).state;
        state = AndroidPeripheralRoute.advertisingStarted(state, state.expected, true).state;
        return AndroidPeripheralRoute.inboundConnected(state, state.serverOwner).state;
    }

    private static AndroidPeripheralRoute.State throughPeerProof(
            BleRouteEpoch epoch) {
        AndroidPeripheralRoute.State state = throughInbound(epoch);
        state = AndroidPeripheralRoute.routeControlSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        return AndroidPeripheralRoute.peerProof(
                state, state.expected, helperProof()).state;
    }

    private static AndroidPeripheralRoute.State ready(BleRouteEpoch epoch) {
        AndroidPeripheralRoute.State state = throughPeerProof(epoch);
        state = AndroidPeripheralRoute.reverseOwnerObserved(
                state, state.expected, true, true).state;
        state = AndroidPeripheralRoute.ancsDiscovered(
                state, state.expected, complete()).state;
        state = AndroidPeripheralRoute.notificationSourceSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
        return AndroidPeripheralRoute.dataSourceSubscribed(
                state, state.expected, GattResultV2.SUCCESS).state;
    }

    private static AndroidPeripheralRoute.State start(BleRouteEpoch epoch) {
        IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                epoch, BOND, HELPER, true, 0L, IphoneAcquisitionModeV2.SELECTED_BOND);
        return AndroidPeripheralRoute.start(request).state;
    }

    private static IphoneBlePeerProof helperProof() {
        return new IphoneBlePeerProof(IphoneBleProtocolV2.VERSION,
                IphoneBleMode.ANDROID_PERIPHERAL,
                BlePeerRole.IPHONE_HELPER_CENTRAL, HELPER, true, true, true);
    }

    private static IphoneGattInventoryV2 complete() {
        return new IphoneGattInventoryV2(false, false, false, false, false,
                true, true, true, true, false);
    }

    private static boolean hasEffect(BleRouteTransition<?> transition,
                                     BleRouteEffect.Type type) {
        for (BleRouteEffect effect : transition.effects) {
            if (effect.type == type) return true;
        }
        return false;
    }

    private static void assertAllOwnersDrain(
            BleRouteTransition<AndroidPeripheralRoute.State> transition) {
        assertEquals(AndroidPeripheralRoute.Phase.RETRY_DRAINING, transition.state.phase);
        assertTrue(hasEffect(transition, BleRouteEffect.Type.RESET_SESSION_STATE));
        assertTrue(hasEffect(transition, BleRouteEffect.Type.STOP_ADVERTISING));
        assertTrue(hasEffect(transition, BleRouteEffect.Type.CLOSE_REVERSE_CLIENT));
        assertTrue(hasEffect(transition, BleRouteEffect.Type.DISCONNECT_INBOUND_PEER));
        assertTrue(hasEffect(transition, BleRouteEffect.Type.CLOSE_GATT_SERVER));
        assertFalse(hasEffect(transition, BleRouteEffect.Type.OPEN_GATT_SERVER));
    }

    private static void assertCounterFailure(BleRouteTransition<?> transition,
                                             BleRouteEffect.Type forbiddenAllocation) {
        assertTrue(transition.accepted);
        assertEquals(AndroidPeripheralRoute.Phase.FAILED,
                ((AndroidPeripheralRoute.State) transition.state).phase);
        assertTrue(hasEffect(transition, BleRouteEffect.Type.REPORT_ERROR));
        assertFalse(hasEffect(transition, forbiddenAllocation));
        for (BleRouteEffect effect : transition.effects) {
            assertTrue(effect.token.ownerId > 0L);
            assertTrue(effect.token.operationId > 0L);
        }
    }
}
