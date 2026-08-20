/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DualRouteEpochIsolationTest {
    @Test public void switchCannotCrossCompleteCallbacksBetweenModesOrEpochs() {
        IphoneTransportStartRequest centralRequest = request(
                new BleRouteEpoch(31L, 1L), IphoneAcquisitionModeV2.SELECTED_BOND);
        AndroidCentralRoute.State central = AndroidCentralRoute.start(centralRequest).state;
        central = AndroidCentralRoute.startupQuietElapsed(
                central, central.expected, true).state;
        BleRouteToken centralConnect = central.expected;
        central = AndroidCentralRoute.connected(central, centralConnect, true).state;
        BleRouteTransition<AndroidCentralRoute.State> stopping =
                AndroidCentralRoute.stop(central, central.epoch, "switch");
        assertEquals(AndroidCentralRoute.Phase.STOPPING, stopping.state.phase);
        assertFalse(AndroidCentralRoute.connected(
                stopping.state, centralConnect, true).accepted);
        BleRouteTransition<AndroidCentralRoute.State> terminal =
                AndroidCentralRoute.localTeardownComplete(
                        stopping.state, stopping.state.expected);
        assertTrue(hasEffect(terminal, BleRouteEffect.Type.REPORT_LOCAL_TERMINAL));

        IphoneTransportStartRequest peripheralRequest = request(
                new BleRouteEpoch(31L, 2L), IphoneAcquisitionModeV2.SELECTED_BOND);
        AndroidPeripheralRoute.State peripheral = AndroidPeripheralRoute.start(
                peripheralRequest).state;
        assertEquals(AndroidPeripheralRoute.Phase.OPENING_SERVER, peripheral.phase);
        assertFalse(AndroidPeripheralRoute.serverOpened(
                peripheral, centralConnect, true).accepted);
        assertEquals(AndroidPeripheralRoute.Phase.OPENING_SERVER, peripheral.phase);
    }

    @Test public void tokenEqualityIncludesProcessEpochModeOwnerAndOperation() {
        BleRouteToken token = new BleRouteToken(IphoneBleMode.ANDROID_CENTRAL,
                new BleRouteEpoch(41L, 1L), 7L, 3L);
        assertFalse(token.equals(new BleRouteToken(IphoneBleMode.ANDROID_PERIPHERAL,
                new BleRouteEpoch(41L, 1L), 7L, 3L)));
        assertFalse(token.equals(new BleRouteToken(IphoneBleMode.ANDROID_CENTRAL,
                new BleRouteEpoch(42L, 1L), 7L, 3L)));
        assertFalse(token.equals(new BleRouteToken(IphoneBleMode.ANDROID_CENTRAL,
                new BleRouteEpoch(41L, 1L), 7L, 4L)));
    }

    private static IphoneTransportStartRequest request(BleRouteEpoch epoch,
                                                        IphoneAcquisitionModeV2 acquisition) {
        return new IphoneTransportStartRequest(epoch, "aa:bb:cc:dd:ee:ff",
                "helper-installation", true, 0L, acquisition);
    }

    private static boolean hasEffect(BleRouteTransition<?> transition,
                                     BleRouteEffect.Type type) {
        for (BleRouteEffect effect : transition.effects) {
            if (effect.type == type) return true;
        }
        return false;
    }
}
