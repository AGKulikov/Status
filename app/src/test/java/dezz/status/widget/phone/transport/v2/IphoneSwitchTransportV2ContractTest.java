/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Sequence;

import org.junit.Test;

public final class IphoneSwitchTransportV2ContractTest {
    @Test public void coordinatorMayEchoEqualButDistinctOwnerDescriptors() {
        Owner first = new Owner(77L, Sequence.of(4L), Sequence.of(9L),
                Role.HELPER_CENTRAL_ANDROID_PERIPHERAL);
        Owner later = new Owner(77L, Sequence.of(4L), Sequence.of(9L),
                Role.HELPER_CENTRAL_ANDROID_PERIPHERAL);
        assertTrue(first != later);
        assertEquals(first, later);
    }
}
