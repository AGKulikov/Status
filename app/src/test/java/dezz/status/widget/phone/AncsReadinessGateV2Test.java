/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.phone.transport.v2.IphoneTransportLifecycle;

public final class AncsReadinessGateV2Test {
    @Test public void normalReadyThenActiveOrderOpensDelivery() {
        AncsReadinessGateV2 gate = new AncsReadinessGateV2();

        assertFalse(gate.onRouteLifecycle(IphoneTransportLifecycle.READY));
        assertTrue(gate.onCoordinatorActive(true));
        assertTrue(gate.isReady());
    }

    @Test public void reverseCallbackOrderAlsoOpensDelivery() {
        AncsReadinessGateV2 gate = new AncsReadinessGateV2();

        assertFalse(gate.onCoordinatorActive(true));
        assertTrue(gate.onRouteLifecycle(IphoneTransportLifecycle.READY));
    }

    @Test public void freezeAndRouteLossCloseDeliveryImmediately() {
        AncsReadinessGateV2 gate = readyGate();
        assertFalse(gate.onCoordinatorActive(false));
        gate.reset();
        assertFalse(gate.onCoordinatorActive(true));
        assertFalse(gate.onRouteLifecycle(IphoneTransportLifecycle.RETRY_WAIT));
        assertFalse(gate.isReady());
    }

    @Test public void resetCannotRetainEitherHalfOfOldOwner() {
        AncsReadinessGateV2 gate = readyGate();
        gate.reset();

        assertFalse(gate.isReady());
        assertFalse(gate.onCoordinatorActive(true));
    }

    private static AncsReadinessGateV2 readyGate() {
        AncsReadinessGateV2 gate = new AncsReadinessGateV2();
        gate.onRouteLifecycle(IphoneTransportLifecycle.READY);
        gate.onCoordinatorActive(true);
        return gate;
    }
}
